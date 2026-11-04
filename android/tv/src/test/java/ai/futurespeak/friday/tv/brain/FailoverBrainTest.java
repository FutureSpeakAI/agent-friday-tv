package ai.futurespeak.friday.tv.brain;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.futurespeak.friday.tv.pair.BrainMode;
import ai.futurespeak.friday.tv.pair.BrainMode.Mode;
import ai.futurespeak.friday.tv.pair.KeyGate;

import static org.junit.Assert.*;

/**
 * Who answers, and which keys the TV may use (the rule, 2026-11-02): every key stays on
 * the TV; paired with the PC reachable, the TV uses none of them; PC down or unpaired, the TV
 * is fully standalone, Claude plus every tool.
 */
public class FailoverBrainTest {
    /** The TV's key names (secure/SecureStore), as literals so this test loads no Android class. */
    static final String[] TV_KEYS = {"anthropic", "elevenlabs", "firecrawl", "kie", "openrouter", "gemini"};

    /** A brain that, like the TV's tools and voice, reaches for every key while it answers. */
    static final class KeyHungryBrain implements Brain {
        final String name;
        final List<String> keysGot = Collections.synchronizedList(new ArrayList<>());
        int sends;
        BrainException failFirst;      // fail before saying anything
        boolean speakThenFail;

        KeyHungryBrain(String name) { this.name = name; }

        @Override public String displayName() { return "claude".equals(name) ? "Claude" : name; }
        @Override public boolean ready() { return true; }
        @Override public Turn send(String s, List<Message> h, Listener l) { return send(s, h, null, l); }

        @Override public Turn send(String s, List<Message> h, JSONArray tools, Listener l) {
            sends++;
            for (String k : TV_KEYS) {
                String v = KeyGate.release(k, "sealed-" + k);
                if (v != null) keysGot.add(k);
            }
            if (failFirst != null) { BrainException e = failFirst; failFirst = null; l.onError(e); return () -> {}; }
            l.onText("hello from " + name);
            if (speakThenFail) { l.onError(new BrainException(BrainException.Kind.NETWORK, 0, "cut")); return () -> {}; }
            l.onDone("hello from " + name, "end_turn");
            return () -> {};
        }
    }

    static final class Link implements FailoverBrain.Desktop {
        boolean paired, reachable;
        @Override public boolean paired() { return paired; }
        @Override public boolean reachable() { return reachable; }
    }

    static class Out implements Brain.Listener {
        final java.util.concurrent.CountDownLatch end = new java.util.concurrent.CountDownLatch(1);
        final StringBuilder text = new StringBuilder();
        volatile String done;
        volatile BrainException error;
        @Override public void onText(String d) { text.append(d); }
        @Override public void onDone(String full, String stop) { done = full; end.countDown(); }
        @Override public void onError(BrainException e) { error = e; end.countDown(); }
        Out await() {
            try { assertTrue("the turn ended", end.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return this;
        }
    }

    KeyHungryBrain desktop, claude;
    Link link;
    List<String> switches;
    FailoverBrain brain;

    @Before public void up() {
        BrainMode.set(Mode.UNPAIRED);
        desktop = new KeyHungryBrain("desktop");
        claude = new KeyHungryBrain("claude");
        link = new Link();
        switches = new ArrayList<>();
        brain = new FailoverBrain(desktop, claude, link, (from, to, why) -> switches.add(from + "->" + to + ": " + why));
    }

    @After public void down() { BrainMode.set(Mode.UNPAIRED); }

    Out turn() {
        Out o = new Out();
        brain.send("system", Collections.singletonList(new Message(Message.USER, "hi", 0)), new JSONArray(), o);
        return o.await();
    }

    @Test public void pairedAndReachableTheTvUsesNoneOfItsKeys() {
        link.paired = link.reachable = true;
        Out o = turn();
        assertEquals("hello from desktop", o.done);
        assertEquals(0, claude.sends);
        assertEquals("a TV key was released while the PC was the brain", Collections.emptyList(), desktop.keysGot);
        for (String k : TV_KEYS) assertNull(k, KeyGate.release(k, "sealed"));
        assertEquals("Friday desktop", brain.displayName());
    }

    @Test public void pcDownTheTvIsFullyStandaloneWithEveryTool() {
        link.paired = true;
        link.reachable = false;
        Out o = turn();
        assertEquals("hello from claude", o.done);
        assertEquals(0, desktop.sends);
        assertEquals("a standalone tool lost its key while the PC was down",
                java.util.Arrays.asList(TV_KEYS), claude.keysGot);
        assertEquals("Claude on this TV (PC unreachable)", brain.displayName());
    }

    @Test public void unpairedTheTvWorksAsItAlwaysHas() {
        Out o = turn();
        assertEquals("hello from claude", o.done);
        assertEquals(java.util.Arrays.asList(TV_KEYS), claude.keysGot);
        assertEquals("Claude", brain.displayName());
    }

    @Test public void theDesktopFailingBeforeItSpeaksHandsTheTurnToClaudeOnTheTvAndSaysSo() {
        link.paired = link.reachable = true;
        desktop.failFirst = new BrainException(BrainException.Kind.NETWORK, 0, "gone");
        Out o = turn();
        assertEquals("hello from claude", o.done);
        assertNull(o.error);
        assertEquals("Claude on this TV (PC unreachable)", brain.displayName());
        assertEquals(java.util.Arrays.asList(TV_KEYS), claude.keysGot);    // standalone again, with its tools
        assertTrue(switches.get(switches.size() - 1), switches.get(switches.size() - 1).startsWith("DESKTOP->STANDALONE"));
    }

    @Test public void aDesktopThatAlreadySpokeIsNeverFinishedByAnotherBrain() {
        link.paired = link.reachable = true;
        desktop.speakThenFail = true;
        Out o = turn();
        assertNotNull(o.error);
        assertEquals(0, claude.sends);
    }

    @Test public void aRefusalOfTheRequestIsPassedOnNotRetriedElsewhere() {
        link.paired = link.reachable = true;
        desktop.failFirst = new BrainException(BrainException.Kind.BAD_REQUEST, 400, "no");
        Out o = turn();
        assertEquals(BrainException.Kind.BAD_REQUEST, o.error.kind);
        assertEquals(0, claude.sends);
    }

    @Test public void everySwitchIsReceiptedAndNoTurnSwitchesSilently() {
        link.paired = link.reachable = true;
        turn();
        link.reachable = false;
        turn();
        link.reachable = true;
        turn();
        assertEquals(3, switches.size());                 // UNPAIRED->DESKTOP, ->STANDALONE, ->DESKTOP
        assertTrue(switches.get(1).startsWith("DESKTOP->STANDALONE"));
        assertTrue(switches.get(2).startsWith("STANDALONE->DESKTOP"));
        turn();
        assertEquals("no switch, no receipt", 3, switches.size());
    }

    @Test public void sendReturnsAtOnceWhileTheReachabilityCheckRunsElsewhere() {
        link.paired = true;
        FailoverBrain slow = new FailoverBrain(desktop, claude, new FailoverBrain.Desktop() {
            @Override public boolean paired() { return true; }
            @Override public boolean reachable() {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                return false;
            }
        }, null);
        Out o = new Out();
        long t0 = System.nanoTime();
        slow.send("s", Collections.singletonList(new Message(Message.USER, "hi", 0)), new JSONArray(), o);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue("send blocked the caller for " + ms + " ms", ms < 1000);   // the check takes 1500
        assertEquals("hello from claude", o.await().done);
    }

    @Test public void desktopProposalsStillGoToTheTvsGate() {
        link.paired = link.reachable = true;
        final List<String> gated = new ArrayList<>();
        Brain proposing = new Brain() {
            @Override public String displayName() { return "desktop"; }
            @Override public boolean ready() { return true; }
            @Override public Turn send(String s, List<Message> h, Listener l) { return send(s, h, null, l); }
            @Override public Turn send(String s, List<Message> h, JSONArray t, Listener l) {
                ToolReply r = l.onTool("app.open", new JSONObject());
                l.onDone(r.toModel, "end_turn");
                return () -> {};
            }
        };
        FailoverBrain b = new FailoverBrain(proposing, claude, link, null);
        Out o = new Out() {
            @Override public Brain.ToolReply onTool(String name, JSONObject input) {
                gated.add(name);
                return new Brain.ToolReply("REFUSED by the TV: kids mode.", false);
            }
        };
        b.send("s", Collections.singletonList(new Message(Message.USER, "open netflix", 0)), new JSONArray(), o);
        o.await();
        assertEquals(Collections.singletonList("app.open"), gated);
        assertEquals("REFUSED by the TV: kids mode.", o.done);
    }
}
