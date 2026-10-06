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

import java.util.Locale;

public class SpeedMonitorService extends Service
        implements LocationListener, ObdClient.Listener {

    public static final String ACTION_DATA = "com.grimforsaken.carscanner.DATA";
    private static final int NOTIFY = 7001;
    private static final String CHANNEL = "monitor";

    private LocationManager locationManager;
    private RoadDatabase roadDb;
    private ObdClient classic;
    private BleObdClient ble;
    private TextToSpeech tts;

    private volatile double gpsSpeedMph = -1;
    private volatile long gpsSpeedAt;
    private boolean armed = true;
    private int overCount;

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Vehicle monitoring",
                            NotificationManager.IMPORTANCE_LOW));
        }

        roadDb = new RoadDatabase(this);
        locationManager = getSystemService(LocationManager.class);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.US);
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFY, notification("Starting vehicle monitoring…"));

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 1000, 2, this);
            } catch (Exception ignored) {}
        }

        startObd();
        DataStore.put("connection", 0, "starting");
        sendData();
        return START_STICKY;
    }

    private void startObd() {
        closeObd();

        String address = Prefs.get(this).getString(Prefs.KEY_OBD_ADDRESS, "");
        String transport = Prefs.get(this).getString(Prefs.KEY_OBD_TRANSPORT, "classic");

        if (address.isEmpty()) {
            DataStore.put("connection", 0, "no OBD selected");
            return;
        }

        if ("ble".equals(transport)) {
            ble = new BleObdClient(this, address, this);
            ble.start();
        } else {
            classic = new ObdClient(this, address, this);
            classic.start();
        }
    }

    @Override
    public void onValue(String id, double raw, String display) {
        DataStore.put(id, raw, display);
        if ("speed".equals(id)) evaluateWarning();
        sendData();
    }

    @Override
    public void onStatus(String text) {
        DataStore.put("connection", 0, text);
        sendData();
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location.hasSpeed()) {
            gpsSpeedMph = location.getSpeed() * 2.236936292;
            gpsSpeedAt = System.currentTimeMillis();
            DataStore.put("gps_speed", gpsSpeedMph, Math.round(gpsSpeedMph) + " mph");
        }

        RoadDatabase.Match match = null;
        if (!location.hasAccuracy() || location.getAccuracy() <= 60) {
            match = roadDb.find(
                    location.getLatitude(),
                    location.getLongitude(),
                    location.hasBearing() ? location.getBearing() : null);
        }

        if (match == null) {
            DataStore.put("speed_limit", -1, "--");
            DataStore.put("road_name", 0, "--");
        } else {
            DataStore.put("speed_limit", match.limitMph, match.limitMph + " mph");
            DataStore.put("road_name", 0,
                    match.roadName == null || match.roadName.isEmpty() ? "Unnamed road" : match.roadName);
        }

        evaluateWarning();
        sendData();
    }

    private synchronized void evaluateWarning() {
        if (!Prefs.get(this).getBoolean(Prefs.KEY_WARNING_ENABLED, true)) {
            armed = true;
            overCount = 0;
            return;
        }

        double limit = DataStore.raw("speed_limit", -1);
        double speed = -1;

        if (DataStore.fresh("speed", 5000)) {
            speed = DataStore.raw("speed", -1);
        } else if (Prefs.get(this).getBoolean(Prefs.KEY_GPS_FALLBACK, true)
                && System.currentTimeMillis() - gpsSpeedAt <= 4000) {
            speed = gpsSpeedMph;
        }

        if (limit <= 0 || speed < 0) {
            overCount = 0;
            return;
        }

        int offset = Prefs.get(this).getInt(Prefs.KEY_WARNING_OFFSET, 5);
        double trigger = limit + offset;
        double rearm = limit + Math.max(0, offset - 2);

        if (speed > trigger) {
            overCount++;
            if (armed && overCount >= 2) {
                armed = false;
                if (tts != null) {
                    tts.speak("Speed warning. Limit " + Math.round(limit),
                            TextToSpeech.QUEUE_FLUSH, null, "speed-warning");
                }
            }
        } else {
            overCount = 0;
            if (speed <= rearm) armed = true;
        }
    }

    private void sendData() {
        String text = DataStore.text("connection", "running");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFY, notification(text));
        sendBroadcast(new Intent(ACTION_DATA).setPackage(getPackageName()));
    }

    private Notification notification(String text) {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        return b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Car Scanner")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void closeObd() {
        if (classic != null) {
            classic.close();
            classic = null;
        }
        if (ble != null) {
            ble.close();
            ble = null;
        }
    }

    @Override
    public void onDestroy() {
        closeObd();
        try {
            if (locationManager != null) locationManager.removeUpdates(this);
        } catch (Exception ignored) {}

        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        DataStore.put("connection", 0, "stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
