package com.roadspeed.alert;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

public class SpeedLimitInitProvider extends ContentProvider {
    @Override public boolean onCreate() {
        Context c = getContext();
        if (c != null) {
            UpdateScheduler.schedulePeriodic(c);
            try {
                Intent i = new Intent(c, RoadLimitService.class);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            } catch (Exception ignored) {}
        }
        return true;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String so) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
