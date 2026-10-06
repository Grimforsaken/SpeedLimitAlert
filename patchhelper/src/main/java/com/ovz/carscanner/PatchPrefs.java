package com.ovz.carscanner;

import android.content.Context;
import android.content.SharedPreferences;

public final class PatchPrefs {
    private static final String NAME="offline_speed_limit";
    public static final String REGION="region",AUTO_UPDATE="auto_update",DELETE_SOURCE="delete_source",
            LAST_MODIFIED="last_modified",LAST_UPDATE="last_update",WARNING="warning",
            WARNING_OFFSET="warning_offset";
    private PatchPrefs(){}
    public static SharedPreferences get(Context c){return c.getSharedPreferences(NAME,Context.MODE_PRIVATE);}
}
