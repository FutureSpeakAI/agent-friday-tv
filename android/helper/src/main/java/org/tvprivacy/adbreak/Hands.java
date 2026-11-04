package org.tvprivacy.adbreak;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * Slice S1 "hands" for Agent Friday: launch(pkg) and back. Nothing else.
 * launch only ever starts the package's own launcher intent from PackageManager; it never
 * takes a component, action, data URI or extras from the caller (guard-tested).
 */
final class Hands {
    private Hands() {}

    private static long lastLaunchAt;
    private static String lastLaunchPkg;

    static boolean serviceRunning() { return AdGuardService.instance != null; }

    /** status.kids, fail closed. */
    static boolean kidsNow(Context c) {
        boolean active;
        try { active = new Kids(c).active(); } catch (Throwable t) { active = true; }
        return HelperPolicy.kidsFlag(serviceRunning(), active);
    }

    static synchronized void launch(Context c, String pkg, String ref, Bundle out) {
        ForegroundTracker fg = ForegroundTracker.INSTANCE;
        long seqBefore = fg.seq();
        out.putLong("seqBefore", seqBefore);

        PackageManager pm = c.getPackageManager();
        boolean installed = false;
        Intent start = null;
        if (HelperPolicy.wellFormed(pkg) && !HelperPolicy.forbidden(pkg)) {
            try { pm.getPackageInfo(pkg, 0); installed = true; } catch (PackageManager.NameNotFoundException e) { installed = false; }
            if (installed) {
                start = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (start == null) start = pm.getLaunchIntentForPackage(pkg);
            }
        }
        long now = SystemClock.elapsedRealtime();
        boolean busy = HelperPolicy.busy(now, lastLaunchAt, lastLaunchPkg, fg.pkg());
        String reason = HelperPolicy.refuse(pkg, installed, start != null, kidsNow(c), busy);

        boolean ok = false;
        if (reason == null) {
            start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            AccessibilityService svc = AdGuardService.instance;
            try {
                (svc != null ? svc : c).startActivity(start);
                ok = true;
                lastLaunchAt = now;
                lastLaunchPkg = pkg;
            } catch (Throwable t) {
                reason = HelperPolicy.NO_LAUNCHER;
            }
        }
        out.putBoolean("ok", ok);
        if (!ok) out.putString("reason", reason);
        Events.log(c, "helper: launch " + (pkg == null ? "-" : HelperPolicy.cleanRef(pkg)) + " ref=" + HelperPolicy.cleanRef(ref)
                + " -> " + (ok ? "ok" : reason) + " (seqBefore=" + seqBefore + ")");
    }

    static void back(Context c, String ref, Bundle out) {
        AccessibilityService svc = AdGuardService.instance;
        boolean ok = svc != null && svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        out.putBoolean("ok", ok);
        Events.log(c, "helper: back ref=" + HelperPolicy.cleanRef(ref) + " -> " + (ok ? "ok" : "failed" + (svc == null ? " (service not running)" : "")));
    }
}
