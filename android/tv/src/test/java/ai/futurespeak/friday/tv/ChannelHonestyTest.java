package ai.futurespeak.friday.tv;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import ai.futurespeak.friday.tv.channels.NetPolicy;
import ai.futurespeak.friday.tv.reply.ReplyParser;
import ai.futurespeak.friday.tv.safety.GrownUpSession;
import ai.futurespeak.friday.tv.web.RenderVerdict;

/** Failures seen in testing: channels vanishing silently, and the PIN asked again and again. */
public class ChannelHonestyTest {
    static final class Rec implements ReplyParser.Sink {
        final List<String> events = new ArrayList<>();
        public void caption(String t) { }
        public void sentence(String s) { }
        public void block(String tag, String body) { events.add("block:" + tag); }
        public void blockStarted(String tag) { events.add("start:" + tag); }
        public void blockCutOff(String tag, int chars) { events.add("cut:" + tag + ":" + chars); }
    }

    @Test public void cutOffChannelIsReportedNotDropped() {
        Rec r = new Rec();
        ReplyParser p = new ReplyParser(r);
        p.feed("Here it is. <channel>{\"title\":\"News\",\"html\":\"<div>");
        assertEquals("channel", p.openBlock());
        p.finish();   // the reply ended (max_tokens) before </channel>
        assertEquals(List.of("start:channel", "cut:channel:29"), r.events);
    }

    @Test public void completeChannelIsNotCutOff() {
        Rec r = new Rec();
        ReplyParser p = new ReplyParser(r);
        p.feed("<channel>{}</channel> done.");
        p.finish();
        assertEquals(List.of("start:channel", "block:channel"), r.events);
        assertNull(p.openBlock());
    }

    @Test public void cutOffReasonsArePlain() {
        assertTrue(Session.cutOffReason("max_tokens").contains("ran out of room"));
        assertTrue(Session.cutOffReason("incomplete").contains("connection"));
    }

    @Test public void renderVerdict() {
        assertNull(RenderVerdict.problem(500, 0, "ReferenceError: x", null, null));   // shows text: fine despite an error
        assertNull(RenderVerdict.problem(0, 1, null, null, null));                    // a picture or canvas counts
        assertEquals("its page came up blank", RenderVerdict.problem(3, 0, null, null, null));
        assertTrue(RenderVerdict.problem(0, 0, "Uncaught SyntaxError", "x.com", null).contains("script error"));
        assertTrue(RenderVerdict.problem(0, 0, null, "api.rss2json.com", null).contains("api.rss2json.com"));
        assertTrue(RenderVerdict.problem(0, 0, null, null, "the news feed from www.npr.org didn't load").contains("npr.org"));
    }

    @Test public void listingTheFeedProxyItselfIsNotARejection() throws Exception {
        // Claude's real newsreader listed feeds.friday.local in "network"; it was rejected silently.
        ai.futurespeak.friday.tv.channels.ChannelSpec s = ai.futurespeak.friday.tv.channels.ChannelSpec.parseProposal(
                "{\"title\":\"Riverbend News\",\"html\":\"<p>x</p>\",\"network\":[\"feeds.friday.local\",\"www.npr.org\"]}");
        assertEquals(List.of("www.npr.org"), s.requestedHosts);
    }

    @Test public void cspAllowsTheFeedProxyOnlyWithApprovedHosts() {
        String none = NetPolicy.csp(List.of());
        assertTrue(none.contains("connect-src https://voice.friday.local;"));   // Friday's voice on the TV; no internet
        String some = NetPolicy.csp(List.of("www.npr.org"));
        assertTrue(some.contains("connect-src https://voice.friday.local https://feeds.friday.local https://www.npr.org"));
        assertTrue(some.contains("img-src data: blob: https://feeds.friday.local https://www.npr.org"));
    }

    // ---- grown-up session -------------------------------------------------------------
    static final class Mem implements GrownUpSession.Store {
        boolean on; long boot, cap;
        public boolean on() { return on; }
        public long bootWall() { return boot; }
        public long capWall() { return cap; }
        public void put(boolean o, long b, long c) { on = o; boot = b; cap = c; }
    }

    @Test public void unlockSurvivesAppRestartButNotSleepOrReboot() {
        Mem m = new Mem();
        long wall = 1_800_000_000_000L, up = 3_600_000L;
        new GrownUpSession(m).unlock(wall, up);
        // The app process restarts 20 minutes later (a new object over the same stored state):
        GrownUpSession again = new GrownUpSession(m);
        assertTrue(again.unlocked(wall + 20 * 60_000L, up + 20 * 60_000L));
        // Two hours later, still unlocked (the old build asked again after 5 minutes).
        assertTrue(again.unlocked(wall + 2 * 3600_000L, up + 2 * 3600_000L));
        // The TV rebooted: uptime restarted, so the boot time moved.
        assertFalse(again.unlocked(wall + 3 * 3600_000L, 120_000L));
        assertFalse(m.on);
    }

    @Test public void screenOffOrFamilyModeLocks() {
        Mem m = new Mem();
        GrownUpSession s = new GrownUpSession(m);
        s.unlock(1000_000L, 500_000L);
        s.lock();
        assertFalse(s.unlocked(1001_000L, 501_000L));
    }

    @Test public void safetyCap() {
        Mem m = new Mem();
        GrownUpSession s = new GrownUpSession(m);
        s.unlock(0, 0);
        assertTrue(s.unlocked(GrownUpSession.CAP_MS - 1, GrownUpSession.CAP_MS - 1));
        assertFalse(s.unlocked(GrownUpSession.CAP_MS, GrownUpSession.CAP_MS));
    }
}
