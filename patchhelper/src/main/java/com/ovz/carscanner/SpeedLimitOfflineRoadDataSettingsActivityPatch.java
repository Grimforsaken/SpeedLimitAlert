package com.ovz.carscanner;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.text.DateFormat;
import java.util.*;

public final class SpeedLimitOfflineRoadDataSettingsActivityPatch extends Activity {
    private static final int PICK=4312;
    private LinearLayout filesBox;
    private TextView info,status;
    private EditText region,offset;
    private CheckBox autoUpdate,deleteSource,warning;
    private ProgressBar bar;
    private final Handler main=new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b){super.onCreate(b);build();refresh();RoadSpeedRuntime.start(this);}
    private void build(){
        ScrollView s=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(16),dp(18),dp(24));root.setBackgroundColor(Color.rgb(20,20,20));s.addView(root);
        root.addView(label("CAR SCANNER • OFFLINE SPEED LIMITS",22,true));
        info=label("",14,false);root.addView(info);
        root.addView(label("Geofabrik U.S. region",14,true));
        region=new EditText(this);region.setTextColor(Color.WHITE);region.setSingleLine(true);region.setText(PatchPrefs.get(this).getString(PatchPrefs.REGION,"oklahoma"));root.addView(region);
        autoUpdate=check("Automatically update on Wi-Fi",PatchPrefs.get(this).getBoolean(PatchPrefs.AUTO_UPDATE,true));root.addView(autoUpdate);
        deleteSource=check("Delete large PBF after successful extraction",PatchPrefs.get(this).getBoolean(PatchPrefs.DELETE_SOURCE,true));root.addView(deleteSource);
        warning=check("Audio warning when more than offset over limit",PatchPrefs.get(this).getBoolean(PatchPrefs.WARNING,true));root.addView(warning);
        LinearLayout wr=new LinearLayout(this);wr.setOrientation(LinearLayout.HORIZONTAL);wr.addView(label("Warning offset:",14,false));offset=new EditText(this);offset.setInputType(2);offset.setTextColor(Color.WHITE);offset.setEms(3);offset.setText(String.valueOf(PatchPrefs.get(this).getInt(PatchPrefs.WARNING_OFFSET,5)));wr.addView(offset);wr.addView(label("mph",14,false));root.addView(wr);
        Button dl=button("CHECK / DOWNLOAD UPDATE ON WI-FI");dl.setOnClickListener(v->{save();runDownload();});root.addView(dl);
        Button imp=button("IMPORT .OSM.PBF FILE");imp.setOnClickListener(v->pick());root.addView(imp);
        bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);root.addView(bar);status=label("Idle",13,false);root.addView(status);
        root.addView(label("SOURCE DOWNLOADS",18,true));root.addView(label("EXTRACT builds the compact database used by the Speed Limit dashboard sensor.",13,false));
        filesBox=new LinearLayout(this);filesBox.setOrientation(LinearLayout.VERTICAL);root.addView(filesBox);
        Button done=button("DONE");done.setOnClickListener(v->{save();finish();});root.addView(done);
        setContentView(s);
    }
    private void save(){
        String slug=region.getText().toString().trim().toLowerCase(Locale.US).replace(' ','-');if(slug.isEmpty())slug="oklahoma";
        int o=5;try{o=Math.max(1,Math.min(20,Integer.parseInt(offset.getText().toString().trim())));}catch(Exception ignored){}
        PatchPrefs.get(this).edit().putString(PatchPrefs.REGION,slug).putBoolean(PatchPrefs.AUTO_UPDATE,autoUpdate.isChecked()).putBoolean(PatchPrefs.DELETE_SOURCE,deleteSource.isChecked()).putBoolean(PatchPrefs.WARNING,warning.isChecked()).putInt(PatchPrefs.WARNING_OFFSET,o).apply();
    }
    private void refresh(){
        long size=RoadDatabase.databaseSize(this),last=PatchPrefs.get(this).getLong(PatchPrefs.LAST_UPDATE,0);
        info.setText("Processed offline database: "+(size>0?RoadDataManager.sizeText(size):"not installed")+(last>0?"\nLast successful update: "+DateFormat.getDateTimeInstance().format(new Date(last)):""));
        filesBox.removeAllViews();File[] fs=RoadDataManager.downloadsDir(this).listFiles();if(fs==null||fs.length==0){filesBox.addView(label("No retained source downloads.",13,false));return;}
        Arrays.sort(fs,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        for(File f:fs){if(f.getName().endsWith(".part"))continue;LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);TextView x=label(f.getName()+"\n"+RoadDataManager.sizeText(f.length()),12,false);row.addView(x,new LinearLayout.LayoutParams(0,-2,1));Button ex=button("EXTRACT");ex.setOnClickListener(v->extract(f));row.addView(ex);Button del=button("DELETE");del.setOnClickListener(v->{f.delete();refresh();});row.addView(del);filesBox.addView(row);}
    }
    private void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,PICK);}
    @Override protected void onActivityResult(int r,int result,Intent data){super.onActivityResult(r,result,data);if(r==PICK&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri u=data.getData();new Thread(()->copyAndExtract(u),"RoadImport").start();}}
    private void copyAndExtract(Uri u){
        File f=new File(RoadDataManager.downloadsDir(this),"manual-"+System.currentTimeMillis()+".osm.pbf");progress(0,"Copying selected PBF…");
        try(InputStream in=getContentResolver().openInputStream(u);OutputStream out=new BufferedOutputStream(new FileOutputStream(f),1024*1024)){if(in==null)throw new IOException("Cannot open selected file");byte[]buf=new byte[1024*1024];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}
        catch(Exception e){progress(0,"Import error: "+e.getMessage());return;}extractNow(f);
    }
    private void extract(File f){save();new Thread(()->extractNow(f),"RoadExtract").start();}
    private void extractNow(File f){
        try{new RoadDataImporter(this,f,this::progress).run();PatchPrefs.get(this).edit().putLong(PatchPrefs.LAST_UPDATE,System.currentTimeMillis()).apply();if(PatchPrefs.get(this).getBoolean(PatchPrefs.DELETE_SOURCE,true))f.delete();progress(100,"Road data ready");main.post(()->{refresh();RoadSpeedRuntime.start(this);});}
        catch(Exception e){progress(0,"Road-data error: "+e.getMessage());}
    }
    private void runDownload(){
        new Thread(()->{try{File f=RoadDataManager.downloadGeofabrik(this,PatchPrefs.get(this).getString(PatchPrefs.REGION,"oklahoma"),this::progress);if(f==null){progress(100,"Road data is already current");return;}extractNow(f);}catch(Exception e){progress(0,"Download error: "+e.getMessage());}},"RoadDownload").start();
    }
    private void progress(int p,String t){main.post(()->{bar.setProgress(Math.max(0,Math.min(100,p)));status.setText(t);});}
    private CheckBox check(String t,boolean b){CheckBox c=new CheckBox(this);c.setText(t);c.setTextColor(Color.WHITE);c.setChecked(b);return c;}
    private TextView label(String t,int sp,boolean bold){TextView v=new TextView(this);v.setText(t);v.setTextColor(Color.WHITE);v.setTextSize(sp);if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);v.setPadding(dp(4),dp(5),dp(4),dp(5));return v;}
    private Button button(String t){Button b=new Button(this);b.setText(t);return b;}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
}
