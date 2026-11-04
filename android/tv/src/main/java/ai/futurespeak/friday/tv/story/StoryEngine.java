package ai.futurespeak.friday.tv.story;

import ai.futurespeak.friday.tv.actions.Images;
import ai.futurespeak.friday.tv.actions.Receipts;
import ai.futurespeak.friday.tv.kids.KidsRules;
import ai.futurespeak.friday.tv.studio.Engines;
import ai.futurespeak.friday.tv.studio.Prices;
import ai.futurespeak.friday.tv.studio.Spent;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The story being told (spec §6): plans page by page, speaks each line in the right voice,
 * asks for the page's picture after the choice, makes the next page's text (both branches) and
 * first line while this page plays, saves every page as it is made, keeps the money per
 * service, and writes the receipts. One decision receipt per story; one outcome at the end.
 * Everything paid beyond the words happens only in {@link StoryGate.Mode#FULL}. Runs on its
 * own threads; the screen listens.
 */
public final class StoryEngine {
    public interface Listener {
        void onTitle(String title, List<Cast.Member> cast);
        /** A page's words and audio are ready; its picture may follow (null here), or be absent in words mode. */
        void onPage(int index, StoryPage page, Seams.Audio audio, File picture, String chosen);
        void onPicture(int index, File picture);
        void onPictureFailed(int index, String why);
        void onProgress(String words);
        void onEnded(Book book);
        void onFailed(String why);
        void onCost(double usd);
    }

    /** What the card shows before a story starts. */
    public static final class Prepared {
        public final StoryGate.Mode mode; public final Spent estimate; public final int pages; public final boolean bedtime;
        public final List<String> oneOffs; public final String note;
        Prepared(StoryGate.Mode mode, Spent estimate, int pages, boolean bedtime, List<String> oneOffs, String note) {
            this.mode = mode; this.estimate = estimate; this.pages = pages; this.bedtime = bedtime; this.oneOffs = oneOffs; this.note = note;
        }
    }

    /** Counts and timings only, never a word of the story, never a secret; the TV wires it to logcat, tests leave it silent. */
    public interface Logger { void log(boolean warn, String message); }
    private static volatile Logger logger = (w, m) -> { };
    public static void setLogger(Logger l) { logger = l == null ? (w, m) -> { } : l; }
    private static void logi(String m) { logger.log(false, m); }
    private static void logw(String m) { logger.log(true, m); }
    public static final int MAX_TOKENS = 1400;
    /** A quarter over the estimate: the rest of the story is told in words and voice only (the Studio rule, kept simple). */
    public static final double OVER = 1.25;

    private final File root;
    private final Engines.Writer writer;
    private final Seams.Speaker friday, tv;
    private final Images pictures;
    private final Images.Store pictureStore;
    private final SoundPack sounds;
    private final MusicBeds beds;
    private final Seams.VoiceDesigner designer;
    private final Receipts receipts;
    private final Prices prices;
    private final Seams.Clock clock;
    private final Cast cast;
    private final File castFile;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "story-worker"); t.setDaemon(true); return t; });
    private final ExecutorService pool = Executors.newFixedThreadPool(3, r -> { Thread t = new Thread(r, "story-ahead"); t.setDaemon(true); return t; });
    private final AtomicBoolean stop = new AtomicBoolean();

    // the story in progress
    private volatile Book book;
    private volatile Listener listener;
    private StoryGate.Mode mode;
    private KidsRules.Band band;
    private String hero, principal, decisionId;
    private boolean bedtime, older;
    private Spent spent = new Spent(), estimate = new Spent();
    private boolean degraded;
    private String system;
    private final List<StoryPage> pages = new ArrayList<>();
    private StoryPage current;
    private int pagesTarget;
    private Future<String>[] aheadText;
    private Future<Seams.Audio>[] aheadFirst;
    private Future<Seams.Audio> aheadBridge;

    public StoryEngine(File storyRoot, Engines.Writer writer, Seams.Speaker friday, Seams.Speaker tv, Images pictures, Images.Store pictureStore,
                       SoundPack sounds, MusicBeds beds, Seams.VoiceDesigner designer, Receipts receipts, Prices prices, Seams.Clock clock) {
        this.root = storyRoot; this.writer = writer; this.friday = friday; this.tv = tv; this.pictures = pictures; this.pictureStore = pictureStore;
        this.sounds = sounds; this.beds = beds; this.designer = designer; this.receipts = receipts; this.prices = prices; this.clock = clock;
        castFile = new File(storyRoot, "cast.json");
        cast = loadCast();
    }

    public Cast cast() { return cast; }
    public SoundPack sounds() { return sounds; }
    public MusicBeds beds() { return beds; }
    public Book book() { return book; }
    public boolean busy() { return book != null && Book.MAKING.equals(book.state()); }

    // ---------------------------------------------------------------- prepare -----

    /** The card's facts: mode, estimate and the one-off lines a first night adds. Spends nothing. */
    public Prepared prepare(StoryGate.Room room, boolean bedtime, long now, long bedtimeAt, boolean fridayVoiceIsEleven) {
        StoryGate.Mode m = StoryGate.allowed(room);
        if (m == null) return null;
        int pages = Bedtime.pagesFor(now, bedtimeAt, bedtime);
        int soundSeconds = 0;
        for (SoundPack.Sound s : sounds.missing()) soundSeconds += Math.round(s.seconds);
        int bedsMissing = (beds.cached(MusicBeds.ADVENTURE) ? 0 : 1) + (beds.cached(MusicBeds.BEDTIME) ? 0 : 1);
        int toDesign = 0;
        for (Cast.Member c : cast.members()) if (!c.designed()) toDesign++;
        toDesign = Math.min(2, toDesign == 0 ? 2 : toDesign);   // a new story may bring two new voices
        Spent est = StoryPrices.estimate(pages, m, writer.model(), fridayVoiceIsEleven, prices,
                m == StoryGate.Mode.FULL ? soundSeconds : 0, m == StoryGate.Mode.FULL ? bedsMissing : 0, m == StoryGate.Mode.FULL ? toDesign : 0);
        List<String> oneOffs = new ArrayList<>();
        if (m == StoryGate.Mode.FULL && soundSeconds > 0) oneOffs.add("First time: the sound pack, " + Spent.words(StoryPrices.soundUsd(soundSeconds)));
        if (m == StoryGate.Mode.FULL && bedsMissing > 0) oneOffs.add("First time: " + bedsMissing + (bedsMissing == 1 ? " music bed, " : " music beds, ") + Spent.words(StoryPrices.musicUsd(MusicBeds.SECONDS) * bedsMissing));
        if (m == StoryGate.Mode.FULL && toDesign > 0) oneOffs.add("New voices for the cast: up to " + toDesign + ", " + Spent.words(StoryPrices.voiceDesignUsd() * toDesign) + " (estimate)");
        String note = m == StoryGate.Mode.WORDS && room.kids && !room.unlocked() ? "Pictures, voices, sounds and music need a grown-up." : "";
        return new Prepared(m, est, pages, bedtime, oneOffs, note);
    }

    // ---------------------------------------------------------------- start -----

    /** Starts a story. The decision receipt is written first; nothing is made before it. */
    public void start(String topic, Prepared p, KidsRules.Band band, String hero, boolean kids, Listener l) {
        stop.set(false);
        listener = l;
        this.mode = p.mode; this.band = band; this.hero = hero; this.bedtime = p.bedtime; this.older = band == KidsRules.Band.OLDER;
        this.principal = kids ? "kids" : "family";
        this.estimate = p.estimate; this.spent = new Spent(); this.degraded = false; this.pagesTarget = p.pages;
        pages.clear(); current = null; aheadText = null; aheadFirst = null; aheadBridge = null;
        worker.execute(() -> {
            try {
                JSONObject rec = StoryGate.decisionRec(topic, mode, bedtime, band == null ? "" : band.name(), p.estimate.meter("usd_est"), kids);
                decisionId = receipts.decision(principal, rec);
                String id = "s" + Long.toHexString(clock.now()) + Integer.toHexString((int) (Math.random() * 0xffff));
                book = Book.create(root, id, topic, mode.name().toLowerCase(Locale.ROOT), band == null ? "" : band.name(), hero, bedtime, pagesTarget, decisionId, principal);
                progress("Getting ready…");
                if (mode == StoryGate.Mode.FULL) makePacks();
                system = StoryPlanner.system(band, hero, bedtime, SoundPack.names(), cast.members());
                progress("Thinking of a story…");
                StoryPlanner.Start s = null;
                String problem = null;
                for (int attempt = 0; attempt < 2 && s == null && !stop.get(); attempt++) {
                    Engines.Reply r = writer.write(system, StoryPlanner.startUser(topic, pagesTarget, cast.members()) + (problem == null ? "" : "\n(Last time: " + problem + ". Fix that.)"), MAX_TOKENS);
                    meterClaude(r);
                    try { s = StoryPlanner.parseStart(r.text, SoundPack.names(), older, hero, cast); }
                    catch (StoryPage.Rejected e) { problem = e.getMessage(); }
                }
                if (s == null) { fail("I couldn't think of a story that's right for tonight. Let's try another idea."); return; }
                for (Cast.Member m : s.cast) cast.put(m);
                saveCast();
                book.setTitle(s.title);
                book.setCast(cast);
                book.save();
                if (mode == StoryGate.Mode.FULL) designVoices(s.cast);
                final StoryPlanner.Start started = s;
                post(() -> { Listener x = listener; if (x != null) x.onTitle(started.title, started.cast); });
                present(started.page, null);
            } catch (Throwable t) {
                fail("Something went wrong starting the story.");
            }
        });
    }

    /** The child chose Left (0) or Right (1). */
    public void choose(int which) {
        worker.execute(() -> {
            StoryPage cur = current;
            if (cur == null || !cur.hasChoices() || stop.get()) return;
            String chosen = cur.choices.get(which).label;
            try {
                String text = aheadText != null && aheadText[which] != null ? aheadText[which].get() : null;
                if (text == null) text = nextText(chosen, null);
                StoryPage page = parseNext(text, chosen, null);
                if (page == null) return;
                Seams.Audio first = aheadFirst != null && aheadFirst[which] != null ? aheadFirst[which].get() : null;
                present(page, chosen, first);
            } catch (Throwable t) {
                fail("I lost the thread of the story. Let's start another.");
            }
        });
    }

    /** The child said what should happen next, or just "go on" (null). */
    public void goOn(String suggestion) {
        worker.execute(() -> {
            StoryPage cur = current;
            if (cur == null || cur.ending || stop.get()) return;
            try {
                String text = null;
                if ((suggestion == null || suggestion.isEmpty()) && !cur.hasChoices() && aheadText != null && aheadText[0] != null) text = aheadText[0].get();
                if (text == null) text = nextText(null, suggestion);
                StoryPage page = parseNext(text, null, suggestion);
                if (page == null) return;
                Seams.Audio first = (suggestion == null || suggestion.isEmpty()) && !cur.hasChoices() && aheadFirst != null && aheadFirst[0] != null ? aheadFirst[0].get() : null;
                present(page, suggestion, first);
            } catch (Throwable t) {
                fail("I lost the thread of the story. Let's start another.");
            }
        });
    }

    /** "Friday, stop" / Back: the story stops here and is kept as it is. */
    public void stop() {
        stop.set(true);
        worker.execute(() -> {
            Book b = book;
            if (b == null || !Book.MAKING.equals(b.state())) return;
            try {
                b.setState(Book.STOPPED); b.setSpent(spent); b.save();
                receipts.outcome(decisionId, principal, "stopped", "stopped on the TV at page " + b.pageCount(), extra());
            } catch (IOException ignored) { }
        });
    }

    public void release() { stop.set(true); worker.shutdownNow(); pool.shutdownNow(); }

    // ---------------------------------------------------------------- the page -----

    private String nextText(String chosen, String suggestion) throws Exception {
        int left = Math.max(1, pagesTarget - pages.size());
        Engines.Reply r = writer.write(system, StoryPlanner.nextUser(book.title(), pages, chosen, suggestion, left, bedtime), MAX_TOKENS);
        meterClaude(r);
        return r.text;
    }

    private StoryPage parseNext(String text, String chosen, String suggestion) throws Exception {
        String problem = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                StoryPage p = StoryPlanner.parsePage(text, cast.names(), SoundPack.names(), older, hero);
                int left = pagesTarget - pages.size();
                if (left <= 1 && !p.ending) p = asEnding(p);   // the TV holds the length: the last page ends, whatever the model said
                return p;
            } catch (StoryPage.Rejected e) {
                problem = e.getMessage();
                if (attempt == 1) break;
                int left = Math.max(1, pagesTarget - pages.size());
                Engines.Reply r = writer.write(system, StoryPlanner.nextUser(book.title(), pages, chosen, suggestion, left, bedtime)
                        + "\n(Last time: " + problem + ". Fix that; turn the story gently another way if needed.)", MAX_TOKENS);
                meterClaude(r);
                text = r.text;
            }
        }
        fail("The next page wasn't right for tonight, so the story stops here.");
        return null;
    }

    private static StoryPage asEnding(StoryPage p) {
        List<StoryPage.Line> lines = new ArrayList<>(p.lines);
        return new StoryPage(p.title, lines, p.brief, p.sound, new ArrayList<>(), true);
    }

    private void present(StoryPage page, String chosen) throws Exception { present(page, chosen, null); }

    /** Speaks the page (in the right voices), saves it, shows it, asks for its picture, and makes the next page ahead. */
    private void present(StoryPage page, String chosen, Seams.Audio firstLine) throws Exception {
        if (stop.get()) return;
        int index = pages.size();
        progress(index == 0 ? "Finding the words…" : "Turning the page…");
        List<byte[]> parts = new ArrayList<>();
        List<WordTiming.Word> words = new ArrayList<>();
        String text = page.text();
        int offset = 0; long at = 0; String voiceNote = "";
        for (int i = 0; i < page.lines.size(); i++) {
            StoryPage.Line l = page.lines.get(i);
            Seams.Audio a = i == 0 && firstLine != null ? firstLine : speak(l);
            if (!"elevenlabs".equals(a.voice)) voiceNote = "Parts are in the TV's voice";
            parts.add(a.pcm);
            for (WordTiming.Word w : a.words) words.add(new WordTiming.Word(w.start + offset, w.end + offset, w.fromMs + at, w.toMs + at));
            at += a.ms;
            offset += l.text.length() + 1;
        }
        byte[] pcm = join(parts);
        Seams.Audio audio = new Seams.Audio(pcm, words, voiceNote.isEmpty() ? "elevenlabs" : "mixed");
        logi("page " + (index + 1) + " ready: " + page.lines.size() + " lines, " + audio.ms + " ms, voices=" + audio.voice + ", choices=" + page.choices.size() + ", ending=" + page.ending + ", spent=" + String.format(java.util.Locale.ROOT, "%.2f", spent.usd()));
        Files.write(book.audioFile(index).toPath(), pcm);
        book.addPage(page, book.audioFile(index).getName(), "", WordTiming.toJson(words), audio.ms, voiceNote, chosen);
        book.setSpent(spent); book.save();
        pages.add(page);
        current = page;
        post(() -> { Listener x = listener; if (x != null) x.onPage(index, page, audio, null, chosen); });
        cost();
        // The picture, after the words (and after the choice): exactly one request per page (SB-E05).
        if (mode == StoryGate.Mode.FULL && !degraded) pool.execute(() -> picture(index, page));
        // Ahead: the next page's text for each branch, and its first line.
        ahead(page);
        if (page.ending) finish();
    }

    private Seams.Audio speak(StoryPage.Line l) {
        Cast.Member who = l.narrator() ? null : cast.find(l.who);
        if (mode != StoryGate.Mode.FULL) who = null;   // in words mode every line is Friday's own voice
        try {
            Seams.Audio a = friday.speak(l.text, who);
            if (friday.paid()) spent.add("elevenlabs", "characters", l.text.length(), l.text.length() / 1000.0 * prices.elevenPer1k);
            return a;
        } catch (Exception e) {
            try { return tv.speak(l.text, null); }
            catch (Exception e2) { return new Seams.Audio(new byte[0], new ArrayList<>(), "none"); }
        }
    }

    @SuppressWarnings("unchecked")
    private void ahead(StoryPage page) {
        aheadText = null; aheadFirst = null;
        int n = Prefetch.textsAhead(page);
        if (n == 0) return;
        aheadText = new Future[n];
        aheadFirst = new Future[n];
        for (int i = 0; i < n; i++) {
            final String chosen = page.hasChoices() ? page.choices.get(i).label : null;
            aheadText[i] = pool.submit(() -> nextText(chosen, null));
            final int k = i;
            aheadFirst[i] = pool.submit(() -> {
                String t = aheadText[k].get();
                try {
                    StoryPage p = StoryPlanner.parsePage(t, cast.names(), SoundPack.names(), older, hero);
                    return speak(p.lines.get(0));
                } catch (StoryPage.Rejected e) { return null; }
            });
        }
    }

    private void picture(int index, StoryPage page) {
        if (!pictures.ready()) { post(() -> { Listener x = listener; if (x != null) x.onPictureFailed(index, "no picture service"); }); return; }
        String brief = StorySafety.fullBrief(StorySafety.withCast(page.brief, cast.members()), older);
        if (StorySafety.briefProblem(page.brief, hero) != null) { post(() -> { Listener x = listener; if (x != null) x.onPictureFailed(index, "brief refused"); }); return; }
        long t0 = clock.now();
        Images.Result r = pictures.create(brief, (pct, w) -> { });
        spent.add("kie.ai", "pictures", 1, r.credits * Images.USD_PER_CREDIT);
        logi("picture " + (index + 1) + (r.ok ? " ok" : " failed (" + r.error + ")") + " in " + (clock.now() - t0) + " ms, credits=" + r.credits);
        cost();
        if (!r.ok) {
            post(() -> { Listener x = listener; if (x != null) x.onPictureFailed(index, r.error); });
            return;
        }
        try {
            File f = book.pictureFile(index);
            Files.write(f.toPath(), r.bytes);
            book.setPicture(index, f.getName());
            post(() -> { Listener x = listener; if (x != null) x.onPicture(index, f); });
        } catch (IOException e) {
            post(() -> { Listener x = listener; if (x != null) x.onPictureFailed(index, "couldn't save it"); });
        }
    }

    private void finish() {
        worker.execute(() -> {
            Book b = book;
            if (b == null) return;
            try {
                String recap = "";
                try {
                    Engines.Reply r = writer.write(system, StoryPlanner.recapUser(b.title(), pages), 300);
                    meterClaude(r);
                    if (StorySafety.textOk(r.text)) recap = r.text.trim();
                } catch (Exception ignored) { }
                b.setRecap(recap);
                b.setState(Book.DONE); b.setSpent(spent); b.save();
                logi("ended: " + b.pageCount() + " pages, spent=" + String.format(java.util.Locale.ROOT, "%.2f", spent.usd()) + (degraded ? " (degraded)" : ""));
                receipts.outcome(decisionId, principal, "executed", b.pageCount() + " pages; " + (degraded ? "finished in words after the estimate was passed" : "as planned"), extra());
                post(() -> { Listener x = listener; if (x != null) x.onEnded(b); });
            } catch (IOException ignored) { }
        });
    }

    // ---------------------------------------------------------------- packs and voices -----

    private void makePacks() {
        try {
            if (!sounds.allCached()) {
                progress("Making the sounds (first time)…");
                for (SoundPack.Sound s : sounds.missing()) {
                    if (stop.get()) return;
                    try { sounds.make(s.name); spent.add("elevenlabs sounds", "seconds", s.seconds, StoryPrices.soundUsd(s.seconds)); logi("sound made: " + s.name); }
                    catch (Exception e) { logw("sound failed: " + s.name + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")"); }
                }
            }
            for (String mood : new String[]{bedtime ? MusicBeds.BEDTIME : MusicBeds.ADVENTURE}) {
                if (!beds.cached(mood)) {
                    progress("Making the music (first time)…");
                    try { beds.make(mood); spent.add("elevenlabs music", "beds", 1, StoryPrices.musicUsd(MusicBeds.SECONDS)); logi("music bed made: " + mood); }
                    catch (Exception e) { logw("music bed failed: " + mood + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")"); }
                }
            }
            cost();
        } catch (Throwable ignored) { }
    }

    private void designVoices(List<Cast.Member> members) {
        int designed = 0;
        for (Cast.Member m : members) {
            if (stop.get() || designed >= 2) break;
            if (m.designed() || m.voiceDesc == null || m.voiceDesc.isEmpty()) continue;
            if (Cast.problem(m.name, m.look, m.voiceDesc) != null) continue;
            progress("Finding " + m.name + "'s voice…");
            try {
                String id = designer.design(m.name, m.voiceDesc, null);
                cast.put(m.withVoice(id));
                spent.add("elevenlabs voices", "designs", 1, StoryPrices.voiceDesignUsd());
                designed++;
                logi("voice designed for a cast member");
            } catch (Exception e) {
                // no slot or no service: Friday's voice in character (ST-D3)
                logw("voice design failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "); Friday in character");
            }
        }
        saveCast();
        book.setCast(cast);
        try { book.save(); } catch (IOException ignored) { }
        cost();
    }

    // ---------------------------------------------------------------- plumbing -----

    private void meterClaude(Engines.Reply r) {
        Prices.Claude c = prices.claude(writer.model());
        spent.add("claude", "pages", 1, c.usd(r.in, r.out, r.cacheRead, r.cacheWrite));
    }

    private void cost() {
        double usd = spent.usd();
        if (!degraded && mode == StoryGate.Mode.FULL && estimate.usd() > 0 && usd > estimate.usd() * OVER) {
            degraded = true;
            progress("It's costing more than I thought; the rest is words and voice.");
        }
        post(() -> { Listener x = listener; if (x != null) x.onCost(usd); });
    }

    private JSONObject extra() {
        try { return new JSONObject().put("meter", spent.meter("usd")).put("pages", book == null ? 0 : book.pageCount()).put("degraded", degraded); }
        catch (JSONException e) { return null; }
    }

    private void fail(String why) {
        Book b = book;
        try {
            if (b != null) { b.setState(Book.STOPPED); b.setProblem(why); b.setSpent(spent); b.save(); }
            if (decisionId != null) receipts.outcome(decisionId, principal, "failed", why, extra());
        } catch (IOException ignored) { }
        post(() -> { Listener x = listener; if (x != null) x.onFailed(why); });
    }

    private void progress(String words) { post(() -> { Listener x = listener; if (x != null) x.onProgress(words); }); }

    private final java.util.function.Consumer<Runnable> poster = r -> r.run();
    private volatile java.util.function.Consumer<Runnable> mainPoster = poster;
    /** Where listener calls go (the screen's main thread); tests leave it inline. */
    public void setPoster(java.util.function.Consumer<Runnable> p) { mainPoster = p == null ? poster : p; }
    private void post(Runnable r) { mainPoster.accept(r); }

    static byte[] join(List<byte[]> parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, at, p.length); at += p.length; }
        return out;
    }

    private Cast loadCast() {
        try {
            if (castFile.isFile()) return Cast.fromJson(new JSONObject(new String(Files.readAllBytes(castFile.toPath()), StandardCharsets.UTF_8)));
        } catch (IOException | JSONException ignored) { }
        return new Cast();
    }

    public synchronized void saveCast() {
        try {
            if (!root.isDirectory() && !root.mkdirs()) return;
            File tmp = new File(root, "cast.tmp");
            Files.write(tmp.toPath(), cast.toJson().toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), castFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) { }
    }

    /** The books on the TV, newest first (from their records; the shelf's seal is the Library's business). */
    public List<Book> books() {
        List<Book> out = new ArrayList<>();
        File[] dirs = new File(root, "books").listFiles(File::isDirectory);
        if (dirs == null) return out;
        for (File d : dirs) try { out.add(Book.load(d)); } catch (IOException ignored) { }
        out.sort((a, b) -> Long.compare(b.created(), a.created()));
        return out;
    }

    public Set<String> soundNames() { return new HashSet<>(SoundPack.names()); }
}
