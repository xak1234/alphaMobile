package org.alphamobile.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;

/** One message alert, repeated at most every 30 seconds while the receiver or UI runs. */
final class MessageAlerts {
    static final int NOTIFICATION_ID=20;
    static final long REMINDER_MS=30000;
    private static MessageAlerts instance;
    static synchronized MessageAlerts get(Context context){if(instance==null)instance=new MessageAlerts(context.getApplicationContext());return instance;}
    private final Context context;
    private final Data data;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean visible;
    private String conversation="",posted="";
    private long lastPing=-REMINDER_MS;
    private final Runnable reminder=this::refresh;
    private MessageAlerts(Context context){this.context=context;data=new Data(context);MessageState.initialize(data);
        NotificationChannel channel=new NotificationChannel("messages","Agent messages",NotificationManager.IMPORTANCE_DEFAULT);
        channel.setShowBadge(true);channel.enableVibration(true);channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    void viewing(boolean foreground,String call){visible=foreground;conversation=foreground?call:"";refresh();}
    @android.annotation.SuppressLint("MissingPermission")
    void refresh(){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::refresh);return;}
        main.removeCallbacks(reminder);
        if(visible&&!conversation.isEmpty())MessageState.readConversation(data,conversation);
        JSONArray unread=MessageState.unread(data);NotificationManager manager=context.getSystemService(NotificationManager.class);
        if(unread.length()==0){manager.cancel(NOTIFICATION_ID);posted="";lastPing=-REMINDER_MS;return;}
        CallService service=CallService.instance;
        if(!visible&&(service==null||!service.isAvailable()))return;
        main.postDelayed(reminder,REMINDER_MS);
        if(!manager.areNotificationsEnabled()||(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED))return;
        JSONObject latest=unread.optJSONObject(0);StringBuilder signature=new StringBuilder();for(int i=0;i<unread.length();i++){JSONObject m=unread.optJSONObject(i);signature.append(m.optString("id")).append(MessageState.version(m));}
        boolean quiet=data.flag("quiet",false)||(service!=null&&!service.activeId().isEmpty())||data.incoming()!=null;
        long now=SystemClock.elapsedRealtime();boolean ping=!quiet&&now-lastPing>=REMINDER_MS;
        if(!ping&&signature.toString().equals(posted))return;
        Intent intent=new Intent(context,MainActivity.class).putExtra("messages",true).putExtra("call_id",latest.optString("call_id")).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open=PendingIntent.getActivity(context,NOTIFICATION_ID,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification note=new NotificationCompat.Builder(context,"messages").setSmallIcon(R.drawable.ic_alpha)
            .setContentTitle(unread.length()+" unread message"+(unread.length()==1?"":"s"))
            .setContentText(latest.optString("agent_name")+" · "+latest.optString("text"))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(latest.optString("text")))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open).setNumber(unread.length()).setOngoing(true).setAutoCancel(false)
            .setSilent(!ping).setOnlyAlertOnce(!ping).build();
        try{manager.notify(NOTIFICATION_ID,note);posted=signature.toString();if(ping)lastPing=now;}
        catch(RuntimeException error){Diagnostics.record(context,"message-notification",error);}
    }
}
