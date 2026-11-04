package ai.futurespeak.friday.tv.safety;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

/**
 * One grown-up PIN for the whole TV. Ad Break Guard 0.7+ (same signing key) is the only
 * keeper of the PIN hash and of the shared "grown-up window"; Friday asks it
 * (the helper's grown-up API):
 *
 *   content://org.tvprivacy.adbreak.grownup, permission org.tvprivacy.adbreak.permission.GROWN_UP
 *   every reply: hasPin (boolean), lockedForMs (long, > 0 = wait), unlockedUntil (long wall ms, 0 = locked)
 *   status · checkPin(pin) -> ok · lock -> ok · adoptHash(hash, Friday's "v1$it$salt$hash") -> ok
 *
 * The window is shared (a PIN entered in either app, or leaving kids mode, opens it) and
 * closes on screen off, "lock", or after 4 hours. Friday keeps no timer of its own then.
 * On first use Friday hands over its own PIN hash once (adoptHash) and deletes it either way.
 *
 * Without Ad Break Guard (e.g. the emulator) Friday falls back to its own PIN and a persisted
 * {@link GrownUpSession}. If Ad Break Guard is installed but won't answer (permission not
 * granted: reinstall Friday after Ad Break Guard), Friday fails closed: locked, family mode.
 */
public final class GrownUp {
    public static final String AUTHORITY = "org.tvprivacy.adbreak.grownup";
    public static final String PERMISSION = "org.tvprivacy.adbreak.permission.GROWN_UP";
    private static final Uri URI = Uri.parse("content://" + AUTHORITY);
    private static final String TAG = "FridayTV";

    public static final class Status {
        /** shared = answered by Ad Break Guard; unavailable = installed but not answering (fail closed). */
        public final boolean shared, unavailable, hasPin, unlocked;
        public final long unlockedUntil, lockedForMs;
        /** Kept for the callers' "kids mode" checks; the shared API has no kids flag, so false. */
        public final boolean kids = false;
        Status(boolean shared, boolean unavailable, boolean hasPin, boolean unlocked, long until, long lockedFor) {
            this.shared = shared; this.unavailable = unavailable; this.hasPin = hasPin; this.unlocked = unlocked;
            this.unlockedUntil = until; this.lockedForMs = lockedFor;
        }
    }

    public interface LocalPin {
        String hash(); void setHash(String h);
        /** Friday's own check with lockouts: null = right, else the message to show. */
        String check(String pin);
    }

    private final Context ctx;
    private final LocalPin local;
    private final GrownUpSession session;

    public GrownUp(Context c, LocalPin local) {
        this.ctx = c.getApplicationContext();
        this.local = local;
        SharedPreferences sp = ctx.getSharedPreferences("grownup", Context.MODE_PRIVATE);
        this.session = new GrownUpSession(new GrownUpSession.Store() {
            public boolean on() { return sp.getBoolean("on", false); }
            public long bootWall() { return sp.getLong("boot", 0); }
            public long capWall() { return sp.getLong("cap", 0); }
            public void put(boolean on, long b, long cap) { sp.edit().putBoolean("on", on).putLong("boot", b).putLong("cap", cap).apply(); }
        });
    }

    /** Ad Break Guard's provider is on the TV (whether or not it lets us in). */
    private boolean sharedInstalled() {
        try {
            ProviderInfo pi = ctx.getPackageManager().resolveContentProvider(AUTHORITY, 0);
            return pi != null;
        } catch (Throwable t) { return false; }
    }

    private Bundle call(String method, Bundle extras) {
        try {
            return ctx.getContentResolver().call(URI, method, null, extras);
        } catch (Throwable t) {
            Log.w(TAG, "grown-up service: " + method + " failed (" + t.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private static Status from(Bundle b) {
        long until = b.getLong("unlockedUntil", 0);
        return new Status(true, false, b.getBoolean("hasPin"), until > System.currentTimeMillis(), until, b.getLong("lockedForMs", 0));
    }

    /** One-time: hand Friday's PIN hash (never the PIN) to Ad Break Guard, then forget it locally. */
    private Bundle migrate(Bundle status) {
        String h = local.hash();
        if (h == null) return status;
        if (!status.getBoolean("hasPin")) {
            Bundle x = new Bundle();
            x.putString("hash", h);
            Bundle r = call("adoptHash", x);
            if (r == null) return status;                     // try again next time
            Log.i(TAG, "grown-up: Friday's PIN moved to the shared PIN: " + r.getBoolean("ok"));
            if (!r.getBoolean("ok")) return r;
            status = r;
        } else {
            Log.i(TAG, "grown-up: the shared PIN already exists; Friday's own PIN is retired");
        }
        local.setHash(null);
        return status;
    }

    public Status status() {
        if (sharedInstalled()) {
            Bundle b = call("status", null);
            if (b == null) return new Status(false, true, true, false, 0, 0);   // fail closed
            return from(migrate(b));
        }
        return new Status(false, false, local.hash() != null,
                session.unlocked(System.currentTimeMillis(), SystemClock.elapsedRealtime()), 0, 0);
    }

    /** Checks a PIN; unlocks on success. Returns null when right, else the message to show. */
    public String verify(String pin) {
        if (sharedInstalled()) {
            Bundle x = new Bundle();
            x.putString("pin", pin);
            Bundle b = call("checkPin", x);
            if (b == null) return "The grown-up check isn't available right now.";
            if (b.getBoolean("ok")) return null;
            long wait = b.getLong("lockedForMs", 0);
            return wait > 0 ? "Too many tries. Wait " + (wait / 1000 + 1) + " seconds." : "Wrong PIN.";
        }
        String err = local.check(pin);
        if (err == null) session.unlock(System.currentTimeMillis(), SystemClock.elapsedRealtime());
        return err;
    }

    /** A first PIN when there is none anywhere. With Ad Break Guard it becomes the shared PIN. */
    public String create(String pin) {
        String h = Pin.hash(pin);
        if (sharedInstalled()) {
            Bundle x = new Bundle();
            x.putString("hash", h);
            Bundle r = call("adoptHash", x);
            if (r == null || !r.getBoolean("ok")) return "Couldn't set the shared PIN. Set it in Ad Break Guard.";
            return verify(pin);
        }
        local.setHash(h);
        session.unlock(System.currentTimeMillis(), SystemClock.elapsedRealtime());
        return null;
    }

    /** Family mode back on, or "Lock now". */
    public void lock() {
        session.lock();
        if (sharedInstalled()) call("lock", null);
    }

    /** The TV went to sleep: Friday's own session (Ad Break Guard watches its own). */
    public void screenOff() { session.lock(); }
}
