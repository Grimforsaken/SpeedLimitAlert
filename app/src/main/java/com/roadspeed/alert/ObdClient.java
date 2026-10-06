package com.roadspeed.alert;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ObdClient implements AutoCloseable {
    public interface Listener {
        void onValue(String id, double raw, String display);
        void onStatus(String text);
    }

    private static final UUID SPP =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context context;
    private final String address;
    private final Listener listener;
    private volatile boolean running;
    private BluetoothSocket socket;
    private Thread thread;

    public ObdClient(Context context, String address, Listener listener) {
        this.context = context.getApplicationContext();
        this.address = address;
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        running = true;
        thread = new Thread(() -> {
            while (running) {
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter == null) throw new IllegalStateException("Bluetooth unavailable");

                    BluetoothDevice device = adapter.getRemoteDevice(address);
                    listener.onStatus("Connecting to OBD (Bluetooth Classic)…");
                    adapter.cancelDiscovery();

                    socket = device.createRfcommSocketToServiceRecord(SPP);
                    socket.connect();
                    listener.onStatus("OBD connected");

                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream();

                    init(out, in);
                    int otherIndex = 0;

                    while (running && socket.isConnected()) {
                        poll("speed", out, in);

                        Set<String> selected = Prefs.selectedWidgets(context);
                        List<String> ids = WidgetCatalog.pollableIds(selected);
                        if (!ids.isEmpty()) {
                            if (otherIndex >= ids.size()) otherIndex = 0;
                            String id = ids.get(otherIndex++);
                            if (!"speed".equals(id)) poll(id, out, in);
                        }
                        Thread.sleep(180);
                    }
                } catch (Exception e) {
                    if (running) listener.onStatus("OBD disconnected; retrying");
                    closeSocket();
                    sleep(2500);
                }
            }
            closeSocket();
        }, "obd-classic");
        thread.start();
    }

    private void init(OutputStream out, InputStream in) throws Exception {
        command(out, in, "ATZ", 3000);
        command(out, in, "ATE0", 1500);
        command(out, in, "ATL0", 1500);
        command(out, in, "ATS0", 1500);
        command(out, in, "ATH0", 1500);
        command(out, in, "ATAT1", 1500);
        command(out, in, "ATSP0", 3000);
    }

    private void poll(String id, OutputStream out, InputStream in) throws Exception {
        WidgetCatalog.Def def = WidgetCatalog.byId(id);
        if (def == null || def.command == null) return;
        String response = command(out, in, def.command, 1800);
        WidgetCatalog.Parsed parsed = WidgetCatalog.parse(id, response);
        if (parsed != null) listener.onValue(id, parsed.raw, parsed.text);
    }

    private String command(OutputStream out, InputStream in, String cmd, long timeoutMs)
            throws Exception {
        while (in.available() > 0) in.read();
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        long end = System.currentTimeMillis() + timeoutMs;
        StringBuilder b = new StringBuilder();

        while (running && System.currentTimeMillis() < end) {
            while (in.available() > 0) {
                int ch = in.read();
                if (ch < 0) break;
                if (ch == '>') return b.toString();
                b.append((char) ch);
            }
            Thread.sleep(15);
        }
        return b.toString();
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    @Override
    public void close() {
        running = false;
        closeSocket();
        if (thread != null) thread.interrupt();
    }
}
