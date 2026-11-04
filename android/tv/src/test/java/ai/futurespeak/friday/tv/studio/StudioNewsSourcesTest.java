package ai.futurespeak.friday.tv.studio;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.futurespeak.friday.tv.actions.ActionGate;
import ai.futurespeak.friday.tv.actions.Catalogue;
import ai.futurespeak.friday.tv.actions.Receipts;
import ai.futurespeak.friday.tv.actions.Web;

import static org.junit.Assert.*;

/**
 * Seen in testing, 2026-11-02: a podcast from a News story said "there isn't enough text on that page".
 * A News story must never dead-end on its feed blurb: the full article, else other outlets, and a
 * length that fits what was found, said plainly on the card.
 */
public class StudioNewsSourcesTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final String BLURB = "The Supreme Court on Monday turned down a request from the administration to intervene in a California ballot case.";
    static final String PARA = "The justices declined, without noted dissent, to pause a lower-court order about how mail ballots are counted in "
            + "California. State officials said the order changes nothing for voters this year. Lawyers for the administration had argued "
            + "that the counting rules conflict with federal law, an argument two lower courts rejected. ";

    static String article(int chars) { StringBuilder b = new StringBuilder(); while (b.length() < chars) b.append(PARA); return b.toString(); }

    Receipts receipts;
    final List<String> reads = new ArrayList<>(), searches = new ArrayList<>();
    final Map<String, String> pages = new HashMap<>();
    String searchText = "";

    final Web web = new Web() {
        public boolean ready() { return true; }
        public Result search(String q) { searches.add(q); return new Result(true, null, "", "", searchText, 2); }
        public Result read(String url) {
            reads.add(url);
            String t = pages.get(url);
            return t == null ? Result.fail("page answered 404") : new Result(true, null, "A story", url, t, 1);
        }
    };

    final class Port implements StudioPort {
        List<StudioSource> sources; String builtFrom; StudioRequest request; int[] offered; int pick = 2; boolean asked;
        public boolean ready() { return true; }
        public int pickLength(StudioRequest r, int[] lengths, String note) { offered = lengths; return pick; }
        public Prepared prepare(StudioRequest r, List<StudioSource> src, Spent before, String note) {
            sources = src; builtFrom = note; request = r;
            Storyboard plan = Storyboard.parse("{\"title\":\"Ballot case\",\"chapters\":[" + chapters(Planner.chaptersFor(r.lengthMin)) + "]}");
            int chars = 0;
            for (StudioSource s : src) chars += s.text.length();
            return new Prepared(r, src, plan, null, Estimate.podcast(r, chars, plan.chapters.size(), "claude-opus-5-5", true, new Prices()),
                    before, false, null, "Friday's voice (ElevenLabs)", note);
        }
        public boolean approve(Prepared p) { asked = true; return true; }
        public void start(Prepared p, String id, String who) { }
    }

    static String chapters(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(i == 0 ? "" : ",").append("{\"title\":\"Part ").append(i + 1).append("\",\"summary\":\"s\",\"sources\":[0]}");
        return b.toString();
    }

    @Before public void up() {
        receipts = new Receipts(tmp.getRoot(), h -> Receipts.sha256("k:" + h), System::currentTimeMillis);
    }

    ActionGate.Verdict podcastFromNews(StudioSource story, Port port) throws Exception {
        StudioSource.hold("news:0a1b2c3d4e5f6a7b", story);
        return new ActionGate(new Catalogue(new ArrayList<>()), null, receipts, new ActionGate.Room() {
            public boolean kids() { return false; } public boolean grownUp() { return false; } }, ms -> { }, web, new ActionGate.Turn())
                .withStudio(port).decide("studio.make", new JSONObject().put("kind", "podcast").put("source_ref", "news:0a1b2c3d4e5f6a7b"), "remote");
    }

    static StudioSource story(String text, StudioSource... takes) {
        return new StudioSource("news", "Supreme Court Rejects Request in California Ballot Case", "The New York Times",
                "https://www.nytimes.com/2026/11/02/us/ballot-case.html", text, Arrays.asList(takes));
    }

    static StudioSource take(String outlet, String url) { return new StudioSource("news", "Court declines ballot case", outlet, url, "short summary"); }

    // ---------------------------------------------------------- the reported case -----

    /** The bug: a blurb-only story was refused. Now the full article is fetched through Firecrawl and used. */
    @Test public void aBlurbOnlyStoryFetchesTheFullArticle() throws Exception {
        pages.put("https://www.nytimes.com/2026/11/02/us/ballot-case.html", article(9000));
        Port port = new Port();
        port.pick = 10;
        ActionGate.Verdict v = podcastFromNews(story(BLURB), port);
        assertEquals("executed", v.result);
        assertEquals(Collections.singletonList("https://www.nytimes.com/2026/11/02/us/ballot-case.html"), reads);
        assertTrue("the article, not the blurb", port.sources.get(0).text.length() >= 9000);
        assertArrayEquals(new int[]{2, 5, 10}, port.offered);
        assertEquals("The full article from The New York Times.", port.builtFrom);
        JSONObject m = receipts.recent(1).get(0).decision.getJSONObject("meter");
        assertTrue("the read is receipted in the job's meter", m.toString().contains("\"service\":\"firecrawl\""));
    }

    /** Paywalled: the story's own page is a subscribe prompt, so 2 to 4 other outlets (News's other takes) are read and cited. */
    @Test public void aPaywalledStoryIsBuiltFromOtherOutletsAndSaysSo() throws Exception {
        pages.put("https://www.nytimes.com/2026/11/02/us/ballot-case.html", "Subscribe to continue reading. Already a subscriber? Log in.");
        pages.put("https://apnews.com/a", article(3000));
        pages.put("https://www.npr.org/b", article(2500));
        pages.put("https://www.reuters.com/c", article(2800));
        Port port = new Port();
        port.pick = 5;
        assertEquals("executed", podcastFromNews(story(BLURB, take("AP", "https://apnews.com/a"), take("NPR", "https://www.npr.org/b"),
                take("Reuters", "https://www.reuters.com/c"), take("NYT again", "https://nytimes.com/other")), port).result);
        assertEquals("the story first, then three others; never the same outlet twice", 4, port.sources.size());
        assertEquals(BLURB, port.sources.get(0).text);
        assertTrue(port.builtFrom, port.builtFrom.startsWith("The New York Times only shares a summary (the full story is behind a paywall or too short), so it's built from that and 3 other outlets covering the same story: AP, NPR and Reuters."));
        assertTrue("no search was needed", searches.isEmpty());
        assertFalse(reads.contains("https://nytimes.com/other"));
        assertArrayEquals(new int[]{2, 5, 10}, port.offered);
    }

    @Test public void withNoOtherTakesItSearchesForCoverage() throws Exception {
        searchText = "1. Court declines ballot case\n   https://apnews.com/x\n   desc\n2. Same story\n   https://www.nytimes.com/dup\n   desc\n"
                + "3. Justices turn away case\n   https://www.npr.org/y\n   desc\n";
        pages.put("https://apnews.com/x", article(2500));
        pages.put("https://www.npr.org/y", article(2500));
        Port port = new Port();
        port.pick = 5;
        assertEquals("executed", podcastFromNews(story(BLURB), port).result);
        assertEquals(Collections.singletonList("Supreme Court Rejects Request in California Ballot Case"), searches);
        assertEquals(3, port.sources.size());
        assertEquals("apnews.com", port.sources.get(1).outlet);
        assertArrayEquals(new int[]{2, 5}, port.offered);
        Spent.Line fc = Spent.fromMeter(receipts.recent(1).get(0).decision.getJSONObject("meter"), "usd_est").line("firecrawl");
        assertEquals("one search (2) and two reads; the paper's own page failed, unbilled", 4, fc.qty, 0.01);
    }

    /** Nothing more to be had: still never a refusal. A 2-minute brief from the headline and summary, said plainly. */
    @Test public void withNothingElseItIsAShortBriefNeverARefusal() throws Exception {
        Port port = new Port();
        ActionGate.Verdict v = podcastFromNews(story(BLURB), port);
        assertEquals("executed", v.result);
        assertArrayEquals(new int[]{2}, port.offered);
        assertEquals(2, port.request.lengthMin);
        assertTrue(port.builtFrom, port.builtFrom.contains("headline and summary") && port.builtFrom.contains("short brief"));
    }

    /** A said length longer than the material is fitted by the TV (not the plan), and the card and receipt say so. */
    @Test public void aSaidLengthIsFittedToTheMaterialAndSaidPlainly() throws Exception {
        pages.put("https://example.com/ferry", article(4000));
        Port port = new Port();
        ActionGate.Verdict v = new ActionGate(new Catalogue(new ArrayList<>()), null, receipts, new ActionGate.Room() {
            public boolean kids() { return false; } public boolean grownUp() { return false; } }, ms -> { }, web, new ActionGate.Turn())
                .withStudio(port).decide("studio_make", new JSONObject().put("url", "https://example.com/ferry").put("length_min", 10), "voice");
        assertEquals("executed", v.result);
        assertEquals(5, port.request.lengthMin);
        assertNull("not asked: the person said a length", port.offered);
        assertTrue(port.builtFrom, port.builtFrom.endsWith("You asked for 10 minutes; there's enough for 5 minutes."));
        JSONObject args = receipts.recent(1).get(0).decision.getJSONObject("args");
        assertEquals(5, args.getInt("length_min"));
        assertEquals(10, args.getInt("requested_length"));
    }

    @Test public void aFullStoryOnTheTvCostsNoReads() throws Exception {
        Port port = new Port();
        port.pick = 5;
        assertEquals("executed", podcastFromNews(story(article(5000)), port).result);
        assertTrue(reads.isEmpty() && searches.isEmpty());
    }

    // ------------------------------------------------------------- the gatherer -----

    @Test public void lengthsFitTheMaterial() {
        assertArrayEquals(new int[]{2}, Gatherer.lengthsFor(400));
        assertArrayEquals(new int[]{2}, Gatherer.lengthsFor(2999));
        assertArrayEquals(new int[]{2, 5}, Gatherer.lengthsFor(3000));
        assertArrayEquals(new int[]{2, 5, 10}, Gatherer.lengthsFor(8000));
    }

    @Test public void readsAreCappedAndPaywallsAndThinPagesSkipped() {
        List<StudioSource> takes = new ArrayList<>();
        for (int i = 0; i < 10; i++) takes.add(take("Outlet " + i, "https://o" + i + ".com/s"));
        int[] n = {0};
        Gatherer.Result g = Gatherer.gather(story(BLURB, takes.toArray(new StudioSource[0])), new Gatherer.Reader() {
            public Web.Result read(String url) { n[0]++; return new Web.Result(true, null, "t", url, url.contains("o1.") ? "Subscribe to continue reading. " + article(900) : "thin", 1); }
            public Web.Result search(String q) { throw new AssertionError("no search with takes in hand"); }
        }, 0.005);
        assertEquals("the story's own read plus at most six more", 1 + Gatherer.MAX_READS, n[0]);
        assertEquals("nothing usable: just the story", 1, g.sources.size());
        assertTrue(g.note.contains("other coverage couldn't be read"));
        assertEquals(7, g.spent.line("firecrawl").qty, 0.01);
    }

    @Test public void searchResultsParse() {
        List<StudioSource> s = Gatherer.parseSearch("1. One\n   https://a.com/1\n   d\n2. Two\n   https://www.b.org/2?x=1\n   d\n");
        assertEquals(2, s.size());
        assertEquals("b.org", s.get(1).outlet);
        assertEquals("https://www.b.org/2?x=1", s.get(1).url);
        assertTrue(Gatherer.parseSearch("no results").isEmpty());
    }
}
