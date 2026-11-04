package ai.futurespeak.friday.tv.kids;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.futurespeak.friday.tv.FridayApp;
import ai.futurespeak.friday.tv.MainActivity;
import ai.futurespeak.friday.tv.Prefs;
import ai.futurespeak.friday.tv.actions.ActionGate;
import ai.futurespeak.friday.tv.actions.Catalogue;
import ai.futurespeak.friday.tv.actions.TvActions;
import ai.futurespeak.friday.tv.ambient.Weather;
import ai.futurespeak.friday.tv.audio.FallbackVoice;
import ai.futurespeak.friday.tv.audio.Voice;
import ai.futurespeak.friday.tv.secure.SecureStore;
import ai.futurespeak.friday.tv.ui.Brand;
import ai.futurespeak.friday.tv.ui.InputLayer;
import ai.futurespeak.friday.tv.ui.Motion;

/**
 * The kids home, in Friday (K-D1): big tiles for the remote (Watch, Ask Friday, Story time),
 * time left in words, the wind-down line, the weather, the disclosure, and a dim Grown-up
 * button that opens the helper's PIN screen. The helper shows it when kids mode starts and
 * falls back to its own one-tile screen if Friday doesn't come to the front.
 * Nothing here can widen kids mode: Watch goes through the same gate as voice, and the only
 * way out is the helper's PIN. Back does nothing while kids mode is on.
 */
public final class KidsHomeActivity extends Activity {
    private static final long POLL_MS = 30_000;
    static final String PBS_KIDS = "org.pbskids.video";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "kids-home"); t.setDaemon(true); return t; });
    private final InputLayer input = new InputLayer(this::route);
    private TextView clock, weather, timeLeft, windDown, notice;
    private LinearLayout tiles;
    private KidsStatus status = KidsStatus.FAIL_CLOSED;
    private int warned = -1;
    private long warnedFor = 0;
    private FallbackVoice voice;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(build());
    }

    private int dp(float v) { return Brand.dp(this, v); }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0B1430, 0xFF0A0E1A, 0xFF130F33}));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(34), dp(56), dp(26));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Header: who we are, and the time and weather.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView mark = Brand.wordmark(this, 13);
        brand.addView(mark, new LinearLayout.LayoutParams(-2, -2));
        TextView kids = Brand.text(this, "KIDS TIME", 30, 0xFFFFFFFF, Brand.display(this));
        kids.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(-2, -2);
        kl.topMargin = dp(4);
        brand.addView(kids, kl);
        head.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        clock = Brand.text(this, "", 30, 0xF2FFFFFF, Brand.display(this));
        right.addView(clock);
        weather = Brand.text(this, "", 17, 0xD99FE6FF, Brand.body(this));
        right.addView(weather);
        head.addView(right);
        col.addView(head);

        // The tiles.
        tiles = new LinearLayout(this);
        tiles.setGravity(Gravity.CENTER);
        tiles.setClipChildren(false);
        tiles.setClipToPadding(false);
        tiles.setPadding(dp(12), dp(14), dp(12), dp(14));   // room for the focus lift, so the ring is never cut
        col.setClipChildren(false);
        tiles.addView(tile("📺", "Watch", "PBS KIDS", Brand.CYAN, this::watch), tileParams(false));
        tiles.addView(tile("🎙", "Ask Friday", "Press OK, then talk", Brand.VIOLET, this::ask), tileParams(true));
        tiles.addView(tile("📖", "Story time", "Friday tells one", 0xFFFF4FD8, this::story), tileParams(true));
        for (ai.futurespeak.friday.tv.addon.AddonRegistry.Addon ad : ai.futurespeak.friday.tv.addon.AddonRegistry.shown(this, true))   // kids add-ons a grown-up marked OK
            tiles.addView(tile(ad.spec.icon, ad.spec.title, "", 0xFFFFC94D, () -> ai.futurespeak.friday.tv.addon.AddonRegistry.open(this, ad, null, null)), tileParams(true));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, 0, 1);
        tp.topMargin = dp(18);
        col.addView(tiles, tp);

        // Time left, and the wind-down, in words.
        timeLeft = Brand.text(this, "", 24, 0xFFFFFFFF, Brand.bodyBold(this));
        timeLeft.setGravity(Gravity.CENTER);
        col.addView(timeLeft, new LinearLayout.LayoutParams(-1, -2));
        windDown = Brand.text(this, "", 19, 0xFFB9A8FF, Brand.body(this));
        windDown.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.topMargin = dp(6);
        col.addView(windDown, wl);

        // Bottom: the disclosure and the limits (small), and the dim Grown-up button.
        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.BOTTOM);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(Brand.text(this, KidsRules.DISCLOSURE_FOR_HOME, 13, Brand.DIM, Brand.body(this)));
        TextView cannot = Brand.text(this, KidsRules.homeCannotLine(), 13, Brand.FAINT, Brand.body(this));
        cannot.setPadding(0, dp(4), 0, 0);
        words.addView(cannot);
        foot.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView grownUp = Brand.button(this, "Grown-up", 0x997A8699);
        grownUp.setTextSize(15);
        grownUp.setTextColor(Brand.FAINT);
        grownUp.setOnClickListener(v -> grownUp());
        foot.addView(grownUp);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = dp(18);
        col.addView(foot, fl);

        notice = Brand.text(this, "", 20, 0xFFFFFFFF, Brand.body(this));
        notice.setPadding(dp(24), dp(12), dp(24), dp(12));
        notice.setBackground(Brand.pill(this, 0xEB0A0E1A, 0x8000D4FF));
        notice.setVisibility(View.GONE);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        nl.topMargin = dp(20);
        root.addView(notice, nl);
        return root;
    }

    private LinearLayout.LayoutParams tileParams(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);   // fill the row: a fixed height overflowed it on 1080p
        if (gap) lp.leftMargin = dp(26);
        return lp;
    }

    /** A big tile: glyph, word, a small line under it. Focus: a ring twice the family width and a lift. */
    private View tile(String glyph, String word, String sub, int accent, Runnable action) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setClickable(true);
        t.setDefaultFocusHighlightEnabled(false);
        GradientDrawable rest = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF1A2347, 0xFF121A36});
        rest.setCornerRadius(dp(30));
        rest.setStroke(dp(2), (0x55 << 24) | (accent & 0xFFFFFF));
        GradientDrawable on = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF22305E, 0xFF182248});
        on.setCornerRadius(dp(30));
        on.setStroke(dp(6), accent);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, rest);
        t.setBackground(bg);
        TextView g = Brand.text(this, glyph, 64, 0xFFFFFFFF, Brand.body(this));
        g.setGravity(Gravity.CENTER);
        t.addView(g);
        TextView w = Brand.text(this, word, 34, 0xFFFFFFFF, Brand.bodyBold(this));
        w.setGravity(Gravity.CENTER);
        w.setPadding(0, dp(8), 0, 0);
        t.addView(w);
        TextView s = Brand.text(this, sub, 16, Brand.DIM, Brand.body(this));
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(4), 0, 0);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(s);
        t.setOnClickListener(v -> action.run());
        t.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                .setDuration(Motion.reduced(this) ? 0 : 150).start());
        return t;
    }

    // ------------------------------------------------------------- refreshing -----

    @Override protected void onResume() {
        super.onResume();
        tick.run();
        if (tiles.getChildCount() > 0 && getCurrentFocus() == null) tiles.getChildAt(0).requestFocus();
    }

    @Override protected void onPause() {
        main.removeCallbacks(tick);
        if (voice != null) voice.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (voice != null) voice.release();
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (!has) input.reset();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            SimpleDateFormat f = new SimpleDateFormat("h:mm", Locale.US);
            f.setTimeZone(TimeZone.getDefault());
            clock.setText(f.format(now));
            Weather.Now w = Weather.cached();
            weather.setText(w == null ? "" : w.icon() + "  " + w.temp + "°" + (Weather.place().isEmpty() ? "" : "  " + Weather.place()));
            bg.execute(() -> {
                KidsStatus s = KidsHelper.status(KidsHomeActivity.this);
                main.post(() -> show(s));
            });
            main.removeCallbacks(this);
            main.postDelayed(this, POLL_MS);
        }
    };

    private void show(KidsStatus s) {
        status = s;
        String left = KidsStatus.timeLeftWords(s.remainingMs);
        timeLeft.setText(left == null ? "" : capital(left.startsWith("no ") ? left : left + " of TV left today"));
        long now = System.currentTimeMillis();
        String wd = KidsStatus.windDownWords(now, s.windDownAt, TimeZone.getDefault());
        windDown.setText(wd == null ? "" : wd);
        if (s.windDownAt != warnedFor) { warnedFor = s.windDownAt; warned = -1; }
        int due = KidsStatus.warningDue(now, s.windDownAt, warned);
        if (due >= 0 && hasWindowFocus()) { warned = due; say(KidsStatus.warningLine(due)); }
    }

    private static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    /** Friday's gentle voice for the wind-down lines: her voice, a little slower (K-D7). */
    private void say(String line) {
        if (line == null) return;
        showNotice(line);
        Prefs p = ((FridayApp) getApplication()).prefs();
        if (!p.voiceOn()) return;
        if (voice == null) {
            SecureStore secure = ((FridayApp) getApplication()).secure();
            voice = new FallbackVoice(this, () -> ai.futurespeak.friday.tv.Prefs.elevenVoiceOn() ? secure.get(SecureStore.ELEVENLABS) : null, p.elevenVoice(), p.elevenModel(), new Voice.Events() {
                @Override public void onSpeaking() { }
                @Override public void onQuiet() { }
                @Override public void onVoiceError(String why) { }
            });
        }
        voice.setVolume(p.voiceVolume());
        voice.setSpeed(KidsVoice.speed(p.voiceSpeed(), true));
        voice.stop();
        voice.speak(line, 0);
        voice.endOfReply();
    }

    private final Runnable hideNotice = () -> notice.setVisibility(View.GONE);
    private void showNotice(String s) {
        notice.setText(s);
        notice.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideNotice);
        main.postDelayed(hideNotice, 6000);
    }

    // ---------------------------------------------------------------- tiles -----

    /** Watch: PBS KIDS through the same gate, checks and receipts as asking by voice. */
    private void watch() {
        TvActions a = ((FridayApp) getApplication()).actions();
        if (a == null) { showNotice("I can't open PBS KIDS right now. " + KidsRules.ASK_GROWN_UP); return; }
        String label = null;
        for (Catalogue.App app : a.catalogue().apps()) if (PBS_KIDS.equals(app.pkg)) label = app.label;
        if (label == null) { showNotice("PBS KIDS isn't on this TV. " + KidsRules.ASK_GROWN_UP); return; }
        String l = label;
        showNotice("Opening PBS KIDS…");
        bg.execute(() -> {
            JSONObject in = new JSONObject();
            try { in.put("app", l); } catch (Exception ignored) { }
            ActionGate.Verdict v = a.fromRemote("app.open", in);
            main.post(() -> { if (!v.leftFriday) showNotice(v.say); });
        });
    }

    private void ask() {
        startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_LISTEN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    private void story() {
        // Storyteller slice 1: the Storyteller's own screen; the old ask-Friday path stays as its fallback.
        if (ai.futurespeak.friday.tv.story.Storyteller.get(this) != null) {
            startActivity(new Intent(this, ai.futurespeak.friday.tv.story.StoryActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            return;
        }
        startActivity(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_ASK)
                .putExtra(MainActivity.EXTRA_ASK, "Please tell me a short, gentle story, and let me choose what happens next.")
                .putExtra(MainActivity.EXTRA_ASK_LABEL, "Tell me a story")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    /** The only way out of kids mode: the helper's PIN screen (Friday never takes the PIN for this). */
    private void grownUp() {
        Intent i = new Intent(KidsHelper.ACTION_GROWN_UP).setPackage("org.tvprivacy.adbreak").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(getPackageManager()) != null) { startActivity(i); return; }
        showNotice("A grown-up can leave kids mode from the helper's kids screen.");
    }

    // ------------------------------------------------------------------ keys -----

    @Override public boolean dispatchKeyEvent(KeyEvent e) { return input.feed(e); }

    private boolean route(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // A held arrow moves one tile; repeats stop here.
        if (down && e.getRepeatCount() > 0 && k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            // Back does nothing on the kids home while kids mode is on (as on the helper's screen).
            if (down && e.getRepeatCount() == 0 && !status.kids) finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
