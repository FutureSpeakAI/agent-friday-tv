package ai.futurespeak.friday.tv.story;

import static org.junit.Assert.*;

import ai.futurespeak.friday.tv.actions.Images;
import ai.futurespeak.friday.tv.actions.Receipts;
import ai.futurespeak.friday.tv.kids.KidsRules;
import ai.futurespeak.friday.tv.studio.Engines;
import ai.futurespeak.friday.tv.studio.Prices;
import ai.futurespeak.friday.tv.studio.Spent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Storyteller slice 1: the page schema (AC-B1), prefetch and one picture per choice
 * (AC-B2, SB-E05), word timings (AC-B3), resume from a saved book (AC-B4), the brief rules and
 * the style lock (AC-B5, SB-E02), kids mode without an unlock makes no paid call (AC-B7), the
 * sound pack's cache (AC-B8), ducking (AC-B9), the cast's voices (AC-B10, AC-B11), bedtime
 * arithmetic (AC-B12), the story night's seal (AC-B13), and the receipts' meter.
 */
public class StorySlice1Test {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    static final Receipts.Signer SIGNER = h -> Receipts.sha256("test-key:" + h);
    static final Set<String> SOUNDS = SoundPack.names();
    static final Set<String> CAST = new HashSet<>(Arrays.asList("Sparky", "Mo"));

    File root;
    Receipts receipts;

    @Before public void up() {
        root = new File(tmp.getRoot(), "story");
        receipts = new Receipts(tmp.getRoot(), SIGNER, System::currentTimeMillis);
    }

    // ------------------------------------------------------------ fixtures -----

    static JSONObject page(String text, int choices, boolean ending, String sound) throws Exception {
        JSONArray lines = new JSONArray().put(new JSONObject().put("who", "narrator").put("text", text));
        JSONArray ch = new JSONArray();
        for (int i = 0; i < choices; i++) ch.put(new JSONObject().put("label", i == 0 ? "Go to the cave" : "Go to the river").put("hint", "it leads on"));
        return new JSONObject().put("title", "Page").put("lines", lines).put("picture", "Sparky the small green dragon peeks out of a cosy cave at dusk, stars above.")
                .put("sound", sound == null ? JSONObject.NULL : sound).put("choices", ch).put("ending", ending);
    }

    static String start() throws Exception {
        JSONObject o = new JSONObject().put("title", "Sparky and the Dark")
                .put("cast", new JSONArray()
                        .put(new JSONObject().put("name", "Sparky").put("look", "a small round green dragon with tiny wings and big golden eyes").put("voice", "small, bright and a little squeaky"))
                        .put(new JSONObject().put("name", "Mo").put("look", "a plump grey owl with huge round glasses").put("voice", "deep, slow and kind")))
                .put("page", page("Sparky the dragon did not like the dark. Not one bit.", 2, false, "wind"));
        return "```json\n" + o + "\n```";
    }

    /** A writer that answers the start, then pages with two choices every other page, then an ending; counts its calls. */
    static final class FakeWriter implements Engines.Writer {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> users = new ArrayList<>();
        int pageNo = 1;
        boolean neverEnds = false;
        @Override public String model() { return "claude-sonnet-5-5"; }
        @Override public synchronized Engines.Reply write(String system, String user, int maxTokens) throws Exception {
            calls.incrementAndGet();
            users.add(user);
            if (user.startsWith("Start a story")) return new Engines.Reply(start(), 1200, 300, 0, 0);
            if (user.startsWith("Tell the story")) return new Engines.Reply("Sparky was scared. Mo helped. They found the light.", 500, 60, 0, 0);
            pageNo++;
            boolean ending = !neverEnds && user.contains("This is the last page");
            int choices = ending ? 0 : (pageNo % 2 == 0 ? 0 : 2);
            String text = (user.contains("The child chose") ? "They went " + (user.contains("cave") ? "to the cave." : "to the river.") : "The night was soft and quiet.") + " Sparky took a deep breath.";
            JSONObject pg = page(text, choices, ending, null);
            pg.getJSONArray("lines").put(new JSONObject().put("who", "Sparky").put("text", "Here I go!"));
            return new Engines.Reply(pg.toString(), 1500, 300, 0, 0);
        }
    }

    static final class FakeSpeaker implements Seams.Speaker {
        final boolean paid; final AtomicInteger calls = new AtomicInteger(); final List<String> voices = new ArrayList<>();
        FakeSpeaker(boolean paid) { this.paid = paid; }
        @Override public Seams.Audio speak(String text, Cast.Member who) {
            calls.incrementAndGet();
            voices.add(who == null ? "friday" : who.designed() ? who.voiceId : "friday-as-" + who.name);
            byte[] pcm = new byte[22050 * 2 * 2];   // two seconds
            return new Seams.Audio(pcm, WordTiming.proportional(text, 2000), paid ? "elevenlabs" : "tv");
        }
        @Override public String name() { return paid ? "eleven" : "tv"; }
        @Override public boolean paid() { return paid; }
    }

    static final class FakePictures implements Images {
        final AtomicInteger calls = new AtomicInteger(); final List<String> briefs = new ArrayList<>(); volatile boolean ready = true;
        @Override public boolean ready() { return ready; }
        @Override public Result create(String prompt, Progress progress) {
            calls.incrementAndGet(); briefs.add(prompt);
            return new Result(true, null, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0}, "image/jpeg", 8, "nano-banana-2");
        }
        @Override public String service() { return "fake"; }
    }

    static final class Counting implements SoundPack.Fetcher, MusicBeds.Fetcher, Seams.VoiceDesigner {
        final AtomicInteger sounds = new AtomicInteger(), music = new AtomicInteger(), designs = new AtomicInteger();
        @Override public byte[] pcm(String brief, float seconds) { sounds.incrementAndGet(); return new byte[22050 * 2]; }
        @Override public byte[] pcm(String brief, int seconds) { music.incrementAndGet(); return new byte[22050 * 2 * 4]; }
        @Override public String design(String name, String description, String sample) { designs.incrementAndGet(); return "voice-" + name.toLowerCase(); }
    }

    static final class Events implements StoryEngine.Listener {
        final List<Integer> pages = new ArrayList<>(); final List<Integer> pictures = new ArrayList<>(); final List<String> progress = new ArrayList<>();
        volatile Book ended; volatile String failed; volatile String title; volatile double usd;
        CountDownLatch pageLatch = new CountDownLatch(1), endLatch = new CountDownLatch(1);
        @Override public void onTitle(String t, List<Cast.Member> cast) { title = t; }
        @Override public synchronized void onPage(int index, StoryPage page, Seams.Audio audio, File picture, String chosen) { pages.add(index); pageLatch.countDown(); }
        @Override public synchronized void onPicture(int index, File picture) { pictures.add(index); }
        @Override public void onPictureFailed(int index, String why) { }
        @Override public void onProgress(String words) { progress.add(words); }
        @Override public void onEnded(Book book) { ended = book; endLatch.countDown(); }
        @Override public void onFailed(String why) { failed = why; endLatch.countDown(); pageLatch.countDown(); }
        @Override public void onCost(double u) { usd = u; }
        void waitPage() throws Exception { assertTrue("a page", pageLatch.await(10, TimeUnit.SECONDS)); pageLatch = new CountDownLatch(1); }
    }

    static class Rig {
        final FakeWriter writer = new FakeWriter(); final FakeSpeaker friday = new FakeSpeaker(true), tv = new FakeSpeaker(false);
        final FakePictures pictures = new FakePictures(); final Counting counting = new Counting();
        final StoryEngine engine; final Events events = new Events();
        Rig(File root, Receipts receipts) {
            engine = new StoryEngine(root, writer, friday, tv, pictures, new Images.Store(root), new SoundPack(root, counting), new MusicBeds(root, counting),
                    counting, receipts, new Prices(), System::currentTimeMillis);
        }
    }

    static StoryGate.Room familyFull() { return new StoryGate.Room(false, false, false, true, true, true); }
    static StoryGate.Room kidsLocked() { return new StoryGate.Room(true, false, false, true, true, true); }

    // ------------------------------------------------------------ AC-B1: the page is data, whole or nothing -----

    @Test public void aPageWithThreeChoicesOrTooManyWordsOrAPersonInTheBriefIsRejectedWhole() throws Exception {
        JSONObject ok = page("Sparky peeked out.", 2, false, "wind");
        assertEquals(2, StoryPage.parse(ok, CAST, SOUNDS, false, "Sam").choices.size());
        JSONObject three = page("Sparky peeked out.", 2, false, null);
        three.getJSONArray("choices").put(new JSONObject().put("label", "Fly").put("hint", ""));
        assertRejected(three, "exactly two");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 70; i++) many.append("word ");
        assertRejected(page(many.toString().trim(), 0, false, null), "too long");
        JSONObject photo = page("Sparky peeked out.", 0, false, null).put("picture", "a photo of a real boy named Sam in the park");
        assertRejected(photo, "real person");
        JSONObject named = page("Sparky peeked out.", 0, false, null).put("picture", "Sam rides Sparky over the hills");
        assertRejected(named, "names the child");
        JSONObject link = page("Sparky peeked out. See www.example.com", 0, false, null);
        assertRejected(link, "link");
        JSONObject badSound = page("Sparky peeked out.", 0, false, "gunshot");
        assertRejected(badSound, "sound");
        JSONObject stranger = page("Sparky peeked out.", 0, false, null);
        stranger.getJSONArray("lines").put(new JSONObject().put("who", "Rex").put("text", "Hello!"));
        assertRejected(stranger, "cast");
        JSONObject scary = page("Sparky was killed by the dark.", 0, false, null);
        assertRejected(scary, "for a child");
    }

    static void assertRejected(JSONObject o, String why) {
        try { StoryPage.parse(o, CAST, SOUNDS, false, "Sam"); fail("accepted: " + why); }
        catch (StoryPage.Rejected e) { assertTrue(e.getMessage() + " should mention " + why, e.getMessage().toLowerCase().contains(why.toLowerCase())); }
    }

    @Test public void theOlderBandAllowsMoreWordsAndTheEndingHasNoChoices() throws Exception {
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < 80; i++) t.append("word ");
        assertEquals(80, StoryPage.parse(page(t.toString().trim(), 0, false, null), CAST, SOUNDS, true, "").words());
        assertRejected(page("Done.", 2, true, null), "ending");
    }

    // ------------------------------------------------------------ AC-B5 / SB-E02: briefs -----

    @Test public void everyPictureRequestCarriesTheStyleLockAndTheCastsLooksAndNeverTheChildsName() throws Exception {
        Rig rig = new Rig(root, receipts);
        rig.engine.prepare(familyFull(), false, 0, 0, true);
        StoryEngine.Prepared p = rig.engine.prepare(familyFull(), false, 0, 0, true);
        rig.engine.start("a dragon who's scared of the dark", p, KidsRules.Band.YOUNG, "Sam", false, rig.events);
        rig.events.waitPage();
        waitFor(() -> rig.pictures.calls.get() >= 1);
        String brief = rig.pictures.briefs.get(0);
        assertTrue(brief.endsWith(StorySafety.styleLock(false)));
        assertTrue("the cast's look is in the brief", brief.contains("Sparky always looks like this: a small round green dragon"));
        assertFalse(brief.contains("Sam"));
        assertNull(StorySafety.briefProblem("a small drawn child in yellow boots hugs Sparky", "Sam"));
        assertNotNull(StorySafety.briefProblem("Sam hugs Sparky", "Sam"));
        assertNotNull(StorySafety.briefProblem("a photorealistic dragon", ""));
        rig.engine.release();
    }

    // ------------------------------------------------------------ AC-B2 / SB-E05: ahead, and one picture per page -----

    @Test public void bothBranchesAreWrittenAheadButOnlyTheChosenOneGetsAPicture() throws Exception {
        Rig rig = new Rig(root, receipts);
        StoryEngine.Prepared p = rig.engine.prepare(familyFull(), false, 0, 0, true);
        rig.engine.start("a dragon", p, KidsRules.Band.YOUNG, "", false, rig.events);
        rig.events.waitPage();               // page 1 (with two choices)
        waitFor(() -> rig.writer.calls.get() >= 3);   // the start + both branches ahead
        int picturesAfterPage1 = rig.pictures.calls.get();
        assertEquals("one picture for page 1, none ahead of the choice", 1, picturesAfterPage1);
        int writerBefore = rig.writer.calls.get();
        rig.engine.choose(0);
        rig.events.waitPage();               // page 2, from the branch written ahead
        waitFor(() -> rig.pictures.calls.get() >= 2);
        assertEquals("exactly one picture per page", 2, rig.pictures.calls.get());
        assertTrue("page 2's text came from the branch made ahead", rig.writer.users.stream().anyMatch(u -> u.contains("The child chose: \"Go to the cave\"")));
        assertTrue(rig.writer.users.stream().anyMatch(u -> u.contains("The child chose: \"Go to the river\"")));
        assertTrue("page 2 only wrote page 3 ahead", rig.writer.calls.get() - writerBefore <= 1);
        rig.engine.release();
    }

    @Test public void prefetchRulesAreFixed() throws Exception {
        StoryPage two = StoryPage.parse(page("Hi.", 2, false, null), CAST, SOUNDS, false, "");
        StoryPage none = StoryPage.parse(page("Hi.", 0, false, null), CAST, SOUNDS, false, "");
        StoryPage end = StoryPage.parse(page("Bye.", 0, true, null), CAST, SOUNDS, false, "");
        assertEquals(2, Prefetch.textsAhead(two));
        assertEquals(1, Prefetch.textsAhead(none));
        assertEquals(0, Prefetch.textsAhead(end));
        assertEquals(0, Prefetch.picturesAhead(two));
        assertEquals(1, Prefetch.picturesAfterChoice());
        assertEquals(Prefetch.PictureState.WAITING, Prefetch.pictureState(5_000, false, false));
        assertEquals(Prefetch.PictureState.SHADOW, Prefetch.pictureState(20_000, false, false));
        assertEquals(Prefetch.PictureState.GIVEN_UP, Prefetch.pictureState(45_000, false, false));
        assertEquals(Prefetch.PictureState.READY, Prefetch.pictureState(30_000, true, false));
    }

    // ------------------------------------------------------------ AC-B7: kids mode without an unlock -----

    @Test public void kidsModeWithNoUnlockMakesNoPictureSoundMusicOrVoiceCall() throws Exception {
        Rig rig = new Rig(root, receipts);
        StoryEngine.Prepared p = rig.engine.prepare(kidsLocked(), false, 0, 0, true);
        assertEquals(StoryGate.Mode.WORDS, p.mode);
        assertTrue(p.note.contains("grown-up"));
        rig.engine.start("a dragon", p, KidsRules.Band.YOUNG, "", true, rig.events);
        rig.events.waitPage();
        rig.engine.choose(1);
        rig.events.waitPage();
        assertEquals(0, rig.pictures.calls.get());
        assertEquals(0, rig.counting.sounds.get());
        assertEquals(0, rig.counting.music.get());
        assertEquals(0, rig.counting.designs.get());
        assertTrue("every line in Friday's own voice", rig.friday.voices.stream().allMatch("friday"::equals));
        String log = new String(java.nio.file.Files.readAllBytes(new File(tmp.getRoot(), "receipts/actions.jsonl").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("the decision is receipted under the kids principal", log.contains("\"principal\":\"kids\"") && log.contains("story.make"));
        assertEquals("story.make", receipts.recent(5).get(0).decision.optString("action"));
        rig.engine.release();
    }

    @Test public void theGateHoldsTheCeiling() {
        assertEquals(StoryGate.Mode.WORDS, StoryGate.allowed(kidsLocked()));
        assertEquals(StoryGate.Mode.FULL, StoryGate.allowed(new StoryGate.Room(true, false, true, true, true, true)));
        assertEquals(StoryGate.Mode.FULL, StoryGate.allowed(new StoryGate.Room(true, true, false, true, true, true)));
        assertEquals(StoryGate.Mode.WORDS, StoryGate.allowed(new StoryGate.Room(false, true, true, true, false, true)));
        assertNull(StoryGate.allowed(new StoryGate.Room(false, true, true, false, true, true)));
        assertEquals(KidsRules.ASK_GROWN_UP, StoryGate.refuse("can I have more TV time", true));
        assertNotNull(StoryGate.refuse("make it look like me with my photo", false));
        assertNull(StoryGate.refuse("the dragon finds a cat", true));
    }

    // ------------------------------------------------------------ AC-B10 / AC-B11: the cast's voices -----

    @Test public void castLinesUseTheirDesignedVoiceAndFridayInCharacterWithoutOne() throws Exception {
        Rig rig = new Rig(root, receipts);
        StoryEngine.Prepared p = rig.engine.prepare(familyFull(), false, 0, 0, true);
        rig.engine.start("a dragon", p, KidsRules.Band.YOUNG, "", false, rig.events);
        rig.events.waitPage();
        assertEquals("two cast members designed", 2, rig.counting.designs.get());
        assertEquals("voice-sparky", rig.engine.cast().find("Sparky").voiceId);
        rig.engine.choose(0);
        rig.events.waitPage();
        assertTrue("Sparky's line on page 2 is in Sparky's designed voice", rig.friday.voices.contains("voice-sparky"));
        // a page where Mo speaks
        rig.engine.cast().put(rig.engine.cast().find("Mo").withVoice(""));   // Mo loses the slot: Friday in character
        StoryPage spoken = StoryPage.parse(new JSONObject().put("lines", new JSONArray()
                .put(new JSONObject().put("who", "narrator").put("text", "Mo blinked."))
                .put(new JSONObject().put("who", "Sparky").put("text", "Are you awake?"))
                .put(new JSONObject().put("who", "Mo").put("text", "Always, little one."))).put("picture", "Mo the owl on a branch.").put("choices", new JSONArray()),
                rig.engine.cast().names(), SOUNDS, false, "");
        assertEquals("Sparky", spoken.lines.get(1).who);
        assertEquals("Mo", spoken.lines.get(2).who);
        assertNotNull(Cast.problem("Sam", "a boy who looks like my brother", "deep"));
        assertNotNull(Cast.problem("Rex", "a red fox", "sounds like Morgan Freeman"));
        assertNull(Cast.problem("Rex", "a red fox with a white tail tip", "quick and bright"));
        float[] deep = Cast.styleFor("deep, slow and kind");
        assertTrue(deep[0] < 1f && deep[1] > 0.5f);
        rig.engine.release();
    }

    // ------------------------------------------------------------ AC-B8: sounds come from the cache -----

    @Test public void theSoundPackIsMadeOnceAndThenNeverFetched() throws Exception {
        Counting c = new Counting();
        SoundPack pack = new SoundPack(root, c);
        assertEquals(SoundPack.all().size(), pack.missing().size());
        assertNull(pack.cachedPcm("roar"));
        pack.make("roar");
        assertEquals(1, c.sounds.get());
        assertNotNull(pack.cachedPcm("roar"));
        pack.make("roar");
        assertEquals("a cached sound is never fetched again", 1, c.sounds.get());
        SoundPack again = new SoundPack(root, c);
        assertTrue(again.cached("roar"));
        try { pack.make("gunshot"); fail(); } catch (Exception expected) { }
        assertTrue(SoundPack.totalSeconds() > 20 && SoundPack.totalSeconds() < 40);
    }

    @Test public void theBedDucksToAQuarterWhileALinePlaysAndComesBackWithin300ms() {
        long start = 10_000, end = 14_000;
        assertEquals(1f, Ducking.gain(9_000, start, end), 0.001);
        assertTrue(Ducking.db(Ducking.gain(start + 300, start, end)) <= -12.0);
        assertTrue(Ducking.db(Ducking.gain(start + 2000, start, -1)) <= -12.0);
        assertEquals(1f, Ducking.gain(end + 300, start, end), 0.001);
        assertTrue(Ducking.gain(end + 150, start, end) > 0.25f && Ducking.gain(end + 150, start, end) < 1f);
        assertNotNull(MusicBeds.brief(MusicBeds.BEDTIME));
        assertTrue(MusicBeds.trimToLoop(new byte[22050 * 2]).length == 22050 * 2);
    }

    // ------------------------------------------------------------ AC-B3: read-along timing -----

    @Test public void wordTimingsFollowTheAlignmentAndFallBackInProportion() throws Exception {
        String text = "Sparky took a breath.";
        JSONArray chars = new JSONArray(), st = new JSONArray(), en = new JSONArray();
        double t = 0;
        for (char ch : text.toCharArray()) { chars.put(String.valueOf(ch)); st.put(t); en.put(t + 0.05); t += 0.05; }
        JSONObject alignment = new JSONObject().put("characters", chars).put("character_start_times_seconds", st).put("character_end_times_seconds", en);
        List<WordTiming.Word> w = WordTiming.fromAlignment(text, alignment, 1100);
        assertEquals(4, w.size());
        assertEquals(0, w.get(0).fromMs);
        assertEquals("took".length() + 0, w.get(1).end - w.get(1).start);
        assertEquals(1, WordTiming.wordAt(w, w.get(1).fromMs + 10));
        List<WordTiming.Word> prop = WordTiming.proportional(text, 2000);
        assertEquals(4, prop.size());
        assertEquals(2000, prop.get(3).toMs);
        assertTrue(Math.abs(prop.get(0).toMs - 2000 * 7 / 22) < 200);
        List<WordTiming.Word> bad = WordTiming.fromAlignment(text, new JSONObject().put("characters", new JSONArray().put("x")).put("character_start_times_seconds", new JSONArray().put(0)).put("character_end_times_seconds", new JSONArray().put(1)), 2000);
        assertEquals(4, bad.size());
        assertEquals(3, WordTiming.fromJson(WordTiming.toJson(prop)).get(3).start == prop.get(3).start ? 3 : 0);
    }

    // ------------------------------------------------------------ AC-B4: every page is saved; a book reopens -----

    @Test public void everyPageIsSavedAsItIsMadeAndTheBookReopensWithItsPages() throws Exception {
        Rig rig = new Rig(root, receipts);
        StoryEngine.Prepared p = rig.engine.prepare(familyFull(), false, 0, 0, true);
        rig.engine.start("a dragon", p, KidsRules.Band.YOUNG, "", false, rig.events);
        rig.events.waitPage();
        rig.engine.choose(0);
        rig.events.waitPage();
        Book b = rig.engine.book();
        assertEquals(2, b.pageCount());
        assertTrue(b.audioFile(0).isFile() && b.audioFile(1).isFile());
        rig.engine.release();   // the TV "sleeps"
        Book again = Book.load(b.dir);
        assertEquals(2, again.pageCount());
        assertEquals("Sparky and the Dark", again.title());
        assertEquals("Go to the cave", again.pages().get(1).chosen);
        assertEquals(Book.MAKING, again.state());
        assertTrue(again.spent().usd() > 0);
        Shelf shelf = new Shelf(root, SIGNER);
        shelf.put(again);
        assertEquals(1, shelf.items().size());
        assertEquals(2, shelf.items().get(0).pages);
    }

    @Test public void theShelfSealIsCheckedAndATamperedIndexReadsEmpty() throws Exception {
        Shelf shelf = new Shelf(root, SIGNER);
        Book b = Book.create(root, "s1", "a fox", "words", "YOUNG", "", false, 8, "d1", "kids");
        b.setTitle("The Fox"); b.setState(Book.DONE); b.save();
        shelf.put(b);
        assertEquals("The Fox", shelf.items().get(0).title);
        File index = new File(root, "index.json");
        String body = new String(java.nio.file.Files.readAllBytes(index.toPath()));
        java.nio.file.Files.write(index.toPath(), body.replace("The Fox", "The Wolf").getBytes());
        assertTrue("tampered: empty", shelf.items().isEmpty());
    }

    // ------------------------------------------------------------ the story ends, with its receipts -----

    @Test public void aStoryEndsWithinItsPageCountAndTheReceiptsCarryTheEstimateAndTheActual() throws Exception {
        Rig rig = new Rig(root, receipts);
        rig.writer.neverEnds = true;   // the model never writes an ending: the TV holds the length
        StoryEngine.Prepared p = rig.engine.prepare(familyFull(), true, 0, 0, true);
        assertEquals(Bedtime.MIN_PAGES, p.pages);   // bedtime with no bedtime time known: the shortest story
        rig.engine.start("an owl", p, KidsRules.Band.YOUNG, "", false, rig.events);
        rig.events.waitPage();
        rig.engine.choose(0);
        assertTrue(rig.events.endLatch.await(10, TimeUnit.SECONDS));
        assertNotNull(rig.events.ended);
        assertEquals(Book.DONE, rig.events.ended.state());
        assertEquals(2, rig.events.ended.pageCount());
        assertTrue(rig.events.ended.storyPages().get(1).ending);
        assertFalse(rig.events.ended.recap().isEmpty());
        List<Receipts.Entry> rows = receipts.recent(5);
        Receipts.Entry e = rows.get(0);
        assertEquals("story.make", e.decision.optString("action"));
        assertTrue(e.decision.optJSONObject("meter").optDouble("usd_est") > 0);
        assertEquals("executed", e.outcome.optString("result"));
        JSONObject meter = e.outcome.optJSONObject("meter");
        assertTrue(meter.optDouble("usd") > 0);
        Spent actual = Spent.fromMeter(meter, "usd");
        assertNotNull(actual.line("claude"));
        assertNotNull(actual.line("elevenlabs"));
        assertNotNull(actual.line("kie.ai"));
        rig.engine.release();
    }

    @Test public void theEstimateCountsTheOneOffsOnlyWhenTheyAreMissing() throws Exception {
        Rig rig = new Rig(root, receipts);
        StoryEngine.Prepared p = rig.engine.prepare(familyFull(), false, 0, 0, true);
        assertEquals(StoryGate.Mode.FULL, p.mode);
        assertNotNull(p.estimate.line("elevenlabs sounds"));
        assertNotNull(p.estimate.line("elevenlabs music"));
        assertTrue(p.estimate.usd() > 0.5 && p.estimate.usd() < 3);
        assertFalse(p.oneOffs.isEmpty());
        for (SoundPack.Sound s : SoundPack.all()) rig.engine.sounds().make(s.name);
        rig.engine.beds().make(MusicBeds.ADVENTURE); rig.engine.beds().make(MusicBeds.BEDTIME);
        StoryEngine.Prepared q = rig.engine.prepare(familyFull(), false, 0, 0, true);
        assertNull(q.estimate.line("elevenlabs sounds"));
        assertNull(q.estimate.line("elevenlabs music"));
        StoryEngine.Prepared w = rig.engine.prepare(new StoryGate.Room(false, false, false, true, false, false), false, 0, 0, false);
        assertEquals(StoryGate.Mode.WORDS, w.mode);
        assertNull(w.estimate.line("kie.ai"));
        assertNull(w.estimate.line("elevenlabs"));
        rig.engine.release();
    }

    // ------------------------------------------------------------ AC-B12: bedtime arithmetic -----

    @Test public void bedtimeFitsThePagesBeforeTheTvSaysGoodnightAndNeverStartsAfter() {
        long now = 1_000_000L;
        assertEquals(Bedtime.NORMAL_PAGES, Bedtime.pagesFor(now, now + 60 * 60_000, false));
        assertEquals(Bedtime.BEDTIME_PAGES, Bedtime.pagesFor(now, now + 60 * 60_000, true));
        assertEquals(4, Bedtime.pagesFor(now, now + 4 * Bedtime.PAGE_MS + 10, true));   // T−6 minutes fits four pages (spec AC-B12)
        assertEquals(Bedtime.MIN_PAGES, Bedtime.pagesFor(now, now + 30_000, true));
        assertTrue(Bedtime.tooLate(now, now - 1));
        assertFalse(Bedtime.tooLate(now, 0));
        assertTrue(Bedtime.windingDown(now, now - 1) && !Bedtime.windingDown(now, now + 1) && !Bedtime.windingDown(now, 0));
        assertEquals(0f, Bedtime.dim(0, 6, true), 0.001);
        assertEquals(1f, Bedtime.dim(5, 6, true), 0.001);
        assertEquals(0f, Bedtime.dim(5, 6, false), 0.001);
        assertTrue(Bedtime.steer(1, true).contains("Goodnight"));
        assertTrue(Bedtime.steer(4, true).contains("Bedtime mode"));
        assertFalse(Bedtime.steer(4, false).contains("Bedtime mode"));
    }

    // ------------------------------------------------------------ AC-B13: the story night's seal; no voice makes one -----

    @Test public void aStoryNightIsSealedExpiresAndCannotBeForged() throws Exception {
        NightGrant g = new NightGrant(root, SIGNER);
        long now = 5_000_000L;
        assertFalse(g.open(now));
        g.make(now, 12, 0.9);
        assertTrue(g.open(now + 1));
        assertFalse(g.open(now + 13 * 3600_000L));
        File json = new File(root, "night.json");
        String body = new String(java.nio.file.Files.readAllBytes(json.toPath()));
        java.nio.file.Files.write(json.toPath(), body.replace("\"hours\":12", "\"hours\":480").replaceAll("\"until\":[0-9]+", "\"until\":" + (now + 20L * 24 * 3600_000)).getBytes());
        assertFalse("a forged grant reads as none", g.open(now + 1));
        assertFalse("even inside the sanity window", g.open(now + 15L * 24 * 3600_000));
        g.make(now, 24 * 7, 0.9);
        assertTrue(g.open(now + 6L * 24 * 3600_000));
        g.revoke();
        assertFalse(g.open(now + 1));
        assertTrue(NightGrant.words(now + 3 * 3600_000L, now).contains("3 hours"));
        // no phrase makes a grant: the only path is the screen (a static walk of this package)
        for (File f : storySources()) {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            if (!f.getName().equals("StoryActivity.java")) assertFalse(f.getName() + " makes a grant", s.contains("night().make(") || s.contains(".make(now, hours"));
        }
    }

    // ------------------------------------------------------------ guards: hosts and no publish -----

    @Test public void theStorytellerReachesOnlyElevenLabsAndKieAndNeverPublishes() throws Exception {
        for (File f : storySources()) {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            for (String host : hosts(s)) assertTrue(f.getName() + " reaches " + host, host.equals("api.elevenlabs.io") || host.equals("api.anthropic.com"));
            assertFalse(f.getName(), s.contains("youtube.com") || s.contains("googleapis") || s.contains("upload(") || s.contains("publish("));
            assertFalse(f.getName() + " records audio", s.contains("AudioRecord"));
        }
    }

    static File[] storySources() {
        for (String p : new String[]{"src/main/java/ai/futurespeak/friday/tv/story", "app/src/main/java/ai/futurespeak/friday/tv/story"}) {
            File d = new File(p);
            if (d.isDirectory()) return d.listFiles((x, n) -> n.endsWith(".java"));
        }
        throw new AssertionError("story sources not found from " + new File(".").getAbsolutePath());
    }

    static List<String> hosts(String s) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https://([a-z0-9.-]+)").matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    static void waitFor(java.util.function.BooleanSupplier cond) throws Exception {
        long until = System.currentTimeMillis() + 8000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(20);
        assertTrue("timed out", cond.getAsBoolean());
    }
}
