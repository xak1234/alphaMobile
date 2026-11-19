package org.alphamobile.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Durable per-message read state; repeated snapshots do not revive a read reply. */
final class MessageState {
    private static final Object LOCK=new Object();
    private MessageState(){}
    static String version(JSONObject message){
        try{boolean last=message.optBoolean("is_final",message.optInt("is_final",1)==1);
            byte[] hash=MessageDigest.getInstance("SHA-256").digest((message.optString("text")+"\n"+last).getBytes(StandardCharsets.UTF_8));
            return android.util.Base64.encodeToString(hash,android.util.Base64.NO_WRAP);
        }catch(Exception error){throw new IllegalStateException(error);}
    }
    private static JSONObject state(Data data){try{return new JSONObject(data.get("message_read_state","{}"));}catch(Exception error){return new JSONObject();}}
    static void initialize(Data data){synchronized(LOCK){
        String device=data.get("device_id","");if(device.equals(data.get("message_read_device",""))&&data.prefs.contains("message_read_started"))return;
        try{JSONObject entries=new JSONObject();JSONArray cached=data.messages();for(int i=0;i<cached.length();i++){JSONObject m=cached.getJSONObject(i);entries.put(m.getString("id"),new JSONObject().put("version",version(m)).put("read",true));}
            if(!data.prefs.edit().putString("message_read_state",entries.toString()).putString("message_read_device",device).putLong("message_read_started",System.currentTimeMillis()).commit())throw new IllegalStateException("Unable to save message read state");
        }catch(org.json.JSONException error){throw new IllegalStateException(error);}
    }}
    static void observe(Data data,JSONArray messages){synchronized(LOCK){initialize(data);try{
        JSONObject entries=state(data);boolean changed=false;
        for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);String id=m.getString("id"),revision=version(m);JSONObject old=entries.optJSONObject(id);
            if(old!=null&&revision.equals(old.optString("version")))continue;
            boolean historical=old==null&&m.optDouble("created")*1000<data.prefs.getLong("message_read_started",0);
            entries.put(id,new JSONObject().put("version",revision).put("read",historical));changed=true;
        }
        if(changed&&!data.prefs.edit().putString("message_read_state",entries.toString()).commit())throw new IllegalStateException("Unable to save unread messages");
    }catch(org.json.JSONException error){throw new IllegalStateException(error);}}}
    static boolean unread(Data data,JSONObject message){synchronized(LOCK){JSONObject saved=state(data).optJSONObject(message.optString("id"));return saved!=null&&!saved.optBoolean("read",true);}}
    static JSONArray unread(Data data){synchronized(LOCK){JSONArray result=new JSONArray(),messages=data.messages();JSONObject entries=state(data);for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m==null)continue;JSONObject saved=entries.optJSONObject(m.optString("id"));if(saved!=null&&!saved.optBoolean("read",true))result.put(m);}return result;}}
    static int count(Data data,String call){JSONArray messages=unread(data);int count=0;for(int i=0;i<messages.length();i++)if(call==null||call.equals(messages.optJSONObject(i).optString("call_id")))count++;return count;}
    static void read(Data data,JSONObject message){synchronized(LOCK){try{
        JSONObject entries=state(data);String id=message.getString("id"),revision=version(message);JSONObject current=entries.optJSONObject(id);
        // An earlier visible revision must not acknowledge a newer reply arriving concurrently.
        if(current!=null&&!revision.equals(current.optString("version")))return;
        if(current!=null&&current.optBoolean("read"))return;
        entries.put(id,new JSONObject().put("version",revision).put("read",true));
        if(!data.prefs.edit().putString("message_read_state",entries.toString()).commit())throw new IllegalStateException("Unable to save message read receipt");
    }catch(org.json.JSONException error){throw new IllegalStateException(error);}}}
    static void readConversation(Data data,String call){JSONArray all=data.messages();for(int i=0;i<all.length();i++){JSONObject m=all.optJSONObject(i);if(m!=null&&call.equals(m.optString("call_id")))read(data,m);}}
}
