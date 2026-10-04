package com.roadspeed.alert;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.io.File;

public class UpdateJobService extends JobService {
    private volatile boolean stopped;
    @Override public boolean onStartJob(JobParameters p){
        stopped=false;
        if(!Prefs.get(this).getBoolean(Prefs.KEY_AUTO_UPDATE,true)||!RoadDataManager.isWifi(this)){jobFinished(p,false);return false;}
        new Thread(()->{
            try{
                File f=RoadDataManager.downloadGeofabrik(this,Prefs.get(this).getString(Prefs.KEY_REGION,"oklahoma"),(x,t)->{});
                if(!stopped&&f!=null){new RoadDataImporter(this,f,(x,t)->{}).run();Prefs.get(this).edit().putLong(Prefs.KEY_LAST_UPDATE,System.currentTimeMillis()).apply();if(Prefs.get(this).getBoolean(Prefs.KEY_DELETE_SOURCE,true))f.delete();}
                if(!stopped)jobFinished(p,false);
            }catch(Exception e){if(!stopped)jobFinished(p,true);}
        },"wifi-road-update").start();return true;
    }
    @Override public boolean onStopJob(JobParameters p){stopped=true;return true;}
}
