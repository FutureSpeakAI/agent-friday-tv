package ai.futurespeak.friday.tv.story;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import ai.futurespeak.friday.tv.FridayApp;
import ai.futurespeak.friday.tv.audio.OfflineSpeech;
import ai.futurespeak.friday.tv.audio.RecognizerSpeechIn;
import ai.futurespeak.friday.tv.audio.SpeechIn;
import ai.futurespeak.friday.tv.kids.KidsHelper;
import ai.futurespeak.friday.tv.kids.KidsRules;
import ai.futurespeak.friday.tv.kids.KidsSettings;
import ai.futurespeak.friday.tv.kids.KidsStatus;
import ai.futurespeak.friday.tv.studio.Spent;
import ai.futurespeak.friday.tv.ui.Brand;
import ai.futurespeak.friday.tv.ui.InputLayer;
import ai.futurespeak.friday.tv.ui.Motion;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The Storyteller's fixed screens (spec §4, §5, §11): the start (an idea by tile or by voice, a
 * bedtime story, the books on the shelf, the grown-up's story night), the plan card where one
 * is due, and the story itself: the picture (or the shadow page while it's coming), the words
 * lighting up as they are read, two choices on the arrows, the silly-sound row on Up, the
 * story so far on Down, and Back to stop and keep. Replaying a book makes no calls at all.
 * Every control works with the arrows and OK; voice is a bonus.
 */
public final class StoryActivity extends Activity {
    public static final String EXTRA_TOPIC = "topic", EXTRA_BEDTIME = "bedtime", EXTRA_BOOK = "book";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final InputLayer input = new InputLayer(this::route);
    private Storyteller teller;
    private StoryEngine engine;
    private Mixer mixer;
    private FrameLayout root;
    private boolean kids, bedtime, older;
    private KidsStatus status = KidsStatus.FAIL_CLOSED;
    private SpeechIn speech;
    private Runnable onHeard;

    // the story screen
    private ImageView picture;
    private ShadowView shadow;
    private View dimmer;
    private TextView words, title, strip, cost;
    private LinearLayout choices, silly, bottom;
    private StoryPage page;
    private int pageIndex = -1, pagesTotal = Bedtime.NORMAL_PAGES;
    private List<WordTiming.Word> timings = new ArrayList<>();
    private String pageText = "";
    private boolean spoken, replaying, storyOn;
    private Book replayBook;
    private List<Book.Made> replayPages;
    private long pictureAskedAt;
    private final Runnable shadowCheck = new Runnable() {
        @Override public void run() {
            if (!storyOn || picture == null) return;
            if (pictureAskedAt > 0 && picture.getDrawable() == null && Prefetch.pictureState(System.currentTimeMillis() - pictureAskedAt, false, false) == Prefetch.PictureState.SHADOW) {
                shadow.setComing(true);
                shadow.setVisibility(View.VISIBLE);
                strip.setText("The picture's coming…");
            }
            main.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        teller = Storyteller.get(this);
        root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0B1430, 0xFF0A0E1A, 0xFF130F33}));
        setContentView(root);
        if (teller == null) { root.addView(message("The Storyteller can't start on this TV right now.")); return; }
        engine = teller.engine();
        mixer = new Mixer();
        readRoom();
        String topic = getIntent().getStringExtra(EXTRA_TOPIC);
        String bookId = getIntent().getStringExtra(EXTRA_BOOK);
        if (getIntent().getBooleanExtra(EXTRA_BEDTIME, false)) bedtime = true;
        if (bookId != null) replay(bookId);
        else if (topic != null && !topic.trim().isEmpty()) begin(topic.trim());
        else showStart();
    }

    private void readRoom() {
        status = KidsHelper.status(this);
        kids = status.kids;
        KidsRules.Band band = new KidsSettings(this).band();
        older = band == KidsRules.Band.OLDER;
        long now = System.currentTimeMillis();
        if (Bedtime.windingDown(now, status.windDownAt)) bedtime = true;   // ST-D8
    }

    private int dp(float v) { return Brand.dp(this, v); }

    // ================================================================= the start screen =====

    private void showStart() {
        storyOn = false;
        root.removeAllViews();
        readRoom();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(30), dp(56), dp(20));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(Brand.wordmark(this, 13));
        TextView h = Brand.text(this, bedtime ? "BEDTIME STORY" : "STORY TIME", 30, 0xFFFFFFFF, Brand.display(this));
        h.setLetterSpacing(0.14f);
        brand.addView(h);
        head.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        StoryGate.Room room = teller.room(kids);
        StoryGate.Mode mode = StoryGate.allowed(room);
        TextView modeLine = Brand.text(this, mode == null ? "Friday needs her Claude key to tell stories."
                : mode == StoryGate.Mode.FULL ? "Pictures, voices, sounds and music: on" : (room.kids ? "Words and Friday's voice. " + "A grown-up can switch on pictures, voices and sounds." : "Words and Friday's voice (pictures need the ElevenLabs and kie.ai keys)"),
                16, Brand.DIM, Brand.body(this));
        modeLine.setGravity(Gravity.END);
        head.addView(modeLine, new LinearLayout.LayoutParams(dp(560), -2));
        col.addView(head);

        TextView ask = Brand.text(this, "What should the story be about?", 26, 0xFFFFFFFF, Brand.bodyBold(this));
        ask.setPadding(0, dp(26), 0, dp(12));
        col.addView(ask);
        LinearLayout tiles = new LinearLayout(this);
        tiles.setClipChildren(false); tiles.setClipToPadding(false);
        tiles.setPadding(dp(8), dp(8), dp(8), dp(8));
        String[][] ideas = bedtime
                ? new String[][]{{"🐉", "A dragon who's scared of the dark"}, {"🦉", "An owl who can't sleep"}, {"⭐", "A star that lost its twinkle"}}
                : new String[][]{{"🐉", "A dragon who's scared of the dark"}, {"🐢", "A turtle who wants to fly"}, {"🤖", "A robot who learns to laugh"}};
        for (String[] i : ideas) tiles.addView(tile(i[0], i[1], "", Brand.CYAN, () -> begin(i[1])), tileParams(tiles.getChildCount() > 0));
        tiles.addView(tile("🎙", "Say an idea", "Hold OK and talk", Brand.VIOLET, null), tileParams(true));
        View say = tiles.getChildAt(tiles.getChildCount() - 1);
        say.setOnKeyListener((v, k, e) -> holdToTalk(k, e, text -> begin(text)));
        if (!bedtime) tiles.addView(tile("🌙", "Bedtime story", "Calm and sleepy", 0xFF8A7CFF, () -> { bedtime = true; showStart(); }), tileParams(true));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(200));
        col.addView(tiles, tp);

        // the shelf
        List<Shelf.Item> books = teller.shelf().items();
        if (!books.isEmpty()) {
            TextView mine = Brand.text(this, "My stories", 22, 0xFFFFFFFF, Brand.bodyBold(this));
            mine.setPadding(0, dp(18), 0, dp(8));
            col.addView(mine);
            LinearLayout row = new LinearLayout(this);
            row.setClipChildren(false); row.setClipToPadding(false);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            int n = 0;
            for (Shelf.Item it : books) {
                if (n++ >= 5) break;
                String sub = it.pages + (it.pages == 1 ? " page" : " pages") + (it.fresh ? " · new" : "") + (Book.STOPPED.equals(it.state) ? " · unfinished" : "");
                row.addView(tile(it.bedtime ? "🌙" : "📖", it.title, sub, 0xFFFF4FD8, () -> replay(it.id)), tileParams(row.getChildCount() > 0));
            }
            col.addView(row, new LinearLayout.LayoutParams(-1, dp(150)));
        }

        // the grown-up line
        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        long now = System.currentTimeMillis();
        TextView night = Brand.text(this, NightGrant.words(teller.night().until(), now) + (kids ? "" : " · " + (room.grownUpOpen ? "grown-up window open" : "family mode")), 15, Brand.DIM, Brand.body(this));
        foot.addView(night, new LinearLayout.LayoutParams(0, -2, 1));
        TextView grown = Brand.button(this, "Grown-up", Brand.NEUTRAL);
        grown.setOnClickListener(v -> showNight());
        foot.addView(grown);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = dp(14);
        col.addView(foot, fl);
        TextView disclosure = Brand.text(this, KidsRules.DISCLOSURE_FOR_HOME, 13, Brand.FAINT, Brand.body(this));
        col.addView(disclosure);
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        main.post(() -> { if (tiles.getChildCount() > 0) tiles.getChildAt(0).requestFocus(); });
    }

    private LinearLayout.LayoutParams tileParams(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (gap) lp.leftMargin = dp(20);
        return lp;
    }

    private View tile(String glyph, String word, String sub, int accent, Runnable action) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true); t.setFocusableInTouchMode(true); t.setClickable(true);
        t.setDefaultFocusHighlightEnabled(false);
        GradientDrawable rest = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF1A2347, 0xFF121A36});
        rest.setCornerRadius(dp(26)); rest.setStroke(dp(2), (0x55 << 24) | (accent & 0xFFFFFF));
        GradientDrawable on = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF22305E, 0xFF182248});
        on.setCornerRadius(dp(26)); on.setStroke(dp(6), accent);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, rest);
        t.setBackground(bg);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView g = Brand.text(this, glyph, 44, 0xFFFFFFFF, Brand.body(this));
        g.setGravity(Gravity.CENTER);
        t.addView(g);
        TextView w = Brand.text(this, word, 20, 0xFFFFFFFF, Brand.bodyBold(this));
        w.setGravity(Gravity.CENTER); w.setMaxLines(2);
        t.addView(w);
        if (sub != null && !sub.isEmpty()) {
            TextView s = Brand.text(this, sub, 14, Brand.DIM, Brand.body(this));
            s.setGravity(Gravity.CENTER); s.setSingleLine(true);
            t.addView(s);
        }
        if (action != null) t.setOnClickListener(v -> action.run());
        t.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(Motion.reduced(this) ? 0 : 150).start());
        return t;
    }

    private View message(String s) {
        TextView t = Brand.text(this, s, 24, 0xFFFFFFFF, Brand.body(this));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    // ================================================================= the plan card =====

    private void begin(String topic) {
        if (topic == null || topic.trim().isEmpty()) return;
        String refusal = StoryGate.refuse(topic, kids);
        if (refusal != null) { notice(refusal); return; }
        if (!StorySafety.textOk(topic)) { notice("Let's pick a different story idea."); return; }
        readRoom();
        long now = System.currentTimeMillis();
        if (Bedtime.tooLate(now, status.bedtimeAt)) { notice("It's past bedtime. The TV will tell a story tomorrow."); return; }
        StoryGate.Room room = teller.room(kids);
        StoryEngine.Prepared p = engine.prepare(room, bedtime, now, status.bedtimeAt, teller.fridayVoiceIsEleven());
        if (p == null) { notice("Friday needs her Claude key to tell stories."); return; }
        if (engine.busy()) engine.stop();
        // Kids mode: words and voice just start (ordinary kids talk); with a story night open, the grant was the approval.
        if (kids) { startStory(topic, p); return; }
        showCard(topic, p);
    }

    private void showCard(String topic, StoryEngine.Prepared p) {
        root.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(120), dp(60), dp(120), dp(40));
        col.addView(Brand.wordmark(this, 13));
        TextView h = Brand.text(this, "Tell a story: " + topic, 30, 0xFFFFFFFF, Brand.display(this));
        h.setMaxLines(2);
        col.addView(h);
        String what = p.mode == StoryGate.Mode.FULL ? "About " + p.pages + " pages, with pictures, the cast's voices, sounds and music."
                : "About " + p.pages + " pages, in words and Friday's voice.";
        col.addView(line(what));
        col.addView(line(p.mode == StoryGate.Mode.FULL ? "What leaves the TV: the story's words to Claude; Friday's and the cast's lines to ElevenLabs; picture descriptions to kie.ai."
                : "What leaves the TV: the story's words to Claude; Friday's lines to ElevenLabs."));
        col.addView(line("Can it be undone: no, the money is spent as each page is made. You can stop at any page and keep the book."));
        col.addView(line("Cost: " + Spent.words(p.estimate.usd()) + " for this story" + (p.oneOffs.isEmpty() ? "" : ", plus one-off: " + String.join("; ", p.oneOffs)) + "."));
        if (!p.note.isEmpty()) col.addView(line(p.note));
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(30), 0, 0);
        TextView not = Brand.button(this, "Not now", Brand.NEUTRAL);
        not.setOnClickListener(v -> showStart());
        TextView doIt = Brand.button(this, "Do it", Brand.CYAN);
        doIt.setOnClickListener(v -> startStory(topic, p));
        row.addView(doIt);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.leftMargin = dp(20);
        row.addView(not, nl);
        col.addView(row);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        main.post(not::requestFocus);   // focus starts on the safest choice
    }

    private TextView line(String s) {
        TextView t = Brand.text(this, s, 18, Brand.TEXT, Brand.body(this));
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    // ================================================================= the story =====

    private void startStory(String topic, StoryEngine.Prepared p) {
        replaying = false;
        pagesTotal = p.pages;
        buildStoryScreen();
        strip.setText("Thinking of a story…");
        KidsRules.Band band = new KidsSettings(this).band();
        String hero = getSharedPreferences("story", MODE_PRIVATE).getString("hero", "");
        engine.start(topic, p, band, hero, kids, listener);
        startBed(p.mode);
    }

    private void startBed(StoryGate.Mode mode) {
        if (mode != StoryGate.Mode.FULL) return;
        byte[] bed = engine.beds().cachedPcm(bedtime ? MusicBeds.BEDTIME : MusicBeds.ADVENTURE);
        if (bed != null) mixer.bed(bed, bedtime ? 0.3f : 0.4f);
        else main.postDelayed(() -> { byte[] b2 = engine.beds().cachedPcm(bedtime ? MusicBeds.BEDTIME : MusicBeds.ADVENTURE); if (b2 != null && storyOn) mixer.bed(b2, bedtime ? 0.3f : 0.4f); }, 45_000);
    }

    private void buildStoryScreen() {
        storyOn = true;
        root.removeAllViews();
        picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        shadow = new ShadowView(this);
        shadow.setVisibility(View.GONE);
        root.addView(shadow, new FrameLayout.LayoutParams(-1, -1));
        View grad = new View(this);
        grad.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x66000000, 0xEE05060C}));
        root.addView(grad, new FrameLayout.LayoutParams(-1, -1));
        dimmer = new View(this);
        dimmer.setBackgroundColor(0xFF05060C);
        dimmer.setAlpha(0f);
        root.addView(dimmer, new FrameLayout.LayoutParams(-1, -1));

        title = Brand.text(this, "", 16, Brand.DIM, Brand.display(this));
        title.setLetterSpacing(0.1f);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tl.setMargins(dp(48), dp(26), 0, 0);
        root.addView(title, tl);
        strip = Brand.text(this, "", 15, Brand.DIM, Brand.body(this));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        sl.setMargins(0, dp(26), dp(48), 0);
        root.addView(strip, sl);
        cost = Brand.text(this, "", 13, Brand.FAINT, Brand.mono(this));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cl.setMargins(0, dp(50), dp(48), 0);
        root.addView(cost, cl);

        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(64), 0, dp(64), dp(30));
        silly = new LinearLayout(this);
        silly.setVisibility(View.GONE);
        silly.setPadding(0, 0, 0, dp(10));
        for (SoundPack.Sound s : SoundPack.SILLY) {
            TextView b = Brand.button(this, glyph(s.name) + " " + s.name, Brand.VIOLET);
            b.setOnClickListener(v -> { byte[] pcm = engine.sounds().cachedPcm(s.name); if (pcm != null) mixer.shot(pcm, 0.9f); else notice("That sound isn't on the TV yet."); });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
            bl.rightMargin = dp(12);
            silly.addView(b, bl);
        }
        bottom.addView(silly);
        words = Brand.text(this, "", older ? 30 : 34, 0xFFFFFFFF, Brand.body(this));
        words.setLineSpacing(0, 1.25f);
        words.setShadowLayer(dp(6), 0, dp(2), 0xCC000000);
        bottom.addView(words);
        choices = new LinearLayout(this);
        choices.setPadding(0, dp(14), 0, 0);
        choices.setVisibility(View.GONE);
        bottom.addView(choices);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bottom, bl);
        main.removeCallbacks(shadowCheck);
        main.postDelayed(shadowCheck, 1000);
    }

    static String glyph(String sound) {
        switch (sound) { case "toot": return "🎺"; case "boing": return "🪀"; case "giggle": return "😆"; case "roar": return "🐉"; default: return "✨"; }
    }

    private final StoryEngine.Listener listener = new StoryEngine.Listener() {
        @Override public void onTitle(String t, List<Cast.Member> cast) { if (title != null) title.setText(t.toUpperCase()); }
        @Override public void onPage(int index, StoryPage p, Seams.Audio audio, File pic, String chosen) { showPage(index, p, audio, pic); }
        @Override public void onPicture(int index, File f) { if (index == pageIndex) setPicture(f); }
        @Override public void onPictureFailed(int index, String why) { if (index == pageIndex && strip != null) { strip.setText("No picture for this page"); shadow.setVisibility(View.VISIBLE); } }
        @Override public void onProgress(String w) { if (strip != null) strip.setText(w); }
        @Override public void onEnded(Book b) {
            try { teller.shelf().put(b); } catch (Exception ignored) { }
            if (strip != null) strip.setText("The end");
            main.postDelayed(() -> { if (storyOn && !spokenNow()) offerAfterEnd(); }, 1500);
        }
        @Override public void onFailed(String why) { notice(why); Book b = engine.book(); if (b != null) try { teller.shelf().put(b); } catch (Exception ignored) { } main.postDelayed(StoryActivity.this::showStart, 2500); }
        @Override public void onCost(double usd) { if (cost != null) cost.setText(kids ? "" : Spent.words(usd)); }
    };

    private boolean spokenNow() { return mixer.speaking(); }

    private void showPage(int index, StoryPage p, Seams.Audio audio, File pic) {
        page = p; pageIndex = index; spoken = false;
        pageText = p.text();
        timings = audio.words;
        words.setText(pageText);
        choices.removeAllViews();
        choices.setVisibility(View.GONE);
        silly.setVisibility(View.GONE);
        strip.setText(p.ending ? "The last page" : "Page " + (index + 1));
        dimmer.animate().alpha(Bedtime.dim(index, pagesTotal, bedtime) * 0.6f).setDuration(Motion.reduced(this) ? 0 : 900).start();
        if (pic != null) setPicture(pic);
        else {
            pictureAskedAt = engine.book() != null && StoryGate.Mode.FULL.name().toLowerCase().equals(engine.book().mode()) ? System.currentTimeMillis() : 0;
            if (pictureAskedAt == 0) { shadow.setComing(false); shadow.setVisibility(View.VISIBLE); shadow.setCast(engine.cast().members().size()); }
            else if (picture.getDrawable() != null && !Motion.reduced(this)) picture.animate().scaleX(1.04f).scaleY(1.04f).setDuration(20_000).start();
        }
        if (p.sound != null) { byte[] s = engine.sounds().cachedPcm(p.sound); if (s != null) mixer.shot(s, 0.8f); }
        try { Book b = engine.book(); if (b != null) teller.shelf().put(b); } catch (Exception ignored) { }
        mixer.say(audio.pcm, new Mixer.Progress() {
            @Override public void at(long ms) { highlight(ms); }
            @Override public void done() { spoken = true; highlight(-1); afterSpoken(); }
        });
    }

    private void setPicture(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inSampleSize = 1;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b == null) return;
            if (b.getWidth() > 1400) { Bitmap s = Bitmap.createScaledBitmap(b, 1280, 1280 * b.getHeight() / b.getWidth(), true); b.recycle(); b = s; }
            picture.setImageBitmap(b);
            picture.setScaleX(1f); picture.setScaleY(1f);
            picture.setAlpha(0f);
            picture.animate().alpha(1f).setDuration(Motion.reduced(this) ? 0 : 600).start();
            if (!Motion.reduced(this)) picture.animate().scaleX(1.03f).scaleY(1.03f).setDuration(25_000).start();
            shadow.setVisibility(View.GONE);
            if (strip != null && strip.getText().toString().startsWith("The picture")) strip.setText("Page " + (pageIndex + 1));
        } catch (Throwable ignored) { }
    }

    private void highlight(long ms) {
        SpannableString s = new SpannableString(pageText);
        int w = ms < 0 ? -1 : WordTiming.wordAt(timings, ms);
        if (w >= 0 && w < timings.size()) {
            WordTiming.Word x = timings.get(w);
            int a = Math.max(0, Math.min(pageText.length(), x.start)), b = Math.max(a, Math.min(pageText.length(), x.end));
            s.setSpan(new BackgroundColorSpan(0x66FF4FD8), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new ForegroundColorSpan(0xFFFFFFFF), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (a > 0) s.setSpan(new ForegroundColorSpan(0xC8FFFFFF), 0, a, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        words.setText(s);
    }

    /** After the page is read: the choices, or "what happens next", or the end. */
    private void afterSpoken() {
        if (replaying) { strip.setText(pageIndex + 1 < replayPages.size() ? "Right for the next page" : "The end"); return; }
        if (page == null) return;
        choices.removeAllViews();
        if (page.ending) { return; }
        if (page.hasChoices()) {
            for (int i = 0; i < 2; i++) {
                final int which = i;
                View t = tile(i == 0 ? "◀" : "▶", page.choices.get(i).label, "", i == 0 ? Brand.CYAN : 0xFFFF4FD8, () -> chose(which));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(120), 1);
                if (i == 1) lp.leftMargin = dp(24);
                choices.addView(t, lp);
            }
        } else {
            View go = tile("➡", "What happens next?", "OK: go on · hold OK: say it", Brand.CYAN, () -> goOn(null));
            go.setOnKeyListener((v, k, e) -> holdToTalk(k, e, this::goOn));
            choices.addView(go, new LinearLayout.LayoutParams(0, dp(120), 1));
        }
        choices.setVisibility(View.VISIBLE);
        main.post(() -> { if (choices.getChildCount() > 0) choices.getChildAt(0).requestFocus(); });
    }

    private void chose(int which) {
        choices.setVisibility(View.GONE);
        strip.setText("…");
        pictureAskedAt = 0;
        engine.choose(which);
    }

    private void goOn(String suggestion) {
        if (suggestion != null) {
            String refusal = StoryGate.refuse(suggestion, kids);
            if (refusal != null) { notice(refusal); return; }
        }
        choices.setVisibility(View.GONE);
        strip.setText("…");
        pictureAskedAt = 0;
        engine.goOn(suggestion);
    }

    private void offerAfterEnd() {
        choices.removeAllViews();
        choices.addView(tile("📖", "Read it again", "", Brand.CYAN, () -> { Book b = engine.book(); if (b != null) replay(b.id()); }), new LinearLayout.LayoutParams(0, dp(110), 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(110), 1);
        lp.leftMargin = dp(24);
        choices.addView(tile("✨", "Another story", "", 0xFFFF4FD8, this::showStart), lp);
        choices.setVisibility(View.VISIBLE);
        main.post(() -> choices.getChildAt(0).requestFocus());
    }

    // ================================================================= replay (no calls) =====

    private void replay(String id) {
        Book b = null;
        for (Book x : engine.books()) if (x.id().equals(id)) b = x;
        if (b == null || b.pageCount() == 0) { notice("That book is empty."); return; }
        replaying = true; replayBook = b; replayPages = b.pages();
        pagesTotal = replayPages.size();
        bedtime = b.bedtime();
        buildStoryScreen();
        title.setText(b.title().toUpperCase());
        b.markPlayed();
        try { b.save(); teller.shelf().put(b); } catch (Exception ignored) { }
        byte[] bed = engine.beds().cachedPcm(bedtime ? MusicBeds.BEDTIME : MusicBeds.ADVENTURE);
        if (bed != null && StoryGate.Mode.FULL.name().toLowerCase().equals(b.mode())) mixer.bed(bed, bedtime ? 0.3f : 0.4f);
        replayPage(0);
    }

    private void replayPage(int i) {
        if (replayPages == null || i < 0 || i >= replayPages.size()) return;
        Book.Made m = replayPages.get(i);
        byte[] pcm = new byte[0];
        try { pcm = Files.readAllBytes(replayBook.audioFile(i).toPath()); } catch (Exception ignored) { }
        File pic = m.picture.isEmpty() ? null : replayBook.pictureFile(i);
        if (pic != null && !pic.isFile()) pic = null;
        Seams.Audio a = new Seams.Audio(pcm, WordTiming.fromJson(m.words), "file");
        pictureAskedAt = 0;
        showPage(i, m.page, a, pic);
        if (pic == null) { shadow.setComing(false); shadow.setVisibility(View.VISIBLE); shadow.setCast(3); }
        strip.setText("Page " + (i + 1) + " of " + replayPages.size());
    }

    // ================================================================= the grown-up's screen =====

    private void showNight() {
        root.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(120), dp(50), dp(120), dp(40));
        col.addView(Brand.wordmark(this, 13));
        TextView h = Brand.text(this, "Story night", 30, 0xFFFFFFFF, Brand.display(this));
        col.addView(h);
        long now = System.currentTimeMillis();
        col.addView(line(NightGrant.words(teller.night().until(), now) + "."));
        col.addView(line("A story night lets a child's stories have pictures, the cast's voices, sounds and music without the PIN each time. "
                + "A full story is " + Spent.words(engine.prepare(new StoryGate.Room(false, true, true, true, true, true), bedtime, now, status.bedtimeAt, teller.fridayVoiceIsEleven()).estimate.usd())
                + "; the first night also makes the sound pack and a music bed."));
        col.addView(line("Type the grown-up PIN, then choose how long."));
        TextView dots = Brand.text(this, "", 34, 0xFFFFFFFF, Brand.mono(this));
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, dp(14), 0, dp(14));
        col.addView(dots);
        StringBuilder pin = new StringBuilder();
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER_HORIZONTAL);
        String[] rows = {"123", "456", "789", "⌫0✓"};
        for (String r : rows) {
            LinearLayout rr = new LinearLayout(this);
            for (char ch : r.toCharArray()) {
                TextView b = Brand.button(this, String.valueOf(ch), Brand.CYAN);
                b.setOnClickListener(v -> {
                    if (ch == '⌫') { if (pin.length() > 0) pin.setLength(pin.length() - 1); }
                    else if (ch == '✓') { checkPin(pin.toString(), dots); return; }
                    else if (pin.length() < 8) pin.append(ch);
                    dots.setText(new String(new char[pin.length()]).replace('\0', '●'));
                });
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(90), -2);
                bl.setMargins(dp(6), dp(6), dp(6), dp(6));
                rr.addView(b, bl);
            }
            pad.addView(rr);
        }
        col.addView(pad);
        TextView back = Brand.button(this, "Back", Brand.NEUTRAL);
        back.setOnClickListener(v -> showStart());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = dp(16);
        col.addView(back, bl);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        main.post(() -> ((LinearLayout) pad.getChildAt(0)).getChildAt(0).requestFocus());
    }

    private void checkPin(String pin, TextView dots) {
        FridayApp app = (FridayApp) getApplication();
        String problem;
        try { problem = app.grownUp().verify(pin); } catch (Throwable t) { problem = "The grown-up check isn't available."; }
        if (problem != null && !problem.isEmpty()) { dots.setText(problem); return; }
        // The PIN is right: choose how long (nothing pre-selected).
        root.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(120), dp(80), dp(120), dp(40));
        col.addView(Brand.text(this, "How long is story night?", 30, 0xFFFFFFFF, Brand.display(this)));
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(30), 0, 0);
        long now = System.currentTimeMillis();
        double est = engine.prepare(new StoryGate.Room(false, true, true, true, true, true), bedtime, now, status.bedtimeAt, teller.fridayVoiceIsEleven()).estimate.usd();
        for (int i = 0; i < NightGrant.HOURS.length; i++) {
            final int hours = NightGrant.HOURS[i];
            View t = tile("🌙", NightGrant.LABELS[i], "", Brand.CYAN, () -> {
                try { teller.night().make(now, hours, est); notice("Story night is on."); } catch (Exception e) { notice("Couldn't save that."); }
                showStart();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), dp(150));
            lp.leftMargin = i == 0 ? 0 : dp(20);
            row.addView(t, lp);
        }
        col.addView(row);
        TextView off = Brand.button(this, "End story night", Brand.NEUTRAL);
        off.setOnClickListener(v -> { teller.night().revoke(); notice("Story night is off."); showStart(); });
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-2, -2);
        ol.topMargin = dp(30);
        col.addView(off, ol);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        main.post(() -> row.getChildAt(0).requestFocus());
    }

    // ================================================================= voice in =====

    /** Hold OK on a tile to talk; let go to send. The words go through the kids check before anything else. */
    private boolean holdToTalk(int key, KeyEvent e, java.util.function.Consumer<String> heard) {
        if (key != KeyEvent.KEYCODE_DPAD_CENTER && key != KeyEvent.KEYCODE_ENTER) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (speech == null) {
                try {
                    OfflineSpeech.Route route = RecognizerSpeechIn.onDeviceAvailable(this) ? OfflineSpeech.Route.ON_DEVICE
                            : RecognizerSpeechIn.serviceAvailable(this) ? OfflineSpeech.Route.SERVICE_OFFLINE : OfflineSpeech.Route.NONE;
                    speech = RecognizerSpeechIn.create(this, new SpeechIn.Events() {
                        @Override public void onMic(boolean open) { if (strip != null) strip.setText(open ? "Listening…" : ""); }
                        @Override public void onPartial(String t) { }
                        @Override public void onFinal(String t) { Runnable r = onHeard; if (r != null) { onHeard = null; } if (t != null && !t.trim().isEmpty()) heard.accept(t.trim()); }
                        @Override public void onNothing(String why) { notice("I didn't catch that. Try again?"); }
                        @Override public void onLevel(double l) { }
                    }, route);
                    ((RecognizerSpeechIn) speech).setHoldMode(true);
                } catch (Throwable t) { notice("The TV can't hear right now; pick a tile instead."); return true; }
            }
            speech.start();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) { if (speech != null) speech.finish(); return true; }
        return true;
    }

    // ================================================================= keys =====

    @Override public boolean dispatchKeyEvent(KeyEvent e) { return input.feed(e); }

    private boolean route(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down && e.getRepeatCount() > 0 && k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (!down || e.getRepeatCount() > 0) return true;
            if (storyOn) {
                if (!replaying && engine.busy()) { engine.stop(); Book b = engine.book(); if (b != null) try { teller.shelf().put(b); } catch (Exception ignored) { } }
                mixer.stopVoice(); mixer.stopBed();
                showStart();
                return true;
            }
            if (kids) return true;   // Back does nothing on the kids side of the start screen
            finish();
            return true;
        }
        if (storyOn && down && e.getRepeatCount() == 0) {
            if (k == KeyEvent.KEYCODE_DPAD_UP && !bedtime && silly.getVisibility() != View.VISIBLE && (choices.getVisibility() != View.VISIBLE || true)) {
                View f = getCurrentFocus();
                if (f == null || choices.indexOfChild(f) < 0 || true) {
                    if (silly.getVisibility() != View.VISIBLE) { silly.setVisibility(View.VISIBLE); silly.getChildAt(0).requestFocus(); return true; }
                }
            }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                View f = getCurrentFocus();
                if (f != null && silly.indexOfChild(f) >= 0) { silly.setVisibility(View.GONE); if (choices.getVisibility() == View.VISIBLE) choices.getChildAt(0).requestFocus(); return true; }
                if (replaying) { strip.setText(soFar()); return true; }
                strip.setText(soFar());
                return true;
            }
            if (replaying && (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_LEFT) && (choices.getVisibility() != View.VISIBLE)) {
                replayPage(pageIndex + (k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1));
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private String soFar() {
        List<StoryPage> ps = replaying ? pagesOf(replayPages) : (engine.book() == null ? new ArrayList<>() : engine.book().storyPages());
        if (ps.isEmpty()) return "";
        StringBuilder s = new StringBuilder("So far: ");
        for (int i = Math.max(0, ps.size() - 3); i < ps.size(); i++) s.append(ps.get(i).lines.get(0).text).append(" ");
        return s.length() > 160 ? s.substring(0, 157) + "…" : s.toString();
    }

    private static List<StoryPage> pagesOf(List<Book.Made> made) {
        List<StoryPage> out = new ArrayList<>();
        if (made != null) for (Book.Made m : made) out.add(m.page);
        return out;
    }

    private void notice(String s) {
        TextView t = Brand.text(this, s, 20, 0xFFFFFFFF, Brand.bodyBold(this));
        t.setBackground(Brand.pill(this, 0xEE1A2347, 0x66FFFFFF));
        t.setPadding(dp(22), dp(10), dp(22), dp(10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(70);
        root.addView(t, lp);
        main.postDelayed(() -> root.removeView(t), 3500);
    }

    @Override protected void onPause() {
        main.removeCallbacks(shadowCheck);
        if (mixer != null) { mixer.stopVoice(); mixer.stopBed(); }
        if (speech != null) speech.cancel();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (mixer != null) mixer.release();
        if (speech != null) speech.release();
        super.onDestroy();
    }

    /** The shadow page: the cast as soft silhouettes while the picture is still coming (spec §6 step 6). */
    static final class ShadowView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int cast = 2;
        private boolean coming = true;
        ShadowView(android.content.Context c) { super(c); setBackgroundColor(0xFF0E1538); }
        void setCast(int n) { cast = Math.max(1, Math.min(4, n)); invalidate(); }
        /** False in words mode: the silhouettes are the page's whole picture, nothing is coming. */
        void setComing(boolean b) { coming = b; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            p.setColor(0xFF1B2452);
            cv.drawCircle(w * 0.78f, h * 0.22f, h * 0.09f, p);   // a moon
            p.setColor(0xFF161E45);
            cv.drawOval(-w * 0.1f, h * 0.62f, w * 1.1f, h * 1.4f, p);   // a hill
            p.setColor(0xFF0A0F2A);
            for (int i = 0; i < cast; i++) {
                float cx = w * (0.3f + i * 0.4f / Math.max(1, cast - 1) * (cast > 1 ? 1 : 0)) + (cast == 1 ? w * 0.2f : 0);
                float r = h * (0.11f + (i % 2) * 0.03f);
                cv.drawCircle(cx, h * 0.62f, r, p);
                cv.drawCircle(cx, h * 0.62f - r * 1.3f, r * 0.6f, p);
            }
            if (!coming) return;
            p.setColor(0xB3FFFFFF);
            p.setTextSize(h * 0.035f);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            cv.drawText("the picture's coming…", w / 2, h * 0.5f, p);
        }
    }
}
