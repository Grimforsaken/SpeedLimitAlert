package com.ovz.carscanner;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import android.speech.tts.TextToSpeech;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoadSpeedRuntime implements LocationListener {
    private static RoadSpeedRuntime instance;
    private final Context c;
    private final RoadDatabase db;
    private final LocationManager lm;
    private TextToSpeech tts;
    private int lastLimit=-1,overCount=0;
    private boolean armed=true;
    private final AtomicBoolean updateRunning=new AtomicBoolean(false);
    private final Handler main=new Handler(Looper.getMainLooper());

    private RoadSpeedRuntime(Context c){
        this.c=c.getApplicationContext();db=new RoadDatabase(this.c);lm=(LocationManager)this.c.getSystemService(Context.LOCATION_SERVICE);
        tts=new TextToSpeech(this.c,s->{if(s==TextToSpeech.SUCCESS)tts.setLanguage(Locale.US);});
    }
    public static synchronized void start(Context c){
        if(instance==null)instance=new RoadSpeedRuntime(c);
        instance.ensureLocation();
        instance.maybeAutoUpdate();
        instance.postSetupNotification();
    }
    public static synchronized RoadSpeedRuntime get(Context c){start(c);return instance;}
    private void ensureLocation(){
        if(c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            main.postDelayed(this::ensureLocation,15000);return;
        }
        try{lm.removeUpdates(this);}catch(Exception ignored){}
        try{lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,900,1,this);}catch(Exception ignored){}
    }
    @Override public void onLocationChanged(Location l){
        if(l==null)return;
        RoadDatabase.Match m=null;
        if(!l.hasAccuracy()||l.getAccuracy()<=60)m=db.find(l.getLatitude(),l.getLongitude(),l.hasBearing()?l.getBearing():null);
        int limit=m==null?-1:m.limitMph;
        if(limit>0){lastLimit=limit;writeValue(String.valueOf(limit));}
        else {lastLimit=-1;writeValue("NaN");}
        if(l.hasSpeed())warning(l.getSpeed()*2.236936292,limit);
    }
    private void warning(double speed,int limit){
        if(!PatchPrefs.get(c).getBoolean(PatchPrefs.WARNING,true)||limit<=0){overCount=0;return;}
        int off=PatchPrefs.get(c).getInt(PatchPrefs.WARNING_OFFSET,5);
        if(speed>limit+off){
            overCount++;
            if(armed&&overCount>=2){armed=false;if(tts!=null)tts.speak("Speed warning. Limit "+limit,TextToSpeech.QUEUE_FLUSH,null,"road-speed-warning");}
        }else{overCount=0;if(speed<=limit+Math.max(0,off-2))armed=true;}
    }
    private void writeValue(String value){
        File dst=new File(c.getFilesDir(),"GPS_ALTITUDE"),tmp=new File(c.getFilesDir(),"GPS_ALTITUDE.tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(value.getBytes(StandardCharsets.US_ASCII));out.getFD().sync();}
        catch(Exception e){return;}
        if(dst.exists())dst.delete();tmp.renameTo(dst);
    }
    private void maybeAutoUpdate(){
        if(!PatchPrefs.get(c).getBoolean(PatchPrefs.AUTO_UPDATE,true)||!RoadDataManager.isWifi(c)||updateRunning.get())return;
        long last=PatchPrefs.get(c).getLong(PatchPrefs.LAST_UPDATE,0);
        boolean stale=!RoadDatabase.currentFile(c).exists()||System.currentTimeMillis()-last>7L*24*60*60*1000;
        if(!stale)return;
        new Thread(()->{
            if(!updateRunning.compareAndSet(false,true))return;
            try{
                File f=RoadDataManager.downloadGeofabrik(c,PatchPrefs.get(c).getString(PatchPrefs.REGION,"oklahoma"),(p,t)->{});
                if(f!=null){new RoadDataImporter(c,f,(p,t)->{}).run();PatchPrefs.get(c).edit().putLong(PatchPrefs.LAST_UPDATE,System.currentTimeMillis()).apply();if(PatchPrefs.get(c).getBoolean(PatchPrefs.DELETE_SOURCE,true))f.delete();}
            }catch(Exception ignored){}finally{updateRunning.set(false);}
        },"CarScanner-RoadData").start();
    }
    private void postSetupNotification(){
        if(RoadDatabase.currentFile(c).exists())return;
        try{
            NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
            String ch="carscanner_speed_limit";
            if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(ch,"Offline speed limits",NotificationManager.IMPORTANCE_LOW));
            Intent i=new Intent(c,SpeedLimitOfflineRoadDataSettingsActivityPatch.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi=PendingIntent.getActivity(c,7211,i,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
            Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,ch):new Notification.Builder(c);
            nm.notify(7211,b.setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Car Scanner: Speed Limit").setContentText("Tap to install offline road speed-limit data").setContentIntent(pi).setAutoCancel(true).build());
        }catch(Exception ignored){}
    }
    @Override public void onProviderEnabled(String p){}
    @Override public void onProviderDisabled(String p){}
    @Override public void onStatusChanged(String p,int s,Bundle b){}
}
