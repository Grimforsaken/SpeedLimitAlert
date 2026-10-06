package com.roadspeed.alert;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

public class RoadLimitService extends Service implements LocationListener {
    private static final int NOTIFY = 7411;
    private static final String CHANNEL = "car_scanner_speed_limit";

    private LocationManager locationManager;
    private RoadDatabase roadDb;
    private TextToSpeech tts;
    private boolean armed = true;
    private int overCount = 0;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Speed limit matching", NotificationManager.IMPORTANCE_LOW));
        }
        roadDb = new RoadDatabase(this);
        locationManager = getSystemService(LocationManager.class);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.US);
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFY, notification("Offline speed-limit matching active"));
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED && locationManager != null) {
            try {
                locationManager.removeUpdates(this);
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 1000L, 2f, this);
            } catch (Exception ignored) {}
        }
        return START_STICKY;
    }

    @Override public void onLocationChanged(Location location) {
        RoadDatabase.Match match = null;
        if (!location.hasAccuracy() || location.getAccuracy() <= 60f) {
            match = roadDb.find(location.getLatitude(), location.getLongitude(),
                    location.hasBearing() ? location.getBearing() : null);
        }

        if (match == null || match.limitMph <= 0) {
            writeManagedLimit(Double.NaN);
            overCount = 0;
            return;
        }

        // Car Scanner's speed-unit conversion expects speed values internally in km/h.
        writeManagedLimit(match.limitMph / 0.621371192);
        evaluateWarning(match.limitMph);
    }

    private void evaluateWarning(double limitMph) {
        double obdKph = readDouble(new File(getFilesDir(), "010D"));
        if (Double.isNaN(obdKph) || obdKph < 0) {
            overCount = 0;
            return;
        }

        double speedMph = obdKph * 0.621371192;
        double trigger = limitMph + 5.0;
        double rearm = limitMph + 3.0;

        if (speedMph > trigger) {
            overCount++;
            if (armed && overCount >= 2) {
                armed = false;
                if (tts != null) {
                    tts.speak("Speed warning. Limit " + Math.round(limitMph),
                            TextToSpeech.QUEUE_FLUSH, null, "car-scanner-speed-warning");
                }
            }
        } else {
            overCount = 0;
            if (speedMph <= rearm) armed = true;
        }
    }

    private void writeManagedLimit(double kph) {
        File f = new File(getFilesDir(), "GPS_SPEED");
        try (FileWriter w = new FileWriter(f, false)) {
            w.write(Double.isNaN(kph) ? "NaN" : Double.toString(kph));
        } catch (Exception ignored) {}
    }

    private static double readDouble(File f) {
        try {
            if (!f.exists()) return Double.NaN;
            byte[] data;
            if (Build.VERSION.SDK_INT >= 26) data = Files.readAllBytes(f.toPath());
            else {
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[128];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                data = out.toByteArray();
            }
            return Double.parseDouble(new String(data, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private Notification notification(String text) {
        Intent manage = new Intent(this, StorageActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 7411, manage,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Car Scanner speed limits")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        try { if (locationManager != null) locationManager.removeUpdates(this); }
        catch (Exception ignored) {}
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
