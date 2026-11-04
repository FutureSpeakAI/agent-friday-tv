package ai.futurespeak.friday.tv.kids;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import ai.futurespeak.friday.tv.FridayApp;
import ai.futurespeak.friday.tv.actions.Receipts;
import ai.futurespeak.friday.tv.actions.TvActions;
import ai.futurespeak.friday.tv.safety.GrownUp;

/**
 * Friday's side of kids mode's channel to the helper (Ad Break Guard), the same signed
 * provider as the PIN. Friday only reads the helper's clock and schedule and
 * asks it to narrow; it never extends, mints, schedules or leaves kids mode (those are the
 * helper's PIN screens, KM-E01). Every call is off the main thread.
 */
public final class KidsHelper {
    private static final String TAG = "FridayTV";
    private static final Uri URI = Uri.parse("content://" + GrownUp.AUTHORITY);
    /** Opens the helper's grown-up PIN screen for leaving kids mode. */
    public static final String ACTION_GROWN_UP = "org.tvprivacy.adbreak.action.KIDS_GROWN_UP";

    private static volatile KidsEntry entry;

    private KidsHelper() {}

    private static Bundle call(Context c, String method) {
        try { return c.getApplicationContext().getContentResolver().call(URI, method, null, null); }
        catch (IllegalArgumentException e) { return null; }   // a helper that predates the method
        catch (Throwable t) { Log.w(TAG, "kids: helper " + method + " failed (" + t.getClass().getSimpleName() + ")"); return null; }
    }

    /** Kids mode, time left and the schedule: {@code kidsStatus} where the helper has it, else {@code status}. Fail closed. */
    public static KidsStatus status(Context c) {
        Bundle b = call(c, "kidsStatus");
        if (b == null) b = call(c, "status");
        if (b == null) return KidsStatus.FAIL_CLOSED;
        Map<String, Object> m = new HashMap<>();
        for (String k : b.keySet()) m.put(k, b.get(k));
        KidsStatus s = KidsStatus.from(m);
        watch(c, s.kids, "helper");
        return s;
    }

    /** The room facts for the kids prompt block: the reading band, time left and wind-down, as words. */
    public static KidsPrompt.Room room(Context c) {
        KidsStatus s = status(c);
        return new KidsPrompt.Room(new KidsSettings(c).band(), KidsStatus.timeLeftWords(s.remainingMs),
                KidsStatus.windDownWords(System.currentTimeMillis(), s.windDownAt, java.util.TimeZone.getDefault()), s.quiet);
    }

    /** A spoken request that would widen kids mode was answered "That needs a grown-up." (receipted, for the report). */
    public static void refused(Context c, String origin) {
        FridayApp app = (FridayApp) c.getApplicationContext();
        TvActions a = app.actions();
        Receipts r = a == null ? null : a.receipts();
        if (r == null) return;
        try {
            String id = r.decision("kids", new org.json.JSONObject().put("action", "kids.widen").put("origin", origin).put("tier", 0).put("policy", "never"));
            r.outcome(id, "kids", "refused", "needs a grown-up");
        } catch (Exception ignored) { }
    }

    /** "Friday, kids mode": narrowing, so a word may do it (K-D13). False if this helper can't yet. */
    public static boolean enter(Context c) {
        Bundle b = call(c, "kidsEnter");
        boolean ok = b != null && b.getBoolean("ok", false);
        if (ok) watch(c, true, "voice");
        return ok;
    }

    /**
     * Each reading of kids mode goes through one process-wide {@link KidsEntry}: when kids mode
     * turns on, an open grown-up window is closed first, and both are receipted (KM-E03, AC-K3).
     */
    static synchronized void watch(Context c, boolean kids, String origin) {
        FridayApp app = (FridayApp) c.getApplicationContext();
        if (entry == null) {
            entry = new KidsEntry(new KidsEntry.Window() {
                @Override public boolean open() { try { return app.grownUp().status().unlocked; } catch (Throwable t) { return false; } }
                @Override public void lock() { app.grownUp().lock(); }
            }, (what, from) -> receipt(app, what, from));
        }
        if (entry.seen(kids, origin)) Log.i(TAG, "kids: on (" + origin + ")");
    }

    private static void receipt(FridayApp app, String what, String origin) {
        TvActions a = app.actions();
        Receipts r = a == null ? null : a.receipts();
        if (r == null) return;
        try {
            String id = r.decision("kids", new org.json.JSONObject().put("action", "kids.mode").put("origin", origin).put("tier", 0).put("policy", "record"));
            r.outcome(id, "kids", "recorded", what);
        } catch (Exception ignored) { }
    }
}
