package com.tvracer.lite;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wi-Fi remote for this game. Connects to the "TV Pad" phone app (the phone's hotspot is the TV's Wi-Fi gateway)
 * and replays its key presses into this activity, exactly like a TV remote or gamepad would.
 * Hook: call PadClient.attach(this) once in MainActivity.onCreate.
 */
final class PadClient implements Runnable, Application.ActivityLifecycleCallbacks {
    private static final int PORT = 8765;

    private final Activity act;
    private final Set<Integer> held = new HashSet<Integer>();
    private volatile boolean running;
    private Thread thread;
    private volatile Socket sock;
    private boolean announced;

    static void attach(Activity a) {
        a.getApplication().registerActivityLifecycleCallbacks(new PadClient(a));
    }

    private PadClient(Activity a) { this.act = a; }

    // ------------------------------------------------------------ lifecycle

    @Override public void onActivityResumed(Activity a) { if (a == act) start(); }
    @Override public void onActivityPaused(Activity a) { if (a == act) stop(); }
    @Override public void onActivityDestroyed(Activity a) {
        if (a == act) { stop(); a.getApplication().unregisterActivityLifecycleCallbacks(this); }
    }
    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }

    private synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "pad-client");
        thread.setDaemon(true);
        thread.start();
    }

    private synchronized void stop() {
        running = false;
        Socket s = sock;
        if (s != null) { try { s.close(); } catch (IOException ignored) { } }
        if (thread != null) thread.interrupt();
        releaseAll();
    }

    // ------------------------------------------------------------ network

    private List<String> candidates() {
        List<String> l = new ArrayList<String>();
        try {
            WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm.getDhcpInfo();
            add(l, d.gateway);
            add(l, d.serverAddress);
        } catch (Exception ignored) { }
        if (!l.contains("192.168.43.1")) l.add("192.168.43.1");
        return l;
    }

    private static void add(List<String> l, int g) {
        if (g == 0) return;
        String ip = (g & 255) + "." + ((g >> 8) & 255) + "." + ((g >> 16) & 255) + "." + ((g >> 24) & 255);
        if (!l.contains(ip)) l.add(ip);
    }

    @Override
    public void run() {
        while (running) {
            Socket s = null;
            try {
                for (String ip : candidates()) {
                    if (!running) break;
                    try {
                        Socket t = new Socket();
                        t.connect(new InetSocketAddress(ip, PORT), 1200);
                        s = t;
                        break;
                    } catch (IOException e) {
                        s = null;
                    }
                }
                if (s != null) {
                    sock = s;
                    s.setTcpNoDelay(true);
                    toast("Phone pad connected");
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                    String line;
                    while (running && (line = r.readLine()) != null) handle(line);
                }
            } catch (Exception ignored) {
            } finally {
                releaseAll();
                if (s != null) { try { s.close(); } catch (IOException ignored) { } }
                sock = null;
            }
            if (!running) break;
            try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
        }
    }

    private void handle(String line) {
        if (line.length() < 2) return;
        try {
            int code = Integer.parseInt(line.substring(1));
            boolean down = line.charAt(0) == 'D';
            synchronized (held) {
                if (down) { if (!held.add(code)) return; }
                else if (!held.remove(code)) return;
            }
            dispatch(code, down);
        } catch (NumberFormatException ignored) { }
    }

    private void releaseAll() {
        List<Integer> l;
        synchronized (held) { l = new ArrayList<Integer>(held); held.clear(); }
        for (int c : l) dispatch(c, false);
    }

    private void dispatch(final int code, final boolean down) {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                long t = SystemClock.uptimeMillis();
                act.dispatchKeyEvent(new KeyEvent(t, t, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                        code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD));
            }
        });
    }

    private void toast(final String m) {
        if (announced) return;
        announced = true;
        act.runOnUiThread(new Runnable() {
            @Override public void run() { Toast.makeText(act, m, Toast.LENGTH_SHORT).show(); }
        });
    }
}
