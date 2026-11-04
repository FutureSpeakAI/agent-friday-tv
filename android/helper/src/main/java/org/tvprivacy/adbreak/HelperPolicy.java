package org.tvprivacy.adbreak;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The helper's own refusals for "hands" (slice S1), as plain Java so they can be unit-tested.
 * Defence in depth: these apply even if Agent Friday's gate approved the action.
 * No Android imports on purpose.
 */
final class HelperPolicy {
    private HelperPolicy() {}

    // Fixed refusal reasons. Friday turns each into plain words.
    static final String NOT_INSTALLED = "not_installed";
    static final String NO_LAUNCHER = "no_launcher";
    static final String FORBIDDEN = "forbidden";
    static final String KIDS = "kids";
    static final String BUSY = "busy";

    static final String SELF = "org.tvprivacy.adbreak";
    static final String PBS_KIDS = "org.pbskids.video";
    static final String FRIDAY = "ai.futurespeak.friday.tv";

    /**
     * The one kids list, shared by kids mode (what may stay in front, which remote buttons work)
     * and by launch(). Friday added 2026-11-02 by request; Friday runs its own family mode.
     */
    static final Set<String> KIDS_APPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(PBS_KIDS, FRIDAY)));

    /** A remote-button target ("pkg", "pkg/.Activity" or "pkg/.Activity|extra=x") is allowed in kids mode if its package is on the list. */
    static boolean kidsAllowedTarget(String target) {
        if (target == null || target.isEmpty()) return false;
        String base = target.split("\\|", 2)[0];
        String pkg = base.split("/", 2)[0];
        return KIDS_APPS.contains(pkg);
    }

    /** Settings, installers, permissions, the store, system UI, and the helper itself. */
    static final Set<String> DENY = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.android.tv.settings",
            "com.android.settings",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.android.vending",
            "com.android.systemui",
            SELF)));

    static final long BUSY_WINDOW_MS = 1_000;

    static boolean forbidden(String pkg) {
        if (pkg == null) return false;
        return DENY.contains(pkg) || pkg.endsWith(".packageinstaller") || pkg.equals("packageinstaller");
    }

    /** A plausible Android package name; anything else is treated as not installed. */
    static boolean wellFormed(String pkg) {
        return pkg != null && pkg.length() <= 200 && pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }

    /**
     * Returns null if the launch may go ahead, otherwise one of the fixed reasons.
     * Order: forbidden first (never reveals whether a forbidden app is installed), then
     * not_installed, no_launcher, kids, busy.
     */
    static String refuse(String pkg, boolean installed, boolean hasLauncher, boolean kidsOn, boolean busy) {
        if (forbidden(pkg)) return FORBIDDEN;
        if (!wellFormed(pkg) || !installed) return NOT_INSTALLED;
        if (!hasLauncher) return NO_LAUNCHER;
        if (kidsOn && !KIDS_APPS.contains(pkg)) return KIDS;
        if (busy) return BUSY;
        return null;
    }

    /** A launch is still pending if it was asked for under 1 s ago and its app isn't in front yet. */
    static boolean busy(long now, long lastLaunchAt, String lastLaunchPkg, String foregroundPkg) {
        if (lastLaunchPkg == null || lastLaunchAt <= 0) return false;
        if (now - lastLaunchAt >= BUSY_WINDOW_MS || now < lastLaunchAt) return false;
        return !lastLaunchPkg.equals(foregroundPkg);
    }

    /** status.kids: fail closed. If the helper's service isn't running, report kids mode on. */
    static boolean kidsFlag(boolean serviceRunning, boolean kidsActive) {
        return !serviceRunning || kidsActive;
    }

    /** Receipt ids are logged, never interpreted: keep them short and single-line. */
    static String cleanRef(String ref) {
        if (ref == null) return "-";
        String r = ref.replaceAll("[^A-Za-z0-9._:\\-]", "");
        if (r.isEmpty()) return "-";
        return r.length() > 64 ? r.substring(0, 64) : r;
    }

    /**
     * Throws SecurityException unless the caller is this app or holds the signature permission.
     * (The framework doesn't enforce provider permissions on call(), so the provider asks here.)
     */
    static void enforce(boolean sameUid, boolean holdsPermission) {
        if (!sameUid && !holdsPermission) {
            throw new SecurityException("requires org.tvprivacy.adbreak.permission.GROWN_UP");
        }
    }
}
