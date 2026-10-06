package com.roadspeed.alert;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DataStore {
    public static final class Value {
        public final double raw;
        public final String text;
        public final long time;

        Value(double raw, String text, long time) {
            this.raw = raw;
            this.text = text;
            this.time = time;
        }
    }

    private static final Map<String, Value> VALUES = new ConcurrentHashMap<>();

    private DataStore() {}

    public static void put(String id, double raw, String text) {
        VALUES.put(id, new Value(raw, text, System.currentTimeMillis()));
    }

    public static Value get(String id) {
        return VALUES.get(id);
    }

    public static String text(String id, String fallback) {
        Value v = get(id);
        return v == null ? fallback : v.text;
    }

    public static double raw(String id, double fallback) {
        Value v = get(id);
        return v == null ? fallback : v.raw;
    }

    public static boolean fresh(String id, long maxAgeMs) {
        Value v = get(id);
        return v != null && System.currentTimeMillis() - v.time <= maxAgeMs;
    }

    public static void clearVehicleValues() {
        for (String id : WidgetCatalog.pollableIds()) {
            VALUES.remove(id);
        }
    }
}
