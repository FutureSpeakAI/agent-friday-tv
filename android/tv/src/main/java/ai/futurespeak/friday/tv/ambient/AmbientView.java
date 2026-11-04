package ai.futurespeak.friday.tv.ambient;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import ai.futurespeak.friday.tv.Prefs;
import ai.futurespeak.friday.tv.cards.PinnedCards;
import ai.futurespeak.friday.tv.channels.ChannelSpec;
import ai.futurespeak.friday.tv.channels.ChannelStore;
import ai.futurespeak.friday.tv.ui.Brand;
import ai.futurespeak.friday.tv.web.SandboxView;

/**
 * Friday's ambient screen, used by the TV screensaver ({@link FridayDream}) and the in-app
 * preview. A still constellation, the time and the local weather, and the pinned cards and
 * chosen channels taking turns. Light on purpose: native text, one still bitmap, at most one
 * channel WebView at a time, and nothing redrawn between changes. Everything moves to a new
 * spot every minute and the stars are redrawn every few minutes, so nothing burns in.
 */
public final class AmbientView extends FrameLayout {
    private static final long SHIFT_MS = 60_000, RESEED_MS = 5 * 60_000;

    private final Prefs prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final Starfield stars;
    private final FrameLayout stage;
    private final LinearLayout group;
    private final LinearLayout clockBlock;
    private final TextView time, date, weather;
    private final FrameLayout panel;
    private final View dim;
    private final List<Object> items = new ArrayList<>();
    private SandboxView live;
    private int index = -1, tick = 0, corner = 0;
    private boolean running = false;

    // News (and any later source) through the AmbientSource hook.
    private AmbientSource news;
    private final AmbientMix mix;
    private final boolean hasPanel;
    private boolean showedNews = false;

    public AmbientView(Context c, Prefs prefs, PinnedCards cards, ChannelStore channels) {
        super(c);
        this.prefs = prefs;
        setBackgroundColor(0xFF010208);
        stars = new Starfield(c, System.currentTimeMillis());
        addView(stars, new LayoutParams(-1, -1));

        mix = new AmbientMix(prefs.ssMix());

        stage = new FrameLayout(c);
        addView(stage, new LayoutParams(-1, -1));
        int layout = prefs.ssLayout();
        group = new LinearLayout(c);
        group.setOrientation(layout == 2 ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER);

        clockBlock = new LinearLayout(c);
        clockBlock.setOrientation(LinearLayout.VERTICAL);
        clockBlock.setGravity(layout == 2 ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        boolean small = layout == 1;
        time = Brand.text(c, "", small ? 40 : 76, 0xF2FFFFFF, Brand.body(c));
        time.setFontVariationSettings("'wght' 250");
        time.setLetterSpacing(-0.01f);
        date = Brand.text(c, "", small ? 12 : 16, 0xB3FFFFFF, Brand.body(c));
        date.setPadding(dp(3), dp(2), 0, 0);
        weather = Brand.text(c, "", small ? 11 : 14, 0xCC9FE6FF, Brand.body(c));
        weather.setPadding(dp(3), dp(8), 0, 0);
        weather.setLineSpacing(dp(4), 1f);
        TextView mark = Brand.wordmark(c, small ? 9 : 10.5f);
        mark.setAlpha(0.55f);
        mark.setPadding(dp(3), dp(16), 0, 0);
        if (prefs.ssClock()) { clockBlock.addView(time); clockBlock.addView(date); }
        if (prefs.ssWeather()) clockBlock.addView(weather);
        clockBlock.addView(mark);

        panel = new FrameLayout(c);

        // build the rotation: pinned cards, then chosen channels
        if (prefs.ssCards()) items.addAll(cards.list());
        List<String> want = prefs.ssChannels();
        for (ChannelSpec ch : channels.list()) if (want.contains(ch.id)) items.add(ch);
        if (prefs.ssNews() && AmbientSources.news != null) {
            try { news = AmbientSources.news.apply(c); } catch (Throwable t) { news = null; }
        }
        hasPanel = !items.isEmpty() || news != null;

        if (layout == 1) {
            // clock in a corner, the content centred
            stage.addView(clockBlock, new LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
            stage.addView(panel, new LayoutParams(dp(560), dp(330), Gravity.CENTER));
        } else {
            group.addView(clockBlock);
            if (hasPanel) {
                LinearLayout.LayoutParams pl = layout == 2 ? new LinearLayout.LayoutParams(dp(520), dp(250)) : new LinearLayout.LayoutParams(dp(480), dp(300));
                if (layout == 2) pl.topMargin = dp(26); else pl.leftMargin = dp(56);
                group.addView(panel, pl);
            }
            stage.addView(group, new LayoutParams(-2, -2, Gravity.CENTER));
        }

        dim = new View(c);
        dim.setBackgroundColor(0xFF000000);
        dim.setAlpha(prefs.ssDim() == 2 ? 0.55f : prefs.ssDim() == 1 ? 0.3f : 0f);
        addView(dim, new LayoutParams(-1, -1));
    }

    private int dp(float v) { return Brand.dp(getContext(), v); }

    public void start() {
        if (running) return;
        running = true;
        updateClock();
        refreshWeather();
        shift();
        h.postDelayed(this::reseed, RESEED_MS);
        next();
    }

    public void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
        dropLive();
        if (news != null) { news.release(); }
    }

    // ---- clock and weather --------------------------------------------------------------
    private void updateClock() {
        if (!running) return;
        Date now = new Date();
        time.setText(new SimpleDateFormat("h:mm", Locale.US).format(now));
        date.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now));
        long ms = 60_000 - (System.currentTimeMillis() % 60_000) + 50;
        h.postDelayed(this::updateClock, ms);
    }

    private void refreshWeather() {
        if (!running || !prefs.ssWeather()) return;
        Weather.Now cached = Weather.cached();
        if (cached != null) weather.setText(cached.icon() + "  " + cached.twoLines());
        new Thread(() -> {
            Weather.Now w = Weather.get();
            if (w != null) h.post(() -> weather.setText(w.icon() + "  " + w.twoLines()));
        }, "ambient-weather").start();
        h.postDelayed(this::refreshWeather, 20 * 60_000);
    }

    // ---- rotation -------------------------------------------------------------------------
    private void next() {
        if (!running) return;
        AmbientMix.Kind k = mix.next(news != null, !items.isEmpty());
        if (k == AmbientMix.Kind.NOTHING) return;
        View v = null;
        if (k == AmbientMix.Kind.NEWS) {
            dropLive();
            panel.removeAllViews();
            try { v = news.next(getContext()); } catch (Throwable t) { v = null; }   // null: nothing ready, use a card
        }
        for (int tries = 0; tries < items.size() && v == null; tries++) {
            index = (index + 1) % items.size();
            Object it = items.get(index);
            dropLive();
            panel.removeAllViews();
            if (it instanceof ChannelSpec) v = channelView((ChannelSpec) it);
            else v = cardView((JSONObject) it);
        }
        if (v == null) { h.postDelayed(this::next, 5000); return; }
        v.setAlpha(0f);
        panel.addView(v, new LayoutParams(-1, -1));
        v.animate().alpha(1f).setDuration(900).start();
        if (prefs.ssLayout() == 0 && group.getChildCount() == 2) {
            // Drifting: swap sides each turn
            View a = group.getChildAt(0);
            group.removeViewAt(0);
            LinearLayout.LayoutParams pl = (LinearLayout.LayoutParams) panel.getLayoutParams();
            pl.leftMargin = a == clockBlock ? dp(56) : 0;
            pl.rightMargin = a == clockBlock ? 0 : dp(56);
            group.addView(a);
            panel.setLayoutParams(pl);
        }
        h.postDelayed(this::next, prefs.ssRotateSec() * 1000L);
    }

    private void dropLive() {
        if (live != null) { panel.removeView(live); live.shutdown(); live = null; }
    }

    private View channelView(ChannelSpec ch) {
        FrameLayout f = new FrameLayout(getContext());
        f.setBackground(panelBg());
        f.setClipToOutline(true);
        live = new SandboxView(getContext(), ch.id, ch.html, ch.grantedHosts);
        live.setFocusable(false);
        f.addView(live, new LayoutParams(-1, -1));
        TextView tag = Brand.text(getContext(), ch.emoji + "  " + ch.title, 10.5f, 0xE6FFFFFF, Brand.bodyBold(getContext()));
        tag.setBackground(Brand.pill(getContext(), 0xB3050812, 0x3300D4FF));
        tag.setPadding(dp(10), dp(4), dp(10), dp(4));
        LayoutParams tl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        tl.setMargins(dp(10), 0, 0, dp(10));
        f.addView(tag, tl);
        live.start();
        return f;
    }

    /** A pinned card drawn natively (text, list, table or chart as text): no WebView. */
    private View cardView(JSONObject card) {
        Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(panelBg());
        box.setPadding(dp(26), dp(22), dp(26), dp(20));
        TextView eyebrow = Brand.text(c, "PINNED", 9, Brand.CYAN, Brand.display(c));
        eyebrow.setLetterSpacing(0.3f);
        box.addView(eyebrow);
        TextView title = Brand.text(c, card.optString("title", ""), 21, 0xFFFFFFFF, Brand.bodyBold(c));
        title.setPadding(0, dp(8), 0, dp(10));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(title);
        String body = cardText(card);
        TextView b = Brand.text(c, body, 14, 0xD9FFFFFF, Brand.body(c));
        b.setLineSpacing(dp(4), 1f);
        b.setMaxLines(9);
        b.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(b);
        return box;
    }

    /** The words of a card, for the screensaver. Package-private for tests. */
    static String cardText(JSONObject card) {
        String type = card.optString("type");
        StringBuilder s = new StringBuilder();
        switch (type) {
            case "list": {
                JSONArray a = card.optJSONArray("items");
                boolean ordered = card.optBoolean("ordered");
                for (int i = 0; a != null && i < Math.min(a.length(), 7); i++)
                    s.append(ordered ? (i + 1) + ".  " : "•  ").append(plain(a.optString(i))).append('\n');
                break;
            }
            case "table": {
                JSONArray rows = card.optJSONArray("rows");
                for (int i = 0; rows != null && i < Math.min(rows.length(), 6); i++) {
                    JSONArray r = rows.optJSONArray(i);
                    if (r == null) continue;
                    List<String> cells = new ArrayList<>();
                    for (int j = 0; j < r.length(); j++) cells.add(plain(r.optString(j)));
                    s.append(String.join("   ·   ", cells)).append('\n');
                }
                break;
            }
            case "chart": {
                JSONArray labels = card.optJSONArray("labels");
                JSONArray series = card.optJSONArray("series");
                JSONObject first = series == null ? null : series.optJSONObject(0);
                JSONArray vals = first == null ? null : first.optJSONArray("values");
                for (int i = 0; labels != null && i < Math.min(labels.length(), 7); i++)
                    s.append(plain(labels.optString(i))).append("   ").append(vals == null ? "" : vals.opt(i)).append('\n');
                break;
            }
            default:
                s.append(plain(card.optString("body", "")));
        }
        return s.toString().trim();
    }

    private static String plain(String s) { return s == null ? "" : s.replace("**", "").replace("__", "").trim(); }

    private GradientDrawable panelBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x99070B18);
        g.setCornerRadius(dp(20));
        g.setStroke(Math.max(1, dp(1)), 0x2600D4FF);
        return g;
    }

    // ---- burn-in protection ----------------------------------------------------------------
    private void shift() {
        if (!running) return;
        tick++;
        float dx = BurnIn.offset(rnd, dp(BurnIn.RANGE_X_DP)), dy = BurnIn.offset(rnd, dp(BurnIn.RANGE_Y_DP));
        View target = prefs.ssLayout() == 1 ? panel : group;
        target.animate().translationX(dx).translationY(dy).setDuration(4000).start();
        if (prefs.ssLayout() == 1) {
            corner = (corner + 1) % 4;
            LayoutParams cl = (LayoutParams) clockBlock.getLayoutParams();
            cl.gravity = BurnIn.CORNERS[corner];
            cl.setMargins(dp(48), dp(40), dp(48), dp(40));
            clockBlock.setLayoutParams(cl);
        }
        h.postDelayed(this::shift, SHIFT_MS);
    }

    private void reseed() {
        if (!running) return;
        stars.reseed(System.currentTimeMillis());
        h.postDelayed(this::reseed, RESEED_MS);
    }

    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
}
