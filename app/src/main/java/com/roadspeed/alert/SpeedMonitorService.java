package com.roadspeed.alert;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

public class SpeedMonitorService extends Service implements LocationListener, ObdClient.Listener {
    public static final String ACTION_STATUS = "com.roadspeed.alert.STATUS";
    private static final int NOTIFY = 7001;

    private LocationManager lm;
    private RoadDatabase roadDb;
    private ObdClient obd;
    private TextToSpeech tts;
    private volatile Location lastLocation;
    private volatile double obdSpeed = -1;
    private volatile long lastObdAt;
    private int offset = 5;
    private boolean gpsFallback = true, armed = true, floatingEnabled = false;
    private long lastWarning;
    private String obdStatus = "Starting";

    private WindowManager windowManager;
    private View floatingView;
    private TextView floatingLimit;
    private WindowManager.LayoutParams floatingParams;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("monitor", "Speed monitoring", NotificationManager.IMPORTANCE_LOW));
        roadDb = new RoadDatabase(this);
        lm = getSystemService(LocationManager.class);
        windowManager = getSystemService(WindowManager.class);
        tts = new TextToSpeech(this, s -> {
            if (s == TextToSpeech.SUCCESS) tts.setLanguage(Locale.US);
        });
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String addr = i == null ? null : i.getStringExtra("address");
        offset = i == null ? 5 : i.getIntExtra("offset", 5);
        gpsFallback = i == null || i.getBooleanExtra("gpsFallback", true);
        floatingEnabled = i != null
                ? i.getBooleanExtra("floatingWidget", Prefs.get(this).getBoolean(Prefs.KEY_FLOATING_WIDGET, false))
                : Prefs.get(this).getBoolean(Prefs.KEY_FLOATING_WIDGET, false);

        startForeground(NOTIFY, notification("Starting…"));
        refreshFloatingWidget();

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 2, this);
            } catch (Exception ignored) {}
        }

        if (addr != null && !addr.isEmpty()) {
            if (obd != null) obd.close();
            obd = new ObdClient(addr, this);
            obd.start();
        } else {
            obdStatus = "No OBD selected";
        }
        return START_STICKY;
    }

    @Override public void onLocationChanged(Location l) {
        lastLocation = l;
        evaluate();
    }

    @Override public void onSpeedMph(double mph) {
        obdSpeed = mph;
        lastObdAt = System.currentTimeMillis();
        evaluate();
    }

    @Override public void onStatus(String t) {
        obdStatus = t;
        evaluate();
    }

    private synchronized void evaluate() {
        Location loc = lastLocation;
        double speed = -1;
        boolean fresh = System.currentTimeMillis() - lastObdAt < 4000 && obdSpeed >= 0;

        if (fresh) speed = obdSpeed;
        else if (gpsFallback && loc != null && loc.hasSpeed()) speed = loc.getSpeed() * 2.236936292;

        RoadDatabase.Match m = loc == null ? null
                : roadDb.find(loc.getLatitude(), loc.getLongitude(), loc.hasBearing() ? loc.getBearing() : null);
        int limit = m == null ? -1 : m.limitMph;

        if (speed >= 0 && limit > 0) {
            double trigger = limit + offset;
            double rearm = limit + Math.max(0, offset - 2);
            if (speed > trigger && (armed || System.currentTimeMillis() - lastWarning > 30000)) {
                armed = false;
                lastWarning = System.currentTimeMillis();
                if (tts != null) {
                    tts.speak("Speed warning. Limit " + limit, TextToSpeech.QUEUE_FLUSH, null, "speed-warning");
                }
            } else if (speed <= rearm) {
                armed = true;
            }
        } else {
            armed = true;
        }

        updateFloatingLimit(limit);

        String sp = speed < 0 ? "--" : String.valueOf((int) Math.round(speed));
        String li = limit < 0 ? "--" : String.valueOf(limit);
        String road = m == null || m.roadName.isEmpty() ? "" : " • " + m.roadName;
        String status = "Speed " + sp + " mph • Limit " + li + road + " • " + obdStatus;

        getSystemService(NotificationManager.class).notify(NOTIFY, notification(status));
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra("speed", sp)
                .putExtra("limit", li)
                .putExtra("road", road)
                .putExtra("obd", obdStatus)
                .putExtra("source", fresh ? "OBD" : (speed >= 0 ? "GPS fallback" : "--")));
    }

    private void refreshFloatingWidget() {
        mainHandler.post(() -> {
            if (floatingEnabled && Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this)) {
                if (floatingView == null) createFloatingWidget();
            } else {
                removeFloatingWidget();
            }
        });
    }

    private void createFloatingWidget() {
        if (windowManager == null || floatingView != null) return;

        int size = dp(82);
        int inset = dp(6);

        FrameLayout outer = new FrameLayout(this);
        GradientDrawable redCircle = new GradientDrawable();
        redCircle.setShape(GradientDrawable.OVAL);
        redCircle.setColor(Color.rgb(215, 25, 32));
        outer.setBackground(redCircle);

        FrameLayout inner = new FrameLayout(this);
        GradientDrawable whiteCircle = new GradientDrawable();
        whiteCircle.setShape(GradientDrawable.OVAL);
        whiteCircle.setColor(Color.WHITE);
        inner.setBackground(whiteCircle);

        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        innerLp.setMargins(inset, inset, inset, inset);
        outer.addView(inner, innerLp);

        floatingLimit = new TextView(this);
        floatingLimit.setText("—");
        floatingLimit.setTextColor(Color.BLACK);
        floatingLimit.setTextSize(28);
        floatingLimit.setGravity(Gravity.CENTER);
        floatingLimit.setTypeface(null, android.graphics.Typeface.BOLD);
        inner.addView(floatingLimit, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        floatingParams = new WindowManager.LayoutParams(
                size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        floatingParams.gravity = Gravity.TOP | Gravity.START;
        floatingParams.x = Prefs.get(this).getInt(Prefs.KEY_WIDGET_X, dp(18));
        floatingParams.y = Prefs.get(this).getInt(Prefs.KEY_WIDGET_Y, dp(120));

        outer.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float touchX, touchY;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = floatingParams.x;
                        startY = floatingParams.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        floatingParams.x = startX + (int) (e.getRawX() - touchX);
                        floatingParams.y = startY + (int) (e.getRawY() - touchY);
                        try {
                            windowManager.updateViewLayout(floatingView, floatingParams);
                        } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        Prefs.get(SpeedMonitorService.this).edit()
                                .putInt(Prefs.KEY_WIDGET_X, floatingParams.x)
                                .putInt(Prefs.KEY_WIDGET_Y, floatingParams.y)
                                .apply();
                        return true;
                }
                return false;
            }
        });

        floatingView = outer;
        try {
            windowManager.addView(floatingView, floatingParams);
        } catch (Exception e) {
            floatingView = null;
            floatingLimit = null;
            floatingParams = null;
        }
    }

    private void updateFloatingLimit(int limit) {
        if (!floatingEnabled) return;
        mainHandler.post(() -> {
            if (floatingView == null) refreshFloatingWidget();
            if (floatingLimit != null) {
                floatingLimit.setText(limit > 0 ? String.valueOf(limit) : "—");
            }
        });
    }

    private void removeFloatingWidget() {
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {}
        }
        floatingView = null;
        floatingLimit = null;
        floatingParams = null;
    }

    private Notification notification(String text) {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "monitor")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Speed Limit Alert is running")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        try {
            if (lm != null) lm.removeUpdates(this);
        } catch (Exception ignored) {}
        if (obd != null) obd.close();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        mainHandler.post(this::removeFloatingWidget);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) {
        return null;
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }
}
