package org.tvprivacy.adbreak;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.util.ArrayList;

/**
 * The single grown-up PIN, shared with Agent Friday. call() only; no data is queryable.
 * Every call requires org.tvprivacy.adbreak.permission.GROWN_UP (signature level), checked
 * here explicitly because the framework does not enforce provider permissions on call().
 * The PIN hash never leaves this app; callers only get yes/no and timestamps.
 */
public class GrownUpProvider extends ContentProvider {
    public static final String AUTHORITY = "org.tvprivacy.adbreak.grownup";
    public static final String PERMISSION = "org.tvprivacy.adbreak.permission.GROWN_UP";

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        HelperPolicy.enforce(Binder.getCallingUid() == Process.myUid(),
                getContext().checkCallingPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED);
        String caller = getCallingPackage();
        if (caller == null) caller = "uid" + Binder.getCallingUid();
        Kids kids = new Kids(getContext());
        Bundle out = new Bundle();
        try {
            switch (method == null ? "" : method) {
                case "status":
                    break;
                case "checkPin":
                case "unlock": {
                    String pin = extras == null ? null : extras.getString("pin");
                    boolean wasLocked = kids.lockedForMs() > 0;
                    boolean ok = !wasLocked && kids.checkPin(pin);
                    if (ok) {
                        kids.openGrownUp();
                        Events.log(getContext(), "grown-up: unlocked by " + caller);
                    } else if (wasLocked) {
                        Events.log(getContext(), "grown-up: refused, locked out (" + caller + ")");
                    } else if (kids.lockedForMs() > 0) {
                        Events.log(getContext(), "grown-up: 5 wrong tries, locked 60 s (" + caller + ")");
                    }
                    out.putBoolean("ok", ok);
                    break;
                }
                case "lock":
                    if (kids.lockGrownUp()) Events.log(getContext(), "grown-up: locked by " + caller);
                    out.putBoolean("ok", true);
                    break;
                case "adoptHash": {
                    String h = extras == null ? null : extras.getString("hash");
                    boolean ok = kids.adoptHash(h);
                    Events.log(getContext(), "adoptHash from " + caller + ": " + (ok ? "adopted" : (kids.hasPin() ? "refused (PIN already set)" : "refused (bad format)")));
                    out.putBoolean("ok", ok);
                    break;
                }
                // ---- slice S1 "hands" ----
                case "foreground": {
                    ForegroundTracker fg = ForegroundTracker.INSTANCE;
                    out.putString("pkg", fg.pkg());
                    out.putLong("seq", fg.seq());
                    out.putLong("changedAt", fg.changedAt());
                    break;
                }
                case "launch": {
                    // Only "pkg" and "ref" are read. Never a component, action, URI or extras.
                    String pkg = extras == null ? null : extras.getString("pkg");
                    String ref = extras == null ? null : extras.getString("ref");
                    Hands.launch(getContext(), pkg, ref, out);
                    break;
                }
                case "back": {
                    String ref = extras == null ? null : extras.getString("ref");
                    Hands.back(getContext(), ref, out);
                    break;
                }
                // ---- kids slice 1: read, and narrow only. Nothing here can extend,
                // mint, schedule, grant or leave kids mode (KM-E01, guard-tested). No extras are read.
                case "kidsStatus": {
                    long now = System.currentTimeMillis();
                    KidsSchedule s = kids.schedule();
                    boolean on = Hands.kidsNow(getContext());
                    out.putLong("remainingMs", kids.remainingMs());
                    out.putLong("windDownAt", s.t0(now));
                    out.putLong("bedtimeAt", s.bedtimeAt(now));
                    out.putLong("morningAt", s.morningAt(now));
                    out.putBoolean("quiet", false);
                    out.putBoolean("lockedOut", on && kids.active() && (kids.lockedOut() || kids.timeUp()));
                    break;
                }
                case "kidsEnter": {
                    out.putBoolean("ok", AdGuardService.kidsEnterFromFriday(getContext()));
                    break;
                }
                case "events": {
                    out.putStringArrayList("lines", new ArrayList<>(Events.tail(getContext(), 50)));
                    break;
                }
                default:
                    throw new IllegalArgumentException("unknown method");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            out.putBoolean("ok", false);
            out.putString("error", e.getClass().getSimpleName());
        }
        out.putBoolean("hasPin", kids.hasPin());
        out.putLong("lockedForMs", kids.lockedForMs());
        out.putLong("unlockedUntil", kids.unlockedUntil());
        out.putBoolean("kids", Hands.kidsNow(getContext()));   // fail closed: true if the service isn't running
        return out;
    }

    // No tabular data.
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
