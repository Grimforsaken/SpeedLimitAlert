package com.roadspeed.alert;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;

public class DevicePickerActivity extends Activity {
    private LinearLayout list;
    private TextView status;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner leScanner;
    private final Set<String> seen = new HashSet<>();
    private boolean receiverRegistered;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        requestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scan();
    }

    private void buildUi() {
        ScrollView s = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        root.setBackgroundColor(Color.rgb(17, 17, 17));
        s.addView(root);

        root.addView(label("SELECT OBD ADAPTER", 24, true));
        root.addView(label(
                "Scanning Bluetooth Classic and BLE. Choose the adapter that Car Scanner normally uses.",
                14, false));

        status = label("Starting scan…", 13, false);
        root.addView(status);

        Button settings = new Button(this);
        settings.setText("OPEN BLUETOOTH SETTINGS");
        settings.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        root.addView(settings);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        setContentView(s);
    }

    private void requestPermissions() {
        java.util.ArrayList<String> p = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                p.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                p.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), 77);
    }

    @SuppressLint("MissingPermission")
    private void scan() {
        stopScan();
        list.removeAllViews();
        seen.clear();

        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            status.setText("Bluetooth is not available on this device.");
            return;
        }
        if (!adapter.isEnabled()) {
            status.setText("Turn Bluetooth on, then return to this screen.");
            return;
        }

        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                int type = d.getType();
                if (type == BluetoothDevice.DEVICE_TYPE_LE) addDevice(d, "ble");
                else if (type == BluetoothDevice.DEVICE_TYPE_DUAL) {
                    addDevice(d, "ble");
                    addDevice(d, "classic");
                } else {
                    addDevice(d, "classic");
                }
            }
        } catch (SecurityException ignored) {}

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(classicReceiver, filter);
        receiverRegistered = true;
        try { adapter.startDiscovery(); } catch (SecurityException ignored) {}

        try {
            leScanner = adapter.getBluetoothLeScanner();
            if (leScanner != null) leScanner.startScan(leCallback);
        } catch (SecurityException ignored) {}

        status.setText("Scanning… Tap the OBD adapter when it appears.");
    }

    @SuppressLint("MissingPermission")
    private void addDevice(BluetoothDevice device, String transport) {
        if (device == null || device.getAddress() == null) return;
        String key = transport + ":" + device.getAddress();
        if (!seen.add(key)) return;

        String name;
        try { name = device.getName(); } catch (SecurityException e) { name = null; }
        if (name == null || name.trim().isEmpty()) name = "(unnamed device)";

        String upper = name.toUpperCase(java.util.Locale.US);
        boolean likely = upper.contains("OBD") || upper.contains("ELM")
                || upper.contains("VLINK") || upper.contains("VGATE")
                || upper.contains("OBDLINK");

        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText((likely ? "★ " : "") + name + "\n"
                + device.getAddress() + "   [" + transport.toUpperCase() + "]");
        final String finalName = name;
        b.setOnClickListener(v -> {
            Prefs.get(this).edit()
                    .putString(Prefs.KEY_OBD_NAME, finalName)
                    .putString(Prefs.KEY_OBD_ADDRESS, device.getAddress())
                    .putString(Prefs.KEY_OBD_TRANSPORT, transport)
                    .apply();
            Toast.makeText(this, "Selected " + finalName, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
        list.addView(b);
    }

    private final BroadcastReceiver classicReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                addDevice(d, "classic");
            }
        }
    };

    private final ScanCallback leCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            addDevice(result.getDevice(), "ble");
        }
    };

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (adapter != null) {
            try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
        }
        if (leScanner != null) {
            try { leScanner.stopScan(leCallback); } catch (Exception ignored) {}
            leScanner = null;
        }
        if (receiverRegistered) {
            try { unregisterReceiver(classicReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
    }

    @Override
    protected void onPause() {
        stopScan();
        super.onPause();
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setPadding(dp(4), dp(6), dp(4), dp(6));
        return v;
    }

    private int dp(int n) {
        return (int)(n * getResources().getDisplayMetrics().density + .5f);
    }
}
