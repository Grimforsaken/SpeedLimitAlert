package com.roadspeed.alert;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    private TextView obdStatus, roadStatus, dbStatus;
    private GridLayout grid;
    private final Map<String, TextView> valueViews = new ConcurrentHashMap<>();
    private CheckBox warningEnabled, gpsFallback;
    private EditText warningOffset;
    private BroadcastReceiver receiver;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        requestPermissionsIfNeeded();
        UpdateScheduler.schedulePeriodic(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHeader();
        rebuildWidgets();
        refreshValues();
    }

    @Override
    protected void onStart() {
        super.onStart();
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                refreshHeader();
                refreshValues();
            }
        };
        IntentFilter f = new IntentFilter(SpeedMonitorService.ACTION_DATA);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
    }

    @Override
    protected void onStop() {
        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }
        saveWarningSettings();
        super.onStop();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.setBackgroundColor(Color.rgb(15,15,15));
        scroll.addView(root);

        root.addView(label("CAR DASHBOARD", 26, true));
        root.addView(label("OBD dashboard + offline speed-limit data", 14, false));

        obdStatus = label("", 14, false);
        roadStatus = label("", 13, false);
        dbStatus = label("", 13, false);
        root.addView(obdStatus);
        root.addView(roadStatus);
        root.addView(dbStatus);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button select = button("SELECT / SCAN OBD");
        select.setOnClickListener(v -> startActivity(new Intent(this, DevicePickerActivity.class)));
        Button start = button("START");
        start.setOnClickListener(v -> startMonitor());
        Button stop = button("STOP");
        stop.setOnClickListener(v -> stopService(new Intent(this, SpeedMonitorService.class)));
        actions.addView(select, new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(start, new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(stop, new LinearLayout.LayoutParams(0,-2,1));
        root.addView(actions);

        Button customize = button("CUSTOMIZE WIDGETS");
        customize.setOnClickListener(v -> showWidgetPicker());
        root.addView(customize);

        grid = new GridLayout(this);
        grid.setColumnCount(2);
        root.addView(grid);

        root.addView(label("SPEED WARNING", 18, true));
        warningEnabled = new CheckBox(this);
        warningEnabled.setText("Audio warning when over the posted speed limit");
        warningEnabled.setTextColor(Color.WHITE);
        warningEnabled.setChecked(Prefs.get(this).getBoolean(Prefs.KEY_WARNING_ENABLED, true));
        root.addView(warningEnabled);

        LinearLayout warnRow = new LinearLayout(this);
        warnRow.setOrientation(LinearLayout.HORIZONTAL);
        warnRow.addView(label("Warn when more than", 14, false));
        warningOffset = new EditText(this);
        warningOffset.setTextColor(Color.WHITE);
        warningOffset.setInputType(2);
        warningOffset.setEms(2);
        warningOffset.setText(String.valueOf(Prefs.get(this).getInt(Prefs.KEY_WARNING_OFFSET, 5)));
        warnRow.addView(warningOffset);
        warnRow.addView(label("mph over", 14, false));
        root.addView(warnRow);

        gpsFallback = new CheckBox(this);
        gpsFallback.setText("Use GPS speed if OBD speed is unavailable");
        gpsFallback.setTextColor(Color.WHITE);
        gpsFallback.setChecked(Prefs.get(this).getBoolean(Prefs.KEY_GPS_FALLBACK, true));
        root.addView(gpsFallback);

        Button roadData = button("OFFLINE ROAD DATA & STORAGE");
        roadData.setOnClickListener(v -> startActivity(new Intent(this, StorageActivity.class)));
        root.addView(roadData);

        setContentView(scroll);
    }

    private void rebuildWidgets() {
        grid.removeAllViews();
        valueViews.clear();

        for (String id : Prefs.selectedWidgets(this)) {
            WidgetCatalog.Def def = WidgetCatalog.byId(id);
            if (def == null) continue;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(12));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.rgb(35,35,35));
            bg.setCornerRadius(dp(10));
            bg.setStroke(dp(1), Color.rgb(75,75,75));
            card.setBackground(bg);

            TextView title = label(def.label, 14, false);
            TextView value = label("--", 28, true);
            value.setGravity(Gravity.CENTER);
            card.addView(title);
            card.addView(value);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(112);
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(card, lp);
            valueViews.put(id, value);
        }
    }

    private void refreshValues() {
        for (Map.Entry<String, TextView> e : valueViews.entrySet()) {
            e.getValue().setText(DataStore.text(e.getKey(), "--"));
        }
    }

    private void refreshHeader() {
        String name = Prefs.get(this).getString(Prefs.KEY_OBD_NAME, "");
        String transport = Prefs.get(this).getString(Prefs.KEY_OBD_TRANSPORT, "");
        obdStatus.setText(name.isEmpty()
                ? "OBD: not selected"
                : "OBD: " + name + (transport.isEmpty() ? "" : " [" + transport.toUpperCase() + "]")
                    + " • " + DataStore.text("connection", "not running"));

        roadStatus.setText("Road: " + DataStore.text("road_name", "--"));
        long db = RoadDatabase.databaseSize(this);
        dbStatus.setText(db > 0
                ? "Offline road database: " + RoadDataManager.sizeText(db)
                : "Offline road database: not installed");
    }

    private void showWidgetPicker() {
        String[] labels = new String[WidgetCatalog.ALL.size()];
        boolean[] checked = new boolean[labels.length];
        Set<String> selected = Prefs.selectedWidgets(this);

        for (int i = 0; i < WidgetCatalog.ALL.size(); i++) {
            WidgetCatalog.Def d = WidgetCatalog.ALL.get(i);
            labels[i] = d.label;
            checked[i] = selected.contains(d.id);
        }

        new AlertDialog.Builder(this)
                .setTitle("Custom widgets")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("SAVE", (dialog, which) -> {
                    LinkedHashSet<String> ids = new LinkedHashSet<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) ids.add(WidgetCatalog.ALL.get(i).id);
                    }
                    Prefs.saveWidgets(this, ids);
                    rebuildWidgets();
                    refreshValues();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void startMonitor() {
        saveWarningSettings();
        if (Prefs.get(this).getString(Prefs.KEY_OBD_ADDRESS, "").isEmpty()) {
            Toast.makeText(this, "Select or scan for your OBD adapter first.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, DevicePickerActivity.class));
            return;
        }
        Intent i = new Intent(this, SpeedMonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void saveWarningSettings() {
        int offset = 5;
        try { offset = Math.max(1, Math.min(20, Integer.parseInt(warningOffset.getText().toString().trim()))); }
        catch (Exception ignored) {}
        Prefs.get(this).edit()
                .putBoolean(Prefs.KEY_WARNING_ENABLED, warningEnabled.isChecked())
                .putBoolean(Prefs.KEY_GPS_FALLBACK, gpsFallback.isChecked())
                .putInt(Prefs.KEY_WARNING_OFFSET, offset)
                .apply();
    }

    private void requestPermissionsIfNeeded() {
        ArrayList<String> p = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                p.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.POST_NOTIFICATIONS);

        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), 50);
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setPadding(dp(4), dp(5), dp(4), dp(5));
        return v;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private int dp(int n) {
        return (int)(n * getResources().getDisplayMetrics().density + .5f);
    }
}
