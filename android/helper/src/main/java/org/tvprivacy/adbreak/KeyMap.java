package org.tvprivacy.adbreak;

import android.content.Context;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Remote-button map: key code -> app package (or "adbreak:settings").
 * Stored in /sdcard/Android/data/org.tvprivacy.adbreak/files/keymap.json so it can be
 * edited on the TV (Ad Break Guard settings) or pushed from the PC.
 */
final class KeyMap {
    static final String SETTINGS_TARGET = "adbreak:settings";
    static final String KIDS_TARGET = "adbreak:kids";

    static final class Entry {
        int keyCode; int scanCode; String name; String target;
        Entry(int k, int s, String n, String t) { keyCode = k; scanCode = s; name = n; target = t; }
    }

    final List<Entry> entries = new ArrayList<>();

    static File file(Context c) { return new File(c.getExternalFilesDir(null), "keymap.json"); }

    static KeyMap load(Context c) {
        KeyMap m = new KeyMap();
        try {
            File f = file(c);
            if (!f.isFile()) return m;
            byte[] b;
            try (FileInputStream in = new FileInputStream(f)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                b = bos.toByteArray();
            }
            JSONArray a = new JSONObject(new String(b, StandardCharsets.UTF_8)).optJSONArray("keys");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                int k = o.getInt("keyCode");
                if (isHardProtected(k)) continue;
                int sc = o.optInt("scanCode", -1);
                if (k == KeyEvent.KEYCODE_UNKNOWN && sc <= 0) continue;
                m.entries.add(new Entry(k, sc, o.optString("name", KeyEvent.keyCodeToString(k)), o.optString("target", "")));
            }
        } catch (Throwable t) {
            android.util.Log.w(AdGuardService.TAG, "keymap unreadable (" + t.getClass().getSimpleName() + "); no buttons remapped");
            m.entries.clear();
        }
        return m;
    }

    void save(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (Entry e : entries) a.put(new JSONObject().put("keyCode", e.keyCode).put("scanCode", e.scanCode).put("name", e.name).put("target", e.target));
        File f = file(c);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(new JSONObject().put("keys", a).toString(2).getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IllegalStateException("could not save keymap");
    }

    /** Match by key code; for keys Android reports as UNKNOWN, match by hardware scan code. */
    Entry find(int keyCode, int scanCode) {
        for (Entry e : entries) {
            if (e.target.isEmpty()) continue;
            // If an entry records a scan code, require it (e.g. the remote's YouTube key shares
            // KEYCODE_BUTTON_3 with a game controller's C button, but not its scan code).
            if (e.scanCode > 0 && e.scanCode != scanCode) continue;
            if (keyCode != KeyEvent.KEYCODE_UNKNOWN && e.keyCode == keyCode) return e;
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN && e.keyCode == KeyEvent.KEYCODE_UNKNOWN && e.scanCode == scanCode) return e;
        }
        return null;
    }

    Entry findExact(int keyCode, int scanCode) {
        for (Entry e : entries) if (e.keyCode == keyCode && (keyCode != KeyEvent.KEYCODE_UNKNOWN || e.scanCode == scanCode)) return e;
        return null;
    }

    /** Voice/search/menu keys: remappable ONLY by an entry that pins the exact hardware scan code (e.g. this remote's mic button). */
    static boolean isSoft(int k) {
        return k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_ASSIST || k == KeyEvent.KEYCODE_VOICE_ASSIST
            || k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SETTINGS || k == KeyEvent.KEYCODE_INFO || k == KeyEvent.KEYCODE_GUIDE;
    }
    /** Home is protected from the on-TV remap screen, but an explicit keymap.json entry may take it (HomeKey decides when). */
    static boolean isHardProtected(int k) { return isProtected(k) && !isSoft(k) && k != KeyEvent.KEYCODE_HOME; }
    static boolean remappableOnTv(int k) { return !isProtected(k) || isSoft(k); }

    /** Keys that are never remapped: navigation, OK, Back, Home, volume, power, numbers, media, menu, search, settings. */
    static boolean isProtected(int k) {
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) return true;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE: case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_SLEEP: case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_TV_POWER: case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_STOP: case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_TV_INPUT: case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_INFO: case KeyEvent.KEYCODE_CAPTIONS:
                return true;
            default:
                return false;
        }
    }
}
