package org.tvprivacy.adbreak;

import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marker rules. Every foreground app is watched with the global rules unless excluded;
 * per-app entries add markers and options. Loaded from the tunable override file
 *   /sdcard/Android/data/org.tvprivacy.adbreak/files/rules.json
 * if present and valid, otherwise from bundled assets/rules.json.
 *
 * Strong markers ("Ad 1 of 2", "Skip ad", "Ad · 0:15") trigger on their own while audio plays.
 * Weak markers ("Ad", "Sponsored") also need a full-screen video surface, to avoid show
 * titles, menus and browse rows.
 */
final class Rules {
    static final class Marker {
        final String id; final Pattern text; final Pattern viewId; final boolean strong;
        Marker(String id, Pattern text, Pattern viewId, boolean strong) { this.id = id; this.text = text; this.viewId = viewId; this.strong = strong; }
    }
    static final class AppRule {
        final String pkg, name; final List<Marker> markers; final boolean weakIsStrong, requirePlayback;
        AppRule(String pkg, String name, List<Marker> markers, boolean weakIsStrong, boolean requirePlayback) {
            this.pkg = pkg; this.name = name; this.markers = markers; this.weakIsStrong = weakIsStrong; this.requirePlayback = requirePlayback;
        }
    }
    static final class Match {
        final String ruleId, marker; final boolean strong; final int countdownSec;
        Match(String ruleId, String marker, boolean strong, int countdownSec) { this.ruleId = ruleId; this.marker = marker; this.strong = strong; this.countdownSec = countdownSec; }
    }
    static final class ScanResult {
        Match match; boolean fullscreenVideo; String surfaceClass = "";
        final Set<String> probeTexts = new LinkedHashSet<>(); final Set<String> probeIds = new LinkedHashSet<>();
    }

    final Map<String, AppRule> apps = new LinkedHashMap<>();
    final List<Marker> global = new ArrayList<>();
    final Set<String> exclude = new LinkedHashSet<>();
    final List<String> excludePrefix = new ArrayList<>();
    boolean watchAllApps = true;
    Pattern countdown, countdownViewId, surfaceClass;
    double fullscreenFraction = 0.8;
    int exitScans = 2;
    long exitMs = 1500, maxAdMs = 360_000;
    int maxNodes = 3000;
    String source = "assets";

    // Probe mode (tuning only): ad-like short labels and ad-like view ids, never other text.
    private static final Pattern PROBE_TEXT = Pattern.compile("(?i)\\b(ads?|advert\\w*|sponsor\\w*|skip|commercial)\\b");
    private static final Pattern PROBE_ID = Pattern.compile("(?i)((^|[/_.:])ads?([_.:]|$)|advert|sponsor|skip|commercial|countdown)");

    static Rules load(Context c) {
        try {
            File f = new File(c.getExternalFilesDir(null), "rules.json");
            if (f.isFile()) {
                try (InputStream in = new FileInputStream(f)) {
                    Rules r = parse(read(in)); r.source = "override"; return r;
                } catch (Throwable t) {
                    Log.w(AdGuardService.TAG, "rules override invalid (" + t.getClass().getSimpleName() + "); using bundled rules");
                }
            }
        } catch (Throwable ignored) { }
        try (InputStream in = c.getAssets().open("rules.json")) {
            return parse(read(in));
        } catch (Throwable t) {
            throw new IllegalStateException("bundled rules.json unreadable", t);
        }
    }

    private static String read(InputStream in) throws Exception {
        byte[] buf = new byte[8192]; int n;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Pattern pat(JSONObject o, String key) {
        String s = o.optString(key, "");
        return s.isEmpty() ? null : Pattern.compile(s);
    }

    private static List<Marker> markers(JSONArray a) {
        List<Marker> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            if (m == null || m.optBoolean("disabled", false)) continue;
            out.add(new Marker(m.optString("id", "rule" + i), pat(m, "text"), pat(m, "viewId"),
                    !"weak".equals(m.optString("strength", "strong"))));
        }
        return out;
    }

    static Rules parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        Rules r = new Rules();
        r.watchAllApps = root.optBoolean("watchAllApps", true);
        r.global.addAll(markers(root.optJSONArray("global")));
        r.countdown = pat(root, "countdown");
        r.countdownViewId = pat(root, "countdownViewId");
        r.surfaceClass = pat(root, "surfaceClass");
        r.fullscreenFraction = root.optDouble("fullscreenFraction", r.fullscreenFraction);
        r.exitScans = root.optInt("exitScans", r.exitScans);
        r.exitMs = root.optLong("exitMs", r.exitMs);
        r.maxAdMs = root.optLong("maxAdSeconds", r.maxAdMs / 1000) * 1000;
        r.maxNodes = root.optInt("maxNodes", r.maxNodes);
        JSONArray ex = root.optJSONArray("exclude");
        if (ex != null) for (int i = 0; i < ex.length(); i++) r.exclude.add(ex.getString(i));
        JSONArray exp = root.optJSONArray("excludePrefix");
        if (exp != null) for (int i = 0; i < exp.length(); i++) r.excludePrefix.add(exp.getString(i));
        JSONObject apps = root.optJSONObject("apps");
        if (apps != null) {
            for (java.util.Iterator<String> it = apps.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONObject a = apps.getJSONObject(pkg);
                List<Marker> ms = markers(a.optJSONArray("markers"));
                if (a.optBoolean("useGlobal", true)) ms.addAll(r.global);
                r.apps.put(pkg, new AppRule(pkg, a.optString("name", pkg), ms,
                        a.optBoolean("weakIsStrong", false), a.optBoolean("requirePlayback", true)));
            }
        }
        return r;
    }

    boolean isExcluded(String pkg) {
        if (pkg == null || exclude.contains(pkg)) return true;
        for (String p : excludePrefix) if (pkg.startsWith(p)) return true;
        return false;
    }

    /** Rule for a package: its tuned entry, else the generic rule set, else null (not watched). */
    AppRule ruleFor(String pkg) {
        if (pkg == null) return null;
        AppRule a = apps.get(pkg);
        if (a != null) return a;
        if (!watchAllApps || isExcluded(pkg)) return null;
        AppRule g = new AppRule(pkg, pkg, global, false, true);
        apps.put(pkg, g); // cache
        return g;
    }

    /** Breadth-first walk of visible nodes. */
    ScanResult scan(AccessibilityNodeInfo root, AppRule app, boolean probe) {
        ScanResult res = new ScanResult();
        if (root == null) return res;
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        long screenArea = Math.max(1L, (long) screen.width() * screen.height());
        Rect b = new Rect();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0, cd = -1;
        Match strongHit = null, weakHit = null;
        while (!q.isEmpty() && seen < maxNodes) {
            AccessibilityNodeInfo n = q.poll();
            seen++;
            if (n == null) continue;
            if (n.isVisibleToUser()) {
                CharSequence cls = n.getClassName();
                if (!res.fullscreenVideo && cls != null && surfaceClass != null && surfaceClass.matcher(cls).find()) {
                    n.getBoundsInScreen(b);
                    if ((long) b.width() * b.height() >= fullscreenFraction * screenArea) {
                        res.fullscreenVideo = true;
                        res.surfaceClass = cls.toString();
                    }
                }
                String text = n.getText() == null ? null : n.getText().toString().trim();
                String desc = n.getContentDescription() == null ? null : n.getContentDescription().toString().trim();
                String vid = n.getViewIdResourceName();
                if (strongHit == null) {
                    for (Marker m : app.markers) {
                        if (!m.strong && weakHit != null) continue;
                        String found = null;
                        if (m.text != null) {
                            found = find(m.text, text);
                            if (found == null) found = find(m.text, desc);
                        }
                        if (found == null && m.viewId != null && vid != null && m.viewId.matcher(vid).find()) {
                            found = "#" + vid.substring(vid.lastIndexOf('/') + 1);
                        }
                        if (found != null) {
                            int c = countdownFrom(text);
                            if (c < 0) c = countdownFrom(desc);
                            if (c >= 0) cd = c;
                            Match mm = new Match(m.id, clip(found), m.strong, -1);
                            if (m.strong) { strongHit = mm; break; } else weakHit = mm;
                        }
                    }
                }
                if (cd < 0 && countdownViewId != null && vid != null && countdownViewId.matcher(vid).find()) {
                    int c = countdownFrom(text);
                    if (c >= 0) cd = c;
                }
                if (probe) {
                    if (text != null && text.length() <= 30 && PROBE_TEXT.matcher(text).find()) res.probeTexts.add(clip(text));
                    if (desc != null && desc.length() <= 30 && PROBE_TEXT.matcher(desc).find()) res.probeTexts.add("desc:" + clip(desc));
                    if (vid != null && PROBE_ID.matcher(vid).find()) res.probeIds.add(vid.substring(vid.lastIndexOf('/') + 1));
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) q.add(ch);
            }
        }
        Match hit = strongHit != null ? strongHit : weakHit;
        if (hit != null) res.match = new Match(hit.ruleId, hit.marker, hit.strong, cd);
        return res;
    }

    private static String find(Pattern p, String s) {
        if (s == null || s.isEmpty()) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group() : null;   // only the matched marker, never the whole text
    }

    private int countdownFrom(String s) {
        if (countdown == null || s == null) return -1;
        Matcher m = countdown.matcher(s);
        if (!m.find()) return -1;
        try {
            if (m.groupCount() >= 2 && m.group(2) != null) return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
            return Integer.parseInt(m.group(1));
        } catch (Exception e) { return -1; }
    }

    private static String clip(String s) { s = s.replace('\n', ' '); return s.length() > 40 ? s.substring(0, 40) : s; }
}
