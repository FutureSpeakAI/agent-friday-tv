package ai.futurespeak.friday.tv.studio;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import ai.futurespeak.friday.tv.FridayApp;
import ai.futurespeak.friday.tv.actions.TvActions;
import ai.futurespeak.friday.tv.ui.Brand;
import ai.futurespeak.friday.tv.ui.InputLayer;

/**
 * Studio's screen (spec §8.3, §8.6, §11), all fixed UI: the length picker, the plan card, the
 * progress strip and the Library list. The model's words appear only as the plan's title and
 * chapter lines, as data. Every choice is a press; focus starts on Not now; Back, Home or no
 * answer is a no. The build keeps going while Friday is open; the screen keeps the TV awake.
 */
public final class StudioActivity extends Activity implements Studio.Screen {
    /** From Friday News: make a podcast from the story the reader holds ({@link StudioSource#hold}). */
    public static final String ACTION_FROM_NEWS = "ai.futurespeak.friday.tv.action.STUDIO_FROM_NEWS";
    public static final String EXTRA_REF = "ref";
    public static final String ACTION_LIBRARY = "ai.futurespeak.friday.tv.action.STUDIO_LIBRARY";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final InputLayer input = new InputLayer(this::route);
    private Studio studio;
    private FrameLayout body;
    private TextView heading, sub;
    private volatile Pending pending;
    private String showing;   // the job whose progress is up, or null
    private TextView progressLine, progressCost, progressTime;
    private View progressBar;
    private long buildStart;

    /** One question on screen, answered on the main thread, waited for on Studio's thread. */
    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        final Object fallback;
        volatile Object answer;
        Pending(Object fallback) { this.fallback = fallback; answer = fallback; }
        void answer(Object a) { answer = a; done.countDown(); }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        studio = Studio.get();
        setContentView(build());
        if (studio == null) { notice("Studio isn't ready yet. Open Friday first."); return; }
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handle(i); }

    private void handle(Intent i) {
        if (i != null && ACTION_FROM_NEWS.equals(i.getAction())) {
            String ref = i.getStringExtra(EXTRA_REF);
            i.setAction(ACTION_LIBRARY);   // a rotation or return doesn't start it twice
            if (ref == null) { library(); return; }
            working("Getting ready", "Reading the story");
            TvActions a = ((FridayApp) getApplication()).actions();
            new Thread(() -> {
                try {
                    // The remote's path: the same gate, receipts and card as a spoken request (remote parity).
                    String say = a.fromRemote("studio.make", new JSONObject().put("kind", StudioRequest.PODCAST).put("source_ref", ref)).say;
                    main.post(() -> { if (showing == null) { library(); notice(say); } });
                } catch (Exception e) {
                    main.post(this::library);
                }
            }, "studio-news").start();
        } else if (showing == null && pending == null) {
            library();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (studio != null) { studio.attach(this); studio.resumeUnfinished(); }
    }

    @Override protected void onPause() {
        super.onPause();
        if (studio != null) studio.detach(this);
        Pending p = pending;
        if (p != null) p.answer(p.fallback);   // leaving the screen is a no
        input.reset();
    }

    // ------------------------------------------------------------------ layout -----

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0B1022, 0xFF0A0E1A, 0xFF120E2C}));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(64), dp(36), dp(64), dp(28));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.addView(Brand.wordmark(this, 15));
        heading = Brand.text(this, "Studio", 34, 0xFFFFFFFF, Brand.display(this));
        head.addView(heading);
        sub = Brand.text(this, "", 17, Brand.DIM, Brand.body(this));
        head.addView(sub);
        col.addView(head);
        body = new FrameLayout(this);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, 0, 1);
        bl.topMargin = dp(14);
        col.addView(body, bl);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private void show(String title, String subtitle, View content) {
        heading.setText(title);
        sub.setText(subtitle == null ? "" : subtitle);
        sub.setVisibility(subtitle == null || subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        body.removeAllViews();
        body.addView(content, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView button(String label, int accent, Runnable r) {
        TextView b = Brand.button(this, label, accent);
        b.setTextSize(19);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private LinearLayout row(View... vs) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setClipChildren(false);
        l.setPadding(dp(8), dp(8), 0, dp(8));   // room for the focus lift, so the ring is never cut
        for (View v : vs) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(16);
            l.addView(v, lp);
        }
        return l;
    }

    private TextView line(String label, String value) {
        TextView t = Brand.text(this, "", 17, Brand.TEXT, Brand.body(this));
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder();
        s.append(label).append("  ");
        s.setSpan(new android.text.style.ForegroundColorSpan(Brand.DIM), 0, label.length(), 0);
        s.append(value);
        t.setText(s);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private void working(String title, String what) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(Brand.text(this, what + "…", 24, 0xFFFFFFFF, Brand.body(this)));
        show(title, null, l);
    }

    private void notice(String s) {
        if (s == null || s.isEmpty()) return;
        sub.setText(s);
        sub.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------- questions (Studio.Screen) -----

    private <T> T ask(T fallback, Supplier<View> ui, String title, String subtitle) {
        Pending p = new Pending(fallback);
        pending = p;
        main.post(() -> show(title, subtitle, ui.get()));
        try { p.done.await(); } catch (InterruptedException e) { return fallback; }
        if (pending == p) pending = null;
        @SuppressWarnings("unchecked") T a = (T) p.answer;
        return a;
    }

    @Override public int pickLength(StudioRequest r, int[] lengths, String builtFrom) {
        return ask(0, () -> {
            LinearLayout l = new LinearLayout(this);
            l.setOrientation(LinearLayout.VERTICAL);
            l.addView(Brand.text(this, lengths.length == 1 ? "There's enough for a short brief." : "How long should the podcast be?", 26, 0xFFFFFFFF, Brand.body(this)));
            if (builtFrom != null && !builtFrom.isEmpty()) {
                TextView b = line("Built from", builtFrom);
                b.setPadding(0, dp(12), 0, 0);
                l.addView(b);
            }
            TextView not = button("Not now", Brand.NEUTRAL, () -> answer(0));
            View[] vs = new View[lengths.length + 1];
            for (int i = 0; i < lengths.length; i++) {
                final int len = lengths[i];
                vs[i] = button(len == 2 ? "2-minute brief" : len + " minutes", Brand.CYAN, () -> answer(len));
            }
            vs[lengths.length] = not;
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.topMargin = dp(24);
            l.addView(row(vs), cl);
            not.post(not::requestFocus);
            return l;
        }, "Make a podcast", "Nothing is made until you press Do it on the next screen.");
    }

    @Override public boolean approve(StudioPort.Prepared p) {
        Boolean yes = ask(Boolean.FALSE, () -> card(p), "Make a podcast?", null);
        return yes;
    }

    private void answer(Object a) {
        Pending p = pending;
        if (p != null) p.answer(a);
    }

    /** The fixed card (spec §8.3): the TVAC lines in the app's own words, then the storyboard as data. */
    private View card(StudioPort.Prepared p) {
        ScrollView sv = new ScrollView(this);
        sv.setDefaultFocusHighlightEnabled(false);   // the tiles show focus, not the scroller
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        StudioRequest r = p.request;
        int n = p.plan.chapters.size();
        if (p.tainted) {
            TextView t = Brand.text(this, "⚠  " + cap(p.taintSource) + " tried to change how this is made. It changed nothing; the plan below is what you asked for.", 17, Brand.WARN, Brand.body(this));
            t.setPadding(0, 0, 0, dp(6));
            l.addView(t);
        }
        l.addView(line("What will happen", (r.lengthMin == 2 ? "Make a 2-minute brief" : "Make a " + r.lengthMin + "-minute podcast") + ": “" + p.plan.title + "” (" + n + " chapters, in " + p.voice + ")."));
        Spent total = p.total();
        StringBuilder cost = new StringBuilder(cap(Spent.words(total.usd())) + ", ready in about " + p.estimate.minutes + " minute" + (p.estimate.minutes == 1 ? "" : "s") + ".");
        Spent.Line claude = total.line("claude"), eleven = total.line("elevenlabs");
        if (claude != null) cost.append(" Claude ").append(Spent.words(claude.usd));
        if (eleven != null) cost.append(", ElevenLabs ").append(Spent.words(eleven.usd));
        cost.append(". Already spent reading and planning: ").append(Spent.words(p.spentBefore.usd()))
            .append(". Studio today so far: ").append(today() < 0.005 ? "nothing yet" : Spent.words(today())).append(".");
        l.addView(line("Cost and time", cost.toString()));
        if (p.builtFrom != null && !p.builtFrom.isEmpty()) l.addView(line("Built from", p.builtFrom));
        StringBuilder from = new StringBuilder();
        for (StudioSource s : p.sources) { if (from.length() > 0) from.append(";  "); from.append(s.title.isEmpty() ? s.outlet : "“" + Storyboard.clip(s.title, 60) + "”, " + s.outlet); }
        l.addView(line(p.sources.size() == 1 ? "Source" : "Sources (" + p.sources.size() + ")", from.toString()));
        l.addView(line("Why", r.sourceRef != null ? "You asked, from the story you were reading in Friday News." : "You asked by voice, for this page."));
        l.addView(line("Can it be undone", "No: the money is spent once it's made. You can remove the piece from the TV."));
        boolean paidVoice = p.voice.startsWith("Friday");
        l.addView(line("What leaves the TV", (p.sources.size() == 1 ? "The source's text" : "The sources' text") + " and your request go to Claude"
                + (paidVoice ? "; Friday's words go to ElevenLabs to be spoken." : ". It is spoken in the TV's own voice, so nothing else leaves.")));
        // The storyboard itself, readable before approving: Up from the buttons scrolls to it.
        TextView planHead = Brand.text(this, "THE PLAN  ·  press ▲ to read it", 14, Brand.DIM, Brand.bodyBold(this));
        planHead.setPadding(0, dp(12), 0, dp(4));
        l.addView(planHead);
        for (int i = 0; i < n; i++) {
            Storyboard.Chapter c = p.plan.chapters.get(i);
            TextView t = Brand.text(this, "", 16, Brand.TEXT, Brand.body(this));
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            sb.append(String.valueOf(i + 1)).append("   ").append(c.title);
            sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(), 0);
            int at = sb.length();
            sb.append("   ").append(c.summary);
            sb.setSpan(new android.text.style.ForegroundColorSpan(Brand.DIM), at, sb.length(), 0);
            t.setText(sb);
            t.setPadding(0, dp(3), 0, dp(3));
            l.addView(t);
        }
        sv.addView(l);
        sv.setFocusable(true);   // focused (Up from the buttons), the arrows scroll the plan

        // The choices stay put below the scroller, so focusing one never scrolls the card's top lines away.
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView not = button("Not now", Brand.NEUTRAL, () -> answer(Boolean.FALSE));
        LinearLayout choices = row(button("Do it", Brand.CYAN, () -> answer(Boolean.TRUE)), not);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.topMargin = dp(8);
        outer.addView(choices, cl);
        not.post(not::requestFocus);
        return outer;
    }

    @Override public boolean reask(Job j, double spentUsd, double newEstimateUsd) {
        Boolean yes = ask(Boolean.FALSE, () -> {
            LinearLayout l = new LinearLayout(this);
            l.setOrientation(LinearLayout.VERTICAL);
            l.addView(line("What's happening", "“" + j.title() + "” is costing more than its estimate."));
            l.addView(line("Spent so far", Spent.words(spentUsd)));
            l.addView(line("New estimate", Spent.words(newEstimateUsd) + " for the whole piece (it was " + Spent.words(j.est().usd()) + ")."));
            l.addView(line("Can it be undone", "No. If you stop, what's made so far isn't kept as a piece."));
            TextView stop = button("Stop", Brand.NEUTRAL, () -> answer(Boolean.FALSE));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.topMargin = dp(18);
            l.addView(row(button("Keep going", Brand.CYAN, () -> answer(Boolean.TRUE)), stop), cl);
            stop.post(stop::requestFocus);
            return l;
        }, "Keep going?", "A surprise check, not a limit: it's more than a quarter over what you approved.");
        return yes;
    }

    // ------------------------------------------------------------------ progress -----

    @Override public void progress(Job j, String words) {
        main.post(() -> {
            if (j == null) { working("Make a podcast", words); return; }
            if (!j.id().equals(showing)) { showing = j.id(); buildStart = System.currentTimeMillis(); progressView(j); }
            updateProgress(j, words);
        });
    }

    private void progressView(Job j) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        progressLine = Brand.text(this, "", 26, 0xFFFFFFFF, Brand.body(this));
        l.addView(progressLine);
        FrameLayout track = new FrameLayout(this);
        track.setBackground(Brand.pill(this, 0x22FFFFFF, 0));
        progressBar = new View(this);
        progressBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Brand.CYAN, Brand.VIOLET}));
        progressBar.setPivotX(0);
        progressBar.setScaleX(0);
        track.addView(progressBar, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, dp(8));
        tl.topMargin = dp(16);
        l.addView(track, tl);
        progressTime = Brand.text(this, "", 18, Brand.DIM, Brand.body(this));
        progressTime.setPadding(0, dp(14), 0, 0);
        l.addView(progressTime);
        progressCost = Brand.text(this, "", 18, Brand.DIM, Brand.body(this));
        l.addView(progressCost);
        TextView stop = button("Stop making it", Brand.NEUTRAL, () -> { studio.stop(j.id()); progressLine.setText("Stopping…"); });
        TextView lib = button("Library", Brand.CYAN, this::library);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = dp(24);
        l.addView(row(stop, lib), bl);
        show("“" + j.title() + "”", "Friday is making it on the TV. You can leave this screen; keep Friday open until it's done.", l);
        stop.post(stop::requestFocus);
    }

    private void updateProgress(Job j, String words) {
        Job now = studio.job(j.id());
        if (now == null) { library(); return; }
        String state = now.state();
        if (!Job.BUILDING.equals(state)) { finished(now); return; }
        int n = now.plan().chapters.size();
        if (!words.isEmpty()) progressLine.setText(words);
        progressBar.setScaleX(Math.min(1f, (now.done() + 0.5f) / Math.max(1, n)));
        long mins = (System.currentTimeMillis() - buildStart) / 60_000;
        progressTime.setText(now.done() + " of " + n + " chapters done" + (mins > 0 ? "  ·  " + mins + " min so far" : ""));
        progressCost.setText("Spent so far " + Spent.words(now.spent().usd()) + " of " + Spent.words(now.est().usd()) + " estimated");
    }

    private void finished(Job j) {
        showing = null;
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        boolean done = Job.DONE.equals(j.state());
        l.addView(Brand.text(this, done ? "Ready: " + Studio.minutes(j.durationMs()) + ", " + j.plan().chapters.size() + " chapters." : j.problem(), 26, 0xFFFFFFFF, Brand.body(this)));
        l.addView(line("It cost", Spent.words(j.spent().usd()) + " (estimated " + Spent.words(j.est().usd()) + ")"));
        for (String s : j.switches()) l.addView(line("Voice", s));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = dp(24);
        TextView lib = button("Library", Brand.NEUTRAL, this::library);
        if (done) {
            TextView play = button("▶  Play", Brand.CYAN, () -> play(j.id()));
            l.addView(row(play, lib), bl);
            play.post(play::requestFocus);   // after the view is attached, so the first OK presses it
        } else {
            l.addView(row(lib), bl);
            lib.post(lib::requestFocus);
        }
        show("“" + j.title() + "”", done ? "In your Library." : "It didn't finish.", l);
    }

    // ------------------------------------------------------------------ library -----

    private void library() {
        showing = null;
        List<Library.Item> items = studio.library().items();
        ScrollView sv = new ScrollView(this);
        sv.setDefaultFocusHighlightEnabled(false);   // the tiles show focus, not the scroller
        sv.setClipToPadding(false);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(24), dp(12), dp(24), dp(12));   // room for a focused tile's lift
        l.setClipToPadding(false);
        View first = null;
        for (Library.Item it : items) {
            View tile = LibraryRow.tile(this, it, () -> open(it));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
            tl.bottomMargin = dp(10);
            l.addView(tile, tl);
            if (first == null) first = tile;
        }
        if (items.isEmpty()) l.addView(Brand.text(this, "Nothing yet. In Friday News, open a story and press Make a podcast, or ask Friday: “make a five-minute podcast from” a web page.", 20, Brand.DIM, Brand.body(this)));
        sv.addView(l);
        show("Library", "Made by Friday, kept on this TV.", sv);
        if (first != null) first.post(first::requestFocus);
    }

    private void open(Library.Item it) {
        Job j = studio.job(it.id);
        if (j == null) { library(); return; }
        if (Job.BUILDING.equals(j.state())) { progress(j, it.milestone); return; }
        if (Job.PAUSED.equals(j.state())) {
            new Thread(() -> {
                double projected = j.spent().usd() / Math.max(1, j.done()) * j.plan().chapters.size();
                if (reask(j, j.spent().usd(), projected)) studio.approveMore(j.id(), projected); else studio.stop(j.id());
                main.post(this::library);
            }, "studio-reask").start();
            return;
        }
        // A finished (or failed) piece: Play, or Remove from TV.
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        if (!it.note.isEmpty()) l.addView(Brand.text(this, it.note, 20, Brand.DIM, Brand.body(this)));
        TextView back = button("Back", Brand.NEUTRAL, this::library);
        TextView remove = button("Remove from TV", Brand.DENY, () -> { studio.remove(it.id); library(); });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = dp(20);
        if (it.playable()) {
            TextView play = button("▶  Play", Brand.CYAN, () -> play(it.id));
            l.addView(row(play, remove, back), bl);
            play.post(play::requestFocus);   // after the view is attached, so the first OK presses it
        } else {
            l.addView(row(remove, back), bl);
            back.post(back::requestFocus);
        }
        show("“" + it.title + "”", it.minutes + "-minute podcast", l);
    }

    private void play(String id) {
        startActivity(new Intent(this, StudioPlayerActivity.class).putExtra(StudioPlayerActivity.EXTRA_JOB, id));
    }

    private double today() {
        TvActions a = ((FridayApp) getApplication()).actions();
        if (a == null) return 0;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0);
        return Studio.todayUsd(a.receipts().recent(500), c.getTimeInMillis());
    }

    static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private int dp(float v) { return Brand.dp(this, v); }

    // ------------------------------------------------------------------ keys -----

    @Override public boolean dispatchKeyEvent(KeyEvent e) { return input.feed(e); }

    private boolean route(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down && e.getRepeatCount() > 0 && k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (!down || e.getRepeatCount() > 0) return true;
            Pending p = pending;
            if (p != null) { p.answer(p.fallback); return true; }      // Back on a question is a no
            if (!"Library".contentEquals(heading.getText()) && showing == null) { library(); return true; }
            finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }

    // ------------------------------------------------------- "open my library" (hook 3) -----

    private static final java.util.regex.Pattern LIBRARY = java.util.regex.Pattern.compile(
            "^(?:hey )?(?:friday[, ]+)?(?:please )?(?:open|show|show me|go to|take me to)?\\s*(?:my|the)?\\s*(?:studio )?(?:library|podcasts|my podcasts)(?: please)?[.!?]?$");

    /** True for "open my library", "my podcasts", "show the library": the person wants the Library, not a reply. */
    static boolean libraryPhrase(String text) {
        return text != null && LIBRARY.matcher(lower(text).trim()).matches();
    }

    /** Opens the Library for a matching phrase. Kids mode has no Library yet (for-child pieces come later), so it stays with Friday. */
    public static boolean openFor(android.content.Context c, String text, boolean kids) {
        if (kids || !libraryPhrase(text) || Studio.get() == null) return false;
        c.startActivity(new Intent(c, StudioActivity.class).setAction(ACTION_LIBRARY));
        return true;
    }
}
