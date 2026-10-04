package com.roadspeed.alert;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;

public final class RoadDatabase {
    public static final class Match {
        public final int limitMph; public final String roadName; public final double distanceMeters;
        Match(int l,String n,double d){limitMph=l;roadName=n;distanceMeters=d;}
    }
    private final Context context;
    public RoadDatabase(Context c){context=c.getApplicationContext();}
    public static File roadDataDir(Context c){File d=new File(c.getFilesDir(),"road_data");if(!d.exists())d.mkdirs();return d;}
    public static File currentFile(Context c){return new File(roadDataDir(c),"current.rsdb");}
    public static long databaseSize(Context c){File f=currentFile(c);return f.exists()?f.length():0;}

    public Match find(double lat,double lon,Float bearing){
        File f=currentFile(context); if(!f.exists()||f.length()<4096)return null;
        int latE7=(int)Math.round(lat*1e7), lonE7=(int)Math.round(lon*1e7), dLat=12000;
        int dLon=(int)(dLat/Math.max(.2,Math.cos(Math.toRadians(lat))));
        SQLiteDatabase db=null; Cursor c=null;
        try{
            db=SQLiteDatabase.openDatabase(f.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);
            c=db.rawQuery("SELECT a_lat,a_lon,b_lat,b_lon,fwd_mph,back_mph,name,road_rank FROM segments WHERE min_lat<=? AND max_lat>=? AND min_lon<=? AND max_lon>=? LIMIT 250",
                    new String[]{String.valueOf(latE7+dLat),String.valueOf(latE7-dLat),String.valueOf(lonE7+dLon),String.valueOf(lonE7-dLon)});
            Match best=null; double bestScore=Double.MAX_VALUE;
            while(c.moveToNext()){
                double aLat=c.getInt(0)/1e7,aLon=c.getInt(1)/1e7,bLat=c.getInt(2)/1e7,bLon=c.getInt(3)/1e7;
                int fwd=c.getInt(4),back=c.getInt(5),rank=c.getInt(7); String name=c.isNull(6)?"":c.getString(6);
                double dist=pointSegmentMeters(lat,lon,aLat,aLon,bLat,bLon); if(dist>90)continue;
                boolean forward=true; double hp=0;
                if(bearing!=null&&!bearing.isNaN()){
                    double seg=bearingDegrees(aLat,aLon,bLat,bLon);
                    double df=angleDiff(bearing,seg), dbk=angleDiff(bearing,(seg+180)%360);
                    forward=df<=dbk; hp=Math.min(df,dbk)*.45;
                }
                int limit=forward?fwd:back; if(limit<=0)limit=forward?back:fwd; if(limit<=0)continue;
                double score=dist+hp-Math.min(6,rank);
                if(score<bestScore){bestScore=score;best=new Match(limit,name,dist);}
            }
            return best;
        }catch(Exception ignored){return null;}
        finally{if(c!=null)c.close();if(db!=null)db.close();}
    }
    private static double pointSegmentMeters(double pLat,double pLon,double aLat,double aLon,double bLat,double bLon){
        double scale=Math.cos(Math.toRadians(pLat));
        double px=pLon*111320*scale,py=pLat*110540,ax=aLon*111320*scale,ay=aLat*110540,bx=bLon*111320*scale,by=bLat*110540;
        double dx=bx-ax,dy=by-ay,len2=dx*dx+dy*dy,t=len2==0?0:((px-ax)*dx+(py-ay)*dy)/len2;
        t=Math.max(0,Math.min(1,t));double x=ax+t*dx,y=ay+t*dy;return Math.hypot(px-x,py-y);
    }
    private static double bearingDegrees(double aLat,double aLon,double bLat,double bLon){
        double y=Math.sin(Math.toRadians(bLon-aLon))*Math.cos(Math.toRadians(bLat));
        double x=Math.cos(Math.toRadians(aLat))*Math.sin(Math.toRadians(bLat))-Math.sin(Math.toRadians(aLat))*Math.cos(Math.toRadians(bLat))*Math.cos(Math.toRadians(bLon-aLon));
        return (Math.toDegrees(Math.atan2(y,x))+360)%360;
    }
    private static double angleDiff(double a,double b){double d=Math.abs(a-b)%360;return d>180?360-d:d;}
}
