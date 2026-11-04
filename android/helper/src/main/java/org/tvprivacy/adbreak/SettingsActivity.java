package org.tvprivacy.adbreak;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Ad Break Guard settings in the FutureSpeak look: glass cards, remote-friendly rows (label left, value right). */
public class SettingsActivity extends Activity {
    static final String PROJECTIVY_SETTINGS = "com.spocky.projengmenu/com.spocky.projengmenu.ui.sidePanels.settings.SettingsActivity";
    private static final String[] BUTTON_NAMES = {"YouTube", "Netflix", "Prime Video", "Apple TV", "Pluto TV", "TCL tv+", "TCL Home", "Mic", "Other"};

    private Prefs prefs;
    private Kids kids;
    private Learn learn;
    private TextView statusValue;
    private LinearLayout keysBox;
    private View firstRow;

    // ---------- building blocks ----------
    private LinearLayout card(LinearLayout parent, String title, String subtitle) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Fs.glass(this, 22, Fs.EDGE, 1));
        int p = Fs.dp(this, 22);
        c.setPadding(p, p - 4, p, p - 6);
        TextView t = Fs.title(this, title, 17, Fs.CYAN);
        t.setGravity(Gravity.START);
        t.setShadowLayer(Fs.dp(this, 8), 0, 0, 0x6600D4FF);
        c.addView(t);
        if (subtitle != null) {
            TextView s = Fs.body(this, subtitle, 14, Fs.DIM);
            s.setGravity(Gravity.START);
            s.setPadding(0, Fs.dp(this, 4), 0, Fs.dp(this, 8));
            c.addView(s);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, Fs.dp(this, 18));
        parent.addView(c, lp);
        return c;
    }

    /** A focusable row: label on the left, value in cyan on the right. Returns the value view. */
    private TextView row(LinearLayout card, String label, String value, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int ph = Fs.dp(this, 16), pv = Fs.dp(this, 11);
        r.setPadding(ph, pv, ph, pv);
        if (click != null) {
            r.setFocusable(true);
            StateListDrawable st = new StateListDrawable();
            st.addState(new int[]{android.R.attr.state_focused}, Fs.glass(this, 12, Fs.CYAN, 2));
            android.graphics.drawable.GradientDrawable clear = new android.graphics.drawable.GradientDrawable();
            clear.setColor(0x00000000); clear.setCornerRadius(Fs.dp(this, 12));
            st.addState(new int[]{}, clear);
            r.setBackground(st);
            r.setOnClickListener(click);
            if (firstRow == null) firstRow = r;
        }
        TextView l = Fs.body(this, label, 17, Fs.TEXT);
        l.setGravity(Gravity.START);
        TextView v = Fs.body(this, value, 17, click != null ? Fs.CYAN : Fs.DIM);
        v.setGravity(Gravity.END);
        r.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private void note(LinearLayout card, String s) {
        TextView t = Fs.body(this, s, 14, Fs.DIM);
        t.setGravity(Gravity.START);
        t.setPadding(Fs.dp(this, 16), Fs.dp(this, 2), Fs.dp(this, 16), Fs.dp(this, 6));
        card.addView(t);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        kids = new Kids(this);
        learn = new Learn(this);
        Rules rules = Rules.load(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(Fs.backdrop());
        root.addView(new Fs.NodeField(this, 70, 0.80f, 0.99f, 0.25f, false, 5));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, Fs.dp(this, 36), 0, Fs.dp(this, 48));

        // header
        TextView h = Fs.title(this, "AD BREAK GUARD", 34, Fs.CYAN);
        h.setGravity(Gravity.START);
        page.addView(h);
        TextView sub = Fs.body(this, "FutureSpeak  ·  everything stays on this TV  ·  no internet permission", 15, Fs.DIM);
        sub.setGravity(Gravity.START);
        sub.setPadding(0, Fs.dp(this, 8), 0, Fs.dp(this, 10));
        page.addView(sub);
        View line = Fs.gradientLine(this);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(Fs.dp(this, 260), Fs.dp(this, 2));
        ll.setMargins(0, 0, 0, Fs.dp(this, 24));
        page.addView(line, ll);

        // two columns
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = new LinearLayout(this), right = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL); right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lc.setMargins(0, 0, Fs.dp(this, 18), 0);
        cols.addView(left, lc);
        cols.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(cols);

        // ---- left: status, ad detection, learning, kids
        LinearLayout st = card(left, "STATUS", null);
        statusValue = row(st, "Ad Break Guard service", "", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        row(st, "Test the ad screen (5 s)", "Show", v -> {
            AdGuardService s = AdGuardService.instance;
            if (s == null) Toast.makeText(this, "Turn the service on first", Toast.LENGTH_LONG).show(); else s.testPlacard();
        });

        LinearLayout ad = card(left, "AD DETECTION", "Reads on-screen ad labels in every video app. BACK on an ad screen = not an ad, OK = yes, DOWN = pause 30 min.");
        TextView[] ag = new TextView[1];
        ag[0] = row(ad, "Audio guess (ads with no label)", audioLabel(), v -> { prefs.setAudioGuess((prefs.audioGuess() + 1) % 3); ag[0].setText(audioLabel()); });
        TextView[] yt = new TextView[1];
        yt[0] = row(ad, "YouTube opens SmartTube", prefs.redirectYouTube() ? "On" : "Off", v -> { boolean on = !prefs.redirectYouTube(); prefs.setRedirectYouTube(on); yt[0].setText(on ? "On" : "Off"); });
        row(ad, "Reload detection rules", "Reload", v -> { AdGuardService s = AdGuardService.instance; if (s != null) s.reloadRules(); Toast.makeText(this, "Rules reloaded", Toast.LENGTH_SHORT).show(); });

        LinearLayout le = card(left, "LEARNING", "From your BACK / OK presses. Rules that keep getting it wrong stop muting, then switch off for that app.");
        TextView[] stats = new TextView[1];
        stats[0] = row(le, "Confirmed / corrected", learn.confirmed() + " / " + learn.corrected(), null);
        String adj = learn.adjusted();
        if (!adj.isEmpty()) note(le, "Adjusted: " + adj.replace("\n", "  ·  "));
        row(le, "Reset learning", "Reset", v -> { learn.reset(); stats[0].setText("0 / 0"); Toast.makeText(this, "Learning reset", Toast.LENGTH_SHORT).show(); });

        LinearLayout kc = card(left, "KIDS MODE", "TCL Home turns it on. PBS KIDS only. A grown-up PIN turns it off.");
        row(kc, "Daily limit", limitLabel(), null);
        if (kids.hasPin()) row(kc, "Clock, limit and bedtime (PIN)", "Open", v -> startActivity(new Intent(this, KidsSettingsActivity.class)));
        row(kc, kids.hasPin() ? "Change the grown-up PIN" : "Set the grown-up PIN", "Open", v -> startActivity(new Intent(this, PinActivity.class)
                .putExtra(PinActivity.MODE, kids.hasPin() ? PinActivity.MODE_CHANGE : PinActivity.MODE_SET)));
        row(kc, "Used today", (kids.usedMs() / 60_000) + " min", null);

        // ---- right: remote buttons, themes, apps
        LinearLayout rb = card(right, "REMOTE BUTTONS", "Press OK on a button to change what it opens.");
        keysBox = new LinearLayout(this);
        keysBox.setOrientation(LinearLayout.VERTICAL);
        rb.addView(keysBox);
        refreshKeys();
        row(rb, "Learn a remote button", "Learn", v -> learnButton());

        LinearLayout th = card(right, "HOME-SCREEN THEMES", "Pick in Projectivy: wallpaper and tiles are in Pictures / Themes. Focus colour: #00D4FF.");
        String[] themes = {"C  FutureSpeak  (default)", "A  Monogram", "B  Glyph", "D  Winter Lights", "E  Kids Bright", "F  Minimal Line"};
        for (String t : themes) row(th, t, t.startsWith("C") ? "Active" : "", null);
        row(th, "Open Projectivy settings", "Open", v -> {
            try { startActivity(new Intent(Intent.ACTION_MAIN).setComponent(ComponentName.unflattenFromString(PROJECTIVY_SETTINGS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ex) { Toast.makeText(this, "Projectivy not found", Toast.LENGTH_SHORT).show(); }
        });
        note(th, "FutureSpeak screensaver is available as a fallback in the TV's screensaver list.");

        LinearLayout apps = card(right, "APPS", "Every video app is watched. OK cycles: mute + screen, screen only, off.");
        android.content.pm.PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        java.util.TreeMap<String, String> list = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (rules.isExcluded(pkg)) continue;
            list.put(ri.loadLabel(pm).toString() + "\u0000" + pkg, pkg);
        }
        for (java.util.Map.Entry<String, String> e : list.entrySet()) {
            String name = e.getKey().substring(0, e.getKey().indexOf('\u0000'));
            String pkg = e.getValue();
            TextView[] mv = new TextView[1];
            mv[0] = row(apps, name, shortMode(prefs.mode(pkg)), v -> { int m = Prefs.next(prefs.mode(pkg)); prefs.setMode(pkg, m); mv[0].setText(shortMode(m)); });
        }

        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(Fs.dp(this, 1180), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL);
        sv.addView(page);
        root.addView(sv, pl);
        setContentView(root);
        if (firstRow != null) firstRow.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String me = new ComponentName(this, AdGuardService.class).flattenToString();
        boolean enabled = on != null && on.contains(me);
        statusValue.setText(enabled ? "On" : "Off  (turn on)");
        statusValue.setTextColor(enabled ? 0xFF00FF80 : Fs.AMBER);
    }

    private String audioLabel() { int g = prefs.audioGuess(); return g == 2 ? "Act" : g == 1 ? "Suggest only" : "Off"; }
    private String limitLabel() { int m = kids.limitMin(); return m == 0 ? "No limit" : (m % 60 == 0 ? (m / 60) + (m == 60 ? " hour" : " hours") : (m / 60) + " h " + (m % 60) + " min"); }
    private static String shortMode(int m) { return m == Prefs.FULL ? "Mute + screen" : m == Prefs.PLACARD_ONLY ? "Screen only" : "Off"; }

    // ---------- remote buttons ----------
    private void refreshKeys() {
        keysBox.removeAllViews();
        KeyMap km = KeyMap.load(this);
        if (km.entries.isEmpty()) note(keysBox, "No buttons remapped.");
        for (KeyMap.Entry e : km.entries) row(keysBox, e.name, targetLabel(e.target), v -> pickTarget(e.name, e.keyCode, e.scanCode));
    }

    private String targetLabel(String t) {
        String base = t.split("\\|")[0];
        if (KeyMap.KIDS_TARGET.equals(base)) return "Kids mode";
        if (KeyMap.SETTINGS_TARGET.equals(base)) return "Ad Break Guard";
        if (PROJECTIVY_SETTINGS.equals(base)) return "Projectivy settings";
        String pkg = base.contains("/") ? base.substring(0, base.indexOf('/')) : base;
        try {
            String n = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
            return t.contains("listen=true") ? n + " (listen)" : n;
        } catch (Exception ex) { return pkg + " (not installed)"; }
    }

    private void learnButton() {
        AdGuardService s = AdGuardService.instance;
        if (s == null) { Toast.makeText(this, "Turn the service on first", Toast.LENGTH_LONG).show(); return; }
        AlertDialog wait = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Press the remote button now")
                .setMessage("Within 15 seconds, press the button you want to change.")
                .setNegativeButton("Cancel", null).show();
        s.startLearning((keyCode, scanCode) -> {
            wait.dismiss();
            if (!KeyMap.remappableOnTv(keyCode)) { Toast.makeText(this, "That button can't be remapped", Toast.LENGTH_LONG).show(); return; }
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Which button was that?")
                    .setItems(BUTTON_NAMES, (d, which) -> pickTarget(BUTTON_NAMES[which], keyCode, scanCode))
                    .show();
        });
    }

    private void pickTarget(String name, int keyCode, int scanCode) {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        java.util.TreeMap<String, String> apps = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            apps.put(ri.loadLabel(pm).toString(), pkg);
        }
        final java.util.List<String> labels = new java.util.ArrayList<>(), targets = new java.util.ArrayList<>();
        labels.add("Agent Friday, start listening"); targets.add("ai.futurespeak.friday.tv/.MainActivity|listen=true");
        labels.add("Kids mode (PBS KIDS only)"); targets.add(KeyMap.KIDS_TARGET);
        labels.add("Ad Break Guard settings"); targets.add(KeyMap.SETTINGS_TARGET);
        labels.add("Projectivy settings"); targets.add(PROJECTIVY_SETTINGS);
        for (java.util.Map.Entry<String, String> e : apps.entrySet()) { labels.add(e.getKey()); targets.add(e.getValue()); }
        labels.add("Restore the button's normal behaviour"); targets.add("");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(name + " button opens...")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    try {
                        KeyMap km = KeyMap.load(this);
                        KeyMap.Entry e = km.findExact(keyCode, scanCode);
                        String t = targets.get(which);
                        if (t.isEmpty()) { if (e != null) km.entries.remove(e); }
                        else if (e != null) { e.target = t; e.name = name; }
                        else km.entries.add(new KeyMap.Entry(keyCode, scanCode, name, t));
                        km.save(this);
                        AdGuardService s = AdGuardService.instance;
                        if (s != null) s.reloadKeyMap();
                        refreshKeys();
                    } catch (Exception ex) {
                        Toast.makeText(this, "Could not save", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }
}
