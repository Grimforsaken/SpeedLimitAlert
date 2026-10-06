package com.ovz.carscanner;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import java.io.*;
import java.net.*;
import java.util.Locale;

public final class RoadDataManager {
    public interface Progress{void update(int percent,String text);}
    private RoadDataManager(){}
    public static File downloadsDir(Context c){File d=new File(c.getFilesDir(),"pbf_downloads");if(!d.exists())d.mkdirs();return d;}
    public static boolean isWifi(Context c){
        ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);if(cm==null||cm.getActiveNetwork()==null)return false;
        NetworkCapabilities caps=cm.getNetworkCapabilities(cm.getActiveNetwork());return caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
    public static File downloadGeofabrik(Context c,String region,Progress progress)throws Exception{
        region=region.trim().toLowerCase(Locale.US).replace(' ','-');if(!region.matches("[a-z0-9-]+"))throw new IllegalArgumentException("Invalid region name");
        if(!isWifi(c))throw new IllegalStateException("Wi-Fi is required for road-data downloads");
        URL url=new URL("https://download.geofabrik.de/north-america/us/"+region+"-latest.osm.pbf");
        File dir=downloadsDir(c),part=new File(dir,region+"-latest.osm.pbf.part"),out=new File(dir,region+"-latest.osm.pbf");if(part.exists())part.delete();
        HttpURLConnection conn=(HttpURLConnection)url.openConnection();conn.setInstanceFollowRedirects(true);conn.setConnectTimeout(20000);conn.setReadTimeout(90000);
        long last=PatchPrefs.get(c).getLong(PatchPrefs.LAST_MODIFIED,0);if(last>0)conn.setIfModifiedSince(last);conn.connect();
        if(conn.getResponseCode()==HttpURLConnection.HTTP_NOT_MODIFIED&&RoadDatabase.currentFile(c).exists()){progress.update(100,"Road data is already current");conn.disconnect();return null;}
        if(conn.getResponseCode()<200||conn.getResponseCode()>=300)throw new IOException("Download failed: HTTP "+conn.getResponseCode());
        long total=conn.getContentLengthLong(),read=0;
        try(InputStream in=new BufferedInputStream(conn.getInputStream(),1024*1024);OutputStream os=new BufferedOutputStream(new FileOutputStream(part),1024*1024)){
            byte[]buf=new byte[1024*1024];int n;while((n=in.read(buf))!=-1){os.write(buf,0,n);read+=n;if(total>0)progress.update((int)Math.min(32,read*32/total),"Downloading road data");}
        }finally{long lm=conn.getLastModified();if(lm>0)PatchPrefs.get(c).edit().putLong(PatchPrefs.LAST_MODIFIED,lm).apply();conn.disconnect();}
        if(out.exists())out.delete();if(!part.renameTo(out))throw new IOException("Could not finish download");return out;
    }
    public static String sizeText(long b){if(b<1024)return b+" B";if(b<1024L*1024)return String.format(Locale.US,"%.1f KB",b/1024.0);if(b<1024L*1024*1024)return String.format(Locale.US,"%.1f MB",b/(1024.0*1024));return String.format(Locale.US,"%.2f GB",b/(1024.0*1024*1024));}
}
