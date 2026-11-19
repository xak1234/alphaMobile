package org.alphamobile.app;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, private diagnostics: exception types/frames, never messages, audio or credentials. */
final class Diagnostics {
    private static final AtomicBoolean uploading=new AtomicBoolean();
    private Diagnostics() { }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("alphaMobile-diagnostics",Context.MODE_PRIVATE);
    }
    static void record(Context context,String stage,Throwable error) {
        StringBuilder detail=new StringBuilder();
        for(int depth=0;error!=null&&depth<4;depth++,error=error.getCause()) {
            detail.append(error.getClass().getName()).append('\n');
            StackTraceElement[] frames=error.getStackTrace();
            for(int i=0;i<Math.min(30,frames.length);i++)detail.append("  at ").append(frames[i]).append('\n');
        }
        enqueue(context,stage,detail.toString());
    }
    /** Only technical permission/sound flags; no caller, call ID or conversation content. */
    static void incomingState(Context context,String stage){
        try{
            android.app.NotificationManager manager=context.getSystemService(android.app.NotificationManager.class);
            android.app.NotificationChannel channel=manager.getNotificationChannel("incoming");
            android.media.AudioManager audio=context.getSystemService(android.media.AudioManager.class);
            enqueue(context,stage,"receiver="+(CallService.instance!=null)+" notifications="+manager.areNotificationsEnabled()
                +" importance="+(channel==null?-1:channel.getImportance())+" sound="+(channel!=null&&channel.getSound()!=null)
                +" ringer="+audio.getRingerMode()+" filter="+manager.getCurrentInterruptionFilter()
                +" quiet="+new Data(context).flag("quiet",false)+" fullscreen="+(Build.VERSION.SDK_INT<34||manager.canUseFullScreenIntent()));
        }catch(RuntimeException ignored){ }
    }
    private static synchronized void enqueue(Context context,String stage,String detail) {
        try {
            JSONArray existing=new JSONArray(prefs(context).getString("pending","[]"));
            JSONArray pending=new JSONArray();
            for(int i=Math.max(0,existing.length()-19);i<existing.length();i++)pending.put(existing.get(i));
            pending.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("created",System.currentTimeMillis()/1000.0)
                .put("version",BuildConfig.VERSION_NAME).put("sdk",Build.VERSION.SDK_INT)
                .put("device",Build.MANUFACTURER+" "+Build.MODEL).put("stage",stage)
                .put("detail",detail.substring(0,Math.min(detail.length(),12000))));
            prefs(context).edit().putString("pending",pending.toString()).commit();
        } catch(Exception ignored) { /* Error reporting must not break the app. */ }
    }
    static void previousExit(Context context) {
        if(Build.VERSION.SDK_INT<30)return;
        try {
            long seen=prefs(context).getLong("exit_seen",0), newest=seen;
            for(ApplicationExitInfo exit:context.getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(null,0,5)) {
                if(exit.getTimestamp()<=seen)continue;
                newest=Math.max(newest,exit.getTimestamp());
                int reason=exit.getReason();
                if(reason==ApplicationExitInfo.REASON_CRASH||reason==ApplicationExitInfo.REASON_CRASH_NATIVE||reason==ApplicationExitInfo.REASON_ANR)
                    enqueue(context,"previous-process-exit","reason="+reason+" status="+exit.getStatus()+" timestamp="+exit.getTimestamp()+" importance="+exit.getImportance());
            }
            prefs(context).edit().putLong("exit_seen",newest).apply();
        } catch(RuntimeException ignored) { }
    }
    /** Called only on a network worker, through the already paired HTTPS connection. */
    static void upload(Context context,Data data) {
        if(!data.paired()||!uploading.compareAndSet(false,true))return;
        try {
            JSONArray batch;
            synchronized(Diagnostics.class){JSONArray pending=new JSONArray(prefs(context).getString("pending","[]"));batch=new JSONArray();for(int i=0;i<Math.min(5,pending.length());i++)batch.put(pending.get(i));}
            if(batch.length()==0)return;
            Api.request(data.base(),"/api/diagnostics",data.token(),new JSONObject().put("events",batch),8000);
            java.util.HashSet<String> sent=new java.util.HashSet<>();
            for(int i=0;i<batch.length();i++)sent.add(batch.getJSONObject(i).getString("id"));
            synchronized(Diagnostics.class) {
                JSONArray current=new JSONArray(prefs(context).getString("pending","[]")),remaining=new JSONArray();
                for(int i=0;i<current.length();i++){JSONObject event=current.getJSONObject(i);if(!sent.contains(event.getString("id")))remaining.put(event);}
                prefs(context).edit().putString("pending",remaining.toString()).commit();
            }
        } catch(Exception ignored) { /* Retain the batch for a later connection. */ }
        finally {uploading.set(false);}
    }
}
