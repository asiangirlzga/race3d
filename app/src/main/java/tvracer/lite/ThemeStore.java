package com.tvracer.lite;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/**
 * Holds the colour themes. A tiny built-in theme always exists (works offline).
 * On the first launch a theme pack (a few KB of JSON) is downloaded and cached;
 * it is refreshed in the background once a week.
 */
final class ThemeStore {
    static final int PROPS_NONE = 0, PROPS_PINE = 1, PROPS_ROUND = 2, PROPS_CACTUS = 3, PROPS_LAMP = 4;

    static final class Theme {
        String name = "Sunny Day";
        int skyTop = 0xFF3A8DDE, skyBottom = 0xFFBFE6FF, sun = 0xFFFFF3B0, mountain = 0xFF6D8CA8;
        int grassA = 0xFF2E9B4F, grassB = 0xFF288A46;
        int roadA = 0xFF5A5A5A, roadB = 0xFF626262;
        int rumbleA = 0xFFFFFFFF, rumbleB = 0xFFD32F2F;
        int lane = 0xFFFFFFFF, fog = 0xFFBFE6FF;
        int props = PROPS_ROUND;
        boolean stars = false;
        int player = 0xFFE53935;
        int[] traffic = {0xFF1E88E5, 0xFFFDD835, 0xFF43A047, 0xFF8E24AA, 0xFFFB8C00};
    }

    private static final long STALE_MS = 7L * 24 * 3600 * 1000;

    private final File cacheFile;
    private volatile Theme[] list = {new Theme()};

    ThemeStore(Context c) {
        cacheFile = new File(c.getFilesDir(), "themes.json");
        if (cacheFile.exists()) {
            try {
                Theme[] l = parse(readAll(new FileInputStream(cacheFile)));
                if (l.length > 0) list = l;
            } catch (Exception e) {
                cacheFile.delete();
            }
        }
    }

    int count() {
        return list.length;
    }

    Theme get(int i) {
        Theme[] l = list;
        return l[((i % l.length) + l.length) % l.length];
    }

    boolean needsDownload() {
        return !cacheFile.exists();
    }

    boolean isStale() {
        return cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() > STALE_MS;
    }

    /** Downloads and caches the theme pack. Returns true on success. Call from a background thread. */
    boolean download(String urlStr) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(urlStr).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", "TvRacerLite");
            if (con.getResponseCode() != 200) return false;
            String json = readAll(con.getInputStream());
            Theme[] parsed = parse(json);
            if (parsed.length == 0) return false;
            File tmp = new File(cacheFile.getPath() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            o.write(json.getBytes("UTF-8"));
            o.close();
            cacheFile.delete();
            if (!tmp.renameTo(cacheFile)) return false;
            list = parsed;
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n, total = 0;
        while ((n = in.read(b)) > 0) {
            total += n;
            if (total > 65536) throw new IOException("theme pack too large");
            bo.write(b, 0, n);
        }
        in.close();
        return bo.toString("UTF-8");
    }

    private static Theme[] parse(String json) throws JSONException {
        JSONArray a = new JSONObject(json).getJSONArray("themes");
        ArrayList<Theme> out = new ArrayList<>();
        for (int i = 0; i < a.length() && i < 12; i++) {
            JSONObject o = a.getJSONObject(i);
            Theme t = new Theme();
            t.name = o.optString("name", "Theme " + (i + 1));
            t.skyTop = col(o, "skyTop", t.skyTop);
            t.skyBottom = col(o, "skyBottom", t.skyBottom);
            if (o.has("sun")) t.sun = "none".equals(o.optString("sun")) ? 0 : col(o, "sun", t.sun);
            t.mountain = col(o, "mountain", t.mountain);
            t.grassA = col(o, "grassA", t.grassA);
            t.grassB = col(o, "grassB", t.grassB);
            t.roadA = col(o, "roadA", t.roadA);
            t.roadB = col(o, "roadB", t.roadB);
            t.rumbleA = col(o, "rumbleA", t.rumbleA);
            t.rumbleB = col(o, "rumbleB", t.rumbleB);
            t.lane = col(o, "lane", t.lane);
            t.fog = col(o, "fog", t.fog);
            t.player = col(o, "player", t.player);
            t.stars = o.optBoolean("stars", false);
            t.props = propId(o.optString("props", "round"));
            JSONArray tr = o.optJSONArray("traffic");
            if (tr != null && tr.length() > 0) {
                int[] c = new int[tr.length()];
                for (int k = 0; k < c.length; k++) c[k] = parseColor(tr.optString(k), 0xFF888888);
                t.traffic = c;
            }
            out.add(t);
        }
        return out.toArray(new Theme[0]);
    }

    private static int propId(String s) {
        if ("pine".equals(s)) return PROPS_PINE;
        if ("cactus".equals(s)) return PROPS_CACTUS;
        if ("lamp".equals(s)) return PROPS_LAMP;
        if ("none".equals(s)) return PROPS_NONE;
        return PROPS_ROUND;
    }

    private static int col(JSONObject o, String key, int def) {
        String s = o.optString(key, null);
        return s == null ? def : parseColor(s, def);
    }

    private static int parseColor(String s, int def) {
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return def;
        }
    }
}
