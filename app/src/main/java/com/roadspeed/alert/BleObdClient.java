package com.roadspeed.alert;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class BleObdClient implements AutoCloseable {
    private static final UUID CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final String address;
    private final ObdClient.Listener listener;
    private volatile boolean running;
    private Thread thread;
    private Channel channel;

    public BleObdClient(Context context, String address, ObdClient.Listener listener) {
        this.context = context.getApplicationContext();
        this.address = address;
        this.listener = listener;
    }

    public void start() {
        running = true;
        thread = new Thread(() -> {
            while (running) {
                try {
                    listener.onStatus("Connecting to OBD (BLE)…");
                    channel = new Channel(context, address);
                    channel.connect();
                    listener.onStatus("OBD connected");
                    init();

                    int otherIndex = 0;
                    while (running && channel.isConnected()) {
                        poll("speed");
                        Set<String> selected = Prefs.selectedWidgets(context);
                        List<String> ids = WidgetCatalog.pollableIds(selected);
                        if (!ids.isEmpty()) {
                            if (otherIndex >= ids.size()) otherIndex = 0;
                            String id = ids.get(otherIndex++);
                            if (!"speed".equals(id)) poll(id);
                        }
                        Thread.sleep(180);
                    }
                } catch (Exception e) {
                    if (running) listener.onStatus("BLE OBD disconnected; retrying");
                    closeChannel();
                    sleep(2500);
                }
            }
            closeChannel();
        }, "obd-ble");
        thread.start();
    }

    private void init() throws Exception {
        channel.transact("ATZ", 3200);
        channel.transact("ATE0", 1600);
        channel.transact("ATL0", 1600);
        channel.transact("ATS0", 1600);
        channel.transact("ATH0", 1600);
        channel.transact("ATAT1", 1600);
        channel.transact("ATSP0", 3200);
    }

    private void poll(String id) throws Exception {
        WidgetCatalog.Def def = WidgetCatalog.byId(id);
        if (def == null || def.command == null) return;
        String response = channel.transact(def.command, 2000);
        WidgetCatalog.Parsed parsed = WidgetCatalog.parse(id, response);
        if (parsed != null) listener.onValue(id, parsed.raw, parsed.text);
    }

    private void closeChannel() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    @Override
    public void close() {
        running = false;
        closeChannel();
        if (thread != null) thread.interrupt();
    }

    private static final class Channel implements AutoCloseable {
        private final Context context;
        private final String address;
        private final CountDownLatch ready = new CountDownLatch(1);
        private final BlockingQueue<String> incoming = new LinkedBlockingQueue<>();

        private volatile BluetoothGatt gatt;
        private volatile BluetoothGattCharacteristic writeChar;
        private volatile BluetoothGattCharacteristic notifyChar;
        private volatile boolean connected;
        private volatile String failure;

        Channel(Context context, String address) {
            this.context = context;
            this.address = address;
        }

        @SuppressLint("MissingPermission")
        void connect() throws Exception {
            android.bluetooth.BluetoothAdapter adapter =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) throw new IllegalStateException("Bluetooth unavailable");
            BluetoothDevice device = adapter.getRemoteDevice(address);
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);

            if (!ready.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("BLE service discovery timed out");
            }
            if (failure != null) throw new IllegalStateException(failure);
            if (!connected || writeChar == null || notifyChar == null) {
                throw new IllegalStateException("No compatible BLE serial service found");
            }
        }

        boolean isConnected() {
            return connected;
        }

        @SuppressLint("MissingPermission")
        String transact(String command, long timeoutMs) throws Exception {
            if (!connected || gatt == null || writeChar == null)
                throw new IllegalStateException("BLE not connected");

            incoming.clear();
            byte[] bytes = (command + "\r").getBytes(StandardCharsets.US_ASCII);

            if ((writeChar.getProperties()
                    & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }

            writeChar.setValue(bytes);
            if (!gatt.writeCharacteristic(writeChar)) {
                throw new IllegalStateException("BLE write failed");
            }

            long end = System.currentTimeMillis() + timeoutMs;
            long lastData = 0;
            StringBuilder b = new StringBuilder();

            while (System.currentTimeMillis() < end && connected) {
                String chunk = incoming.poll(180, TimeUnit.MILLISECONDS);
                if (chunk != null) {
                    b.append(chunk);
                    lastData = System.currentTimeMillis();
                    if (b.indexOf(">") >= 0) break;
                } else if (b.length() > 0 && lastData > 0
                        && System.currentTimeMillis() - lastData > 450) {
                    break;
                }
            }
            return b.toString();
        }

        @SuppressLint("MissingPermission")
        private void chooseSerialCharacteristics(BluetoothGatt g) {
            BluetoothGattCharacteristic bestWrite = null;
            BluetoothGattCharacteristic bestNotify = null;
            int bestWriteScore = Integer.MIN_VALUE;
            int bestNotifyScore = Integer.MIN_VALUE;

            for (BluetoothGattService service : g.getServices()) {
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    int props = c.getProperties();
                    String uuid = c.getUuid().toString().toLowerCase(Locale.US);

                    if ((props & (BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                        int score = score(uuid, true);
                        if (score > bestWriteScore) {
                            bestWriteScore = score;
                            bestWrite = c;
                        }
                    }

                    if ((props & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                        int score = score(uuid, false);
                        if (score > bestNotifyScore) {
                            bestNotifyScore = score;
                            bestNotify = c;
                        }
                    }
                }
            }

            writeChar = bestWrite;
            notifyChar = bestNotify;

            if (notifyChar != null) {
                g.setCharacteristicNotification(notifyChar, true);
                BluetoothGattDescriptor d = notifyChar.getDescriptor(CCCD);
                if (d != null) {
                    d.setValue((notifyChar.getProperties()
                            & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                            ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                            : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                }
            }
        }

        private static int score(String uuid, boolean write) {
            int score = 1;
            if (uuid.contains("6e400002") && write) score += 100;
            if (uuid.contains("6e400003") && !write) score += 100;
            if (uuid.contains("ffe1")) score += 80;
            if (uuid.contains("fff1")) score += write ? 35 : 75;
            if (uuid.contains("fff2")) score += write ? 75 : 35;
            if (uuid.contains("ff01")) score += write ? 30 : 60;
            if (uuid.contains("ff02")) score += write ? 60 : 30;
            return score;
        }

        private void push(byte[] value) {
            if (value == null || value.length == 0) return;
            incoming.offer(new String(value, StandardCharsets.US_ASCII));
        }

        private final BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            @SuppressLint("MissingPermission")
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failure = "BLE connection error " + status;
                    connected = false;
                    ready.countDown();
                    return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected = true;
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected = false;
                    ready.countDown();
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failure = "BLE service discovery failed";
                } else {
                    chooseSerialCharacteristics(g);
                    if (writeChar == null || notifyChar == null) {
                        failure = "No BLE serial characteristics";
                    }
                }
                ready.countDown();
            }

            @Override
            public void onCharacteristicChanged(
                    BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
                push(characteristic.getValue());
            }

            @Override
            public void onCharacteristicChanged(
                    BluetoothGatt g,
                    BluetoothGattCharacteristic characteristic,
                    byte[] value) {
                push(value);
            }
        };

        @Override
        @SuppressLint("MissingPermission")
        public void close() {
            connected = false;
            BluetoothGatt x = gatt;
            gatt = null;
            if (x != null) {
                try { x.disconnect(); } catch (Exception ignored) {}
                try { x.close(); } catch (Exception ignored) {}
            }
        }
    }
}
