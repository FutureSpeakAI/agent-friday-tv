package org.tvprivacy.adbreak;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Kids mode state: on/off, daily time used, daily limit, and the single grown-up PIN shared
 * with Agent Friday. The PIN is never stored or logged in clear: only a
 * salted PBKDF2 hash is kept. Two hash formats are accepted:
 *  - our own: pin_salt / pin_hash (hex, PBKDF2WithHmacSHA256, 20000 iterations, 256-bit)
 *  - adopted from Friday: pin_v1 = "v1$iterations$saltB64$hashB64" (PBKDF2WithHmacSHA256)
 */
final class Kids {
    static final String PBS_KIDS = "org.pbskids.video";
    /** Grown-up unlock lasts until locked, screen off, or this long at most. */
    static final long GROWN_UP_MAX_MS = 4L * 60 * 60 * 1000;
    private final SharedPreferences sp;

    Kids(Context c) { sp = c.getSharedPreferences("kids", Context.MODE_PRIVATE); }

    boolean active() { return sp.getBoolean("active", false); }
    void setActive(boolean on) { sp.edit().putBoolean("active", on).commit(); }

    /** Daily limit in minutes; 0 = no limit. Default 120. */
    int limitMin() { return sp.getInt("limit_min", 120); }
    void setLimitMin(int m) { sp.edit().putInt("limit_min", m).apply(); }

    private static String today() { return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()); }

    long usedMs() {
        if (!today().equals(sp.getString("used_day", ""))) return 0;
        return sp.getLong("used_ms", 0);
    }
    void addUsed(long ms) {
        String t = today();
        long cur = t.equals(sp.getString("used_day", "")) ? sp.getLong("used_ms", 0) : 0;
        sp.edit().putString("used_day", t).putLong("used_ms", cur + ms).apply();
    }
    /** Today's extensions (ms), granted only by a grown-up's PIN on the time's-up screen. */
    long bonusMs() { return today().equals(sp.getString("bonus_day", "")) ? sp.getLong("bonus_ms", 0) : 0; }
    long remainingMs() { return KidsClock.remainingMs(limitMin(), bonusMs(), usedMs()); }
    boolean timeUp() { return remainingMs() == 0; }

    // ---- kids slice 1: counting rule, schedule, lockout (all changed only behind the PIN) ----
    /** K-D4: "Count everything" (Friday in front counts too). Default off. */
    boolean countEverything() { return sp.getBoolean("count_all", false); }
    void setCountEverything(boolean on) { sp.edit().putBoolean("count_all", on).apply(); }

    KidsSchedule.Times times(boolean weekend) {
        String p = weekend ? "we_" : "wd_";
        return new KidsSchedule.Times(sp.getInt(p + "wind", -1), sp.getInt(p + "bed", -1), sp.getInt(p + "morning", -1));
    }
    /** which = "wind" | "bed" | "morning"; minutes after midnight or -1 to clear. */
    void setTime(boolean weekend, String which, int minutes) {
        if (!which.equals("wind") && !which.equals("bed") && !which.equals("morning")) return;
        sp.edit().putInt((weekend ? "we_" : "wd_") + which, minutes < 0 ? -1 : Math.min(minutes, 24 * 60 - 1)).apply();
    }
    KidsSchedule schedule() { return new KidsSchedule(times(false), times(true), java.time.ZoneId.systemDefault()); }

    long extendUntil() { return sp.getLong("extend_until", 0); }
    long idleSince() { return sp.getLong("idle_since", -1); }
    void setIdleSince(long ms) { if (sp.getLong("idle_since", -2) != ms) sp.edit().putLong("idle_since", ms).apply(); }

    /** Bedtime lockout in force now (remembered for the night once it starts). */
    boolean lockedOut() {
        long now = System.currentTimeMillis();
        KidsSchedule s = schedule();
        String night = s.nightKey(now);
        boolean remembered = night.equals(sp.getString("locked_night", "")) && now < s.nightEnds(now) && extendUntil() <= now;
        if (remembered) return true;
        if (s.lockNow(now, idleSince(), extendUntil())) { sp.edit().putString("locked_night", night).apply(); return true; }
        return false;
    }

    /** Only from TimeUpActivity, after the PIN (KM-E01). */
    void extendToday(int minutes) {
        if (minutes != 10 && minutes != 20 && minutes != 30) return;
        String t = today();
        sp.edit().putString("bonus_day", t).putLong("bonus_ms", bonusMs() + minutes * 60_000L).apply();
    }
    /** Only from BedtimeActivity, after the PIN (KM-E01). */
    void extendBedtime(int minutes) {
        if (minutes != 10 && minutes != 20 && minutes != 30) return;
        long now = System.currentTimeMillis();
        sp.edit().putLong("extend_until", Math.max(now, extendUntil()) + minutes * 60_000L).remove("locked_night").apply();
    }

    String warnedKey() { return sp.getString("warned", ""); }
    void setWarned(String k) { sp.edit().putString("warned", k).apply(); }

    // ---- PIN ----
    boolean hasPin() { return sp.contains("pin_hash") || sp.contains("pin_v1"); }

    /** Setting a new PIN replaces any adopted hash. */
    void setPin(String pin) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        sp.edit().remove("pin_v1")
            .putString("pin_salt", hex(salt)).putString("pin_hash", hex(hash(pin, salt, 20000, 256))).putInt("pin_fails", 0).commit();
    }

    /** One-time migration: take Friday's existing hash, only when we have no PIN. */
    synchronized boolean adoptHash(String v1) {
        if (hasPin() || !validV1(v1)) return false;
        sp.edit().putString("pin_v1", v1).putInt("pin_fails", 0).commit();
        return true;
    }

    static boolean validV1(String s) {
        if (s == null) return false;
        String[] p = s.split("\\$");
        if (p.length != 4 || !"v1".equals(p[0])) return false;
        try {
            int it = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] h = Base64.getDecoder().decode(p[3]);
            return it >= 1000 && it <= 2_000_000 && salt.length >= 8 && h.length >= 16 && h.length <= 64;
        } catch (Exception e) { return false; }
    }

    /** Returns true if correct. After 5 wrong tries, refuses for 60 seconds. Shared by both apps. */
    synchronized boolean checkPin(String pin) throws Exception {
        if (pin == null || !hasPin()) return false;
        long lockUntil = sp.getLong("pin_lock_until", 0);
        if (System.currentTimeMillis() < lockUntil) return false;
        boolean ok = verify(pin);
        if (ok) sp.edit().putInt("pin_fails", 0).apply();
        else {
            int f = sp.getInt("pin_fails", 0) + 1;
            SharedPreferences.Editor e = sp.edit().putInt("pin_fails", f);
            if (f >= 5) e.putLong("pin_lock_until", System.currentTimeMillis() + 60_000).putInt("pin_fails", 0);
            e.apply();
        }
        return ok;
    }
    long lockedForMs() { return Math.max(0, sp.getLong("pin_lock_until", 0) - System.currentTimeMillis()); }

    private boolean verify(String pin) throws Exception {
        String v1 = sp.getString("pin_v1", null);
        if (v1 != null) {
            String[] p = v1.split("\\$");
            int it = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] want = Base64.getDecoder().decode(p[3]);
            return java.security.MessageDigest.isEqual(want, hash(pin, salt, it, want.length * 8));
        }
        byte[] salt = unhex(sp.getString("pin_salt", ""));
        return java.security.MessageDigest.isEqual(hex(hash(pin, salt, 20000, 256)).getBytes(), sp.getString("pin_hash", "").getBytes());
    }

    // ---- grown-up unlock window (wall clock; survives process restarts) ----
    long unlockedUntil() {
        long u = sp.getLong("grownup_until", 0);
        long now = System.currentTimeMillis();
        if (u <= now || u > now + GROWN_UP_MAX_MS) return 0;   // expired, or clock moved backwards
        return u;
    }
    void openGrownUp() {
        sp.edit().putLong("grownup_until", System.currentTimeMillis() + GROWN_UP_MAX_MS)
            .putLong("last_unlock_elapsed", android.os.SystemClock.elapsedRealtime()).apply();
    }
    /** Returns true if a window was open. */
    boolean lockGrownUp() {
        boolean was = unlockedUntil() > 0;
        sp.edit().remove("grownup_until").apply();
        return was;
    }
    /** elapsedRealtime of the last successful PIN unlock (0 if none since boot-ish). */
    long lastUnlockElapsed() { return sp.getLong("last_unlock_elapsed", 0); }

    /** Rescue path (adb only): clears kids mode and the PIN. */
    void reset() { sp.edit().clear().commit(); }

    static byte[] hash(String pin, byte[] salt, int iterations, int bits) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, bits);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    private static byte[] unhex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return b; }
}
