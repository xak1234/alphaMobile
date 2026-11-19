package org.alphamobile.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private state. Device tokens are encrypted with Android Keystore. */
final class Data {
    private static final Object CALLS_LOCK=new Object();
    final SharedPreferences prefs;
    Data(Context context) { prefs = context.getSharedPreferences("alphaMobile", Context.MODE_PRIVATE); }
    String get(String key, String fallback) { return prefs.getString(key, fallback); }
    void put(String key, String value) { prefs.edit().putString(key, value).apply(); }
    boolean flag(String key, boolean fallback) { return prefs.getBoolean(key, fallback); }
    void flag(String key, boolean value, boolean persist) { prefs.edit().putBoolean(key, value).apply(); }
    String base() { return get("backend", BuildConfig.DEFAULT_BACKEND).replaceAll("/+$", ""); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias("alphaMobile-device")) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("alphaMobile-device", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey("alphaMobile-device", null);
    }
    void token(String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!prefs.edit().putString("credential", encrypted).commit()) throw new IllegalStateException("Unable to save pairing");
    }
    String token() throws Exception {
        String value = get("credential", ""); if (value.isEmpty()) return "";
        String[] parts = value.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    boolean paired() { return !get("credential", "").isEmpty(); }
    JSONArray calls() { try {
        JSONArray calls=new JSONArray(get("calls", "[]"));JSONObject ends=pendingEnds();
        for(int i=0;i<calls.length();i++){JSONObject call=calls.optJSONObject(i);if(call!=null&&ends.has(call.optString("id"))&&!isTerminal(call))call.put("state","completed");}
        return calls;
    } catch (Exception e) { return new JSONArray(); } }
    static boolean isTerminal(JSONObject call) {
        if(call==null)return false;
        String state=call.optString("state");return "completed".equals(state)||"declined".equals(state)||"cancelled".equals(state)||"missed".equals(state)||"failed".equals(state);
    }
    JSONObject pendingEnds(){try{return new JSONObject(get("pending_ends","{}"));}catch(Exception e){return new JSONObject();}}
    boolean hasPendingEnd(String id){return pendingEnds().has(id);}
    void endLocally(String id) {
        if(id==null||id.isEmpty())return;
        synchronized(CALLS_LOCK){try{
            JSONObject ends=pendingEnds();ends.put(id,true);
            if(!prefs.edit().putString("pending_ends",ends.toString()).commit())throw new IllegalStateException("Unable to save call end");
        }catch(org.json.JSONException e){throw new IllegalStateException(e);}}
    }
    void acknowledgeEnd(JSONObject call) {
        synchronized(CALLS_LOCK){
            if(!isTerminal(call))throw new IllegalArgumentException("Call has not ended on the box");
            saveCall(call);JSONObject ends=pendingEnds();ends.remove(call.optString("id"));
            if(!prefs.edit().putString("pending_ends",ends.toString()).commit())throw new IllegalStateException("Unable to confirm call end");
        }
    }
    private static boolean keepPrevious(JSONObject old,JSONObject current){
        return old!=null&&(isTerminal(old)&&!isTerminal(current)||old.optDouble("updated")>current.optDouble("updated"));
    }
    static boolean isIncoming(JSONObject call) {
        return call!=null&&"ringing".equals(call.optString("state"))&&!"chat".equals(call.optString("channel"))
            &&call.optDouble("expires",0)*1000>System.currentTimeMillis();
    }
    JSONObject incoming() {
        JSONArray all=calls();for(int i=0;i<all.length();i++){JSONObject call=all.optJSONObject(i);if(isIncoming(call))return call;}return null;
    }
    JSONObject call(String id) {
        JSONArray calls = calls();
        for (int i=0;i<calls.length();i++) { JSONObject c=calls.optJSONObject(i); if (c != null && id.equals(c.optString("id"))) return c; }
        return null;
    }
    void mergeInbox(JSONArray incoming) throws Exception { synchronized(CALLS_LOCK){
        JSONArray previous=calls(), merged=new JSONArray();java.util.HashSet<String> ids=new java.util.HashSet<>();
        for(int i=0;i<incoming.length();i++){JSONObject current=incoming.getJSONObject(i);String id=current.getString("id");ids.add(id);JSONObject old=call(id);
            if(keepPrevious(old,current)){merged.put(old);continue;}
            if(old!=null){java.util.LinkedHashMap<String,JSONObject> messages=new java.util.LinkedHashMap<>();JSONArray oldMessages=old.optJSONArray("messages"),newMessages=current.optJSONArray("messages");
                if(oldMessages!=null)for(int j=0;j<oldMessages.length();j++){JSONObject m=oldMessages.getJSONObject(j);messages.put(m.getString("id"),m);}
                if(newMessages!=null)for(int j=0;j<newMessages.length();j++){JSONObject m=newMessages.getJSONObject(j);messages.put(m.getString("id"),m);}
                JSONArray combined=new JSONArray();for(JSONObject m:messages.values())combined.put(m);current.put("messages",combined);
                if(current.optString("context").isEmpty())current.put("context",old.optString("context"));
            }merged.put(current);
        }
        for(int i=0;i<previous.length()&&merged.length()<200;i++){JSONObject c=previous.getJSONObject(i);if(!ids.contains(c.optString("id")))merged.put(c);}
        put("calls",merged.toString());
    }}
    void snapshot(JSONObject result) throws Exception {
        if(result.has("calls"))mergeInbox(result.getJSONArray("calls"));
        if(result.has("messages"))mergeMessages(result.getJSONArray("messages"));
        if(result.has("total"))put("message_total",String.valueOf(result.optInt("total")));
        for(String key:new String[]{"agents","tasks","task_sync"})if(result.has(key))put(key,result.getJSONArray(key).toString());
        if(result.has("ai_connected")){flag("ai_connected",result.optBoolean("ai_connected"),true);put("connection_checked",String.valueOf(System.currentTimeMillis()));}
        if(result.has("task_sync")){JSONArray sync=result.getJSONArray("task_sync");boolean complete=sync.length()>0;for(int i=0;i<sync.length();i++)complete&="completed".equals(sync.getJSONObject(i).optString("state"));flag("tasks_synced",complete,true);}
    }
    boolean aiConnected(){try{return flag("ai_connected",false)&&System.currentTimeMillis()-Long.parseLong(get("connection_checked","0"))<15000;}catch(Exception e){return false;}}
    JSONArray messages(){try{return new JSONArray(get("messages","[]"));}catch(Exception e){return new JSONArray();}}
    void mergeMessages(JSONArray incoming) throws Exception { synchronized(CALLS_LOCK){
        MessageState.initialize(this);
        java.util.LinkedHashMap<String,JSONObject> byId=new java.util.LinkedHashMap<>();
        JSONArray old=messages();for(int i=0;i<old.length();i++){JSONObject m=old.getJSONObject(i);byId.put(m.getString("id"),m);}
        for(int i=0;i<incoming.length();i++){JSONObject m=incoming.getJSONObject(i),previous=byId.get(m.getString("id"));if(previous!=null&&previous.optInt("is_stream")==1&&(previous.optString("text").length()>m.optString("text").length()||previous.optInt("is_final")==1&&m.optInt("is_final")==0))continue;byId.put(m.getString("id"),m);}
        java.util.ArrayList<JSONObject> sorted=new java.util.ArrayList<>(byId.values());
        sorted.sort((a,b)->Double.compare(b.optDouble("created"),a.optDouble("created")));
        JSONArray result=new JSONArray();for(JSONObject m:sorted)result.put(m);put("messages",result.toString());MessageState.observe(this,result);
    }}
    void saveCall(JSONObject call) { synchronized(CALLS_LOCK){
        JSONArray old=calls(), updated=new JSONArray(); boolean found=false;
        for (int i=0;i<old.length();i++) { JSONObject c=old.optJSONObject(i); if(c == null) continue;
            if(c.optString("id").equals(call.optString("id"))) {updated.put(keepPrevious(c,call)?c:call);found=true;} else updated.put(c); }
        if(!found){JSONArray ordered=new JSONArray();ordered.put(call);for(int i=0;i<updated.length();i++)ordered.put(updated.optJSONObject(i));updated=ordered;}
        put("calls", updated.toString());
    }}
}
