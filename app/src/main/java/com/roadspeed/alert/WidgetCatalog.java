package com.roadspeed.alert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WidgetCatalog {
    public static final class Def {
        public final String id;
        public final String label;
        public final String unit;
        public final String command;

        Def(String id, String label, String unit, String command) {
            this.id = id;
            this.label = label;
            this.unit = unit;
            this.command = command;
        }
    }

    public static final class Parsed {
        public final double raw;
        public final String text;

        Parsed(double raw, String text) {
            this.raw = raw;
            this.text = text;
        }
    }

    public static final List<Def> ALL = Collections.unmodifiableList(Arrays.asList(
            new Def("speed", "Vehicle Speed", "mph", "010D"),
            new Def("speed_limit", "Speed Limit", "mph", null),
            new Def("rpm", "Engine RPM", "rpm", "010C"),
            new Def("coolant", "Coolant Temp", "°F", "0105"),
            new Def("voltage", "Control Module Voltage", "V", "ATRV"),
            new Def("engine_load", "Engine Load", "%", "0104"),
            new Def("throttle", "Throttle Position", "%", "0111"),
            new Def("fuel_level", "Fuel Level", "%", "012F"),
            new Def("intake_temp", "Intake Air Temp", "°F", "010F"),
            new Def("map", "Intake Manifold Pressure", "kPa", "010B"),
            new Def("maf", "MAF Air Flow", "g/s", "0110"),
            new Def("timing", "Timing Advance", "°", "010E"),
            new Def("runtime", "Engine Run Time", "min", "011F")
    ));

    private WidgetCatalog() {}

    public static Def byId(String id) {
        for (Def d : ALL) if (d.id.equals(id)) return d;
        return null;
    }

    public static List<String> pollableIds() {
        ArrayList<String> out = new ArrayList<>();
        for (Def d : ALL) if (d.command != null) out.add(d.id);
        return out;
    }

    public static List<String> pollableIds(Set<String> selected) {
        ArrayList<String> out = new ArrayList<>();
        if (!selected.contains("speed")) out.add("speed");
        for (Def d : ALL) {
            if (d.command != null && selected.contains(d.id)) out.add(d.id);
        }
        if (out.isEmpty()) out.add("speed");
        return out;
    }

    public static Parsed parse(String id, String response) {
        if (response == null) return null;
        String compact = response.toUpperCase(Locale.US)
                .replace("\r", " ")
                .replace("\n", " ")
                .replace(">", " ")
                .replaceAll("[^0-9A-F. V]", " ");

        if ("voltage".equals(id)) {
            Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*V").matcher(compact);
            if (!m.find()) return null;
            double v = Double.parseDouble(m.group(1));
            return new Parsed(v, String.format(Locale.US, "%.1f V", v));
        }

        Def d = byId(id);
        if (d == null || d.command == null || d.command.length() < 4) return null;
        String pid = d.command.substring(2, 4);
        String hex = response.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
        int at = hex.indexOf("41" + pid);
        if (at < 0) return null;
        String bytes = hex.substring(at + 4);

        try {
            switch (id) {
                case "speed": {
                    int a = byteAt(bytes, 0);
                    double mph = a * 0.621371192;
                    return new Parsed(mph, Math.round(mph) + " mph");
                }
                case "rpm": {
                    int a = byteAt(bytes, 0), b = byteAt(bytes, 1);
                    double rpm = (a * 256 + b) / 4.0;
                    return new Parsed(rpm, Math.round(rpm) + " rpm");
                }
                case "coolant":
                case "intake_temp": {
                    int a = byteAt(bytes, 0);
                    double c = a - 40.0;
                    double f = c * 9.0 / 5.0 + 32.0;
                    return new Parsed(f, Math.round(f) + " °F");
                }
                case "engine_load":
                case "throttle":
                case "fuel_level": {
                    int a = byteAt(bytes, 0);
                    double pct = a * 100.0 / 255.0;
                    return new Parsed(pct, Math.round(pct) + " %");
                }
                case "map": {
                    int a = byteAt(bytes, 0);
                    return new Parsed(a, a + " kPa");
                }
                case "maf": {
                    int a = byteAt(bytes, 0), b = byteAt(bytes, 1);
                    double v = (a * 256 + b) / 100.0;
                    return new Parsed(v, String.format(Locale.US, "%.1f g/s", v));
                }
                case "timing": {
                    int a = byteAt(bytes, 0);
                    double v = a / 2.0 - 64.0;
                    return new Parsed(v, String.format(Locale.US, "%.1f°", v));
                }
                case "runtime": {
                    int a = byteAt(bytes, 0), b = byteAt(bytes, 1);
                    double min = (a * 256 + b) / 60.0;
                    return new Parsed(min, String.format(Locale.US, "%.1f min", min));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int byteAt(String hex, int index) {
        int start = index * 2;
        if (hex.length() < start + 2) throw new IllegalArgumentException();
        return Integer.parseInt(hex.substring(start, start + 2), 16);
    }
}
