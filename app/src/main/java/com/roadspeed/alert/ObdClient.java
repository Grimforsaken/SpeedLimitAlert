package com.roadspeed.alert;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ObdClient implements AutoCloseable {
    public interface Listener { void onSpeedMph(double mph); void onStatus(String text); }
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final Pattern SPEED = Pattern.compile("41\\s*0D\\s*([0-9A-F]{2})", Pattern.CASE_INSENSITIVE);
    private final String address; private final Listener listener;
    private volatile boolean running; private BluetoothSocket socket; private Thread thread;
    public ObdClient(String address, Listener listener) { this.address = address; this.listener = listener; }

    @SuppressLint("MissingPermission")
    public void start() {
        running = true;
        thread = new Thread(() -> {
            while (running) {
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter == null) throw new IllegalStateException("Bluetooth unavailable");
                    BluetoothDevice device = adapter.getRemoteDevice(address);
                    listener.onStatus("Connecting to OBD…");
                    adapter.cancelDiscovery();
                    socket = device.createRfcommSocketToServiceRecord(SPP);
                    socket.connect();
                    listener.onStatus("OBD connected");
                    OutputStream out = socket.getOutputStream(); InputStream in = socket.getInputStream();
                    command(out,in,"ATZ",2500); command(out,in,"ATE0",1500); command(out,in,"ATL0",1500);
                    command(out,in,"ATS0",1500); command(out,in,"ATH0",1500); command(out,in,"ATSP0",2500);
                    while (running && socket.isConnected()) {
                        String response = command(out,in,"010D",1800);
                        Matcher m = SPEED.matcher(response);
                        if (m.find()) listener.onSpeedMph(Integer.parseInt(m.group(1),16) * 0.621371192);
                        Thread.sleep(700);
                    }
                } catch (Exception e) {
                    if (running) listener.onStatus("OBD disconnected; retrying");
                    closeSocket();
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                }
            }
            closeSocket();
        },"obd-speed");
        thread.start();
    }
    private String command(OutputStream out, InputStream in, String cmd, long timeout) throws Exception {
        while (in.available() > 0) in.read();
        out.write((cmd+"\r").getBytes(StandardCharsets.US_ASCII)); out.flush();
        long end = System.currentTimeMillis()+timeout; StringBuilder b = new StringBuilder();
        while (System.currentTimeMillis()<end && running) {
            while (in.available()>0) { int ch=in.read(); if(ch<0) break; if(ch=='>') return b.toString(); b.append((char)ch); }
            Thread.sleep(20);
        }
        return b.toString();
    }
    private void closeSocket(){ try{ if(socket!=null) socket.close(); }catch(Exception ignored){} socket=null; }
    @Override public void close(){ running=false; closeSocket(); if(thread!=null) thread.interrupt(); }
}
