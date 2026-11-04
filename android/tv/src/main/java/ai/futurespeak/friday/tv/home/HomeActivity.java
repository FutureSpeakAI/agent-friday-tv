package ai.futurespeak.friday.tv.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.futurespeak.friday.tv.FridayApp;
import ai.futurespeak.friday.tv.MainActivity;
import ai.futurespeak.friday.tv.Prefs;
import ai.futurespeak.friday.tv.actions.ActionGate;
import ai.futurespeak.friday.tv.actions.Catalogue;
import ai.futurespeak.friday.tv.actions.TvActions;
import ai.futurespeak.friday.tv.ambient.Starfield;
import ai.futurespeak.friday.tv.ambient.Weather;
import ai.futurespeak.friday.tv.channels.ChannelSpec;
import ai.futurespeak.friday.tv.channels.ChannelStore;
import ai.futurespeak.friday.tv.ui.Brand;
import ai.futurespeak.friday.tv.web.AvatarView;

/**
 * Friday Home (TV-D1 launcher): desktop Simple mode on the TV. The day board on one side, Friday's
 * live avatar on her stage on the other, with the caption strip under her.
 *
 * Motion is budgeted for the TCL's GPU. Animations are transforms and alpha only (compositor work,
 * no relayout): a 0.35 s brand reveal with a staggered entry, a focus scale with an accent glow
 * (coloured elevation shadow), a slow wallpaper drift and a focus parallax. The avatar runs its own
 * tiered renderer at the size of the stage, pauses whenever Home isn't visible, and remembers its
 * settled tier. "Motion" in Customize turns this down to calm (no drift or parallax) or off.
 *
 * Every app still opens through ActionGate (remote parity, receipts, kids mode). Customization only
 * reorders or hides catalogue apps (HomeLayout); kids mode keeps one fixed layout.
 */
public final class HomeActivity extends Activity {
    private static final String TAG = "FridayHome";
    static final PathInterpolator REVEAL = new PathInterpolator(0.2f, 0.8f, 0.3f, 1f);   // desktop --fr-reveal

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private TvActions actions;
    private Prefs appPrefs;
    private HomePrefs homePrefs;
    HomeLayout layout;
    private boolean kids;

    private FrameLayout root, backdrop, stage;
    private View wall;
    private LinearLayout board, column;
    private AvatarView avatar;
    private TextView clock, date, weather, notice, captionState, captionLine;
    private View talkTile;
    private ValueAnimator drift;
    public static final int HOLD_MS = 600;
    private ScrollView boardScroll;
    private ai.futurespeak.friday.tv.studio.LibraryRow library;

    /** The wallpaper drift waits too while focus moves (the backdrop is the biggest redraw). */
    private final Runnable resumeDrift = () -> { if (drift != null && drift.isPaused() && hasWindowFocus()) drift.resume(); };
    private void holdDrift() {
        if (drift == null || !drift.isStarted()) return;
        drift.pause();
        root.removeCallbacks(resumeDrift);
        root.postDelayed(resumeDrift, HOLD_MS);
    }

    /** Focus in the top half of the board scrolls it right back to the top, so the date and greeting never sit cut off (seen on the TCL). */
    private View focused() { return getCurrentFocus(); }
    private boolean focusedInTopHalf() {
        View f = getCurrentFocus();
        try { android.graphics.Rect r = new android.graphics.Rect(); f.getDrawingRect(r); column.offsetDescendantRectToMyCoords(f, r); return r.top < boardScroll.getHeight() / 2; }
        catch (RuntimeException e) { return true; }
    }

    private void keepHeaderInView(View f) {
        if (boardScroll == null || column == null || f == null) return;
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            f.getDrawingRect(r);
            column.offsetDescendantRectToMyCoords(f, r);
            if (r.top < boardScroll.getHeight() / 2) boardScroll.smoothScrollTo(0, 0);
        } catch (IllegalArgumentException notOnTheBoard) { }
    }
    private CustomizePanel panel;
    private AppMenu menu;
    private boolean firstBuild = true;
    private final Runnable tick = new Runnable() { public void run() { updateClock(); main.postDelayed(this, 20_000); } };
    private final Runnable hideNotice = () -> { if (notice != null) notice.animate().alpha(0f).setDuration(200).withEndAction(() -> notice.setVisibility(View.GONE)).start(); };

    int dp(float v) { return Brand.dp(this, v); }
    int accent() { return layout.accentColor(); }
    HomeLayout.Motion motion() { return kids ? HomeLayout.Motion.CALM : layout.motion; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FridayApp app = (FridayApp) getApplication();
        actions = app.actions();
        appPrefs = app.prefs();
        homePrefs = new HomePrefs(this);
        layout = homePrefs.load();
        kids = actions == null || actions.kids();
        root = new FrameLayout(this);
        root.setBackgroundColor(Brand.SURFACE);
        setContentView(root);
        // Parallax: the backdrop leans gently away from whatever has focus.
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            // Measured on the TCL: focus moves missed 60 fps with the avatar rendering (p50 44 ms), so she holds her last frame while focus moves.
            if (avatar != null && oldF != null) avatar.hold(HOLD_MS);
            if (oldF != null) holdDrift();
            parallax(newF);
            keepHeaderInView(newF);
        });
        assemble();
        arrive(getIntent(), true);
    }

    /** Builds the whole screen for the current layout (on create, and after Customize changes something structural). */
    void assemble() {
        if (avatar != null) { avatar.destroy(); avatar = null; }   // one renderer at a time
        root.removeAllViews();
        if (drift != null) drift.cancel();
        HomeLayout l = kids ? HomeLayout.kidsFixed() : layout;

        // Backdrop: wallpaper (or the ambient starfield, or solid), oversized so it can drift and parallax.
        backdrop = new FrameLayout(this);
        wall = wallpaper(l.wallpaper);
        if (wall != null) backdrop.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        backdrop.setScaleX(1.08f); backdrop.setScaleY(1.08f);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        boolean right = l.side == HomeLayout.Side.RIGHT;
        scrim.setBackground(new GradientDrawable(right ? GradientDrawable.Orientation.LEFT_RIGHT : GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{0xF20A0E1A, 0xB30A0E1A, 0x330A0E1A}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // Board | stage (or stage | board).
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.HORIZONTAL);
        board = new LinearLayout(this);
        board.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        boardScroll = scroll;
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(dp(40), dp(30), dp(24), dp(30));
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));
        board.addView(scroll, new LinearLayout.LayoutParams(-1, -1));

        float share = kids ? 0.30f : l.stage();
        stage = null; avatar = null;
        if (share > 0) {
            stage = buildStage();
            int w = (int) (getResources().getDisplayMetrics().widthPixels * share);
            if (right) { split.addView(board, new LinearLayout.LayoutParams(0, -1, 1)); split.addView(stage, new LinearLayout.LayoutParams(w, -1)); }
            else { split.addView(stage, new LinearLayout.LayoutParams(w, -1)); split.addView(board, new LinearLayout.LayoutParams(0, -1, 1)); }
        } else {
            split.addView(board, new LinearLayout.LayoutParams(0, -1, 1));
        }
        root.addView(split, new FrameLayout.LayoutParams(-1, -1));

        notice = Brand.text(this, "", 13, Brand.TEXT, Brand.body(this));
        notice.setBackground(Brand.pill(this, 0xE60A0E1A, 0x1FFFFFFF));
        notice.setPadding(dp(18), dp(8), dp(18), dp(8));
        notice.setVisibility(View.GONE);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        nl.bottomMargin = dp(22);
        root.addView(notice, nl);

        firstBuild = true;
        build();
        startDrift();
    }

    // ---- The stage: Friday's avatar and the caption strip ------------------------------------------

    private FrameLayout buildStage() {
        FrameLayout s = new FrameLayout(this);
        s.setClipChildren(false);
        avatar = new AvatarView(this);
        avatar.setTierListener(level -> getSharedPreferences("home", MODE_PRIVATE).edit().putInt("tier", level).apply());
        s.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
        // Blend the stage into the board, so Friday's space and the board read as one surface.
        View edge = new View(this);
        boolean right = (kids ? HomeLayout.Side.RIGHT : layout.side) == HomeLayout.Side.RIGHT;
        edge.setBackground(new GradientDrawable(right ? GradientDrawable.Orientation.LEFT_RIGHT : GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{0xE60A0E1A, 0x660A0E1A, 0x000A0E1A}));
        s.addView(edge, new FrameLayout.LayoutParams(dp(110), -1, right ? Gravity.START : Gravity.END));
        int tier = getSharedPreferences("home", MODE_PRIVATE).getInt("tier", -1);
        avatar.start(appPrefs.quality(), motion() == HomeLayout.Motion.OFF, appPrefs.structure(), tier);

        // Caption strip, as on the desktop: glass, an accent hairline on top, state and presence.
        LinearLayout cap = new LinearLayout(this);
        cap.setOrientation(LinearLayout.VERTICAL);
        cap.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xBD0A0E1A);
        g.setCornerRadii(new float[]{0, 0, 0, 0, dp(10), dp(10), dp(10), dp(10)});
        g.setStroke(1, (accent() & 0x00FFFFFF) | 0x33000000);
        cap.setBackground(g);
        cap.setPadding(dp(16), dp(7), dp(16), dp(8));
        captionState = Brand.text(this, "IDLE", 10, accent(), Brand.display(this));
        captionState.setLetterSpacing(0.08f);
        captionLine = Brand.text(this, "Press Talk, or the mic on the remote", 12, Brand.DIM, Brand.body(this));
        cap.addView(captionState);
        cap.addView(captionLine);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cl.bottomMargin = dp(30);
        s.addView(cap, cl);
        return s;
    }

    private void avatarState(String state, String label, String line) {
        if (avatar != null) avatar.setState(state);
        if (captionState != null) captionState.setText(label);
        if (captionLine != null && line != null) captionLine.setText(line);
    }

    // ---- Backdrop and motion ---------------------------------------------------------------------

    private View wallpaper(HomeLayout.Wallpaper w) {
        switch (w) {
            case SOLID: {
                View v = new View(this);
                v.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0A0E1A, 0xFF0D1426, 0xFF0A0E1A}));
                return v;
            }
            case CONSTELLATION: case HORIZON: case MARK: {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                String name = "home/wall-" + w.name().toLowerCase(Locale.ROOT) + ".jpg";
                try (InputStream in = getAssets().open(name)) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory, plenty for a dark wallpaper
                    iv.setImageBitmap(BitmapFactory.decodeStream(in, null, o));
                } catch (Exception e) { Log.w(TAG, "wallpaper " + name + " failed"); }
                return iv;
            }
            default:
                return new Starfield(this, 7);
        }
    }

    private void startDrift() {
        if (motion() != HomeLayout.Motion.FULL || backdrop == null) return;
        backdrop.setLayerType(View.LAYER_TYPE_HARDWARE, null);   // drawn once, then only moved by the compositor
        drift = ValueAnimator.ofFloat(0f, 1f);
        drift.setDuration(48_000);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setRepeatMode(ValueAnimator.REVERSE);
        drift.setInterpolator(new android.view.animation.LinearInterpolator());
        float range = dp(26);
        drift.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            backdrop.setTranslationY((t - 0.5f) * range);
            float s = 1.08f + 0.02f * t;
            backdrop.setScaleX(s); backdrop.setScaleY(s);
        });
        drift.start();
    }

    private void parallax(View focus) {
        if (motion() != HomeLayout.Motion.FULL || focus == null || backdrop == null) return;
        int[] at = new int[2];
        focus.getLocationOnScreen(at);
        float w = root.getWidth() == 0 ? 1 : root.getWidth();
        float cx = (at[0] + focus.getWidth() / 2f) / w;
        backdrop.animate().translationX((0.5f - cx) * dp(30)).setDuration(700).setInterpolator(REVEAL).start();
    }

    /** The desktop's reveal (0.35 s, cubic-bezier(0.2, 0.8, 0.3, 1)), staggered row by row. */
    private void reveal(View v, int index) {
        HomeLayout.Motion m = motion();
        if (m == HomeLayout.Motion.OFF || !firstBuild) return;
        v.setAlpha(0f);
        v.setTranslationY(m == HomeLayout.Motion.FULL ? dp(18) : 0);
        v.animate().alpha(1f).translationY(0).setStartDelay(60L * index + 80).setDuration(350).setInterpolator(REVEAL).start();
    }

    // ---- Lifecycle ---------------------------------------------------------------------------------

    @Override protected void onResume() {
        super.onResume();
        main.post(tick);
        if (boardScroll != null && (focused() == null || focusedInTopHalf())) boardScroll.scrollTo(0, 0);
        if (library != null && !kids) library.refresh();   // Studio hook 7: re-read the Library on every return
        if (avatar != null) { avatar.onResume(); avatar.resumeTimers(); avatarState("idle", "IDLE", "Press Talk, or the mic on the remote"); }
        if (drift != null && drift.isPaused()) drift.resume();
        // Kids mode and the app list can change while away: re-read off the main thread, rebuild only if it changed.
        if (actions != null) bg.execute(() -> {
            try { actions.refresh(); } catch (Throwable ignored) { }
            main.post(() -> {
                boolean k = actions.kids();
                if (k != kids) { kids = k; assemble(); }
                else if (!signature().equals(shown)) build();
            });
        });
        bg.execute(() -> { Weather.Now w = Weather.get(); main.post(() -> showWeather(w)); });
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
        if (actions != null) actions.noteLeftFriday();   // S2: the app chosen from Home is the target for "pause"
        if (avatar != null) avatar.onPause();       // her renderer stops while Home isn't visible
        if (drift != null) drift.pause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (drift != null) drift.cancel();
        if (avatar != null) { avatar.destroy(); avatar = null; }
        bg.shutdownNow();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        arrive(i, false);
        if (panel != null) closePanel();
        if (menu != null) closeMenu();
        if (talkTile != null) talkTile.requestFocus();
    }

    // ---- Arrival timing (L3 boot/wake checks, L6 budgets) ------------------------------------------

    public static final String EXTRA_VIA = "via", EXTRA_T = "t";
    private String arrivalVia;
    private long arrivalT, arrivalSeen;
    private boolean arrivalCold;

    private void arrive(Intent i, boolean cold) {
        long now = SystemClock.uptimeMillis();
        arrivalVia = i == null || i.getStringExtra(EXTRA_VIA) == null ? "launch" : i.getStringExtra(EXTRA_VIA);
        long t = i == null ? 0 : i.getLongExtra(EXTRA_T, 0);
        arrivalT = t > 0 && t <= now && now - t < 60_000 ? t : now;
        arrivalSeen = now;
        arrivalCold = cold;
        View d = getWindow().getDecorView();
        android.view.ViewTreeObserver.OnDrawListener[] once = new android.view.ViewTreeObserver.OnDrawListener[1];
        once[0] = () -> {
            long drawn = SystemClock.uptimeMillis();
            Log.i(TAG, "shown via=" + arrivalVia + " " + (arrivalCold ? "cold" : "warm") + " trigger->drawn=" + (drawn - arrivalT)
                    + "ms here->drawn=" + (drawn - arrivalSeen) + "ms");
            main.post(() -> d.getViewTreeObserver().removeOnDrawListener(once[0]));
        };
        d.getViewTreeObserver().addOnDrawListener(once[0]);
        d.invalidate();
        if (cold) main.post(this::reportFullyDrawn);
    }

    // ---- The board ---------------------------------------------------------------------------------

    private String shown = "";

    private String signature() {
        StringBuilder s = new StringBuilder(kids ? "K" : "F");
        if (actions != null) for (Catalogue.App a : actions.catalogue().apps()) s.append('|').append(a.pkg);
        for (ChannelSpec c : channels()) s.append('#').append(c.id).append(c.title);
        return s.toString();
    }

    void build() {
        shown = signature();
        View focused = getCurrentFocus();
        Object focusTag = focused == null ? null : focused.getTag();
        column.removeAllViews();
        HomeLayout l = kids ? HomeLayout.kidsFixed() : layout;
        List<Catalogue.App> installed = actions == null ? new ArrayList<>() : actions.catalogue().apps();
        HomeModel model = HomeModel.of(installed, channels(), kids, layout);
        int index = 0;

        // Heading, as the desktop's day view: eyebrow date, greeting, the line; clock and weather to the side.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.TOP);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        date = eyebrow("");
        TextView greet = Brand.text(this, greeting(), 26, 0xF2FFFFFF, Brand.display(this));
        greet.setPadding(0, dp(6), 0, dp(4));
        TextView line = Brand.text(this, kids ? "Pick something fun, or talk to Friday." : "Your space. Your shows. Your Friday.", 13, Brand.DIM, Brand.body(this));
        words.addView(date); words.addView(greet); words.addView(line);
        head.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        clock = Brand.text(this, "", 40, 0xF2FFFFFF, Brand.bodyBold(this));
        clock.setGravity(Gravity.END);
        weather = Brand.text(this, "", 12, Brand.DIM, Brand.body(this));
        weather.setGravity(Gravity.END);
        if (l.clock) right.addView(clock);
        if (l.weather) right.addView(weather);
        head.addView(right);
        column.addView(head);
        reveal(head, index++);
        updateClock();
        showWeather(Weather.cached());

        for (String r : l.visibleRows()) {
            View row;
            switch (r) {
                case "hero": row = heroRow(); break;
                case "apps": row = appsRow(model); break;
                case "channels": row = model.channels.isEmpty() || kids ? null : channelsRow(model); break;
                default: row = kids ? null : fridayRow(model); break;
            }
            if (row == null) continue;
            column.addView(row);
            reveal(row, index++);
            // Studio: "Made by Friday" sits right under the hero tiles; GONE when empty. Not in kids mode (fixed layout).
            if ("hero".equals(r) && !kids) {
                library = new ai.futurespeak.friday.tv.studio.LibraryRow(this);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
                ll.topMargin = dp(16);
                library.setPadding(dp(8), 0, 0, 0);
                column.addView(library, ll);
                reveal(library, index++);
            }
        }

        TextView status = Brand.text(this, model.status, 11, Brand.FAINT, Brand.body(this));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = dp(14);
        column.addView(status, sl);
        reveal(status, index);
        firstBuild = false;

        View again = focusTag == null ? null : column.findViewWithTag(focusTag);
        View f = again != null ? again : talkTile;
        if (f != null && panel == null && menu == null) f.requestFocus();
    }

    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
    }

    private TextView eyebrow(String s) {
        TextView t = Brand.text(this, s, 11, accent(), Brand.display(this));
        t.setLetterSpacing(0.13f);
        t.setAllCaps(true);
        return t;
    }

    private LinearLayout section(String label) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        s.setLayoutParams(lp);
        if (label != null) {
            TextView t = eyebrow(label);
            t.setTextSize(10);
            t.setPadding(dp(8), 0, 0, dp(2));
            s.addView(t);
        }
        return s;
    }

    private LinearLayout strip() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setPadding(dp(10), dp(12), dp(10), dp(14));
        return r;
    }

    private View scroller(LinearLayout row) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.setFadingEdgeLength(dp(24));
        h.setHorizontalFadingEdgeEnabled(true);
        h.addView(row);
        return h;
    }

    private View heroRow() {
        LinearLayout s = section(null);
        LinearLayout r = strip();
        talkTile = hero("🎙", "Talk to Friday", "Press OK and ask anything", 1.6f, "talk", this::talk);
        talkTile.setOnFocusChangeListener(focusWithGlow(talkTile, has -> {
            if (has) avatarState("listening", "READY", "Press OK and ask anything");
            else avatarState("idle", "IDLE", "Press Talk, or the mic on the remote");
        }));
        r.addView(talkTile);
        r.addView(hero("✦", "Friday", "Her space, cards and channels", 1.15f, "friday", () -> openFriday(null)));
        for (ai.futurespeak.friday.tv.addon.AddonRegistry.Addon ad : ai.futurespeak.friday.tv.addon.AddonRegistry.shown(this, kids))   // enabled add-ons (docs/ADDON-CONTRACT.md)
            r.addView(hero(ad.spec.icon, ad.spec.title, "Add-on", 1f, ad.spec.id, () -> ai.futurespeak.friday.tv.addon.AddonRegistry.open(this, ad, null, null)));
        s.addView(r, new LinearLayout.LayoutParams(-1, -2));   // the hero tiles share the board's width
        return s;
    }

    private View appsRow(HomeModel model) {
        LinearLayout s = section(kids ? "Kids apps" : "Apps");
        LinearLayout r = strip();
        for (Catalogue.App a : model.apps) r.addView(appTile(a));
        if (model.apps.isEmpty()) r.addView(Brand.text(this, kids ? "No kids apps found on this TV." : "Every app is hidden. Customize Home to show them again.", 13, Brand.DIM, Brand.body(this)));
        s.addView(scroller(r));
        return s;
    }

    private View channelsRow(HomeModel model) {
        LinearLayout s = section("Friday's channels");
        LinearLayout r = strip();
        for (ChannelSpec ch : model.channels) r.addView(chip(ch.emoji, ch.title, "ch:" + ch.id, () -> openFriday(i -> i.putExtra(MainActivity.EXTRA_CHANNEL, ch.id))));
        s.addView(scroller(r));
        return s;
    }

    private View fridayRow(HomeModel model) {
        LinearLayout s = section("Settings");
        LinearLayout r = strip();
        r.addView(chip("✎", "Customize Home", "customize", this::openPanel));
        r.addView(chip("🧾", "What Friday did", "did", () -> openFriday(i -> i.putExtra(MainActivity.EXTRA_SETTINGS, "did"))));
        r.addView(chip("⚙", "Friday settings", "fsettings", () -> openFriday(i -> i.putExtra(MainActivity.EXTRA_SETTINGS, "voice"))));
        if (model.tvSettings) r.addView(chip("🛠", "TV settings", "tvsettings", this::openTvSettings));
        s.addView(scroller(r));
        return s;
    }

    // ---- Tiles -------------------------------------------------------------------------------------

    /** Glass at rest; on focus an accent edge, a lift, and a soft accent glow (a coloured shadow). */
    private Drawable tileBackground(float radiusDp) {
        GradientDrawable rest = new GradientDrawable();
        rest.setColor(0xBD0A0E1A);
        rest.setCornerRadius(dp(radiusDp));
        rest.setStroke(Math.max(1, dp(1)), 0x14FFFFFF);
        GradientDrawable on = new GradientDrawable();
        on.setColor(0xF0101A2E);
        on.setCornerRadius(dp(radiusDp));
        on.setStroke(dp(2), accent());
        StateListDrawable s = new StateListDrawable();
        s.setEnterFadeDuration(140);
        s.setExitFadeDuration(140);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, rest);
        return s;
    }

    interface FocusHook { void changed(boolean has); }

    View.OnFocusChangeListener focusWithGlow(View v, FocusHook hook) {
        return (view, has) -> {
            boolean still = motion() == HomeLayout.Motion.OFF;
            float s = has ? 1.06f : 1f;
            view.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setDuration(still ? 0 : 160).setInterpolator(REVEAL).start();
            if (hook != null) hook.changed(has);
        };
    }

    private View focusable(View v, String tag, float radiusDp, Runnable onClick) {
        v.setTag(tag);
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setClickable(true);
        v.setDefaultFocusHighlightEnabled(false);
        v.setBackground(tileBackground(radiusDp));
        v.setOutlineSpotShadowColor(accent());
        v.setOutlineAmbientShadowColor(accent());
        v.setOnFocusChangeListener(focusWithGlow(v, null));
        v.setOnClickListener(x -> onClick.run());
        return v;
    }

    private View hero(String icon, String title, String hint, float weight, String tag, Runnable onClick) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(20), dp(14), dp(20), dp(14));
        t.addView(Brand.text(this, icon, 22, accent(), Brand.body(this)));
        TextView h = Brand.text(this, title, 16, 0xF2FFFFFF, Brand.display(this));
        h.setPadding(0, dp(4), 0, dp(2));
        t.addView(h);
        t.addView(Brand.text(this, hint, 11, Brand.DIM, Brand.body(this)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(110), weight);
        lp.rightMargin = dp(14);
        t.setLayoutParams(lp);
        return focusable(t, tag, 12, onClick);
    }

    private View chip(String icon, String title, String tag, Runnable onClick) {
        TextView t = Brand.text(this, icon + "  " + title, 13, Brand.TEXT, Brand.bodyBold(this));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        t.setPadding(dp(16), 0, dp(18), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(42));
        lp.rightMargin = dp(12);
        t.setLayoutParams(lp);
        return focusable(t, tag, 10, onClick);
    }

    /** An app: its own TV banner (loaded off the main thread), else its name. Menu (or a long press) for pin, move, hide. */
    private View appTile(Catalogue.App a) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(5), dp(5), dp(5), dp(5));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setClipToOutline(true);
        TextView name = Brand.text(this, a.label, 12, Brand.TEXT, Brand.bodyBold(this));
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        f.addView(name, new FrameLayout.LayoutParams(-1, -1));
        f.addView(img, new FrameLayout.LayoutParams(-1, -1));
        boolean pinned = layout.pins.contains(a.pkg) && !kids;
        if (pinned) {
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(accent());
            dot.setBackground(d);
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(dp(7), dp(7), Gravity.TOP | Gravity.END);
            dl.topMargin = dp(4); dl.rightMargin = dp(4);
            f.addView(dot, dl);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(148), dp(83));
        lp.rightMargin = dp(12);
        f.setLayoutParams(lp);
        f.setContentDescription(a.label);
        bg.execute(() -> {
            Drawable d = art(a.pkg);
            main.post(() -> { if (d != null) { img.setImageDrawable(d); name.setVisibility(View.GONE); } });
        });
        View v = focusable(f, "app:" + a.pkg, 10, () -> open(a));
        if (!kids) v.setOnLongClickListener(x -> { openMenu(a, v); return true; });
        return v;
    }

    private Drawable art(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i != null && i.getComponent() != null) {
                Drawable d = pm.getActivityBanner(i.getComponent());
                if (d != null) return d;
            }
            Drawable d = pm.getApplicationBanner(pkg);
            return d != null ? d : pm.getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Customize and the app menu (overlays inside Home) -----------------------------------------

    void openPanel() {
        if (kids) return;
        panel = new CustomizePanel(this);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        panel.show();
    }

    void closePanel() {
        if (panel == null) return;
        CustomizePanel p = panel;
        panel = null;
        p.hide(() -> root.removeView(p));
        View c = column.findViewWithTag("customize");
        if (c != null) c.requestFocus();
    }

    /**
     * Customize changed something: save it, then redo only what that needs. Only the avatar's
     * on/off and side rebuild the stage (and restart her renderer); the rest is cheap.
     */
    void changed(String what) {
        homePrefs.save(layout);
        switch (what) {
            case "wallpaper":
                backdrop.removeAllViews();
                wall = wallpaper(layout.wallpaper);
                if (wall != null) backdrop.addView(wall, new FrameLayout.LayoutParams(-1, -1));
                break;
            case "size":
                if (stage != null) {
                    ViewGroup.LayoutParams lp = stage.getLayoutParams();
                    lp.width = (int) (getResources().getDisplayMetrics().widthPixels * layout.stage());
                    stage.setLayoutParams(lp);
                }
                break;
            case "motion":
                if (drift != null) { drift.cancel(); drift = null; }
                backdrop.animate().cancel();
                backdrop.setTranslationX(0); backdrop.setTranslationY(0); backdrop.setScaleX(1.08f); backdrop.setScaleY(1.08f);
                startDrift();
                if (avatar != null) avatar.setReduced(motion() == HomeLayout.Motion.OFF);
                break;
            case "structure":
                assemble();
                if (panel != null) { root.addView(panel, new FrameLayout.LayoutParams(-1, -1)); panel.bringToFront(); }
                break;
            default:   // board: accent, clock, weather, rows, apps
                if (captionState != null) captionState.setTextColor(accent());
                build();
                break;
        }
        if (panel != null) panel.refocus();
    }

    void openMenu(Catalogue.App a, View anchor) {
        menu = new AppMenu(this, a, anchor, actions == null ? new ArrayList<>() : HomeModel.of(actions.catalogue().apps(), null, false).apps);
        root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        menu.show();
    }

    void closeMenu() {
        if (menu == null) return;
        AppMenu m = menu;
        menu = null;
        root.removeView(m);
    }

    void closeMenuAndRefocus(String pkg) {
        closeMenu();
        build();
        View t = column.findViewWithTag("app:" + pkg);
        if (t != null) t.requestFocus();
    }

    HomePrefs prefs() { return homePrefs; }

    // ---- Actions -----------------------------------------------------------------------------------

    /** Remote parity: the same gate, checks and receipts as "open X" by voice. */
    private void open(Catalogue.App a) {
        if (actions == null) { show("Friday can't open apps right now."); return; }
        show("Opening " + a.label + "…");
        bg.execute(() -> {
            JSONObject in = new JSONObject();
            try { in.put("app", a.label); } catch (Exception ignored) { }
            ActionGate.Verdict v = actions.fromRemote("app.open", in);
            Log.i(TAG, "open " + a.pkg + " -> " + v.result);
            main.post(() -> { if (!v.leftFriday) show(v.say); else hideNotice.run(); });
        });
    }

    /** Friday reacts first (she perks up and listens), then her full screen opens already listening. */
    private void talk() {
        avatarState("listening", "LISTENING", "Go ahead…");
        if (avatar != null) avatar.setLevel(0.6);
        long wait = motion() == HomeLayout.Motion.OFF ? 0 : 260;
        main.postDelayed(() -> startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_LISTEN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)), wait);
    }

    interface Extra { void put(Intent i); }

    private void openFriday(Extra extra) {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (extra != null) extra.put(i);
        startActivity(i);
    }

    /** A person's own press on the remote (not an agent action); not offered in kids mode. */
    private void openTvSettings() {
        try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { show("The TV's settings didn't open."); }
    }

    private List<ChannelSpec> channels() {
        try {
            List<ChannelSpec> all = new ChannelStore(getFilesDir()).list(), out = new ArrayList<>();
            List<String> pinned = appPrefs.pinnedChannels();
            for (String id : pinned) for (ChannelSpec c : all) if (c.id.equals(id)) out.add(c);
            for (ChannelSpec c : all) if (!out.contains(c)) out.add(c);
            return out;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    void show(String s) {
        notice.setText(s);
        notice.setAlpha(0f);
        notice.setVisibility(View.VISIBLE);
        notice.animate().alpha(1f).setDuration(180).start();
        main.removeCallbacks(hideNotice);
        main.postDelayed(hideNotice, 4500);
    }

    private void updateClock() {
        if (clock == null) return;
        Date now = new Date();
        clock.setText(new SimpleDateFormat("h:mm", Locale.US).format(now));
        if (date != null) date.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now));
    }

    private void showWeather(Weather.Now w) {
        if (weather != null && w != null) weather.setText(w.icon() + "  " + w.line());
    }

    // ---- Keys ---------------------------------------------------------------------------------------

    private final ai.futurespeak.friday.tv.ui.InputLayer input = new ai.futurespeak.friday.tv.ui.InputLayer(this::route);

    @Override public boolean dispatchKeyEvent(KeyEvent e) { return input.feed(e); }

    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (!has) input.reset();
    }

    private boolean route(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (menu != null) return menu.key(e) || super.dispatchKeyEvent(e);
        if (panel != null) return panel.key(e) || super.dispatchKeyEvent(e);
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            // Home is the bottom of the stack: Back goes to the Talk tile, never "out".
            if (down && talkTile != null) talkTile.requestFocus();
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU && !kids) {
            View f = getCurrentFocus();
            if (down && f != null && f.getTag() instanceof String && ((String) f.getTag()).startsWith("app:")) f.performLongClick();
            else if (down) openPanel();
            return true;
        }
        if (k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST || k == KeyEvent.KEYCODE_ASSIST) {
            if (down) talk();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
