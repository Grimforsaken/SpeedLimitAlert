package com.roadspeed.alert;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

public class SpeedMonitorService extends Service implements LocationListener,ObdClient.Listener {
    public static final String ACTION_STATUS="com.roadspeed.alert.STATUS";private static final int NOTIFY=7001;
    private LocationManager lm;private RoadDatabase roadDb;private ObdClient obd;private TextToSpeech tts;private volatile Location lastLocation;
    private volatile double obdSpeed=-1;private volatile long lastObdAt;private int offset=5;private boolean gpsFallback=true,armed=true;private long lastWarning;private String obdStatus="Starting";
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("monitor","Speed monitoring",NotificationManager.IMPORTANCE_LOW));roadDb=new RoadDatabase(this);lm=getSystemService(LocationManager.class);tts=new TextToSpeech(this,s->{if(s==TextToSpeech.SUCCESS)tts.setLanguage(Locale.US);});}
    @Override public int onStartCommand(Intent i,int flags,int id){
        String addr=i==null?null:i.getStringExtra("address");offset=i==null?5:i.getIntExtra("offset",5);gpsFallback=i==null||i.getBooleanExtra("gpsFallback",true);startForeground(NOTIFY,notification("Starting…"));
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)try{lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,2,this);}catch(Exception ignored){}
        if(addr!=null&&!addr.isEmpty()){if(obd!=null)obd.close();obd=new ObdClient(addr,this);obd.start();}else obdStatus="No OBD selected";return START_STICKY;
    }
    @Override public void onLocationChanged(Location l){lastLocation=l;evaluate();}
    @Override public void onSpeedMph(double mph){obdSpeed=mph;lastObdAt=System.currentTimeMillis();evaluate();}
    @Override public void onStatus(String t){obdStatus=t;evaluate();}
    private synchronized void evaluate(){
        Location loc=lastLocation;double speed=-1;boolean fresh=System.currentTimeMillis()-lastObdAt<4000&&obdSpeed>=0;if(fresh)speed=obdSpeed;else if(gpsFallback&&loc!=null&&loc.hasSpeed())speed=loc.getSpeed()*2.236936292;
        RoadDatabase.Match m=loc==null?null:roadDb.find(loc.getLatitude(),loc.getLongitude(),loc.hasBearing()?loc.getBearing():null);int limit=m==null?-1:m.limitMph;
        if(speed>=0&&limit>0){double trigger=limit+offset,rearm=limit+Math.max(0,offset-2);if(speed>trigger&&(armed||System.currentTimeMillis()-lastWarning>30000)){armed=false;lastWarning=System.currentTimeMillis();if(tts!=null)tts.speak("Speed warning. Limit "+limit,TextToSpeech.QUEUE_FLUSH,null,"speed-warning");}else if(speed<=rearm)armed=true;}else armed=true;
        String sp=speed<0?"--":String.valueOf((int)Math.round(speed)),li=limit<0?"--":String.valueOf(limit),road=m==null||m.roadName.isEmpty()?"":" • "+m.roadName;
        String status="Speed "+sp+" mph • Limit "+li+road+" • "+obdStatus;getSystemService(NotificationManager.class).notify(NOTIFY,notification(status));
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("speed",sp).putExtra("limit",li).putExtra("road",road).putExtra("obd",obdStatus).putExtra("source",fresh?"OBD":(speed>=0?"GPS fallback":"--")));
    }
    private Notification notification(String text){PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,"monitor").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("Speed Limit Alert is running").setContentText(text).setContentIntent(pi).setOngoing(true).build();}
    @Override public void onDestroy(){try{if(lm!=null)lm.removeUpdates(this);}catch(Exception ignored){}if(obd!=null)obd.close();if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
