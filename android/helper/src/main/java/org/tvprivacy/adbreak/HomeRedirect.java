package org.tvprivacy.adbreak;

/**
 * Launcher step L2 (0.8.3): when Google's home screen comes to the front (after a restart, or
 * after Back goes past an app's first screen) and Projectivy's service is off, open Friday Home
 * instead. With a loop guard: if Friday Home doesn't come forward, or Google's home keeps coming
 * back, stop redirecting for a while and leave Google's screen showing (never bounce forever).
 * Plain Java so it can be unit-tested; the service supplies times and facts.
 */
final class HomeRedirect {
    static final String GOOGLE_HOME_PKG = "com.google.android.apps.tv.launcherx";
    static final String GOOGLE_HOME_CLS = "com.google.android.apps.tv.launcherx.home.HomeActivity";

    static final long CONFIRM_MS = 3_000;       // Friday Home must be in front within this
    static final long WINDOW_MS = 30_000;       // ... and no more than MAX_IN_WINDOW redirects in this window
    static final int MAX_IN_WINDOW = 3;
    static final long COOLDOWN_MS = 10 * 60_000; // after giving up, leave Google's screen alone this long

    enum Action { NONE, REDIRECT, GIVE_UP }

    private final long[] recent = new long[MAX_IN_WINDOW];
    private int count;
    private long pendingSince = -1;  // a redirect waiting to be confirmed (-1 = none)
    private long cooldownUntil;
    private String lastGiveUpReason = "";

    /** Is this window-state event Google's home screen (not one of its other panels)? */
    static boolean isGoogleHome(String pkg, String cls) {
        return GOOGLE_HOME_PKG.equals(pkg) && (cls == null || cls.isEmpty() || GOOGLE_HOME_CLS.equals(cls));
    }

    /**
     * Google's home is in front. Decide what to do.
     * @param projectivyOn Projectivy's accessibility service is enabled (it handles home itself)
     * @param homeMapped   the keymap has a Home target (Friday Home)
     * @param kidsOn       kids mode is on (kids mode's own bounce handles it)
     */
    synchronized Action onGoogleHome(long now, boolean projectivyOn, boolean homeMapped, boolean kidsOn) {
        if (projectivyOn || !homeMapped || kidsOn) return Action.NONE;
        if (now < cooldownUntil) return Action.NONE;
        // A previous redirect never confirmed: Friday Home failed to start.
        if (pendingSince >= 0 && now - pendingSince >= CONFIRM_MS) return giveUp(now, "Friday Home did not come to the front");
        if (pendingSince >= 0) return Action.NONE;   // still waiting for the last one
        int inWindow = 0;
        for (int i = 0; i < Math.min(count, MAX_IN_WINDOW); i++) if (now - recent[i] < WINDOW_MS) inWindow++;
        if (inWindow >= MAX_IN_WINDOW) return giveUp(now, "Google home came back " + MAX_IN_WINDOW + " times in 30 s");
        recent[count % MAX_IN_WINDOW] = now;
        count++;
        pendingSince = now;
        return Action.REDIRECT;
    }

    /** Friday Home (or Friday) reached the front: the pending redirect worked. */
    synchronized void onFridayFront() { pendingSince = -1; }

    /** Google's home left the front by itself before we acted (someone opened an app): nothing pending. */
    synchronized void cancelPending() { pendingSince = -1; }

    /** startActivity threw: give up straight away. */
    synchronized Action onStartFailed(long now) { return giveUp(now, "could not start Friday Home"); }

    /** Called on a timer after a redirect; returns GIVE_UP if it was never confirmed. */
    synchronized Action checkPending(long now) {
        if (pendingSince >= 0 && now - pendingSince >= CONFIRM_MS) return giveUp(now, "Friday Home did not come to the front");
        return Action.NONE;
    }

    private Action giveUp(long now, String why) {
        pendingSince = -1;
        cooldownUntil = now + COOLDOWN_MS;
        lastGiveUpReason = why;
        return Action.GIVE_UP;
    }

    synchronized String lastGiveUpReason() { return lastGiveUpReason; }
    synchronized boolean coolingDown(long now) { return now < cooldownUntil; }
}
