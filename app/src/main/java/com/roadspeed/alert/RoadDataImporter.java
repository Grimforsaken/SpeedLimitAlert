package com.roadspeed.alert;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.*;

public final class RoadDataImporter {
    public interface Progress{void update(int percent,String text);}
    private final Context context;private final File pbf;private final Progress progress;private final LongSet needed=new LongSet();
    public RoadDataImporter(Context c,File p,Progress pr){context=c.getApplicationContext();pbf=p;progress=pr;}

    public File run()throws Exception{
        File dir=RoadDatabase.roadDataDir(context),incoming=new File(dir,"incoming.rsdb");if(incoming.exists())incoming.delete();
        SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(incoming,null);
        try{
            setup(db);progress.update(3,"Pass 1 of 3: selecting speed-limited roads");
            db.beginTransaction();try{PbfParser.read(pbf,b->PbfParser.ways(b,w->importWay(db,b,w)));db.setTransactionSuccessful();}finally{db.endTransaction();}
            progress.update(38,"Pass 2 of 3: extracting only needed coordinates");
            db.beginTransaction();try{
                PbfParser.read(pbf,b->PbfParser.nodes(b,n->{if(needed.contains(n.id)){int lat=toE7(b.latOffset,b.granularity,n.rawLat),lon=toE7(b.lonOffset,b.granularity,n.rawLon);db.execSQL("INSERT OR REPLACE INTO nodes(id,lat,lon) VALUES(?,?,?)",new Object[]{n.id,lat,lon});}}));
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            progress.update(68,"Pass 3 of 3: building compact road database");buildSegments(db);
            db.execSQL("DROP TABLE way_nodes");db.execSQL("DROP TABLE ways");db.execSQL("DROP TABLE nodes");
            long count=singleLong(db,"SELECT COUNT(*) FROM segments");if(count<1)throw new IllegalStateException("No usable speed-limit road segments found");
            db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('segments',?)",new Object[]{String.valueOf(count)});
            db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('built_at',?)",new Object[]{String.valueOf(System.currentTimeMillis())});
            db.execSQL("VACUUM");
        }finally{db.close();}
        File current=RoadDatabase.currentFile(context),backup=new File(dir,"previous.rsdb");if(backup.exists())backup.delete();
        if(current.exists()&&!current.renameTo(backup))throw new IllegalStateException("Could not stage old road database");
        if(!incoming.renameTo(current)){if(backup.exists())backup.renameTo(current);throw new IllegalStateException("Could not activate new road database");}
        if(backup.exists())backup.delete();progress.update(100,"Road data ready");return current;
    }
    private void setup(SQLiteDatabase db){
        db.execSQL("PRAGMA journal_mode=OFF");db.execSQL("PRAGMA synchronous=OFF");
        db.execSQL("CREATE TABLE ways(id INTEGER PRIMARY KEY,name TEXT,road_rank INTEGER,fwd_mph INTEGER,back_mph INTEGER)");
        db.execSQL("CREATE TABLE way_nodes(way_id INTEGER,seq INTEGER,node_id INTEGER,PRIMARY KEY(way_id,seq))");
        db.execSQL("CREATE INDEX idx_way_nodes_way ON way_nodes(way_id)");
        db.execSQL("CREATE TABLE nodes(id INTEGER PRIMARY KEY,lat INTEGER,lon INTEGER)");
        db.execSQL("CREATE TABLE segments(id INTEGER PRIMARY KEY AUTOINCREMENT,way_id INTEGER,seq INTEGER,a_lat INTEGER,a_lon INTEGER,b_lat INTEGER,b_lon INTEGER,min_lat INTEGER,max_lat INTEGER,min_lon INTEGER,max_lon INTEGER,fwd_mph INTEGER,back_mph INTEGER,name TEXT,road_rank INTEGER)");
        db.execSQL("CREATE INDEX idx_segments_bbox1 ON segments(min_lat,max_lat)");db.execSQL("CREATE INDEX idx_segments_bbox2 ON segments(min_lon,max_lon)");
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT)");
    }
    private void importWay(SQLiteDatabase db,PbfParser.PrimitiveBlock b,PbfParser.WayData w){
        Map<String,String>t=tags(b.strings,w.keys,w.vals);String h=t.get("highway");if(h==null||w.refs.length<2||t.containsKey("maxspeed:conditional"))return;
        int base=parseSpeed(t.get("maxspeed")),f=parseSpeed(t.get("maxspeed:forward")),back=parseSpeed(t.get("maxspeed:backward"));if(f<=0)f=base;if(back<=0)back=base;
        String one=lower(t.get("oneway"));if("yes".equals(one)||"1".equals(one)||"true".equals(one))back=0;if("-1".equals(one)){int z=f;f=back;back=z;f=0;}if(f<=0&&back<=0)return;
        db.execSQL("INSERT OR REPLACE INTO ways(id,name,road_rank,fwd_mph,back_mph) VALUES(?,?,?,?,?)",new Object[]{w.id,t.get("name"),roadRank(h),f,back});
        for(int i=0;i<w.refs.length;i++){long n=w.refs[i];needed.add(n);db.execSQL("INSERT OR REPLACE INTO way_nodes(way_id,seq,node_id) VALUES(?,?,?)",new Object[]{w.id,i,n});}
    }
    private void buildSegments(SQLiteDatabase db){
        Cursor ways=db.rawQuery("SELECT id,name,road_rank,fwd_mph,back_mph FROM ways",null);db.beginTransaction();
        try{long done=0,total=ways.getCount();while(ways.moveToNext()){
            long id=ways.getLong(0);String name=ways.isNull(1)?"":ways.getString(1);int rank=ways.getInt(2),f=ways.getInt(3),back=ways.getInt(4);
            Cursor pts=db.rawQuery("SELECT wn.seq,n.lat,n.lon FROM way_nodes wn JOIN nodes n ON n.id=wn.node_id WHERE wn.way_id=? ORDER BY wn.seq",new String[]{String.valueOf(id)});
            try{boolean have=false;int ps=-1,plat=0,plon=0;while(pts.moveToNext()){int seq=pts.getInt(0),lat=pts.getInt(1),lon=pts.getInt(2);
                if(have&&seq==ps+1){ContentValues v=new ContentValues();v.put("way_id",id);v.put("seq",ps);v.put("a_lat",plat);v.put("a_lon",plon);v.put("b_lat",lat);v.put("b_lon",lon);
                    v.put("min_lat",Math.min(plat,lat));v.put("max_lat",Math.max(plat,lat));v.put("min_lon",Math.min(plon,lon));v.put("max_lon",Math.max(plon,lon));v.put("fwd_mph",f);v.put("back_mph",back);v.put("name",name);v.put("road_rank",rank);db.insert("segments",null,v);}
                have=true;ps=seq;plat=lat;plon=lon;}}
            finally{pts.close();}
            done++;if((done&255)==0&&total>0)progress.update(68+(int)Math.min(27,done*27/total),"Pass 3 of 3: building road segments");
        }db.setTransactionSuccessful();}finally{db.endTransaction();ways.close();}
    }
    private static int toE7(long off,int gran,long raw){return(int)Math.round(((off+(double)gran*raw)/1_000_000_000.0)*1e7);}
    private static Map<String,String>tags(List<String>s,int[]k,int[]v){Map<String,String>o=new HashMap<>();for(int i=0;i<Math.min(k.length,v.length);i++)if(k[i]>=0&&k[i]<s.size()&&v[i]>=0&&v[i]<s.size())o.put(s.get(k[i]),s.get(v[i]));return o;}
    private static String lower(String s){return s==null?"":s.trim().toLowerCase(Locale.US);}
    private static int parseSpeed(String v){
        if(v==null)return-1;String s=v.trim().toLowerCase(Locale.US);if(s.isEmpty()||s.contains(";")||s.contains("signals")||s.contains("variable")||s.contains("none")||s.contains("walk"))return-1;
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(s);if(!m.find())return-1;double n;try{n=Double.parseDouble(m.group(1));}catch(Exception e){return-1;}
        if(!s.contains("mph"))n*=.621371192;int mph=(int)Math.round(n);return mph>=5&&mph<=100?mph:-1;
    }
    private static int roadRank(String h){if("motorway".equals(h))return 9;if("trunk".equals(h))return 8;if("primary".equals(h))return 7;if("secondary".equals(h))return 6;if("tertiary".equals(h))return 5;if("unclassified".equals(h))return 4;if("residential".equals(h))return 3;if("living_street".equals(h))return 2;if("service".equals(h))return 1;return 0;}
    private static long singleLong(SQLiteDatabase db,String q){Cursor c=db.rawQuery(q,null);try{return c.moveToFirst()?c.getLong(0):0;}finally{c.close();}}
    private static final class LongSet{
        private long[]keys=new long[1<<16];private boolean zero;private int size;
        boolean contains(long k){if(k==0)return zero;int mask=keys.length-1,i=mix(k)&mask;while(keys[i]!=0){if(keys[i]==k)return true;i=(i+1)&mask;}return false;}
        void add(long k){if(k==0){if(!zero){zero=true;size++;}return;}if((size+1)*10>keys.length*7)resize();int mask=keys.length-1,i=mix(k)&mask;while(keys[i]!=0){if(keys[i]==k)return;i=(i+1)&mask;}keys[i]=k;size++;}
        void resize(){long[]o=keys;keys=new long[o.length<<1];size=zero?1:0;for(long k:o)if(k!=0)add(k);}
        static int mix(long x){x^=x>>>33;x*=0xff51afd7ed558ccdl;x^=x>>>33;x*=0xc4ceb9fe1a85ec53l;x^=x>>>33;return(int)x;}
    }
}
