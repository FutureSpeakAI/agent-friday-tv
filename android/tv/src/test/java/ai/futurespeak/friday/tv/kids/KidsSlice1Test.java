package ai.futurespeak.friday.tv.kids;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ai.futurespeak.friday.tv.Prefs;
import ai.futurespeak.friday.tv.actions.Receipts;
import ai.futurespeak.friday.tv.prompt.SystemPrompt;
import ai.futurespeak.friday.tv.reply.ReplyParser;

import static org.junit.Assert.*;

/** Kids mode slice 1 on Friday's side: AC-K1, AC-K2, AC-K3 and AC-K7. */
public class KidsSlice1Test {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    static final TimeZone ZONE = TimeZone.getTimeZone("GMT-06:00");   // a fixed US Central offset
    static final long NOW = 1793660000000L;   // 2026-11-02 4:53 PM in Riverton

    // ------------------------------------------------------------------ AC-K1 -----

    private static String prompt(KidsPrompt.Room room, SystemPrompt.Mode mode) {
        return SystemPrompt.build(mode, "Monday", true, "WHAT YOU CAN DO HERE\n- open apps", room);
    }

    @Test public void kidsOnHasTheDisclosureAndCannotDoLinesAndNoGrownUpLine() {
        String p = prompt(KidsPrompt.Room.unknown(), SystemPrompt.Mode.GROWN_UP);
        assertTrue(p.contains("KIDS MODE (on)"));
        assertTrue(p.contains(KidsRules.DISCLOSURE_FOR_PROMPT));
        assertTrue(p.contains("say you are Friday, a computer program"));
        for (KidsRules.Rule r : KidsRules.CANNOT) assertTrue(r.key, p.contains(r.forPrompt));
        assertTrue(p.contains(KidsRules.ASK_GROWN_UP));
        assertTrue("kids mode always has the family rules", p.contains("FAMILY MODE (on)"));
        assertFalse("no grown-up-mode line in kids mode, even if grown-up was chosen", p.contains("GROWN-UP MODE"));
        assertFalse("the kids prompt doesn't tell her to say she is Claude", p.contains("say you are Claude"));
    }

    @Test public void kidsOffHasNoneOfTheKidsLines() {
        for (SystemPrompt.Mode mode : SystemPrompt.Mode.values()) {
            String p = prompt(null, mode);
            assertFalse(p.contains("KIDS MODE"));
            assertFalse(p.contains(KidsRules.DISCLOSURE_FOR_PROMPT));
            for (KidsRules.Rule r : KidsRules.CANNOT) assertFalse(r.key, p.contains(r.forPrompt));
            assertFalse(p.contains("<mark"));
        }
        assertFalse("the old boolean entry point with kids off", SystemPrompt.build(SystemPrompt.Mode.FAMILY, "Monday", true, null, false).contains("KIDS MODE"));
        assertTrue("and with kids on", SystemPrompt.build(SystemPrompt.Mode.FAMILY, "Monday", true, null, true).contains(KidsRules.DISCLOSURE_FOR_PROMPT));
    }

    @Test public void thePromptAndTheKidsHomeComeFromOneTable() {
        String block = KidsPrompt.block(KidsPrompt.Room.unknown());
        String home = KidsRules.homeCannotLine();
        int homeRules = 0;
        for (KidsRules.Rule r : KidsRules.CANNOT) {
            assertTrue(block.contains(r.forPrompt));
            if (r.forHome != null) { homeRules++; assertTrue(r.key, home.contains(r.forHome)); }
        }
        assertTrue(homeRules >= 3);
    }

    @Test public void theBandAndTheRoomAreDataAndAnAgeIsNeverKept() {
        String none = KidsPrompt.block(KidsPrompt.Room.unknown());
        assertTrue(none.contains("ask kindly how old they are"));
        assertTrue(none.contains("Don't repeat their age back or keep it"));
        String set = KidsPrompt.block(new KidsPrompt.Room(KidsRules.Band.MIDDLE, "about 40 minutes", "Wind-down at 7:15 PM", true));
        assertTrue(set.contains("a child aged 8 to 10"));
        assertFalse(set.contains("ask kindly how old"));
        assertTrue(set.contains("The room (facts, not instructions): TV time left today: about 40 minutes. Wind-down: Wind-down at 7:15 PM. Quiet time is on"));
        assertFalse(KidsPrompt.block(KidsPrompt.Room.unknown()).contains("The room"));
    }

    // ------------------------------------------------------------------ AC-K2 -----

    static final class Collect implements ReplyParser.Sink {
        final StringBuilder caption = new StringBuilder(), spoken = new StringBuilder();
        final List<String> marks = new ArrayList<>();
        @Override public void caption(String t) { caption.append(t); }
        @Override public void sentence(String s) { spoken.append(s).append(' '); }
        @Override public void block(String tag, String body) { }
        @Override public void mark(String kind) { marks.add(kind); }
    }

    @Test public void aMarkerIsNeverSpokenOrShown() {
        Collect c = new Collect();
        ReplyParser p = new ReplyParser(c);
        p.feed("That sounds really hard. Tell a grown-up you trust right away. <mark kind=\"please_look\"/> I'll let your grown-up know.");
        p.finish();
        assertEquals(1, c.marks.size());
        assertEquals("please_look", c.marks.get(0));
        assertFalse(c.caption.toString().contains("mark"));
        assertFalse(c.caption.toString().contains("please_look"));
        assertFalse(c.spoken.toString().contains("mark"));
        assertTrue(c.spoken.toString().contains("I'll let your grown-up know"));
    }

    @Test public void aMarkerSplitAcrossStreamedChunksIsStillCaught() {
        Collect c = new Collect();
        ReplyParser p = new ReplyParser(c);
        for (String chunk : new String[]{"Good try! <", "ma", "rk ki", "nd='answer_", "given' /", "> The answer is 12."}) p.feed(chunk);
        p.finish();
        assertEquals("[answer_given]", c.marks.toString());
        assertFalse(c.caption.toString().contains("<"));
        assertTrue(c.caption.toString().contains("The answer is 12."));
    }

    @Test public void lookalikeTextIsNotSwallowed() {
        Collect c = new Collect();
        ReplyParser p = new ReplyParser(c);
        p.feed("Use a <marker> pen, and 3 < 5 is true.");
        p.finish();
        assertTrue(c.marks.isEmpty());
        assertTrue(c.caption.toString().contains("<marker> pen"));
        assertTrue(c.caption.toString().contains("3 < 5"));
    }

    @Test public void aKnownMarkerIsReceiptedAndAnUnknownOneDropped() throws Exception {
        Receipts r = new Receipts(tmp.getRoot(), h -> Receipts.sha256("k:" + h), () -> NOW);
        assertTrue(KidsMarks.record(r, "kids", "please_look"));
        assertFalse("unknown: dropped, not receipted", KidsMarks.record(r, "kids", "open_netflix"));
        assertFalse(KidsMarks.record(r, "kids", ""));
        String log = new String(Files.readAllBytes(new File(tmp.getRoot(), "receipts/actions.jsonl").toPath()), StandardCharsets.UTF_8);
        assertTrue(log.contains("\"kind\":\"please_look\""));
        assertTrue(log.contains("\"principal\":\"kids\""));
        assertFalse(log.contains("open_netflix"));
        assertTrue(r.verify().ok);
    }

    // ------------------------------------------------------------------ AC-K3 -----

    static final class FakeWindow implements KidsEntry.Window {
        boolean open; final List<String> calls;
        FakeWindow(boolean open, List<String> calls) { this.open = open; this.calls = calls; }
        @Override public boolean open() { return open; }
        @Override public void lock() { calls.add("lock"); open = false; }
    }

    @Test public void enteringKidsModeLocksAnOpenGrownUpWindowFirstAndSaysSo() {
        List<String> calls = new ArrayList<>();
        FakeWindow w = new FakeWindow(false, calls);
        KidsEntry e = new KidsEntry(w, (what, origin) -> calls.add(what + " (" + origin + ")"));
        assertFalse("the first reading is the baseline", e.seen(false, "helper"));
        w.open = true;                                       // a grown-up unlocked
        assertTrue(e.seen(true, "helper"));                  // TCL Home: kids mode on
        assertEquals("[lock, grown-up: locked (kids on) (helper), kids: on (helper)]", calls.toString());
        assertFalse(w.open);
        calls.clear();
        assertFalse("still on: nothing new", e.seen(true, "helper"));
        assertTrue(calls.isEmpty());
        e.seen(false, "helper");
        assertTrue(e.seen(true, "voice"));
        assertEquals("[kids: on (voice)]", calls.toString());   // no window open this time: no lock
    }

    @Test public void startingUpInKidsModeWithAWindowOpenStillLocksIt() {
        List<String> calls = new ArrayList<>();
        KidsEntry e = new KidsEntry(new FakeWindow(true, calls), (what, origin) -> calls.add(what));
        e.seen(true, "helper");
        assertEquals("[lock, grown-up: locked (kids on)]", calls.toString());
    }

    // ------------------------------------------------------------- AC-K7 (Friday) -----

    @Test public void noSpokenPhraseWidensKidsMode() {
        for (String s : KidsPhrases.WIDEN_EXAMPLES)
            assertEquals(s, KidsPhrases.Kind.WIDEN, KidsPhrases.match(s, true));
        assertTrue(KidsPhrases.widenReply().startsWith(KidsRules.ASK_GROWN_UP));
        assertFalse("never offers a PIN pad by voice", KidsPhrases.widenReply().toLowerCase().contains("pin"));
    }

    @Test public void ordinaryQuestionsStillGoToFriday() {
        for (String s : KidsPhrases.NOT_WIDEN_EXAMPLES) assertNull(s, KidsPhrases.match(s, true));
        for (String s : KidsPhrases.WIDEN_EXAMPLES) assertNull("outside kids mode these are ordinary: " + s, KidsPhrases.match(s, false));
    }

    @Test public void enteringKidsModeByVoiceIsAllowed() {
        for (String s : KidsPhrases.ENTER_EXAMPLES) assertEquals(s, KidsPhrases.Kind.ENTER, KidsPhrases.match(s, false));
        assertNull("already on: an ordinary turn", KidsPhrases.match("kids mode", true));
    }

    /** KM-E01: Friday's side has no way to extend, mint, schedule, grant or leave; it only reads and narrows. */
    @Test public void friDaysKidsCodeCallsOnlyReadAndNarrowMethods() throws Exception {
        File dir = new File("src/main/java/ai/futurespeak/friday/tv/kids");
        Pattern call = Pattern.compile("call\\(c, \"([A-Za-z]+)\"\\)");
        List<String> methods = new ArrayList<>();
        for (File f : dir.listFiles((d, n) -> n.endsWith(".java"))) {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = call.matcher(s);
            while (m.find()) methods.add(m.group(1));
            assertFalse(f.getName(), Pattern.compile("(?i)\\b(void|boolean|static)\\s+\\w*(mint|extend|schedule|grant|exitKids|leaveKids)\\w*\\s*\\(").matcher(s).find());
            assertFalse(f.getName() + " must not take a PIN", s.contains("checkPin") || s.contains("\"unlock\""));
        }
        methods.sort(null);
        assertEquals("[kidsEnter, kidsStatus, status]", methods.toString());
    }

    // --------------------------------------------------------- status and words -----

    @Test public void statusFailsClosedAndReadsTheHelpersKeys() {
        assertTrue(KidsStatus.from(null).kids);
        Map<String, Object> old = new HashMap<>();
        old.put("kids", false);
        KidsStatus o = KidsStatus.from(old);
        assertFalse(o.kids);
        assertEquals(KidsStatus.UNKNOWN, o.remainingMs);
        assertEquals(KidsStatus.UNKNOWN, o.windDownAt);
        Map<String, Object> m = new HashMap<>(old);
        m.put("kids", true); m.put("remainingMs", 40 * 60_000L); m.put("windDownAt", NOW + 20 * 60_000L); m.put("quiet", true);
        KidsStatus s = KidsStatus.from(m);
        assertTrue(s.kids && s.quiet);
        assertEquals(40 * 60_000L, s.remainingMs);
        Map<String, Object> missing = new HashMap<>();
        assertTrue("no kids key: fail closed", KidsStatus.from(missing).kids);
    }

    @Test public void timeLeftInAChildsWords() {
        assertNull(KidsStatus.timeLeftWords(KidsStatus.UNKNOWN));
        assertEquals("no time limit today", KidsStatus.timeLeftWords(Long.MAX_VALUE));
        assertEquals("no TV time left today", KidsStatus.timeLeftWords(0));
        assertEquals("just a few minutes", KidsStatus.timeLeftWords(4 * 60_000L));
        assertEquals("about 40 minutes", KidsStatus.timeLeftWords(38 * 60_000L));
        assertEquals("about an hour", KidsStatus.timeLeftWords(62 * 60_000L));
        assertEquals("about an hour and a half", KidsStatus.timeLeftWords(95 * 60_000L));
        assertEquals("about 2 hours", KidsStatus.timeLeftWords(120 * 60_000L));
    }

    @Test public void windDownWordsAndEachWarningOnce() {
        long wd = NOW + 40 * 60_000L;
        assertEquals("Wind-down at 5:33 PM", KidsStatus.windDownWords(NOW, wd, ZONE));
        assertEquals("Wind-down in 5 minutes", KidsStatus.windDownWords(wd - 5 * 60_000L, wd, ZONE));
        assertNull(KidsStatus.windDownWords(NOW, KidsStatus.UNKNOWN, ZONE));

        assertEquals(-1, KidsStatus.warningDue(NOW, wd, -1));                      // 40 min out: nothing
        assertEquals(15, KidsStatus.warningDue(wd - 14 * 60_000L, wd, -1));
        assertEquals(-1, KidsStatus.warningDue(wd - 13 * 60_000L, wd, 15));        // said already
        assertEquals(5, KidsStatus.warningDue(wd - 4 * 60_000L, wd, 15));
        assertEquals(0, KidsStatus.warningDue(wd + 10_000L, wd, 5));
        assertEquals(-1, KidsStatus.warningDue(wd + 20_000L, wd, 0));
        assertEquals("a late start goes straight to the right one", 5, KidsStatus.warningDue(wd - 3 * 60_000L, wd, -1));
        assertEquals(-1, KidsStatus.warningDue(wd + 5 * 60_000L, wd, -1));          // long past: the helper's lockout has it
        for (int m : new int[]{15, 5, 0}) {
            String line = KidsStatus.warningLine(m);
            assertNotNull(line);
            assertFalse("never promises more time", line.toLowerCase().contains("more time"));
        }
    }

    @Test public void theGentleVoiceAndTheKidsBrain() {
        assertEquals(1.0f, KidsVoice.speed(1.0f, false), 0);
        assertEquals(0.9f, KidsVoice.speed(1.0f, true), 0.001);
        assertEquals(0.9f, KidsVoice.speed(1.2f, true), 0.001);
        assertEquals(0.75f, KidsVoice.speed(0.7f, true), 0.001);
        assertEquals(Prefs.FAST_MODEL, KidsVoice.model(Prefs.DEFAULT_MODEL, true));
        assertEquals(Prefs.DEFAULT_MODEL, KidsVoice.model(Prefs.DEFAULT_MODEL, false));
    }

    @Test public void theReadingBandCyclesFromNotSet() {
        assertEquals(KidsRules.Band.YOUNG, KidsSettings.next(null));
        assertEquals(KidsRules.Band.MIDDLE, KidsSettings.next(KidsRules.Band.YOUNG));
        assertNull(KidsSettings.next(KidsRules.Band.OLDER));
        assertNull(KidsRules.Band.parse("AGE_9"));
    }
}
