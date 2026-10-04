package com.roadspeed.alert;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.IBinder;
import java.io.*;

public class PbfImportService extends Service {
    public static final String ACTION_PROGRESS="com.roadspeed.alert.ROAD_PROGRESS",EXTRA_TEXT="text",EXTRA_PERCENT="percent",EXTRA_MODE="mode",EXTRA_URI="uri",EXTRA_FILE="file";
    private static final int NOTIFY=7002;private volatile boolean running;
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("road_data","Road data",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(running)return START_NOT_STICKY;running=true;startForeground(NOTIFY,notification("Preparing road data",0));
        String mode=intent==null?"download":intent.getStringExtra(EXTRA_MODE),uri=intent==null?null:intent.getStringExtra(EXTRA_URI),file=intent==null?null:intent.getStringExtra(EXTRA_FILE);\n        new Thread(()->runWork(mode,uri,file),"road-import").start();return START_NOT_STICKY;
    }
    private void runWork(String mode,String uriString,String retainedFile){
        File source=null;
        try{
            if("uri".equals(mode)&&uriString!=null){
                Uri uri=Uri.parse(uriString);source=new File(RoadDataManager.downloadsDir(this),"manual-"+System.currentTimeMillis()+".osm.pbf");
                try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new BufferedOutputStream(new FileOutputStream(source),1024*1024)){
                    if(in==null)throw new IOException("Cannot open selected file");byte[]buf=new byte[1024*1024];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}
                Prefs.get(this).edit().putString(Prefs.KEY_EXTERNAL_URI,uriString).apply();
            }else if("retained".equals(mode)&&retainedFile!=null){\n                File dir=RoadDataManager.downloadsDir(this).getCanonicalFile();\n                source=new File(dir,retainedFile).getCanonicalFile();\n                if(!source.getParentFile().equals(dir)||!source.isFile())throw new IOException("Retained PBF file not found");\n            }else{\n                source=RoadDataManager.downloadGeofabrik(this,Prefs.get(this).getString(Prefs.KEY_REGION,"oklahoma"),this::progress);
                if(source==null){progress(100,"Road data is already current");return;}
            }
            new RoadDataImporter(this,source,this::progress).run();Prefs.get(this).edit().putLong(Prefs.KEY_LAST_UPDATE,System.currentTimeMillis()).apply();
            if(Prefs.get(this).getBoolean(Prefs.KEY_DELETE_SOURCE,true))source.delete();progress(100,"Road data ready");
        }catch(Exception e){progress(0,"Road-data error: "+e.getMessage());}
        finally{running=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    }
    private void progress(int percent,String text){
        getSystemService(NotificationManager.class).notify(NOTIFY,notification(text,percent));
        sendBroadcast(new Intent(ACTION_PROGRESS).setPackage(getPackageName()).putExtra(EXTRA_PERCENT,percent).putExtra(EXTRA_TEXT,text));
    }
    private Notification notification(String text,int percent){
        PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,StorageActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"road_data").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Speed Limit Alert").setContentText(text).setContentIntent(pi).setOnlyAlertOnce(true).setOngoing(percent>0&&percent<100).setProgress(100,Math.max(0,percent),percent<=0).build();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
