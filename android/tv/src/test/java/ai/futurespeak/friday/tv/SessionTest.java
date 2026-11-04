package ai.futurespeak.friday.tv;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import ai.futurespeak.friday.tv.audio.Voice;
import ai.futurespeak.friday.tv.brain.Brain;
import ai.futurespeak.friday.tv.brain.BrainException;
import ai.futurespeak.friday.tv.brain.Message;
import ai.futurespeak.friday.tv.channels.ChannelSpec;
import ai.futurespeak.friday.tv.history.Conversation;

import static org.junit.Assert.*;

public class SessionTest {
    /** A brain whose reply the test plays out by hand. */
    static final class FakeBrain implements Brain {
        Listener l; boolean cancelled; List<Message> sent; String system;
        @Override public String displayName() { return "fake"; }
        @Override public boolean ready() { return true; }
        @Override public Turn send(String sys, List<Message> h, Listener l) {
            this.l = l; this.sent = h; this.system = sys; cancelled = false;
            return () -> cancelled = true;
        }
    }

    static final class FakeVoice implements Voice {
        final List<String> said = new ArrayList<>();
        boolean busy, ended; int stops;
        @Override public String label() { return "fake"; }
        @Override public void speak(String s, int i) { said.add(s); idx.add(i); busy = true; }
        final List<Integer> idx = new ArrayList<>();
        @Override public void endOfReply() { ended = true; }
        @Override public void stop() { stops++; busy = false; }
        @Override public boolean busy() { return busy; }
        @Override public double level() { return 0; }
        @Override public void release() { }
    }

    static final class Ui implements Session.Ui {
        final List<String> states = new ArrayList<>();
        final StringBuilder caption = new StringBuilder();
        final List<List<JSONObject>> cards = new ArrayList<>();
        final List<ChannelSpec> channels = new ArrayList<>();
        final List<String> notices = new ArrayList<>();
        @Override public void state(String s) { states.add(s); }
        @Override public void captionStart() { caption.setLength(0); }
        @Override public void captionAppend(String t) { caption.append(t); }
        @Override public void showCards(List<JSONObject> c) { cards.add(c); }
        @Override public void proposeChannel(ChannelSpec p) { channels.add(p); }
        @Override public void notice(String t) { notices.add(t); }
    }

    private final FakeBrain brain = new FakeBrain();
    private final FakeVoice voice = new FakeVoice();
    private final Conversation convo = new Conversation();
    private final Ui ui = new Ui();
    private int saves = 0;
    private final Session s = new Session(brain, () -> voice, convo, () -> saves++, () -> "SYS", ui, Runnable::run);

    @Test public void aFullTurn() throws Exception {
        s.ask("show me a shopping list");
        assertEquals("SYS", brain.system);
        assertEquals("show me a shopping list", brain.sent.get(brain.sent.size() - 1).text);
        assertEquals("thinking", ui.states.get(ui.states.size() - 1));
        brain.l.onText("Here you go. <card>{\"type\":\"list\",\"title\":\"Shopping\",\"items\":[\"milk\"]}</card>");
        brain.l.onText(" Want more?");
        brain.l.onDone("Here you go. <card>{\"type\":\"list\",\"title\":\"Shopping\",\"items\":[\"milk\"]}</card> Want more?", "end_turn");
        assertEquals(1, ui.cards.size());
        assertEquals("Here you go. Want more?", ui.caption.toString().replaceAll("\\s+", " ").trim());
        assertEquals(2, voice.said.size());
        assertTrue(voice.ended);
        // History keeps a note, not the card JSON.
        String kept = convo.all().get(1).text;
        assertTrue(kept, kept.contains("[showed a card: Shopping]"));
        assertFalse(kept.contains("milk"));
        assertTrue(saves >= 2);
    }

    @Test public void bargeInCancelsTheTurnAndTheVoice() throws Exception {
        s.ask("tell me a long story");
        brain.l.onText("Once upon a time. ");
        s.interrupt();
        assertTrue(brain.cancelled);
        assertTrue(voice.stops > 0);
        assertEquals("idle", ui.states.get(ui.states.size() - 1));
        Brain.Listener old = brain.l;
        old.onText("This arrives late and must be ignored.");
        old.onDone("late", "end_turn");
        assertFalse(ui.caption.toString().contains("late"));
        assertTrue(convo.all().get(1).text.endsWith("[interrupted]"));
    }

    @Test public void errorsAreSpokenKindly() throws Exception {
        s.ask("hi");
        brain.l.onError(new BrainException(BrainException.Kind.NETWORK, 0, "x"));
        assertEquals(1, ui.notices.size());
        assertTrue(voice.said.get(0).contains("internet"));
    }

    @Test public void channelProposalsReachTheUiAndBadOnesBecomeNotices() throws Exception {
        s.ask("make a trivia game");
        brain.l.onText("Done.<channel>{\"title\":\"Trivia\",\"html\":\"<p>q</p>\"}</channel><channel>{\"title\":\"x\",\"html\":\"<p>\",\"network\":[\"10.0.0.1\"]}</channel>");
        brain.l.onDone("", "end_turn");
        assertEquals(1, ui.channels.size());
        assertEquals("Trivia", ui.channels.get(0).title);
        assertEquals(1, ui.notices.size());
    }

    @Test public void voiceOffStillCaptionsAndGoesIdle() throws Exception {
        Session quiet = new Session(brain, () -> null, convo, () -> { }, () -> "SYS", ui, Runnable::run);
        quiet.ask("hi");
        brain.l.onText("Hello.");
        brain.l.onDone("Hello.", "end_turn");
        assertTrue(voice.said.isEmpty());
        assertEquals("idle", ui.states.get(ui.states.size() - 1));
    }

    // ---- Slice S1: the conversation hands proposals to the gate and speaks only the gate's words. ----

    final List<String> acted = new ArrayList<>();
    Session.Actions gate(String say, boolean end) {
        return new Session.Actions() {
            public org.json.JSONArray tools() { return new org.json.JSONArray(); }
            public Session.Act act(String name, JSONObject input) {
                acted.add(name + " " + input);
                return new Session.Act(say, "DONE and verified.", end, "[opened Plex]");
            }
        };
    }

    @Test public void aProposalIsActedOnAndTheGatesWordsAreSpoken() throws Exception {
        s.setActions(gate("Plex is open.", true));
        s.ask("open plex");
        brain.l.onText("Opening it.");
        Brain.ToolReply r = brain.l.onTool("app_open", new JSONObject().put("app", "Plex"));
        assertEquals("DONE and verified.", r.toModel);
        assertTrue(r.endTurn);
        assertEquals(1, acted.size());
        brain.l.onDone("Opening it.", "tool_use");
        assertTrue(ui.caption.toString(), ui.caption.toString().contains("Plex is open."));
        assertTrue(voice.said.contains("Plex is open."));
        String kept = convo.all().get(1).text;
        assertTrue(kept, kept.endsWith("[opened Plex]"));
    }

    @Test public void anInterruptedReplyCanNeverAct() throws Exception {
        s.setActions(gate("Plex is open.", true));
        s.ask("open plex");
        Brain.Listener old = brain.l;
        s.interrupt();                      // Back pressed before the proposal arrived
        Brain.ToolReply r = old.onTool("app_open", new JSONObject().put("app", "Plex"));
        assertTrue(acted.isEmpty());
        assertTrue(r.toModel.startsWith("NOT RUN"));
        assertFalse(voice.said.contains("Plex is open."));
    }

    @Test public void withoutActionsProposalsAreRefused() throws Exception {
        s.ask("open plex");
        Brain.ToolReply r = brain.l.onTool("app_open", new JSONObject().put("app", "Plex"));
        assertTrue(acted.isEmpty());
        assertFalse(r.toModel.startsWith("DONE"));
    }

    @Test public void compactKeepsTitlesOnly() throws Exception {
        String c = Session.compact("Saved it. <channel>{\"title\":\"Recipe board\",\"html\":\"<html>huge</html>\"}</channel>");
        assertEquals("Saved it. [proposed a channel: Recipe board]", c);
    }
}
