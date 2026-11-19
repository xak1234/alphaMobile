package org.alphamobile.app;

import android.app.Application;

/** Keep crash evidence without trying to relaunch a failing receiver. */
public final class AlphaApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();MessageAlerts.get(this);
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            Diagnostics.record(this,"uncaught",error);
            getSharedPreferences("alphaMobile",MODE_PRIVATE).edit()
                .putBoolean("receiver_recovery",true).putBoolean("available",false)
                .putString("availability_error","Receiver stopped unexpectedly. Tap Become available to retry.").commit();
            if(previous!=null)previous.uncaughtException(thread,error);
            else {android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
        });
        new Thread(()->Diagnostics.previousExit(this),"alphaMobile-exit-check").start();
    }
}
