package org.alphamobile.localmodel;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class LoopbackApi {
    private LoopbackApi() {}
    static JSONObject request(String base,String path,String token,JSONObject body,int timeout) throws Exception {
        URL target=new URL(base+path);
        if(!"http".equals(target.getProtocol())||!"127.0.0.1".equals(target.getHost())||target.getPort()!=18089)throw new IOException("Local model endpoint must be loopback");
        HttpURLConnection connection=(HttpURLConnection)target.openConnection();
        try {
            connection.setConnectTimeout(5000);connection.setReadTimeout(timeout);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization","Bearer "+token);
            if(body!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");try(java.io.OutputStream out=connection.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=connection.getResponseCode();if(status<200||status>=300)throw new IOException("Local model HTTP "+status);
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(out.size()+n>1048576)throw new IOException("Local model response too large");out.write(buffer,0,n);}return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        }finally{connection.disconnect();}
    }
}
