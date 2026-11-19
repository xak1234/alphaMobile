package org.alphamobile.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.android.RecognitionListener;
import org.vosk.android.StorageService;
import java.util.Locale;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicitly enabled receiver; no audio is captured until the user answers. */
public final class CallService extends Service implements RecognitionListener {
    public static final String UPDATE="org.alphamobile.app.UPDATE";
    public static final String STOP="stop", ANSWER="answer", DECLINE="decline";
    public static volatile CallService instance;
    private static final int ONLINE=10, RING=11;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService control=Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private Data data;
    private Thread poller;
    private PowerManager.WakeLock lock;
    private Model speechModel;
    private SpeechCapture microphone;
    private TextToSpeech tts;
    private boolean ringPosted;
    private final Runnable ringTimeout=()->{clearRing();update();};
    private boolean voiceInitializing, voiceReady, listening, muted, busy, awaitingAgent, userSpeaking;
    private volatile String callId="";
    private String answeringId="", ringId="", diagnosedRingId="", lastReply="", utterance="";
    private final HashSet<String> announced=new HashSet<>();
    private final HashSet<String> silencedQuestions=new HashSet<>();
    private final java.util.HashMap<String,Integer> spokenLengths=new java.util.HashMap<>();
    public boolean echoCancellation;
    private final ArrayDeque<String> agentSpeech=new ArrayDeque<>();
    private volatile int generation;
    public String status="Connecting";
    public String voiceStatus="Ready";
    public final class LocalBinder extends Binder { public CallService service() {return CallService.this;} }
    @Override public IBinder onBind(Intent intent) {return new LocalBinder();}
    @Override public void onCreate() {
        super.onCreate(); instance=this; data=new Data(this);
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("availability","Availability",NotificationManager.IMPORTANCE_LOW));
        NotificationChannel calls=new NotificationChannel("incoming","Agent calls",NotificationManager.IMPORTANCE_HIGH);
        calls.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build());
        calls.enableVibration(true); calls.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE); nm.createNotificationChannel(calls);
        PowerManager pm=getSystemService(PowerManager.class);
        lock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"alphaMobile:Available"); lock.setReferenceCounted(false);
    }
    // Availability must not initialise native speech or contact a TTS engine.
    private void prepareVoice() {
        if(voiceInitializing)return;
        voiceInitializing=true;
        try {
            tts=new TextToSpeech(this,result->main.post(()->configureVoice(result)));
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) {main.post(()->{if(instance==CallService.this&&id.equals(utterance)&&!callId.isEmpty())nextSpeech();});}
                @Override public void onError(String id) {main.post(()->{if(instance==CallService.this){voiceStatus="Voice unavailable. Captions remain available.";update();}});}
            });
        } catch(RuntimeException error) {Diagnostics.record(this,"voice-start",error);voiceStatus="Voice unavailable. Captions remain available.";}
        worker.execute(()->{
            try {
                Model model=new Model(StorageService.sync(this,"model-en-us","speech"));
                main.post(()->{if(instance!=this){model.close();return;}speechModel=model;if(!callId.isEmpty()&&!isSpeaking())nextSpeech();update();});
            } catch(Exception|LinkageError error) {
                Diagnostics.record(this,"speech-model-load",error);
                main.post(()->{if(instance==this){voiceStatus="Speech recognition unavailable. Type your reply below.";update();}});
            }
        });
    }
    boolean voiceInitialized() {return voiceInitializing;}
    private boolean isSpeaking() {return tts!=null&&tts.isSpeaking();}
    private void stopVoice() {if(tts!=null)tts.stop();}
    private void configureVoice(int result) {
        if(instance!=this||tts==null)return;
        if(result!=TextToSpeech.SUCCESS) {voiceStatus="Install an offline English voice in Android settings"; update();return;}
        try {
        Voice chosen=null;
        if(tts.getVoices()!=null) for(Voice voice:tts.getVoices()) {
            if(!voice.isNetworkConnectionRequired() && "en".equals(voice.getLocale().getLanguage())) {
                if(chosen==null || "GB".equals(voice.getLocale().getCountry())) chosen=voice;
                if("GB".equals(voice.getLocale().getCountry())) break;
            }
        }
        if(chosen!=null && tts.setVoice(chosen)==TextToSpeech.SUCCESS) {voiceReady=true;tts.setSpeechRate(0.95f);}
        else voiceStatus="Install an offline English voice; text replies still work";
        tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        } catch(RuntimeException error) {voiceReady=false;Diagnostics.record(this,"voice-configure",error);voiceStatus="Voice unavailable. Captions remain available.";}
        update();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && STOP.equals(intent.getAction())) {goOffline();return START_NOT_STICKY;}
        if(intent==null&&data.flag("receiver_recovery",false)){stopSelf();return START_NOT_STICKY;}
        if(!running&&!foreground(false)) {stopSelf();return START_NOT_STICKY;}
        if(!data.paired()) {stopSelf();return START_NOT_STICKY;}
        data.flag("available",true,true);data.put("availability_error","");
        if(!running) {running=true; acquire(); poller=new Thread(this::poll,"alphaMobile-receiver");poller.start();}
        if(intent!=null && DECLINE.equals(intent.getAction())) change(intent.getStringExtra("call_id"),"declined");
        if(intent!=null && ANSWER.equals(intent.getAction())) answer(intent.getStringExtra("call_id"));
        return START_STICKY;
    }
    @SuppressLint("WakelockTimeout")
    private void acquire() {if(!lock.isHeld()) lock.acquire();}
    private PendingIntent open(String id,String action) {
        Intent intent=new Intent(this,MainActivity.class).putExtra("call_id",id).putExtra("action",action).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this,(id+action).hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification online() {
        PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,CallService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"availability").setSmallIcon(R.drawable.ic_alpha).setContentTitle("alphaMobile · Available")
            .setContentText(status).setContentIntent(open(callId,"")).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null,"Go offline",stop).build()).build();
    }
    private boolean foreground(boolean audio) {
        try {promote(online(),audio);ringPosted=false;return true;}
        catch(RuntimeException error){
            Diagnostics.record(this,audio?"foreground-audio":"foreground-availability",error);
            voiceStatus=audio?"Open alphaMobile and allow microphone access, then answer again.":"Android could not start availability. Open Settings to check app permissions.";
            if(!audio)data.put("availability_error",voiceStatus);
            update();return false;
        }
    }
    private void promote(Notification notification,boolean audio) {
            if(Build.VERSION.SDK_INT>=34) startForeground(ONLINE,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE |
                (audio?ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK:0));
            else if(Build.VERSION.SDK_INT>=29) startForeground(ONLINE,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK |
                (audio&&Build.VERSION.SDK_INT>=30?ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE:0));
            else startForeground(ONLINE,notification);
    }
    private void clearRing() {
        main.removeCallbacks(ringTimeout);ringId="";diagnosedRingId="";
        if(ringPosted)foreground(!callId.isEmpty());
        getSystemService(NotificationManager.class).cancel(RING); // Previous releases used a separate notification.
    }
    private boolean notificationsAllowed() {
        NotificationManager manager=getSystemService(NotificationManager.class);
        NotificationChannel channel=manager.getNotificationChannel("incoming");
        return manager.areNotificationsEnabled()&&(channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE)
            &&(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED);
    }
    @SuppressLint("MissingPermission")
    private void ring(JSONObject call) {
        String id=call.optString("id"); if(!Data.isIncoming(call)||(id.equals(ringId)&&ringPosted))return;
        if(!id.equals(diagnosedRingId)){diagnosedRingId=id;Diagnostics.incomingState(this,"incoming-state");}
        if(!notificationsAllowed()) {status="Enable notifications to receive agent calls";update();return;}
        if(data.flag("quiet",false)) {status="Quiet mode · Call waiting in History";update();return;}
        try {
        PendingIntent decline=PendingIntent.getService(this,id.hashCode(),new Intent(this,CallService.class).setAction(DECLINE).putExtra("call_id",id),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,"incoming").setSmallIcon(R.drawable.ic_alpha)
            .setContentTitle(call.optString("agent_name")).setContentText(call.optString("reason")).setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(open(id,"")).setOngoing(true).setTimeoutAfter(Math.max(1000,(long)(call.optDouble("expires")*1000)-System.currentTimeMillis()))
            .setVisibility(Notification.VISIBILITY_PRIVATE);
        if(Build.VERSION.SDK_INT>=31) builder.setStyle(Notification.CallStyle.forIncomingCall(new Person.Builder().setName(call.optString("agent_name")).setImportant(true).build(),decline,open(id,"answer")));
        else {builder.addAction(new Notification.Action.Builder(null,"Decline",decline).build());builder.addAction(new Notification.Action.Builder(null,"Answer",open(id,"answer")).build());}
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(Build.VERSION.SDK_INT<34 || nm.canUseFullScreenIntent()) builder.setFullScreenIntent(open(id,""),true);
        if(Build.VERSION.SDK_INT>=31)builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        Notification notification=builder.build();notification.flags|=Notification.FLAG_INSISTENT;
        // CallStyle must belong to the foreground service when fullscreen permission is denied.
        promote(notification,false);ringPosted=true;ringId=id;
        main.removeCallbacks(ringTimeout);main.postDelayed(ringTimeout,Math.max(1,(long)(call.optDouble("expires")*1000)-System.currentTimeMillis()));
        update();
        } catch(RuntimeException error) {Diagnostics.record(this,"incoming-notification",error);status="Call waiting. Open Calls to answer; check notification settings.";update();}
    }
    private void poll() {
        boolean first=true;
        while(running) {
            try {
                CallEndings.sync(data);
                JSONObject result=first?Api.call(data,"/api/connect",new JSONObject()):Api.request(data.base(),"/api/inbox?wait=5",data.token(),null,15000);first=false;
                JSONArray calls=result.getJSONArray("calls");data.snapshot(result);Diagnostics.upload(this,data);
                String active=callId;boolean included=false;
                for(int i=0;i<calls.length();i++){JSONObject c=calls.optJSONObject(i);if(c!=null&&active.equals(c.optString("id")))included=true;}
                if(!active.isEmpty()&&!included)data.saveCall(Api.request(data.base(),"/api/calls/"+active,data.token(),null,8000));
                main.post(() -> {
                    if(!running||instance!=this)return;
                    reconcileCall();
                    status="Connected privately to your box";
                    boolean ringing=false;
                    for(int i=0;i<calls.length();i++) {JSONObject c=calls.optJSONObject(i);if(c==null)continue;
                        c=data.call(c.optString("id"));if(c==null)continue;
                        if(Data.isIncoming(c)) {ring(c);ringing=true;}
                        if(c.optString("id").equals(callId)) {
                            if(!"active".equals(c.optString("state"))) finishAudio();
                            else {
                                awaitingAgent=c.optBoolean("awaiting_agent");
                                collectAgentSpeech(c);
                                if(!busy&&!userSpeaking&&!isSpeaking()&&(!agentSpeech.isEmpty()||(awaitingAgent&&!listening)))nextSpeech();
                            }
                        }
                    }
                    if(!ringing)clearRing();
                    update();
                });
            } catch(Exception e) {
                main.post(() -> {data.flag("ai_connected",false,true);status="Reconnecting · Check Tailscale";update();});
                try {Thread.sleep(5000);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();break;}
            }
        }
    }
    public void answer(String id) {
        if(id==null || busy || !callId.isEmpty() || data.hasPendingEnd(id) || Data.isTerminal(data.call(id))) return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {voiceStatus="Microphone permission is required";update();return;}
        if(!foreground(true))return;prepareVoice();busy=true;answeringId=id;int epoch=++generation;voiceStatus="Connecting conversation";update();
        control.execute(() -> {
            try {JSONObject call=Api.call(data,"/api/calls/"+id+"/state",new JSONObject().put("state","active"));data.saveCall(call);
                main.post(() -> {if(instance!=this||epoch!=generation)return;answeringId="";if(Data.isTerminal(data.call(id))){finishAudio();return;}callId=id;generation++;busy=false;clearRing();speaker(data.flag("speaker",true));announced.clear();silencedQuestions.clear();spokenLengths.clear();agentSpeech.clear();userSpeaking=false;
                    JSONArray existing=call.optJSONArray("messages");if(existing!=null)for(int i=0;i<existing.length();i++){JSONObject m=existing.optJSONObject(i);if(m!=null&&"agent".equals(m.optString("role"))){announced.add(m.optString("id"));spokenLengths.put(m.optString("id"),m.optString("text").length());}}if(existing==null||existing.length()==0)send("I have answered. Please explain why you called.");else{voiceStatus="Conversation resumed · Tap Speak to continue";update();}});
            } catch(Exception e) {main.post(() -> {if(instance!=this||epoch!=generation)return;answeringId="";busy=false;voiceStatus=e.getMessage();foreground(false);update();});}
        });
    }
    public void change(String id,String state) {
        if(id==null || id.isEmpty())return;
        if("completed".equals(state)){end(id);return;}
        control.execute(() -> {try {JSONObject call=Api.call(data,"/api/calls/"+id+"/state",new JSONObject().put("state",state));data.saveCall(call);
            main.post(() -> {if(instance!=this)return;if(id.equals(callId))finishAudio();clearRing();update();});
        } catch(Exception e) {main.post(() -> {if(instance!=this)return;busy=false;voiceStatus="Could not finish: "+e.getMessage()+". Tap End to retry.";update();});}});
    }
    public void end(){end(callId.isEmpty()?answeringId:callId);}
    public void end(String id){
        if(id==null||id.isEmpty())return;
        try{data.endLocally(id);}catch(RuntimeException error){Diagnostics.record(this,"save-call-end",error);}
        if(id.equals(callId)||id.equals(answeringId))finishAudio();
        clearRing();update();CallEndings.schedule(this);
    }
    public void goOffline(){
        running=false;end();clearRing();data.flag("available",false,true);stopSelf();
    }
    public void reconcileCall(){
        String id=callId.isEmpty()?answeringId:callId;
        if(!id.isEmpty()&&Data.isTerminal(data.call(id)))finishAudio();
    }
    public void send(String text) {
        if(text.trim().isEmpty() || busy || callId.isEmpty())return;
        silencePreviousReply();userSpeaking=false;busy=true;stopListening();utterance="";stopVoice();voiceStatus="Sending to your agent…";update();
        String id=callId, messageId=UUID.randomUUID().toString();int epoch=generation;
        // Persist the exact request before networking, so a failed turn can be retried idempotently.
        try {JSONObject pending=new JSONObject().put("id",messageId).put("role","user").put("text",text).put("call_id",id);
            data.put("pending_turn",pending.toString());
        } catch(Exception e) {busy=false;voiceStatus=e.getMessage();update();return;}
        runTurn(id,messageId,text,epoch);
    }
    public void retry() {
        if(busy)return;
        try {JSONObject pending=new JSONObject(data.get("pending_turn","{}"));String id=pending.getString("call_id");
            if(!id.equals(callId)) {voiceStatus="That conversation is no longer active";update();return;}
            busy=true;voiceStatus="Retrying saved message";update();runTurn(id,pending.getString("id"),pending.getString("text"),generation);
        } catch(Exception e) {voiceStatus="No saved message to retry";update();}
    }
    private void runTurn(String id,String messageId,String text,int epoch) {
        worker.execute(() -> {
            try {
                JSONObject body=new JSONObject().put("id",messageId).put("role","user").put("text",text);
                JSONObject call=Api.call(data,"/api/calls/"+id+"/turn",body);
                data.saveCall(call);data.put("pending_turn","");
                main.post(() -> {
                    if(epoch!=generation)return;
                    busy=false;
                    JSONObject latest=data.call(id);if(latest==null)latest=call;
                    awaitingAgent=latest.optBoolean("awaiting_agent");collectAgentSpeech(latest);
                    if(!agentSpeech.isEmpty()){utterance="";stopVoice();nextSpeech();}
                    else {
                        JSONArray history=latest.optJSONArray("messages");String answer="";
                        if(history!=null)for(int i=0;i<history.length();i++){JSONObject m=history.optJSONObject(i);if(m!=null&&("reply-"+messageId).equals(m.optString("id"))&&(!"status".equals(m.optString("role"))||awaitingAgent))answer=m.optString("text");}
                        if(!answer.isEmpty()&&announced.add("reply-"+messageId))speak(answer);else nextSpeech();
                    }
                    update();
                });
            } catch(Exception e) {main.post(() -> {if(epoch!=generation)return;busy=false;voiceStatus=e.getMessage()+" · Tap Retry";update();});}
        });
    }
    private void collectAgentSpeech(JSONObject call) {
        JSONArray messages=call.optJSONArray("messages");
        if(messages==null)return;
        for(int j=0;j<messages.length();j++){JSONObject m=messages.optJSONObject(j);
            if(m!=null&&"agent".equals(m.optString("role"))){String id=m.optString("id"),text=m.optString("text");int spoken=spokenLengths.getOrDefault(id,0);if(text.length()>spoken){if(!silencedQuestions.contains(m.optString("reply_to")))agentSpeech.add(text.substring(spoken));spokenLengths.put(id,text.length());announced.add(id);}}}
    }
    private String waitingText(){JSONObject call=data.call(callId);return "Checking with "+(call==null?"your agent":call.optString("agent_name"))+"… Waiting for their reply";}
    private void speak(String text) {
        if(text.isEmpty())return;
        lastReply=text;
        int limit=TextToSpeech.getMaxSpeechInputLength()-1;
        if(text.length()>limit){int split=text.lastIndexOf(' ',limit);if(split<1)split=limit;agentSpeech.addFirst(text.substring(split));text=text.substring(0,split);}
        if(voiceReady) {voiceStatus="Speaking";utterance=UUID.randomUUID().toString();if(tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,utterance)==TextToSpeech.ERROR){voiceStatus="Voice unavailable · Read the reply below";}else if(!muted){listen();voiceStatus="Speaking · You can interrupt";}}
        else {voiceStatus=awaitingAgent?waitingText():"Reply displayed · Tap Speak to answer";main.post(this::nextSpeech);}
    }
    private void nextSpeech(){if(callId.isEmpty()||busy||userSpeaking)return;if(!agentSpeech.isEmpty())speak(agentSpeech.remove());else if(awaitingAgent){stopListening();voiceStatus=waitingText();}else if(!muted)listen();update();}
    public void repeat() {if(!lastReply.isEmpty())speak(lastReply);}
    private void silencePreviousReply(){
        agentSpeech.clear();JSONObject call=data.call(callId);JSONArray messages=call==null?null:call.optJSONArray("messages");
        if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m!=null&&"user".equals(m.optString("role")))silencedQuestions.add(m.optString("id"));}
    }
    private void bargeIn(){userSpeaking=true;silencePreviousReply();utterance="";stopVoice();}
    public void interruptAndListen() {if(busy)return;bargeIn();muted=false;listen();}
    public void toggleMute() {muted=!muted;if(muted){stopListening();voiceStatus="Microphone muted";}else if(!busy){if(isSpeaking()||userSpeaking)listen();else nextSpeech();}update();}
    public boolean isMuted(){return muted;}
    public boolean isBusy(){return busy;}
    public boolean isAwaitingAgent(){return awaitingAgent;}
    public boolean isListening(){return listening;}
    public boolean isAvailable(){return running;}
    public String activeId(){return callId;}
    private void listen() {
        if(callId.isEmpty()||muted||busy||listening)return;
        if(speechModel==null){voiceStatus="Preparing offline speech model";update();return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return;
        try {microphone=new SpeechCapture(speechModel,this,main);echoCancellation=microphone.echoCancellation;listening=true;microphone.start();voiceStatus="Listening · Speak naturally";}
        catch(Exception|LinkageError e){Diagnostics.record(this,"microphone-start",e);stopListening();voiceStatus="Microphone unavailable. Type your reply below.";}
        update();
    }
    private void stopListening() {
        listening=false;
        if(microphone!=null){microphone.close();microphone=null;}
    }
    @Override public void onResult(String result) {
        if(!listening||busy)return;
        try {String text=new JSONObject(result).optString("text").trim();if(!text.isEmpty()) {bargeIn();stopListening();send(text);}}
        catch(Exception e){voiceStatus=e.getMessage();update();}
    }
    @Override public void onPartialResult(String partial) {
        if(!listening)return;
        try {String text=new JSONObject(partial).optString("partial");if(!text.isEmpty()){if(!userSpeaking)bargeIn();userSpeaking=true;}voiceStatus=text.isEmpty()?(isSpeaking()?"Speaking · You can interrupt":"Listening · Speak naturally"):text;update();}catch(Exception ignored){ }
    }
    @Override public void onFinalResult(String result) { }
    @Override public void onError(Exception error) {stopListening();voiceStatus="Microphone: "+error.getMessage();update();}
    @Override public void onTimeout(){stopListening();voiceStatus="Tap Speak to continue";update();}
    public void speaker(boolean speaker) {
        data.flag("speaker",speaker,true);
        AudioManager manager=getSystemService(AudioManager.class);
        manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if(Build.VERSION.SDK_INT>=31){
            for(android.media.AudioDeviceInfo d:manager.getAvailableCommunicationDevices())
                if(d.getType()==(speaker?android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)){manager.setCommunicationDevice(d);return;}
        } else manager.setSpeakerphoneOn(speaker);
    }
    private void finishAudio() {generation++;callId="";answeringId="";utterance="";busy=false;awaitingAgent=false;userSpeaking=false;agentSpeech.clear();announced.clear();silencedQuestions.clear();spokenLengths.clear();stopListening();if(tts!=null)stopVoice();if(running)foreground(false);
        AudioManager manager=getSystemService(AudioManager.class);if(Build.VERSION.SDK_INT>=31)manager.clearCommunicationDevice();manager.setMode(AudioManager.MODE_NORMAL);voiceStatus="Call ended";update();}
    private void update() {MessageAlerts.get(this).refresh();sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()),getPackageName()+".INTERNAL");}
    @Override public void onDestroy() {
        running=false;generation++;callId="";answeringId="";instance=null;main.removeCallbacksAndMessages(null);if(poller!=null)poller.interrupt();stopListening();if(tts!=null){stopVoice();tts.shutdown();}
        if(speechModel!=null)speechModel.close();if(lock.isHeld())lock.release();getSystemService(NotificationManager.class).cancel(RING);
        if(voiceInitializing){try{AudioManager manager=getSystemService(AudioManager.class);if(Build.VERSION.SDK_INT>=31)manager.clearCommunicationDevice();manager.setMode(AudioManager.MODE_NORMAL);}catch(RuntimeException error){Diagnostics.record(this,"audio-release",error);}}
        stopForeground(STOP_FOREGROUND_REMOVE);data.flag("available",false,true);instance=null;worker.shutdownNow();control.shutdownNow();update();super.onDestroy();
    }
}
