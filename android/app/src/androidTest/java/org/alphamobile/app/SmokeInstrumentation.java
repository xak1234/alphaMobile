package org.alphamobile.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Integration checks against a real relay and the bundled speech recognizer. */
public final class SmokeInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args){arguments=args;start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Data data=new Data(getTargetContext());String operation=arguments.getString("operation","launch");
        if("pair".equals(operation)){
            String base=arguments.getString("backend","https://127.0.0.1:8793");
            JSONObject pair=Api.request(base,"/api/pair","",new JSONObject().put("keyword",arguments.getString("keyword")).put("name","alphaMobile integration emulator"),20000);
            data.token(pair.getString("token"));data.put("device_id",pair.getString("device_id"));data.put("backend",base);
            result.putString("device_id",pair.getString("device_id"));
        }
        Intent intent=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String id=("chat".equals(operation)||"keyboard".equals(operation))?Api.call(data,"/api/chats",new JSONObject().put("agent_id",arguments.getString("agent_id"))).getString("id"):arguments.getString("call_id","");
        if("chat".equals(operation)||"keyboard".equals(operation))data.saveCall(Api.call(data,"/api/calls/"+id,null));
        if(operation.startsWith("callback"))data.saveCall(Api.call(data,"/api/calls/"+id,null));
        if(!id.isEmpty()&&!operation.startsWith("incoming"))intent.putExtra("call_id",id);
        if(("conversation".equals(operation)||"handoff".equals(operation)||operation.startsWith("offline_")||"ended_offline".equals(operation))){
            data.saveCall(Api.call(data,"/api/calls/"+id,null));intent.putExtra("action","answer");
        }
        Activity activity=startActivitySync(intent);waitForIdleSync();
        if(activity==null)throw new IllegalStateException("Activity failed to start");
        if("keyboard".equals(operation)){
            getUiAutomation().executeShellCommand("settings put secure show_ime_with_hard_keyboard 1").close();
            runOnMainSync(()->{android.widget.EditText input=findInput(activity.getWindow().getDecorView());if(input==null)throw new IllegalStateException("Composer missing");input.requestFocus();input.setText("I can see the message I am typing.");activity.getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
            long until=System.currentTimeMillis()+10000;AtomicReference<Boolean> keyboard=new AtomicReference<>(false);
            while(System.currentTimeMillis()<until){runOnMainSync(()->{android.view.WindowInsets insets=activity.getWindow().getDecorView().getRootWindowInsets();keyboard.set(insets!=null&&insets.isVisible(android.view.WindowInsets.Type.ime()));});if(keyboard.get())break;Thread.sleep(100);}
            if(!keyboard.get())throw new IllegalStateException("Test keyboard did not open");Thread.sleep(500);
            runOnMainSync(()->{android.widget.EditText input=findInput(activity.getWindow().getDecorView());int[] location=new int[2];input.getLocationOnScreen(location);android.graphics.Rect bounds=activity.getWindowManager().getCurrentWindowMetrics().getBounds();int ime=activity.getWindow().getDecorView().getRootWindowInsets().getInsets(android.view.WindowInsets.Type.ime()).bottom;
                if(location[1]+input.getHeight()>bounds.bottom-ime)throw new IllegalStateException("Composer is hidden behind the keyboard");
                if(!input.getText().toString().equals("I can see the message I am typing."))throw new IllegalStateException("Draft was lost");});
            capture("keyboard-visible");result.putString("keyboard","PASS: actual Android keyboard open; complete composer and typed draft above its top edge");
        }
        if("unread".equals(operation)){
            runOnMainSync(()->{android.view.View button=findText(activity.getWindow().getDecorView(),"Become available");if(button==null)throw new IllegalStateException("Availability missing");button.performClick();});
            long until=System.currentTimeMillis()+15000;android.service.notification.StatusBarNotification first=null;
            while(System.currentTimeMillis()<until){first=messageNotification();if(first!=null&&MessageState.count(data,null)==2)break;Thread.sleep(100);}
            if(first==null||MessageState.count(data,null)!=2)throw new IllegalStateException("Two actual agent messages did not alert");
            if(first.getNotification().number!=2||(first.getNotification().flags&android.app.Notification.FLAG_ONLY_ALERT_ONCE)!=0)throw new IllegalStateException("Initial message alert is silent or has wrong count");
            android.app.NotificationChannel channel=getTargetContext().getSystemService(android.app.NotificationManager.class).getNotificationChannel("messages");if(channel.getSound()==null||!channel.canShowBadge())throw new IllegalStateException("Message sound/badge channel missing");
            AtomicReference<Boolean> badge=new AtomicReference<>(false);runOnMainSync(()->badge.set(findText(activity.getWindow().getDecorView(),"● 2 UNREAD")!=null));if(!badge.get())throw new IllegalStateException("Dashboard unread marker missing");
            long posted=first.getPostTime();until=System.currentTimeMillis()+38000;android.service.notification.StatusBarNotification repeated=null;
            while(System.currentTimeMillis()<until){repeated=messageNotification();if(repeated!=null&&repeated.getPostTime()>posted&&(repeated.getNotification().flags&android.app.Notification.FLAG_ONLY_ALERT_ONCE)==0)break;Thread.sleep(200);}
            if(repeated==null||repeated.getPostTime()<=posted)throw new IllegalStateException("Unread message did not repeat after 30 seconds");
            repeated.getNotification().contentIntent.send();Thread.sleep(800);waitForIdleSync();
            if(MessageState.count(data,null)!=1)throw new IllegalStateException("Opening one conversation did not preserve the other unread reply");
            JSONObject remaining=MessageState.unread(data).getJSONObject(0);String remainingCall=remaining.getString("call_id");
            getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).putExtra("call_id",remainingCall).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));Thread.sleep(700);waitForIdleSync();
            if(MessageState.count(data,null)!=0||messageNotification()!=null)throw new IllegalStateException("Reading the final conversation did not clear alerts");
            data.snapshot(Api.call(data,"/api/connection",null));if(MessageState.count(data,null)!=0)throw new IllegalStateException("Repeated snapshot revived read messages");
            Thread.sleep(32000);if(messageNotification()!=null)throw new IllegalStateException("Reminder continued after reading");
            getTargetContext().stopService(new Intent(getTargetContext(),CallService.class));
            result.putString("unread","PASS: two real replies, dashboard badge, notification sound and 30-second repeat; reading one preserves the other; reading both stops reminders; duplicate snapshot stays read");
        }
        if("read_persist".equals(operation)){data.snapshot(Api.call(data,"/api/connection",null));if(MessageState.count(data,null)!=0)throw new IllegalStateException("Read state lost across process restart");result.putString("read_persist","PASS: read receipts survived process restart");}
        if("status_settings".equals(operation)){
            AtomicReference<Throwable> failure=new AtomicReference<>();
            runOnMainSync(()->{try{
                android.view.View root=activity.getWindow().getDecorView();
                String battery=arguments.getString("battery","");if(!battery.isEmpty()&&findText(root,battery)==null)throw new IllegalStateException("Battery reading missing: "+battery);
                if(findText(root,"AGENT CONNECTION")==null)throw new IllegalStateException("Generic connection label missing");
                findDescription(root,"Settings").performClick();
                android.widget.Switch quiet=(android.widget.Switch)findText(activity.getWindow().getDecorView(),"Quiet mode");
                if(quiet==null)throw new IllegalStateException("Quiet-mode switch missing");boolean prior=quiet.isChecked();quiet.performClick();
                if(data.flag("quiet",false)==prior)throw new IllegalStateException("Quiet switch did not persist");
                quiet=(android.widget.Switch)findText(activity.getWindow().getDecorView(),"Quiet mode");if(quiet.isChecked()==prior)throw new IllegalStateException("Quiet switch did not reflect state");quiet.performClick();
                android.widget.Switch updates=(android.widget.Switch)findText(activity.getWindow().getDecorView(),"Automatic update checks");boolean previous=updates.isChecked();updates.performClick();if(data.flag("update_auto",true)==previous)throw new IllegalStateException("Update switch did not persist");((android.widget.Switch)findText(activity.getWindow().getDecorView(),"Automatic update checks")).performClick();
                android.widget.RadioButton blue=(android.widget.RadioButton)findText(activity.getWindow().getDecorView(),"Midnight blue · Shaded navy");blue.performClick();if(!data.get("theme","").equals("blue")||!((android.widget.RadioButton)findText(activity.getWindow().getDecorView(),"Midnight blue · Shaded navy")).isChecked())throw new IllegalStateException("Blue theme selection not reflected");
            }catch(Throwable error){failure.set(error);}});
            if(failure.get()!=null)throw new IllegalStateException("Settings/device-status check",failure.get());
            result.putString("status_settings","PASS: real Android battery reading, generic connection label, persisted switches and selected Midnight blue theme");
        }
        if("tasks_latest".equals(operation)){
            data.snapshot(Api.call(data,"/api/connection",null));AtomicReference<Boolean> shown=new AtomicReference<>(false);
            runOnMainSync(()->{findDescription(activity.getWindow().getDecorView(),"Tasks. Schedules & results").performClick();try{String key=new JSONArray(data.get("tasks","[]")).getJSONObject(0).getString("id");activity.getWindow().getDecorView().findViewWithTag("compact:task:"+key).performClick();}catch(org.json.JSONException e){throw new IllegalStateException(e);}shown.set(findText(activity.getWindow().getDecorView(),arguments.getString("output"))!=null);});
            if(!shown.get())throw new IllegalStateException("Actual task result output missing from Tasks list");capture("latest-task-result");result.putString("tasks_latest","PASS: latest real relay task output visible in Tasks list");
        }
        if("compact_lists".equals(operation)){
            data.snapshot(Api.call(data,"/api/connection",null));data.flag("quiet",true,true);data.put("theme","blue");
            String voice="",chat="";JSONArray calls=data.calls();for(int i=0;i<calls.length();i++){JSONObject c=calls.getJSONObject(i);if("chat".equals(c.optString("channel")))chat=c.getString("id");else if(voice.isEmpty())voice=c.getString("id");}
            JSONObject saved=null;JSONArray feed=data.messages();for(int i=0;i<feed.length();i++){JSONObject m=feed.getJSONObject(i);if(!m.optString("call_id").equals(chat)){saved=m;break;}}
            JSONObject task=new JSONArray(data.get("tasks","[]")).getJSONObject(0);if(voice.isEmpty()||chat.isEmpty()||saved==null)throw new IllegalStateException("Seed real test calls/messages/tasks first");
            int initial=MessageState.count(data,null);if(initial!=2)throw new IllegalStateException("Expected two unread replies, got "+initial);
            String[] sections={"Calls. History & redial","Messages. Every conversation","Tasks. Schedules & results"};
            String[] keys={"call:"+voice,"message:"+saved.getString("id"),"task:"+task.getString("id")};
            for(int i=0;i<sections.length;i++){final String section=sections[i],key=keys[i];
                runOnMainSync(()->{activity.onConfigurationChanged(activity.getResources().getConfiguration());findDescription(activity.getWindow().getDecorView(),section).performClick();});Thread.sleep(700);waitForIdleSync();
                checkCompact(activity,key,false);capture("compact-"+i+"-closed");
                if(i==1&&MessageState.count(data,null)!=initial)throw new IllegalStateException("Collapsed messages were marked read");
                runOnMainSync(()->activity.getWindow().getDecorView().findViewWithTag("compact:"+key).performClick());checkCompact(activity,key,true);capture("compact-"+i+"-open");
                if(i==1&&MessageState.count(data,null)!=1)throw new IllegalStateException("Expanding one reply did not leave the chat unread");
                if(i==2){AtomicReference<Boolean> shown=new AtomicReference<>(false);runOnMainSync(()->shown.set(findText(activity.getWindow().getDecorView(),"Latest reported task output, expanded only when requested.")!=null));if(!shown.get())throw new IllegalStateException("Expanded latest task output missing");}
                data.snapshot(Api.call(data,"/api/connection",null));runOnMainSync(()->activity.onConfigurationChanged(activity.getResources().getConfiguration()));checkCompact(activity,key,true);
                runOnMainSync(()->activity.getWindow().getDecorView().findViewWithTag("compact:"+key).performClick());checkCompact(activity,key,false);runOnMainSync(activity::onBackPressed);
            }
            final String chatKey="chat:"+chat;
            runOnMainSync(()->findDescription(activity.getWindow().getDecorView(),"Messages. Every conversation").performClick());Thread.sleep(500);checkCompact(activity,chatKey,false);
            runOnMainSync(()->activity.getWindow().getDecorView().findViewWithTag("compact:"+chatKey).performClick());checkCompact(activity,chatKey,true);
            if(MessageState.count(data,null)!=0)throw new IllegalStateException("Visible expanded chat reply stayed unread");capture("compact-chat-open");
            runOnMainSync(()->findText(activity.getWindow().getDecorView(),"Open conversation / Reply  ›").performClick());waitForIdleSync();AtomicReference<Boolean> composer=new AtomicReference<>(false);runOnMainSync(()->composer.set(findInput(activity.getWindow().getDecorView())!=null));if(!composer.get())throw new IllegalStateException("Expanded chat reply action did not open composer");
            data.flag("quiet",false,true);result.putString("compact_lists","PASS: one-line agent/date/time/subject rows, collapsed by default, expand/collapse, preserved expansion on refresh, task output, unread-on-expansion and Reply action");
        }
        if("screens".equals(operation)){
            data.snapshot(Api.call(data,"/api/connect",new JSONObject()));
            for(String theme:new String[]{"dark","blue","light"}){
                data.put("theme",theme);
                runOnMainSync(()->activity.onConfigurationChanged(activity.getResources().getConfiguration()));
                capture(theme+"-home");
                for(String section:new String[]{"Calls. History & redial","Messages. Every conversation","Tasks. Schedules & results","Agents. Your AI network","Settings"}){
                    runOnMainSync(()->{android.view.View control=findDescription(activity.getWindow().getDecorView(),section);if(control==null)throw new IllegalStateException("Missing navigation: "+section);control.performClick();});
                    Thread.sleep(900);waitForIdleSync();capture(theme+"-"+section.split("[. ]")[0]);
                    runOnMainSync(activity::onBackPressed);
                }
            }
            data.put("theme","dark");result.putString("screens","PASS: all four inner pages and Settings in dark/blue/light themes; screenshots captured");
        }
        if("updater".equals(operation)){
            AppUpdates updates=AppUpdates.get(getTargetContext());
            runOnMainSync(()->{android.view.View control=findDescription(activity.getWindow().getDecorView(),"Settings");if(control==null)throw new IllegalStateException("Settings missing");control.performClick();updates.check();});
            awaitUpdates(updates,30000);
            if(updates.release==null||updates.release.optLong("version_code")<=BuildConfig.VERSION_CODE)throw new IllegalStateException("No newer release from the real box: "+updates.status);
            runOnMainSync(updates::download);awaitUpdates(updates,180000);
            if(updates.ready==null||!updates.ready.isFile())throw new IllegalStateException("Download failed: "+updates.status);
            updates.verify(updates.ready,updates.release);
            for(String kind:new String[]{"checksum","version","path"}){
                JSONObject bad=new JSONObject(updates.release.toString());
                if(kind.equals("checksum")){bad.put("sha256","0".repeat(64));bad.put("apk_path","/api/updates/apk/"+"0".repeat(64));}
                if(kind.equals("version"))bad.put("version_code",BuildConfig.VERSION_CODE);
                if(kind.equals("path"))bad.put("apk_path","https://example.com/update.apk");
                boolean rejected=false;try{updates.verify(updates.ready,bad);}catch(Exception expected){rejected=true;}
                if(!rejected)throw new IllegalStateException("Unsafe update accepted: "+kind);
            }
            if(!data.prefs.edit().putString("update_test_device",data.get("device_id","")).putString("update_test_history",data.get("calls","[]")).putString("update_test_token",data.get("credential","")).commit())throw new IllegalStateException("Could not persist update test baseline");
            result.putString("updater","PASS: authenticated download, APK identity/version/signature verified; corrupt checksum, downgrade and external URL rejected");
        }
        if("updated".equals(operation)){
            String originalDevice=arguments.getString("expected_device",data.get("update_test_device",""));
            if(originalDevice.isEmpty()||!originalDevice.equals(data.get("device_id","")))throw new IllegalStateException("Original paired device was not preserved");
            String originalCall=arguments.getString("expected_call","");
            if(!originalCall.isEmpty()){
                if(data.call(originalCall)==null)throw new IllegalStateException("Original cached call history was lost");
                JSONObject persisted=Api.call(data,"/api/calls/"+originalCall,null);
                if(!originalDevice.equals(persisted.getString("device_id")))throw new IllegalStateException("Preserved credential does not own original history");
            }else{
                if(!data.get("update_test_token","").equals(data.get("credential","")))throw new IllegalStateException("Pairing credential changed");
                JSONArray history=new JSONArray(data.get("update_test_history","[]"));for(int i=0;i<history.length();i++)if(data.call(history.getJSONObject(i).getString("id"))==null)throw new IllegalStateException("Update lost call history");
                Api.call(data,"/api/connection",null);
            }
            data.put("update_test_token","");
            if(BuildConfig.VERSION_CODE!=10)throw new IllegalStateException("Expected release version code 10");
            AppUpdates updates=AppUpdates.get(getTargetContext());runOnMainSync(updates::check);awaitUpdates(updates,30000);
            if(updates.release!=null||!updates.status.equals("You're up to date."))throw new IllegalStateException("Installed release still offered: "+updates.status);
            result.putString("updated","PASS: version 0.2.0 installed; pairing, usable encrypted credential and history preserved; no repeat update offered");
        }
        if(operation.startsWith("callback")){
            if(CallService.instance!=null)throw new IllegalStateException("Callback regression must start offline");
            android.app.NotificationManager manager=getTargetContext().getSystemService(android.app.NotificationManager.class);
            for(int attempt=0;attempt<2;attempt++){
                runOnMainSync(()->{android.view.View button=findText(activity.getWindow().getDecorView(),"Call back");if(button==null)throw new IllegalStateException("Call back button missing");button.performClick();});
                AtomicReference<Boolean> shown=new AtomicReference<>(false);long until=System.currentTimeMillis()+25000;
                while(System.currentTimeMillis()<until){runOnMainSync(()->shown.set(findText(activity.getWindow().getDecorView(),"INCOMING AGENT CALL")!=null));if(shown.get())break;Thread.sleep(100);}
                if(!shown.get()){
                    JSONObject inbox=Api.call(data,"/api/inbox",null);boolean arrived=false;JSONArray calls=inbox.getJSONArray("calls");
                    for(int i=0;i<calls.length();i++)arrived|="ringing".equals(calls.getJSONObject(i).optString("state"));
                    throw new IllegalStateException(arrived?"Callback reached relay but receiver/ringing screen did not start":"Test agent did not return a callback");
                }
                if(CallService.instance==null)throw new IllegalStateException("Callback did not enable availability");
                JSONObject call=data.incoming();if(call==null)throw new IllegalStateException("Incoming callback not cached");String callbackId=call.getString("id");
                // A duplicate start must not replace an existing ringing notification with availability.
                runOnMainSync(()->getTargetContext().startForegroundService(new Intent(getTargetContext(),CallService.class)));
                Thread.sleep(800);boolean ringing=false;
                for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications())if("incoming".equals(n.getNotification().getChannelId())&&(n.getNotification().flags&android.app.Notification.FLAG_INSISTENT)!=0)ringing=true;
                if(!ringing)throw new IllegalStateException("Callback ringing notification missing or lost after service start");
                if(CallService.instance.voiceInitialized()||CallService.instance.isListening())throw new IllegalStateException("Unanswered callback started the microphone");
                if(attempt==1&&"callback_hold".equals(operation)){Bundle stage=new Bundle();stage.putString("callback_stage","Second callback is ringing");sendStatus(1,stage);Thread.sleep(12000);}
                runOnMainSync(()->{android.view.View button=findText(activity.getWindow().getDecorView(),"Decline");if(button==null)throw new IllegalStateException("Decline missing");button.performClick();});
                until=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<until&&!"declined".equals(Api.call(data,"/api/calls/"+callbackId,null).optString("state")))Thread.sleep(100);
                shown.set(false);until=System.currentTimeMillis()+5000;
                while(System.currentTimeMillis()<until){runOnMainSync(()->shown.set(findText(activity.getWindow().getDecorView(),"Call back")!=null));if(shown.get())break;Thread.sleep(100);}
                if(!shown.get())throw new IllegalStateException("Ended callback screen did not refresh in place");
            }
            getTargetContext().stopService(new Intent(getTargetContext(),CallService.class));
            result.putString("callback","PASS: offline callback enables receiver, two real MCP callbacks ring in place, duplicate start preserves alert, decline refreshes screen");
        }
        if(operation.startsWith("offline_")||"ended_offline".equals(operation)){
            long until=System.currentTimeMillis()+30000;
            while(System.currentTimeMillis()<until&&(CallService.instance==null||!id.equals(CallService.instance.activeId())||CallService.instance.isBusy()))Thread.sleep(100);
            CallService service=CallService.instance;
            if(service==null||!id.equals(service.activeId())||service.isBusy())throw new IllegalStateException("Real voice call did not become active");
            JSONObject earlier=Api.call(data,"/api/calls/"+id,null);
            String base=data.base();
            try{
                if("ended_offline".equals(operation)){
                    JSONObject ended=Api.call(data,"/api/calls/"+id+"/state",new JSONObject().put("state","failed"));
                    data.saveCall(ended);
                    getTargetContext().sendBroadcast(new Intent(CallService.UPDATE).setPackage(getTargetContext().getPackageName()),getTargetContext().getPackageName()+".INTERNAL");
                    until=System.currentTimeMillis()+3000;while(System.currentTimeMillis()<until&&!service.activeId().isEmpty())Thread.sleep(50);
                    if(!service.activeId().isEmpty())throw new IllegalStateException("Ended call stayed attached to the voice service");
                }else{
                    // A real refused TCP connection, not a fake HTTP response or recognizer.
                    data.put("backend","https://127.0.0.1:1");
                    if("offline_end".equals(operation)){
                        runOnMainSync(()->{android.view.View button=findText(activity.getWindow().getDecorView(),"End");if(button==null)throw new IllegalStateException("End button missing");button.performClick();});
                        if(!service.activeId().isEmpty()||service.isListening()||service.isBusy())throw new IllegalStateException("Hang-up waited for the unavailable box");
                        if(!new Data(getTargetContext()).hasPendingEnd(id))throw new IllegalStateException("Hang-up was not persisted");
                        data.mergeInbox(new JSONArray().put(earlier));
                        if(!Data.isTerminal(data.call(id)))throw new IllegalStateException("Earlier inbox response revived the ended call");
                    }
                }
                runOnMainSync(()->{activity.onBackPressed();android.view.View button=findText(activity.getWindow().getDecorView(),"Available · Go offline");if(button==null)throw new IllegalStateException("Offline button missing");button.performClick();});
                until=System.currentTimeMillis()+3000;while(System.currentTimeMillis()<until&&CallService.instance!=null)Thread.sleep(50);
                if(CallService.instance!=null||data.flag("available",true)||service.isListening()||!service.activeId().isEmpty())throw new IllegalStateException("Go offline left the receiver or call active");
                android.app.NotificationManager nm=getTargetContext().getSystemService(android.app.NotificationManager.class);
                if(nm.getActiveNotifications().length!=0)throw new IllegalStateException("Offline left a foreground or ringing notification");
                if(operation.startsWith("offline_")&&!new Data(getTargetContext()).hasPendingEnd(id))throw new IllegalStateException("Offline lost the pending hang-up");
            }finally{data.put("backend",base);}
            CallEndings.sync(data);
            JSONObject ended=Api.call(data,"/api/calls/"+id,null);
            String expected="ended_offline".equals(operation)?"failed":"completed";
            if(!expected.equals(ended.optString("state"))||data.hasPendingEnd(id))throw new IllegalStateException("Reconnect did not acknowledge the real terminal call");
            data.mergeInbox(new JSONArray().put(earlier));
            if(!Data.isTerminal(data.call(id)))throw new IllegalStateException("Delayed inbox response revived an acknowledged end");
            result.putString("offline","PASS: immediate local hang-up/offline, durable retry, real relay completion, stale response rejected");
        }
        if(operation.startsWith("incoming")){
            android.app.NotificationManager manager=getTargetContext().getSystemService(android.app.NotificationManager.class);
            if(android.os.Build.VERSION.SDK_INT>=34&&arguments.getString("fullscreen","denied").equals("denied")&&manager.canUseFullScreenIntent())throw new IllegalStateException("Reproduction requires fullscreen permission denied");
            runOnMainSync(()->{android.view.View button=findText(activity.getWindow().getDecorView(),"Become available");if(button==null)throw new IllegalStateException("Availability button missing");button.performClick();});
            AtomicReference<Boolean> shown=new AtomicReference<>(false);long until=System.currentTimeMillis()+20000;
            while(System.currentTimeMillis()<until){runOnMainSync(()->shown.set(findText(activity.getWindow().getDecorView(),"INCOMING AGENT CALL")!=null));if(shown.get())break;Thread.sleep(100);}
            if(!shown.get())throw new IllegalStateException("Incoming call screen did not open automatically");
            android.app.Notification incoming=null;
            for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications())if("incoming".equals(n.getNotification().getChannelId()))incoming=n.getNotification();
            if(incoming==null||(incoming.flags&android.app.Notification.FLAG_FOREGROUND_SERVICE)==0)throw new IllegalStateException("Incoming call is not the foreground-service notification");
            if((incoming.flags&android.app.Notification.FLAG_INSISTENT)==0||incoming.actions==null||incoming.actions.length<2)throw new IllegalStateException("Missing repeating ringtone or answer/decline actions");
            android.app.NotificationChannel channel=manager.getNotificationChannel("incoming");
            if(channel.getSound()==null||channel.getImportance()<android.app.NotificationManager.IMPORTANCE_HIGH)throw new IllegalStateException("Test channel is not configured to ring");
            if(CallService.instance.voiceInitialized()||CallService.instance.isListening())throw new IllegalStateException("Ringing started the microphone or speech engine");
            if("incoming_expiry".equals(operation)){
                until=(long)(Api.call(data,"/api/calls/"+id,null).getDouble("expires")*1000)+8000;
                while(System.currentTimeMillis()<until&&"ringing".equals(Api.call(data,"/api/calls/"+id,null).optString("state")))Thread.sleep(300);
                Thread.sleep(1000);
            }else{
                boolean answer="incoming_answer".equals(operation);String action=answer?"Answer":"Decline";
                runOnMainSync(()->{android.view.View button=findText(activity.getWindow().getDecorView(),action);if(button==null)throw new IllegalStateException("Call action missing");button.performClick();});
                until=System.currentTimeMillis()+15000;String expected=answer?"active":"declined";
                while(System.currentTimeMillis()<until&&!expected.equals(Api.call(data,"/api/calls/"+id,null).optString("state")))Thread.sleep(100);
                if(!expected.equals(Api.call(data,"/api/calls/"+id,null).optString("state")))throw new IllegalStateException("Call action did not reach relay");
                Thread.sleep(500);
                if(answer){
                    if(!CallService.instance.voiceInitialized())throw new IllegalStateException("Answer did not initialise speech");
                    runOnMainSync(()->CallService.instance.end());
                    until=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<until&&!"completed".equals(Api.call(data,"/api/calls/"+id,null).optString("state")))Thread.sleep(100);
                    if(!"completed".equals(Api.call(data,"/api/calls/"+id,null).optString("state")))throw new IllegalStateException("End failed");
                }
            }
            Thread.sleep(500);
            for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications())if("incoming".equals(n.getNotification().getChannelId())||(n.getNotification().flags&android.app.Notification.FLAG_INSISTENT)!=0)throw new IllegalStateException("Ringing notification was not cleared");
            getTargetContext().stopService(new Intent(getTargetContext(),CallService.class));
            result.putString("incoming","PASS: automatic ringing screen, foreground call notification, sound configured, actions/expiry stop ringing");
        }
        if("availability".equals(operation)){
            String credential=data.get("credential",""),device=data.get("device_id","");
            if(!data.paired())throw new IllegalStateException("Pair the test device first");
            for(int cycle=0;cycle<5;cycle++){
                runOnMainSync(()->{android.view.View button=findText(activity.getWindow().getDecorView(),"Become available");if(button==null)throw new IllegalStateException("Availability button missing");button.performClick();});
                long until=System.currentTimeMillis()+15000;
                while(System.currentTimeMillis()<until&&(CallService.instance==null||!CallService.instance.status.startsWith("Connected")))Thread.sleep(100);
                if(CallService.instance==null||!CallService.instance.status.startsWith("Connected"))throw new IllegalStateException("Receiver did not connect");
                if(CallService.instance.voiceInitialized()||CallService.instance.isListening())throw new IllegalStateException("Availability started voice resources");
                runOnMainSync(()->{android.view.View button=findText(activity.getWindow().getDecorView(),"Available · Go offline");if(button==null)throw new IllegalStateException("Offline button missing");button.performClick();});
                until=System.currentTimeMillis()+5000;while(CallService.instance!=null&&System.currentTimeMillis()<until)Thread.sleep(50);
                if(CallService.instance!=null)throw new IllegalStateException("Receiver did not stop");
                Thread.sleep(400);waitForIdleSync();
            }
            if(!credential.equals(data.get("credential",""))||!device.equals(data.get("device_id","")))throw new IllegalStateException("Pairing changed");
            Diagnostics.record(getTargetContext(),"instrumentation-regression",new IllegalStateException("PRIVATE-TEXT-MUST-NOT-UPLOAD"));
            String diagnostic=getTargetContext().getSharedPreferences("alphaMobile-diagnostics",0).getString("pending","");
            if(diagnostic.contains("PRIVATE-TEXT-MUST-NOT-UPLOAD"))throw new IllegalStateException("Exception message leaked into diagnostics");
            Diagnostics.upload(getTargetContext(),data);
            if(!"[]".equals(getTargetContext().getSharedPreferences("alphaMobile-diagnostics",0).getString("pending","")))throw new IllegalStateException("Diagnostics were not acknowledged");
            result.putString("availability","PASS: five online/offline cycles, no voice initialisation, pairing preserved, redacted diagnostics delivered");
        }
        if("handoff".equals(operation)){
            awaitReply(data,id,"I have answered. Please explain why you called.");
            runOnMainSync(()->CallService.instance.toggleMute());
            // Force the old phone-model preference: agent turns must still bypass all inference.
            data.put("engine","phone");
            long started=System.currentTimeMillis();
            runOnMainSync(()->CallService.instance.send("Please check GBrain. Do not guess."));
            long until=System.currentTimeMillis()+10000;
            while(System.currentTimeMillis()<until){JSONObject call=data.call(id);if(call!=null&&call.optBoolean("awaiting_agent")&&!CallService.instance.isBusy())break;Thread.sleep(100);}
            if(CallService.instance.isBusy()||!CallService.instance.isAwaitingAgent())throw new IllegalStateException("Question did not enter waiting state");
            result.putLong("acknowledgement_ms",System.currentTimeMillis()-started);
            runOnMainSync(()->{if(CallService.instance.isMuted())CallService.instance.toggleMute();});
            if(CallService.instance.isListening())throw new IllegalStateException("Microphone resumed while waiting for agent");
            // External test driver sends real MCP progress, then its final test answer.
            until=System.currentTimeMillis()+45000;boolean progress=false,finished=false;
            while(System.currentTimeMillis()<until){JSONObject call=data.call(id);JSONArray messages=call.optJSONArray("messages");
                for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);
                    if("integration-progress".equals(m.optString("id"))){progress=true;if(!messages.toString().contains("integration-final")&&!call.optBoolean("awaiting_agent"))throw new IllegalStateException("Progress incorrectly resolved question");}
                    if("integration-final".equals(m.optString("id")))finished=true;
                    if("assistant".equals(m.optString("role"))&&!m.optString("text").startsWith("alphaMobile"))throw new IllegalStateException("Unexpected autonomous reply");
                }
                if(finished&&!call.optBoolean("awaiting_agent"))break;Thread.sleep(100);
            }
            if(!progress||!finished)throw new IllegalStateException("Missing real MCP progress/final delivery");
            JSONObject feed=Api.call(data,"/api/messages",null);data.mergeMessages(feed.getJSONArray("messages"));
            if(!data.messages().toString().contains("integration-final"))throw new IllegalStateException("Final reply missing from saved Messages");
            runOnMainSync(()->CallService.instance.end());
            until=System.currentTimeMillis()+10000;
            while(System.currentTimeMillis()<until&&!"completed".equals(Api.call(data,"/api/calls/"+id,null).optString("state")))Thread.sleep(100);
            if(!"completed".equals(Api.call(data,"/api/calls/"+id,null).optString("state")))throw new IllegalStateException("End failed");
            result.putString("handoff","PASS: immediate hold, no phone inference, progress waits, final saved, end works");
            getTargetContext().stopService(new Intent(getTargetContext(),CallService.class));
        }
        if("chat".equals(operation)){
            runOnMainSync(()->{android.widget.EditText input=findInput(activity.getWindow().getDecorView());android.view.View send=findText(activity.getWindow().getDecorView(),"Send");if(input==null||send==null)throw new IllegalStateException("Chat composer missing");input.setText("Streaming integration question");send.performClick();});
            long until=System.currentTimeMillis()+45000;boolean partial=false,finished=false;
            while(System.currentTimeMillis()<until){JSONObject c=data.call(id);JSONArray messages=c==null?null:c.optJSONArray("messages");if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);if("stream-android-chat".equals(m.optString("id"))){
                if(!m.optBoolean("is_final")&&m.optInt("is_final")==0){partial=true;if(!c.optBoolean("awaiting_agent"))throw new IllegalStateException("Partial stream resolved question");}
                if(m.optInt("is_final")==1){finished=true;if(!m.optString("text").equals("First sentence arrived. Second sentence completes it."))throw new IllegalStateException("Stream lost text");}
            }}if(finished&&!c.optBoolean("awaiting_agent"))break;Thread.sleep(100);}
            if(!partial||!finished)throw new IllegalStateException("Missing partial/final chat delivery");
            waitForIdleSync();AtomicReference<Boolean> visible=new AtomicReference<>(false);
            runOnMainSync(()->visible.set(findText(activity.getWindow().getDecorView(),"First sentence arrived. Second sentence completes it.")!=null));
            if(!visible.get())throw new IllegalStateException("Reply not visible in same conversation");
            if(CallService.instance!=null&&!CallService.instance.activeId().isEmpty())throw new IllegalStateException("Text chat started audio call");
            result.putString("chat","PASS: UI composer sent question; partial then final answer shown in same window without ringing");
        }
        if("speech".equals(operation)){
            CountDownLatch ready=new CountDownLatch(1);AtomicReference<Model> model=new AtomicReference<>();AtomicReference<Exception> failure=new AtomicReference<>();
            StorageService.unpack(getTargetContext(),"model-en-us","speech-test",m->{model.set(m);ready.countDown();},e->{failure.set(e);ready.countDown();});
            if(!ready.await(120,TimeUnit.SECONDS))throw new IllegalStateException("Offline speech model timed out");
            if(failure.get()!=null)throw failure.get();
            StringBuilder transcript=new StringBuilder();
            try(Model loaded=model.get();Recognizer recognizer=new Recognizer(loaded,16000);java.io.InputStream audio=getContext().getAssets().open("speech-test.wav")){
                recognizer.setEndpointerDelays(5.0f,0.6f,30.0f);
                org.vosk.android.RecognitionListener listener=new org.vosk.android.RecognitionListener(){public void onPartialResult(String value){}public void onResult(String value){}public void onFinalResult(String value){}public void onError(Exception error){failure.set(error);}public void onTimeout(){}};
                SpeechCapture capture=new SpeechCapture(loaded,listener,new android.os.Handler(android.os.Looper.getMainLooper()));
                result.putBoolean("aec_enabled",capture.echoCancellation);capture.start();Thread.sleep(300);capture.close();Thread.sleep(300);
                if(failure.get()!=null)throw failure.get();
                byte[] header=new byte[44];if(audio.read(header)!=44)throw new IllegalStateException("Invalid speech fixture");
                byte[] buffer=new byte[4096];int n;while((n=audio.read(buffer))!=-1)if(recognizer.acceptWaveForm(buffer,n))transcript.append(new JSONObject(recognizer.getResult()).optString("text")).append(' ');
                transcript.append(new JSONObject(recognizer.getFinalResult()).optString("text"));
            }
            if(!transcript.toString().contains("one zero zero zero one"))throw new IllegalStateException("Unexpected recognition: "+transcript);
            result.putString("transcript",transcript.toString());
        }
        result.putString("result","PASS: "+operation);finish(Activity.RESULT_OK,result);
    }catch(Exception e){result.putString("result","FAIL: "+e.toString());finish(Activity.RESULT_CANCELED,result);}}
    private void checkCompact(Activity activity,String key,boolean open) throws Exception {waitForIdleSync();Thread.sleep(250);AtomicReference<Throwable> failure=new AtomicReference<>();runOnMainSync(()->{try{
        android.view.View root=activity.getWindow().getDecorView();android.view.ViewGroup header=root.findViewWithTag("compact:"+key);android.view.View details=root.findViewWithTag("details:"+key);
        if(header==null||details==null||header.isSelected()!=open||(details.getVisibility()==android.view.View.VISIBLE)!=open)throw new IllegalStateException("Incorrect expansion: "+key);
        if(header.getHeight()<48*activity.getResources().getDisplayMetrics().density-1)throw new IllegalStateException("Row touch target too small");
        for(int i=0;i<3;i++){android.widget.TextView text=(android.widget.TextView)header.getChildAt(i);if(text.getLineCount()>1||text.getText().length()==0||text.getWidth()==0)throw new IllegalStateException("Missing/wrapped row metadata");}
        if(!header.getContentDescription().toString().startsWith(open?"Collapse ":"Expand "))throw new IllegalStateException("Accessible expansion label missing");
    }catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new IllegalStateException("Compact row check",failure.get());}
    private android.service.notification.StatusBarNotification messageNotification(){for(android.service.notification.StatusBarNotification item:getTargetContext().getSystemService(android.app.NotificationManager.class).getActiveNotifications())if(item.getId()==MessageAlerts.NOTIFICATION_ID)return item;return null;}
    private void awaitUpdates(AppUpdates updates,long timeout) throws Exception {long until=System.currentTimeMillis()+timeout;AtomicReference<Boolean> busy=new AtomicReference<>(true);while(System.currentTimeMillis()<until){runOnMainSync(()->busy.set(updates.busy));if(!busy.get())return;Thread.sleep(100);}throw new IllegalStateException("Update operation timed out");}
    private void capture(String name) throws Exception {waitForIdleSync();Thread.sleep(400);android.graphics.Bitmap image=getUiAutomation().takeScreenshot();if(image==null)throw new IllegalStateException("Screenshot unavailable");java.io.File folder=new java.io.File(getTargetContext().getExternalFilesDir(null),"screens");if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Screenshot folder unavailable");try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,name+".png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
    private android.view.View findDescription(android.view.View view,String text){if(text.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.view.View match=findDescription(group.getChildAt(i),text);if(match!=null)return match;}}return null;}
    private android.widget.EditText findInput(android.view.View view){if(view instanceof android.widget.EditText)return (android.widget.EditText)view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.widget.EditText match=findInput(group.getChildAt(i));if(match!=null)return match;}}return null;}
    private android.view.View findText(android.view.View view,String text){if(view instanceof android.widget.TextView&&text.contentEquals(((android.widget.TextView)view).getText()))return view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.view.View match=findText(group.getChildAt(i),text);if(match!=null)return match;}}return null;}
    private void awaitReply(Data data,String id,String text) throws Exception {
        long until=System.currentTimeMillis()+150000;
        while(System.currentTimeMillis()<until){JSONObject c=data.call(id);JSONArray messages=c==null?null:c.optJSONArray("messages");String request="";
            if(messages!=null){for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);if(text.equals(m.optString("text")))request=m.getString("id");}
                for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);if(!request.isEmpty()&&("reply-"+request).equals(m.optString("id"))&&!m.optString("text").isEmpty())return;}}
            Thread.sleep(300);
        }
        throw new IllegalStateException("No greeting acknowledgement for the submitted turn");
    }
}
