package org.alphamobile.localmodel;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Bundled, pinned llama.cpp executable; private loopback API, no external listener. */
public final class LocalModel {
    private final Context context;
    private volatile Process process;
    private final String secret=UUID.randomUUID().toString();
    public LocalModel(Context context) { this.context=context.getApplicationContext(); }
    public File model() { return new File(context.getFilesDir(), "assistant.gguf"); }
    public void importFile(Uri uri) throws Exception {
        close(); File temporary=new File(context.getFilesDir(), "assistant.gguf.part");
        try(InputStream input=context.getContentResolver().openInputStream(uri); FileOutputStream out=new FileOutputStream(temporary)) {
            if(input==null) throw new IOException("Cannot open model");
            byte[] magic=new byte[4]; int offset=0;
            while(offset<4) { int n=input.read(magic,offset,4-offset); if(n<0) throw new IOException("Incomplete model"); offset+=n; }
            if(!"GGUF".equals(new String(magic,StandardCharsets.US_ASCII))) throw new IOException("Choose a GGUF model file");
            out.write(magic); byte[] buffer=new byte[1024*1024]; int n;
            while((n=input.read(buffer))!=-1) {
                if(context.getSystemService(android.os.storage.StorageManager.class).getAllocatableBytes(context.getSystemService(android.os.storage.StorageManager.class).getUuidForPath(context.getFilesDir()))<n+128L*1024*1024) throw new IOException("Not enough phone storage");
                out.write(buffer,0,n);
            }
            out.getFD().sync();
        } catch(Exception e) { if(!temporary.delete()) temporary.deleteOnExit(); throw e; }
        if(!temporary.renameTo(model())) throw new IOException("Could not save model");
    }
    private void start(java.util.function.BooleanSupplier current) throws Exception {
        if(!current.getAsBoolean())throw new IOException("Call ended");
        if(process!=null && process.isAlive()) return;
        if(!model().isFile()) throw new IOException("Import a compatible GGUF before starting the local model");
        File executable=new File(context.getApplicationInfo().nativeLibraryDir,"libalpha_server.so");
        if(!executable.isFile()) throw new IOException("No local engine for this CPU");
        File key=new File(context.getFilesDir(), "engine-key");
        try(FileOutputStream out=new FileOutputStream(key)) {out.write(secret.getBytes(StandardCharsets.UTF_8));}
        Process started=new ProcessBuilder(executable.getAbsolutePath(),"--model",model().getAbsolutePath(),"--host","127.0.0.1","--port","18089", "--api-key-file",key.getAbsolutePath(),"--alias","alphaMobile", "--ctx-size","4096","--threads","4","--parallel","1", "--batch-size","128","--ubatch-size","64","--gpu-layers","0","--fit","off","--jinja","--no-webui","--reasoning","off","--no-reasoning-preserve")
            .redirectErrorStream(true).redirectOutput(new File(context.getFilesDir(),"local-model.log")).start();
        process=started;
        long until=System.currentTimeMillis()+180000;
        while(System.currentTimeMillis()<until) {
            if(!current.getAsBoolean()){close();throw new IOException("Call ended");}
            if(process!=started || !started.isAlive()) throw new IOException("Model stopped or could not load. Try a smaller compatible GGUF.");
            try {LoopbackApi.request("http://127.0.0.1:18089","/health",secret,null,2000); return;}
            catch(Exception e) {Thread.sleep(400);}
        }
        close(); throw new IOException("Model load timed out");
    }
    public String reply(JSONArray messages,java.util.function.BooleanSupplier current) throws Exception {
        start(current);
        if(!current.getAsBoolean()){close();throw new IOException("Call ended");}
        JSONObject result=LoopbackApi.request("http://127.0.0.1:18089", "/v1/chat/completions",secret,
            new JSONObject().put("model","alphaMobile").put("messages",messages).put("stream",false).put("temperature",0.3).put("max_tokens",180),180000);
        String reply=result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
        if(reply.isEmpty()) throw new IOException("The model returned an empty response");
        return reply;
    }
    public synchronized void close() {if(process!=null) {process.destroy(); if(process.isAlive()) process.destroyForcibly(); process=null;}}
}
