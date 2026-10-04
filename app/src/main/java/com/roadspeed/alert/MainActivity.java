package com.roadspeed.alert;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private Spinner devices;
    private final List<String> addresses = new ArrayList<>();
    private TextView speed, limit, road, obd, data;
    private EditText offset;
    private CheckBox gpsFallback, floatingWidget;
    private BroadcastReceiver receiver;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        requestPermissionsIfNeeded();
        UpdateScheduler.schedulePeriodic(this);
        if (Prefs.get(this).getBoolean(Prefs.KEY_AUTO_UPDATE, true)) {
            UpdateScheduler.scheduleNowWhenWifiAvailable(this);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        loadDevices();
        refreshDataStatus();
    }

    @Override protected void onStart() {
        super.onStart();
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                speed.setText(i.getStringExtra("speed") + " mph");
                limit.setText(i.getStringExtra("limit") + " mph");
                road.setText(i.getStringExtra("road"));
                obd.setText(i.getStringExtra("obd") + " • " + i.getStringExtra("source"));
            }
        };
        IntentFilter f = new IntentFilter(SpeedMonitorService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
    }

    @Override protected void onStop() {
        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }
        super.onStop();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(24));
        root.setBackgroundColor(Color.rgb(17, 17, 17));
        scroll.addView(root);

        root.addView(label("SPEED LIMIT ALERT", 26, true));
        root.addView(label("OBD vehicle speed + offline road limits", 14, false));

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        speed = bigCard("Speed", "-- mph");
        limit = bigCard("Limit", "-- mph");
        cards.addView((View) speed.getParent(), new LinearLayout.LayoutParams(0, dp(110), 1));
        cards.addView((View) limit.getParent(), new LinearLayout.LayoutParams(0, dp(110), 1));
        root.addView(cards);

        road = label("", 15, false);
        obd = label("Not monitoring", 14, false);
        root.addView(road);
        root.addView(obd);

        root.addView(label("Paired Bluetooth OBD-II adapter", 15, true));
        devices = new Spinner(this);
        root.addView(devices);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(label("Warn when more than", 15, false));
        offset = new EditText(this);
        offset.setInputType(2);
        offset.setText(String.valueOf(Prefs.get(this).getInt(Prefs.KEY_WARNING_OFFSET, 5)));
        offset.setTextColor(Color.WHITE);
        offset.setEms(2);
        row.addView(offset);
        row.addView(label("mph over", 15, false));
        root.addView(row);

        gpsFallback = new CheckBox(this);
        gpsFallback.setText("Use GPS speed if OBD disconnects");
        gpsFallback.setTextColor(Color.WHITE);
        gpsFallback.setChecked(Prefs.get(this).getBoolean(Prefs.KEY_GPS_FALLBACK, true));
        root.addView(gpsFallback);

        floatingWidget = new CheckBox(this);
        floatingWidget.setText("Floating speed-limit widget over other apps");
        floatingWidget.setTextColor(Color.WHITE);
        floatingWidget.setChecked(Prefs.get(this).getBoolean(Prefs.KEY_FLOATING_WIDGET, false));
        floatingWidget.setOnCheckedChangeListener((button, checked) -> {
            Prefs.get(this).edit().putBoolean(Prefs.KEY_FLOATING_WIDGET, checked).apply();
            if (checked && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow Speed Limit Alert to appear on top, then return here.", Toast.LENGTH_LONG).show();
                Intent permission = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(permission);
            }
        });
        root.addView(floatingWidget);
        root.addView(label("The floating sign is draggable and shows only the current speed limit.", 12, false));

        Button start = button("START MONITORING");
        start.setOnClickListener(v -> startMonitor());
        root.addView(start);

        Button stop = button("STOP");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, SpeedMonitorService.class));
            obd.setText("Stopped");
        });
        root.addView(stop);

        data = label("", 14, false);
        root.addView(data);

        Button storage = button("ROAD DATA & STORAGE");
        storage.setOnClickListener(v -> startActivity(new Intent(this, StorageActivity.class)));
        root.addView(storage);

        setContentView(scroll);
    }

    private TextView bigCard(String cap, String val) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.addView(label(cap, 13, false));
        TextView v = label(val, 31, true);
        box.addView(v);
        return v;
    }

    private void startMonitor() {
        if (addresses.isEmpty() || devices.getSelectedItemPosition() < 0) {
            Toast.makeText(this, "Pair an ELM327-style Bluetooth OBD adapter first", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }

        if (floatingWidget.isChecked() && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant 'appear on top' permission for the floating speed-limit widget.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }

        int w = 5;
        try {
            w = Math.max(1, Math.min(20, Integer.parseInt(offset.getText().toString().trim())));
        } catch (Exception ignored) {}

        String addr = addresses.get(devices.getSelectedItemPosition());
        Prefs.get(this).edit()
                .putInt(Prefs.KEY_WARNING_OFFSET, w)
                .putBoolean(Prefs.KEY_GPS_FALLBACK, gpsFallback.isChecked())
                .putBoolean(Prefs.KEY_FLOATING_WIDGET, floatingWidget.isChecked())
                .putString(Prefs.KEY_OBD_ADDRESS, addr)
                .apply();

        Intent monitor = new Intent(this, SpeedMonitorService.class)
                .putExtra("address", addr)
                .putExtra("offset", w)
                .putExtra("gpsFallback", gpsFallback.isChecked())
                .putExtra("floatingWidget", floatingWidget.isChecked());
        startForegroundService(monitor);
    }

    @SuppressWarnings("deprecation")
    private void loadDevices() {
        addresses.clear();
        List<String> names = new ArrayList<>();
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();

        if (a == null) {
            names.add("Bluetooth unavailable");
        } else if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            names.add("Bluetooth permission required");
        } else {
            try {
                String saved = Prefs.get(this).getString(Prefs.KEY_OBD_ADDRESS, "");
                int selected = 0, i = 0;
                for (BluetoothDevice d : a.getBondedDevices()) {
                    String n = d.getName() == null ? "Bluetooth device" : d.getName();
                    names.add(n + "  •  " + d.getAddress());
                    addresses.add(d.getAddress());
                    if (d.getAddress().equals(saved)) selected = i;
                    i++;
                }
                if (names.isEmpty()) names.add("No paired devices");
                devices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
                if (!addresses.isEmpty()) devices.setSelection(selected);
                return;
            } catch (SecurityException e) {
                names.add("Bluetooth permission required");
            }
        }
        devices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
    }

    private void refreshDataStatus() {
        long s = RoadDatabase.databaseSize(this);
        data.setText(s > 0 ? "Offline road database: " + RoadDataManager.sizeText(s)
                : "Offline road database: not installed");
    }

    private void requestPermissionsIfNeeded() {
        List<String> p = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), 50);
    }

    private TextView label(String t, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setPadding(dp(4), dp(6), dp(4), dp(6));
        return v;
    }

    private Button button(String t) {
        Button b = new Button(this);
        b.setText(t);
        return b;
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }
}
