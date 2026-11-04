package ai.futurespeak.friday.tv.addon;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import ai.futurespeak.friday.tv.actions.Receipts;
import ai.futurespeak.friday.tv.kids.KidsChannels;

/**
 * The add-ons on this TV (docs/ADDON-CONTRACT.md). An installed add-on does nothing until the
 * owner enables it (the PC setup wizard, or Settings behind the grown-up PIN). Enabling pins the
 * add-on's signing certificate: if a different app later appears under the same package name,
 * it is treated as not enabled. Kids mode shows only add-ons built for kids that a grown-up has
 * also marked OK.
 */
public final class AddonRegistry {
    private AddonRegistry() {}

    public static final class Addon {
        public final AddonSpec spec;
        public final ComponentName entry;
        public final boolean enabled;
        Addon(AddonSpec spec, ComponentName entry, boolean enabled) { this.spec = spec; this.entry = entry; this.enabled = enabled; }
    }

    private static SharedPreferences store(Context c) { return c.getApplicationContext().getSharedPreferences("addons", Context.MODE_PRIVATE); }

    /** Every installed add-on with a valid declaration, enabled or not. */
    public static List<Addon> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Addon> out = new ArrayList<>();
        List<ResolveInfo> found = pm.queryIntentActivities(new Intent(Contract.ACTION_OPEN), PackageManager.GET_META_DATA);
        for (ResolveInfo r : found) {
            ActivityInfo ai = r.activityInfo;
            if (ai == null || !ai.exported) continue;
            AddonSpec spec = declaration(pm, ai);
            if (spec == null) continue;
            boolean dup = false;
            for (Addon a : out) if (a.spec.id.equals(spec.id) || a.spec.packageName.equals(spec.packageName)) dup = true;
            if (dup) continue;   // one entry per add-on; a second claim to the same id is ignored
            out.add(new Addon(spec, new ComponentName(ai.packageName, ai.name), isEnabled(c, ai.packageName)));
        }
        return out;
    }

    /** The add-ons to show now: enabled, and in kids mode only kids add-ons a grown-up marked OK. */
    public static List<Addon> shown(Context c, boolean kids) {
        List<Addon> out = new ArrayList<>();
        KidsChannels marks = new KidsChannels(c);
        for (Addon a : installed(c)) {
            if (!a.enabled) continue;
            if (kids && !(a.spec.kids && marks.ok(a.spec.key()))) continue;
            out.add(a);
        }
        return out;
    }

    private static AddonSpec declaration(PackageManager pm, ActivityInfo ai) {
        try (XmlResourceParser x = ai.loadXmlMetaData(pm, Contract.META_DECLARATION)) {
            if (x == null) return null;
            for (int e = x.getEventType(); e != XmlResourceParser.END_DOCUMENT; e = x.next()) {
                if (e == XmlResourceParser.START_TAG && "friday-addon".equals(x.getName())) {
                    Map<String, String> attrs = new HashMap<>();
                    for (int i = 0; i < x.getAttributeCount() && i < 16; i++) attrs.put(x.getAttributeName(i), x.getAttributeValue(i));
                    return AddonSpec.parse(ai.packageName, attrs);
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ---- enabling ------------------------------------------------------------------------------

    static String signer(Context c, String pkg) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            if (pi.signingInfo == null || pi.signingInfo.hasMultipleSigners()) return null;
            Signature[] s = pi.signingInfo.getSigningCertificateHistory();
            if (s == null || s.length == 0) return null;
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s[s.length - 1].toByteArray());
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** True when this package is enabled and still signed by the certificate it was enabled with. */
    public static boolean isEnabled(Context c, String pkg) {
        if (pkg == null) return false;
        String pinned = store(c).getString(pkg, null);
        return pinned != null && pinned.equals(signer(c, pkg));
    }

    /** Enables an installed add-on (the caller has already checked the grown-up PIN or the adb shell). */
    public static boolean enable(Context c, String pkg, Receipts receipts) {
        Addon found = null;
        for (Addon a : installed(c)) if (a.spec.packageName.equals(pkg)) found = a;
        String cert = signer(c, pkg);
        if (found == null || cert == null) return false;
        store(c).edit().putString(pkg, cert).apply();
        note(receipts, "addon.enable", found.spec, "enabled");
        return true;
    }

    public static void disable(Context c, String pkg, Receipts receipts) {
        store(c).edit().remove(pkg).apply();
        note(receipts, "addon.disable", null, "disabled " + pkg);
    }

    /** The enabled add-on with this package, or null. */
    public static AddonSpec enabledSpec(Context c, String pkg) {
        if (!isEnabled(c, pkg)) return null;
        for (Addon a : installed(c)) if (a.spec.packageName.equals(pkg)) return a.spec;
        return null;
    }

    static void note(Receipts receipts, String action, AddonSpec spec, String detail) {
        if (receipts == null) return;
        try {
            JSONObject rec = new JSONObject().put("action", action).put("origin", spec == null ? "settings" : spec.key()).put("detail", detail);
            String id = receipts.decision("owner", rec);
            receipts.outcome(id, "owner", "executed", detail);
        } catch (Exception ignored) { }
    }

    // ---- opening ---------------------------------------------------------------------------------

    public static boolean open(Context c, Addon a, String utterance, Bundle match) {
        Intent i = new Intent(Contract.ACTION_OPEN).setComponent(a.entry).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (utterance != null) i.putExtra(Contract.EXTRA_UTTERANCE, utterance);
        if (match != null) i.putExtra(Contract.EXTRA_MATCH, match);
        try { c.startActivity(i); return true; } catch (Exception e) { return false; }
    }

    private static final ExecutorService ASK = Executors.newSingleThreadExecutor(r -> new Thread(r, "addon-match"));

    /**
     * A spoken request: offered to each shown add-on whose trigger words it contains; the first
     * that claims it opens. Waits at most 1.2 s per add-on, so a slow add-on can't hang Friday.
     */
    public static boolean routeSpoken(Activity a, String text, boolean kids) {
        for (Addon x : shown(a, kids)) {
            if (!x.spec.mightWant(text)) continue;
            Uri u = Uri.parse("content://" + x.spec.packageName + Contract.ADDON_AUTHORITY_SUFFIX);
            Future<Bundle> f = ASK.submit(() -> a.getContentResolver().call(u, Contract.M_MATCH, text, null));
            Bundle r;
            try { r = f.get(1200, TimeUnit.MILLISECONDS); } catch (Exception e) { f.cancel(true); continue; }
            if (r != null && r.getBoolean(Contract.K_MATCHED)) return open(a, x, text, r.getBundle(Contract.EXTRA_MATCH));
        }
        return false;
    }
}
