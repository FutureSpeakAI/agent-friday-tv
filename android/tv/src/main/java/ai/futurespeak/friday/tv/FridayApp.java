package ai.futurespeak.friday.tv;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import ai.futurespeak.friday.tv.safety.GrownUp;
import ai.futurespeak.friday.tv.safety.Pin;
import ai.futurespeak.friday.tv.secure.SecureStore;

/** Process-wide singletons. */
public final class FridayApp extends Application {
    private SecureStore secure;
    private Prefs prefs;
    private GrownUp grownUp;

    @Override public void onCreate() {
        super.onCreate();
        secure = new SecureStore(this);
        prefs = new Prefs(this);
        Place.load(prefs);
        prefs.loadProviders();
        grownUp = new GrownUp(this, new GrownUp.LocalPin() {
            public String hash() { return prefs.pinHash(); }
            public void setHash(String h) { prefs.setPinHash(h); }
            public String check(String pin) { return checkLocalPin(pin); }
        });
        // Hand Friday's old PIN hash to Ad Break Guard's shared PIN as soon as the app starts
        // (a provider call: off the main thread), not only when a grown-up setting is first used.
        new Thread(() -> { try { grownUp.status(); } catch (Throwable ignored) { } }, "grownup-migrate").start();
        // The shared Look (the add-on SDK) follows the owner's reduce-motion setting.
        ai.futurespeak.friday.tv.ui.Motion.setting = c -> prefs.motion() == Prefs.Motion.ON ? Boolean.TRUE : prefs.motion() == Prefs.Motion.OFF ? Boolean.FALSE : null;
        // The TV going to sleep ends a grown-up unlock.
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { grownUp.screenOff(); }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    /** Friday's own PIN check with lockouts (used only without the shared PIN service). */
    String checkLocalPin(String pin) {
        long now = System.currentTimeMillis();
        if (now < prefs.pinLockedUntil()) return "Locked. Try again in " + ((prefs.pinLockedUntil() - now) / 1000 + 1) + " seconds.";
        if (Pin.verify(pin, prefs.pinHash())) { prefs.setPinFailures(0); return null; }
        int f = prefs.pinFailures() + 1;
        prefs.setPinFailures(f);
        long lock = Pin.lockoutMs(f);
        if (lock > 0) { prefs.setPinLockedUntil(now + lock); return "Wrong PIN. Locked for " + lock / 1000 + " seconds."; }
        return "Wrong PIN. " + (Pin.MAX_FREE_TRIES - f) + " tries left before a pause.";
    }

    public SecureStore secure() { return secure; }
    public Prefs prefs() { return prefs; }
    public GrownUp grownUp() { return grownUp; }

    private ai.futurespeak.friday.tv.actions.TvActions actions;
    private boolean actionsFailed;

    /**
     * Slice S1: the one gate and the one receipts writer for the whole process (voice, the remote's
     * Apps rail, and the shell test hook all share it, so the chain never forks). Null when the
     * Keystore or storage can't keep receipts: then Friday simply can't act.
     */
    public synchronized ai.futurespeak.friday.tv.actions.TvActions actions() {
        if (actions == null && !actionsFailed) {
            try { actions = new ai.futurespeak.friday.tv.actions.TvActions(this, () -> grownUp.status().unlocked); }
            catch (Throwable t) { actionsFailed = true; android.util.Log.w("FridayTV", "actions unavailable (" + t.getClass().getSimpleName() + ")"); }
        }
        return actions;
    }
}
