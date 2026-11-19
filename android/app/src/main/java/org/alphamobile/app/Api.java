package org.alphamobile.app;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class Api {
    private Api() { }
    static JSONObject request(String base, String path, String token, JSONObject body, int timeout) throws Exception {
        URL url = new URL(base + path);
        if(!"https".equals(url.getProtocol()) && !("http".equals(url.getProtocol()) && ("127.0.0.1".equals(url.getHost()) || "localhost".equals(url.getHost()))))
            throw new IOException("Use the secure HTTPS address of your box");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(12000); connection.setReadTimeout(timeout);
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if(body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out=connection.getOutputStream()) {out.write(bytes);}
            }
            int status=connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = stream == null ? "{}" : read(stream);
            JSONObject result;
            try { result=new JSONObject(response); } catch(Exception e) { throw new IOException("Unexpected response from the box ("+status+")"); }
            if(status < 200 || status >= 300) throw new IOException(result.optString("detail", "Connection failed ("+status+")"));
            return result;
        } finally {connection.disconnect();}
    }
    static JSONObject call(Data data, String path, JSONObject body) throws Exception { return request(data.base(), path, data.token(), body, 150000); }
    private static String read(InputStream stream) throws IOException {
        try(InputStream input=stream; ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int n;
            while((n=input.read(buffer))!=-1) { if(output.size()+n>2000000) throw new IOException("Response too large"); output.write(buffer,0,n); }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
