package com.ovz.carscanner;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;

public final class roadspeedinitprovider extends ContentProvider {
    @Override public boolean onCreate(){try{RoadSpeedRuntime.start(getContext());}catch(Throwable ignored){}return true;}
    @Override public Cursor query(Uri u,String[]p,String s,String[]a,String o){return null;}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){return null;}
    @Override public int delete(Uri u,String s,String[]a){return 0;}
    @Override public int update(Uri u,ContentValues v,String s,String[]a){return 0;}
}
