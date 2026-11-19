package org.alphamobile.app;

import android.content.Context;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Durable hang-up delivery; never keeps microphone capture or availability alive. */
final class CallEndings {
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private CallEndings() { }
    static void schedule(Context context){Data data=new Data(context.getApplicationContext());worker.execute(()->sync(data));}
    static synchronized void sync(Data data){
        if(!data.paired())return;
        Iterator<String> ids=data.pendingEnds().keys();
        while(ids.hasNext()){
            String id=ids.next();
            try{
                JSONObject call=Api.request(data.base(),"/api/calls/"+id,data.token(),null,8000);
                if(!Data.isTerminal(call)){
                    String state="ringing".equals(call.optString("state"))?"declined":"completed";
                    try{call=Api.request(data.base(),"/api/calls/"+id+"/state",data.token(),new JSONObject().put("state",state),8000);}
                    catch(Exception error){
                        // The agent or expiry may have ended it between GET and POST.
                        call=Api.request(data.base(),"/api/calls/"+id,data.token(),null,8000);
                        if(!Data.isTerminal(call))return;
                    }
                }
                if(Data.isTerminal(call))data.acknowledgeEnd(call);
            }catch(Exception error){return;} // Keep the request for reconnection or the next app launch.
        }
    }
}
