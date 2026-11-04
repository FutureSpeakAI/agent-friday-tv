package org.tvprivacy.adbreak;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * "foreground" for Agent Friday (tier 0: look). Fed with TYPE_WINDOW_STATE_CHANGED events;
 * keeps the last application package, a change counter and the time of the last change.
 * Skips the keyboard (IME), system UI, toasts/popups and the helper's own overlays.
 * Plain Java (no Android imports) so the sequence rules can be unit-tested.
 */
final class ForegroundTracker {
    static final ForegroundTracker INSTANCE = new ForegroundTracker();

    private final Set<String> ignorePkgs = new HashSet<>(Arrays.asList(
            "com.android.systemui", "android",
            "com.google.android.inputmethod.latin", "com.google.android.leanbackime",
            "com.android.inputmethod.latin", "com.google.android.tvrecommendations"));

    private String pkg = "";
    private long seq = 0;
    private long changedAt = 0;

    /** Adds the system's keyboard packages (read at service start). */
    synchronized void ignore(String p) { if (p != null && !p.isEmpty()) ignorePkgs.add(p); }

    /**
     * @param cls the event's class name (window title class); toasts and popups report
     *            android.widget.* / android.view.* classes and are not app changes.
     * @return true if this event changed the foreground app (seq incremented).
     */
    synchronized boolean onWindowState(String p, String cls, long nowElapsed) {
        if (p == null || p.isEmpty() || ignorePkgs.contains(p)) return false;
        String c = cls == null ? "" : cls;
        if (c.startsWith("android.widget.") || c.startsWith("android.view.")) return false;
        // The helper's own overlays aren't apps; its own screens (kids home, PIN, time up, settings) are.
        if (HelperPolicy.SELF.equals(p) && !c.startsWith(HelperPolicy.SELF + ".")) return false;
        if (p.equals(pkg)) return false;
        pkg = p;
        seq++;
        changedAt = nowElapsed;
        return true;
    }

    synchronized String pkg() { return pkg; }
    synchronized long seq() { return seq; }
    synchronized long changedAt() { return changedAt; }
}
