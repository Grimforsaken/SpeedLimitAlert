package com.roadspeed.alert;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private static final String NAME = "speed_limit_alert";
    public static final String KEY_REGION = "region";
    public static final String KEY_AUTO_UPDATE = "auto_update";
    public static final String KEY_DELETE_SOURCE = "delete_source";
    public static final String KEY_WARNING_OFFSET = "warning_offset";
    public static final String KEY_GPS_FALLBACK = "gps_fallback";
    public static final String KEY_OBD_ADDRESS = "obd_address";
    public static final String KEY_LAST_MODIFIED = "last_modified";
    public static final String KEY_LAST_UPDATE = "last_update";
    public static final String KEY_EXTERNAL_URI = "external_uri";
    private Prefs() {}
    public static SharedPreferences get(Context c) { return c.getSharedPreferences(NAME, Context.MODE_PRIVATE); }
}
