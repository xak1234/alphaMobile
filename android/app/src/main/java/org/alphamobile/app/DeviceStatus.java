package org.alphamobile.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Foreground-only device readings. No location, SIM identifiers or phone permissions. */
final class DeviceStatus extends LinearLayout {
    private final Ui ui;
    private final TextView networkText,batteryText;
    private final Meter signal,battery;
    private int strength=-1,percent=-1;
    private boolean charging;
    DeviceStatus(Context context,Ui ui){super(context);this.ui=ui;setGravity(Gravity.CENTER_VERTICAL);setPadding(ui.dp(20),ui.dp(5),ui.dp(20),ui.dp(2));
        signal=new Meter(context,false);addView(signal,new LayoutParams(ui.dp(21),ui.dp(17)));networkText=ui.text("Network",11,ui.sub,false);networkText.setPadding(ui.dp(7),0,0,0);addView(networkText,new LayoutParams(0,ui.dp(24),1));networkText.setGravity(Gravity.CENTER_VERTICAL);
        batteryText=ui.text("—",11,ui.sub,false);batteryText.setPadding(0,0,ui.dp(8),0);addView(batteryText);battery=new Meter(context,true);addView(battery,new LayoutParams(ui.dp(27),ui.dp(15)));refresh();
    }
    @SuppressWarnings("deprecation")
    void refresh(){
        String network="Offline";strength=-1;
        try{ConnectivityManager cm=getContext().getSystemService(ConnectivityManager.class);Network active=cm.getActiveNetwork();NetworkCapabilities caps=active==null?null:cm.getNetworkCapabilities(active);
            if(caps!=null){
                NetworkCapabilities wifi=null;boolean cellular=caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                for(Network candidate:cm.getAllNetworks()){NetworkCapabilities info=cm.getNetworkCapabilities(candidate);if(info==null)continue;if(info.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))wifi=info;if(info.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))cellular=true;}
                if(wifi!=null){network="Wi-Fi";WifiInfo info=Build.VERSION.SDK_INT>=29&&wifi.getTransportInfo() instanceof WifiInfo?(WifiInfo)wifi.getTransportInfo():null;
                    if(info==null){WifiManager manager=getContext().getApplicationContext().getSystemService(WifiManager.class);if(manager!=null)info=manager.getConnectionInfo();}
                    if(info!=null&&info.getRssi()>-127&&info.getRssi()<0)strength=WifiManager.calculateSignalLevel(info.getRssi(),5);
                }else if(cellular){network="Mobile";if(Build.VERSION.SDK_INT>=28){TelephonyManager manager=getContext().getSystemService(TelephonyManager.class);int subscription=SubscriptionManager.getDefaultDataSubscriptionId();if(manager!=null){if(subscription!=SubscriptionManager.INVALID_SUBSCRIPTION_ID)manager=manager.createForSubscriptionId(subscription);SignalStrength reading=manager.getSignalStrength();if(reading!=null)strength=reading.getLevel();}}}
                else network=caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"Ethernet":caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)?"VPN":"Connected";
            }
        }catch(RuntimeException unavailable){strength=-1;}
        strength=Math.max(-1,Math.min(4,strength));networkText.setText(network+(strength>=0?" · "+strength+"/4":network.equals("Offline")?"":" · —"));signal.setContentDescription(network+(strength>=0?", signal "+strength+" of 4":", signal unavailable"));
        Intent reading=getContext().registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));percent=-1;charging=false;
        if(reading!=null){int level=reading.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=reading.getIntExtra(BatteryManager.EXTRA_SCALE,-1),status=reading.getIntExtra(BatteryManager.EXTRA_STATUS,-1);if(level>=0&&scale>0)percent=Math.min(100,Math.round(level*100f/scale));charging=status==BatteryManager.BATTERY_STATUS_CHARGING||(status==BatteryManager.BATTERY_STATUS_FULL&&reading.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0);}
        batteryText.setText(percent<0?"Battery —":percent+"%"+(charging?" · Charging":""));batteryText.setTextColor(percent>=0&&percent<=15&&!charging?ui.red:ui.sub);battery.setContentDescription(percent<0?"Battery level unavailable":"Battery "+percent+" percent"+(charging?", charging":""));signal.invalidate();battery.invalidate();
    }
    private final class Meter extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean isBattery;
        Meter(Context context,boolean isBattery){super(context);this.isBattery=isBattery;}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.save();canvas.scale(getWidth()/(isBattery?28f:22f),getHeight()/(isBattery?16f:18f));paint.setStrokeWidth(1.2f);
            if(isBattery){int color=percent>=0&&percent<=15&&!charging?ui.red:ui.accent;paint.setStyle(Paint.Style.STROKE);paint.setColor(color);canvas.drawRoundRect(1,2,24,14,2,2,paint);paint.setStyle(Paint.Style.FILL);canvas.drawRoundRect(25,5,27,11,1,1,paint);if(percent>=0)canvas.drawRect(3,4,3+19*percent/100f,12,paint);if(charging){paint.setColor(ui.bg);Path bolt=new Path();bolt.moveTo(14,3);bolt.lineTo(9,9);bolt.lineTo(13,9);bolt.lineTo(11,13);bolt.lineTo(17,7);bolt.lineTo(13,7);bolt.close();canvas.drawPath(bolt,paint);}}
            else for(int i=0;i<4;i++){paint.setColor(i<strength?ui.accent:ui.line);paint.setStyle(Paint.Style.FILL);canvas.drawRoundRect(1+i*5,14-i*4,4+i*5,18,0.8f,0.8f,paint);}
            canvas.restore();
        }
    }
}
