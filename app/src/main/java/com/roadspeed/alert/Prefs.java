package com.roadspeed.alert;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

public final class Prefs {
    private static final String NAME = "car_scanner";

    public static final String KEY_REGION = "region";
    public static final String KEY_AUTO_UPDATE = "auto_update";
    public static final String KEY_DELETE_SOURCE = "delete_source";
    public static final String KEY_LAST_MODIFIED = "last_modified";
    public static final String KEY_LAST_UPDATE = "last_update";
    public static final String KEY_EXTERNAL_URI = "external_uri";

    public static final String KEY_OBD_ADDRESS = "obd_address";
    public static final String KEY_OBD_NAME = "obd_name";
    public static final String KEY_OBD_TRANSPORT = "obd_transport";

    public static final String KEY_WIDGETS = "widgets";
    public static final String KEY_WARNING_ENABLED = "warning_enabled";
    public static final String KEY_WARNING_OFFSET = "warning_offset";
    public static final String KEY_GPS_FALLBACK = "gps_fallback";

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static Set<String> selectedWidgets(Context c) {
        String raw = get(c).getString(KEY_WIDGETS, "speed,speed_limit,rpm,coolant,voltage");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String s : raw.split(",")) {
                s = s.trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        if (out.isEmpty()) out.add("speed");
        return out;
    }

    public static void saveWidgets(Context c, Set<String> ids) {
        StringBuilder b = new StringBuilder();
        for (String id : ids) {
            if (b.length() > 0) b.append(',');
            b.append(id);
        }
        get(c).edit().putString(KEY_WIDGETS, b.toString()).apply();
    }
}
