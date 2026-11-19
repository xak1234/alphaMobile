package org.alphamobile.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads only from the paired box. Every install is verified and confirmed by Android. */
final class AppUpdates {
    private static final long MAX_SIZE=512L*1024*1024;
    private static AppUpdates instance;
    static synchronized AppUpdates get(Context context){
        if(instance==null)instance=new AppUpdates(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Data data;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Runnable listener;
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;
    private long lastAttempt=-300000;
    boolean busy;
    boolean downloading;
    String status="Check for releases from your paired box.";
    String source="";
    JSONObject release;
    File ready;

    private AppUpdates(Context context){this.context=context;data=new Data(context);}
    void listen(Runnable callback){listener=callback;}
    void unlisten(Runnable callback){if(listener==callback)listener=null;}
    private void changed(){if(listener!=null)listener.run();}
    private void finish(String message){busy=false;downloading=false;status=message;changed();}
    void automaticCheck(){
        if(!data.paired()||!data.flag("update_auto",true)||busy||SystemClock.elapsedRealtime()-lastAttempt<300000)return;
        long checked=data.prefs.getLong("update_checked",0);
        if(!data.base().equals(data.get("update_box",""))||System.currentTimeMillis()-checked>6*60*60*1000L)check();
    }
    void check(){
        if(busy)return;
        if(!data.paired()){status="Pair with your box to check for updates.";changed();return;}
        lastAttempt=SystemClock.elapsedRealtime();source=data.base();final String base=source;
        busy=true;ready=null;release=null;status="Checking "+boxName(base)+"…";changed();
        worker.execute(()->{
            try{
                JSONObject found=Api.request(base,"/api/updates/latest",data.token(),null,15000);
                if(found.optBoolean("available"))validateManifest(found);
                File cached=found.optBoolean("available")?apkFile(found):null;
                boolean newer=found.optBoolean("available")&&found.getLong("version_code")>BuildConfig.VERSION_CODE;
                boolean compatible=!newer||found.getInt("min_sdk")<=Build.VERSION.SDK_INT;
                if(newer&&compatible&&cached!=null&&cached.isFile()){
                    try{verify(cached,found);}catch(Exception error){if(!cached.delete())throw new IOException("Unable to remove an invalid cached update",error);}
                }
                main.post(()->{
                    if(!base.equals(data.base())){finish("Pairing changed. Check the new box for updates.");return;}
                    data.prefs.edit().putLong("update_checked",System.currentTimeMillis()).putString("update_box",base).apply();
                    release=newer&&compatible?found:null;
                    ready=release!=null&&cached!=null&&cached.isFile()?cached:null;
                    finish(!found.optBoolean("available")?"No release has been published on this box yet.":!newer?"You're up to date.":!compatible?"The latest release needs Android API "+found.optInt("min_sdk")+".":ready!=null?"Verified update ready to install.":"Version "+found.optString("version_name")+" is available.");
                });
            }catch(Exception error){main.post(()->finish("Could not check for updates. Connect to your box and try again."));}
        });
    }
    static String boxName(String base){try{String host=new URL(base).getHost();return host.isEmpty()?"your box":host;}catch(Exception error){return "your box";}}
    static void validateManifest(JSONObject info) throws Exception {
        String digest=info.getString("sha256");long size=info.getLong("size");
        if(!digest.matches("[0-9a-f]{64}")||size<=0||size>MAX_SIZE||info.getLong("version_code")<=0||info.getLong("version_code")>2100000000L||info.getInt("min_sdk")<26||!info.getString("version_name").matches("[A-Za-z0-9.+_-]{1,40}")||info.optString("release_notes").length()>8000||!info.getString("apk_path").equals("/api/updates/apk/"+digest))throw new IOException("Invalid release information from the box");
    }
    private File apkFile(JSONObject info){return new File(new File(context.getCacheDir(),"updates"),info.optString("sha256")+".apk");}
    void cancel(){cancelled=true;HttpURLConnection current=connection;if(current!=null)current.disconnect();}
    void download(){
        if(busy||release==null)return;
        if(!source.equals(data.base())){check();return;}
        final JSONObject target=release;final String base=source;
        busy=true;downloading=true;cancelled=false;ready=null;status="Starting download…";changed();
        worker.execute(()->{
            File output=apkFile(target);File partial=new File(output.getParentFile(),"download.part");
            try{
                validateManifest(target);
                File folder=output.getParentFile();
                if(folder==null||(!folder.isDirectory()&&!folder.mkdirs()))throw new IOException("Unable to create update folder");
                File[] files=folder.listFiles();if(files!=null)for(File old:files)if(!old.equals(output)&&!old.delete())throw new IOException("Unable to clear an older update");
                long expected=target.getLong("size");
                if(folder.getUsableSpace()<expected*2+20*1024*1024L)throw new IOException("Free some phone storage before downloading");
                URL url=new URL(base+target.getString("apk_path"));
                if(!"https".equals(url.getProtocol())&&!("http".equals(url.getProtocol())&&("127.0.0.1".equals(url.getHost())||"localhost".equals(url.getHost()))))throw new IOException("A secure box connection is required");
                HttpURLConnection current=(HttpURLConnection)url.openConnection();connection=current;
                current.setInstanceFollowRedirects(false);current.setConnectTimeout(12000);current.setReadTimeout(15000);
                current.setRequestProperty("Authorization","Bearer "+data.token());
                current.setRequestProperty("Accept-Encoding","identity");
                long deadline=SystemClock.elapsedRealtime()+10*60*1000L;
                try{
                    if(current.getResponseCode()!=200)throw new IOException("Update download failed. Check your box connection");
                    long length=current.getContentLengthLong();if(length!=-1&&length!=expected)throw new IOException("Update size does not match the release");
                    try(InputStream in=current.getInputStream();FileOutputStream out=new FileOutputStream(partial)){
                        byte[] buffer=new byte[65536];long count=0,last=0;int n;
                        while((n=in.read(buffer))!=-1){
                            if(cancelled)throw new IOException("Download cancelled");
                            if(SystemClock.elapsedRealtime()>deadline)throw new IOException("Download timed out. Try again");
                            count+=n;if(count>expected)throw new IOException("Update is larger than expected");out.write(buffer,0,n);
                            if(SystemClock.elapsedRealtime()-last>300){last=SystemClock.elapsedRealtime();final int percent=(int)(count*100/expected);main.post(()->{status="Downloading · "+percent+"%";changed();});}
                        }
                        if(count!=expected)throw new IOException("Download was interrupted. Try again");out.getFD().sync();
                    }
                }finally{current.disconnect();connection=null;}
                if(cancelled)throw new IOException("Download cancelled");
                main.post(()->{status="Verifying update…";changed();});verify(partial,target);
                if(cancelled)throw new IOException("Download cancelled");
                if(!base.equals(data.base()))throw new IOException("Pairing changed. Check the new box for updates");
                if(!partial.renameTo(output))throw new IOException("Unable to save the verified update");
                main.post(()->{ready=output;finish("Verified update ready to install.");});
            }catch(Exception error){String message=cancelled?"Download cancelled. You can try again.":error.getMessage();main.post(()->finish(message==null?"Update failed. Please try again.":message));}
            finally{if(partial.exists()&&!partial.delete())partial.deleteOnExit();}
        });
    }
    void prepareInstall(java.util.function.Consumer<Intent> launch){
        if(busy||ready==null||release==null)return;
        final File file=ready;final JSONObject target=release;final String base=source;
        busy=true;status="Checking installation…";changed();
        worker.execute(()->{try{
            verify(file,target);
            if(!base.equals(data.base()))throw new IOException("Pairing changed. Check for updates again");
            Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(FileProvider.getUriForFile(context,context.getPackageName()+".updates",file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true);
            main.post(()->{finish("Confirm the update in Android's installer.");launch.accept(intent);});
        }catch(Exception error){main.post(()->{ready=null;finish("Unable to verify this update. Download it again.");});}});
    }
    @SuppressWarnings("deprecation")
    void verify(File file,JSONObject info) throws Exception {
        validateManifest(info);
        if(file.length()!=info.getLong("size"))throw new IOException("Incomplete update. Download it again");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
        if(!hex(digest.digest()).equals(info.getString("sha256")))throw new IOException("Update integrity check failed");
        PackageManager pm=context.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo archive=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags),installed=pm.getPackageInfo(context.getPackageName(),flags);
        if(archive==null||!context.getPackageName().equals(archive.packageName)||archive.applicationInfo==null)throw new IOException("This update is not alphaMobile");
        long version=Build.VERSION.SDK_INT>=28?archive.getLongVersionCode():archive.versionCode;
        if(version!=info.getLong("version_code")||version<=BuildConfig.VERSION_CODE||!info.getString("version_name").equals(archive.versionName)||archive.applicationInfo.minSdkVersion!=info.getInt("min_sdk")||archive.applicationInfo.minSdkVersion>Build.VERSION.SDK_INT)throw new IOException("This release does not match the update information");
        Set<String> signatures=signers(installed);if(signatures.isEmpty()||!signatures.equals(signers(archive)))throw new IOException("Update signature does not match this installation");
    }
    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo info) throws Exception {
        Signature[] signatures=Build.VERSION.SDK_INT>=28?(info.signingInfo==null?null:info.signingInfo.getApkContentsSigners()):info.signatures;
        Set<String> result=new HashSet<>();if(signatures!=null)for(Signature signature:signatures)result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));return result;
    }
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
}
