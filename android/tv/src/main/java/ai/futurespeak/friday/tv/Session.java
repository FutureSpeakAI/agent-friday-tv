package ai.futurespeak.friday.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ai.futurespeak.friday.tv.audio.Voice;
import ai.futurespeak.friday.tv.brain.Brain;
import ai.futurespeak.friday.tv.brain.BrainException;
import ai.futurespeak.friday.tv.brain.Message;
import ai.futurespeak.friday.tv.cards.CardSpec;
import ai.futurespeak.friday.tv.channels.ChannelSpec;
import ai.futurespeak.friday.tv.history.Conversation;
import ai.futurespeak.friday.tv.reply.ReplyParser;

/**
 * One conversation: question in, streamed reply out (captions, speech a sentence at a time,
 * cards, channel proposals), and barge-in. No Android types, so it is tested on the JVM.
 * Every callback to the UI runs on {@code main}.
 */
public final class Session {
    public interface Ui {
        /** idle | thinking | speaking (listening is the UI's own state). */
        void state(String s);
        void captionStart();
        void captionAppend(String text);
        void showCards(List<JSONObject> cards);
        void proposeChannel(ChannelSpec proposal);
        void notice(String text);
        /** The voice is now saying caption characters [start, end): keep them on screen. */
        default void follow(int start, int end) { }
        /** A channel is being written ({@code chars} so far), or finished/abandoned ({@code -1}). */
        default void building(int chars) { }
        /** A report marker from the reply (kids slice 1): receipted by the UI, never spoken or shown. */
        default void mark(String kind) { }
    }

    /**
     * The TV's hands, as the conversation sees them (slice S1). {@link #act} runs on the brain's
     * thread and blocks while the gate decides and verifies; the words it returns are fixed by the
     * gate, never the model's.
     */
    public interface Actions {
        JSONArray tools();
        Act act(String name, JSONObject input);
        /** A new question from the person: whatever tainted the last turn is forgotten. */
        default void newTurn() { }
    }

    public static final class Act {
        public final String say, toModel, note;
        public final boolean endTurn;
        /** A card the TV shows itself (slice T3: a picture it made), or null. */
        public JSONObject card;
        /** @param note one line kept in the history ("[the TV opened Plex]") */
        public Act(String say, String toModel, boolean endTurn, String note) {
            this.say = say; this.toModel = toModel; this.endTurn = endTurn; this.note = note;
        }
    }

    /** Plain words for why a reply stopped early. */
    static String cutOffReason(String stop) {
        if ("max_tokens".equals(stop)) return "it ran out of room before it finished";
        if ("incomplete".equals(stop)) return "the connection dropped before it finished";
        if ("refusal".equals(stop)) return "Claude stopped writing it";
        return "it stopped before it finished";
    }

    private final Brain brain;
    private final Supplier<Voice> voice;          // null result = voice off
    private final Conversation convo;
    private final Runnable persist;
    private final Supplier<String> systemPrompt;
    private final Ui ui;
    private final Executor main;

    private volatile int gen = 0;
    private volatile Actions actions;
    /** One-line notes of what the TV did during the current reply. */
    private final StringBuilder actionNotes = new StringBuilder();
    private Brain.Turn turn;
    private ReplyParser parser;
    private String lastStop = null;
    private boolean streaming = false;
    private final StringBuilder spokenSoFar = new StringBuilder();
    /** Caption range of each sentence handed to the voice in this reply, by sentence index. */
    private final java.util.List<int[]> ranges = new java.util.ArrayList<>();

    public Session(Brain brain, Supplier<Voice> voice, Conversation convo, Runnable persist,
                   Supplier<String> systemPrompt, Ui ui, Executor main) {
        this.brain = brain;
        this.voice = voice;
        this.convo = convo;
        this.persist = persist;
        this.systemPrompt = systemPrompt;
        this.ui = ui;
        this.main = main;
    }

    public void setActions(Actions a) { actions = a; }

    /** True while a channel page is still arriving (barge-in would throw it away). */
    public boolean buildingChannel() {
        return streaming && parser != null && "channel".equals(parser.openBlock());
    }

    public boolean busy() {
        Voice v = voice.get();
        return streaming || (v != null && v.busy());
    }

    /** Ask a question. Anything in progress is interrupted first. */
    public void ask(String text) {
        if (text == null || text.trim().isEmpty()) return;
        interrupt();
        final int my = ++gen;
        convo.add(Message.user(text.trim()));
        persist.run();
        streaming = true;
        spokenSoFar.setLength(0);
        ranges.clear();
        ui.captionStart();
        ui.state("thinking");
        lastStop = null;
        actionNotes.setLength(0);
        final Actions acts = actions;
        if (acts != null) acts.newTurn();
        final ReplyParser parser = new ReplyParser(new ReplyParser.Sink() {
            @Override public void blockStarted(String tag) { if ("channel".equals(tag)) ui.building(0); }
            @Override public void mark(String kind) { ui.mark(kind); }
            @Override public void blockCutOff(String tag, int chars) {
                if (!"channel".equals(tag)) return;
                ui.building(-1);
                String why = "The channel was cut off: " + cutOffReason(lastStop) + ". Nothing was saved. Say “try again” and I'll rebuild it.";
                ui.notice(why);
                Voice v = voice.get();
                if (v != null) v.speak(why, -1);
            }
            @Override public void caption(String t) { ui.captionAppend(t); spokenSoFar.append(t); }
            @Override public void sentence(String s) { sentence(s, spokenSoFar.length()); }
            @Override public void sentence(String s, int captionEnd) {
                int start = ranges.isEmpty() ? 0 : ranges.get(ranges.size() - 1)[1];
                ranges.add(new int[]{start, captionEnd});
                Voice v = voice.get();
                if (v != null) v.speak(s, ranges.size() - 1);
            }
            @Override public void block(String tag, String body) {
                if ("card".equals(tag)) {
                    List<JSONObject> cards = CardSpec.parse(body);
                    if (!cards.isEmpty()) ui.showCards(cards);
                } else if ("channel".equals(tag)) {
                    ui.building(-1);
                    try { ui.proposeChannel(ChannelSpec.parseProposal(body)); }
                    catch (ChannelSpec.Rejected r) { ui.notice(r.getMessage()); }
                }
            }
        });
        this.parser = parser;
        turn = brain.send(systemPrompt.get(), convo.context(), acts == null ? null : acts.tools(), new Brain.Listener() {
            @Override public Brain.ToolReply onTool(String name, JSONObject input) {
                // Never act for a reply that was interrupted (Back, barge-in, a new question).
                if (acts == null || my != gen) return new Brain.ToolReply("NOT RUN: the person interrupted.", true);
                Act a = acts.act(name, input);
                main.execute(() -> {
                    if (my != gen) return;
                    if (a.note != null && !a.note.isEmpty()) actionNotes.append(actionNotes.length() > 0 ? " " : "").append(a.note);
                    if (a.card != null) ui.showCards(java.util.Collections.singletonList(a.card));
                    sayFixed(a.say);
                });
                return new Brain.ToolReply(a.toModel, a.endTurn);
            }
            @Override public void onText(String delta) { main.execute(() -> {
                if (my != gen) return;
                parser.feed(delta);
                if ("channel".equals(parser.openBlock())) ui.building(parser.openBlockChars());
            }); }
            @Override public void onDone(String full, String stop) {
                main.execute(() -> {
                    if (my != gen) return;
                    lastStop = stop;
                    parser.finish();
                    streaming = false;
                    turn = null;
                    String kept = compact(full);
                    if (actionNotes.length() > 0) kept = (kept + " " + actionNotes).trim();
                    convo.add(Message.assistant(kept));
                    persist.run();
                    Voice v = voice.get();
                    if (v != null && v.busy()) v.endOfReply();
                    else ui.state("idle");
                });
            }
            @Override public void onError(BrainException e) {
                main.execute(() -> {
                    if (my != gen) return;
                    lastStop = "incomplete";
                    parser.finish();
                    streaming = false;
                    turn = null;
                    ui.notice(e.friendly());
                    Voice v = voice.get();
                    if (v != null) { v.speak(e.friendly(), -1); v.endOfReply(); }
                    else ui.state("idle");
                });
            }
        });
    }

    /** The TV's own words (an action's result): captioned and spoken like a sentence of the reply. */
    private void sayFixed(String s) {
        if (s == null || s.trim().isEmpty()) return;
        String text = (spokenSoFar.length() > 0 && !Character.isWhitespace(spokenSoFar.charAt(spokenSoFar.length() - 1)) ? " " : "") + s.trim() + " ";
        int start = spokenSoFar.length();
        ui.captionAppend(text);
        spokenSoFar.append(text);
        ranges.add(new int[]{start, spokenSoFar.length()});
        Voice v = voice.get();
        if (v != null) v.speak(s.trim(), ranges.size() - 1);
    }

    /** Barge-in or Back: stop the reply and the voice now. What was already said is kept. */
    public void interrupt() {
        gen++;
        Brain.Turn t = turn;
        turn = null;
        if (t != null) t.cancel();
        if (streaming && parser != null && "channel".equals(parser.openBlock())) ui.building(-1);
        if (streaming) {
            streaming = false;
            String said = spokenSoFar.toString().trim();
            if (!said.isEmpty()) { convo.add(Message.assistant(said + " [interrupted]")); persist.run(); }
        }
        Voice v = voice.get();
        if (v != null) v.stop();
        ui.state("idle");
    }

    /** Voice events, forwarded by the activity. */
    public void onVoiceSpeaking() { ui.state("speaking"); }
    public void onVoiceQuiet() { if (!streaming) ui.state("idle"); }
    /** Sentence {@code index} started playing: captions follow it. */
    public void onVoiceSentence(int index) {
        if (index >= 0 && index < ranges.size()) ui.follow(ranges.get(index)[0], ranges.get(index)[1]);
    }

    private static final Pattern BLOCK = Pattern.compile("<(card|channel)>([\\s\\S]*?)</\\1>");
    private static final Pattern TITLE = Pattern.compile("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){0,80})\"");

    /** What is kept in the history: cards and channel pages become one-line notes. */
    static String compact(String full) {
        Matcher m = BLOCK.matcher(full);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            Matcher t = TITLE.matcher(m.group(2));
            String title = t.find() ? t.group(1) : "";
            String note = "card".equals(m.group(1)) ? "[showed a card" + (title.isEmpty() ? "" : ": " + title) + "]"
                                                    : "[proposed a channel" + (title.isEmpty() ? "" : ": " + title) + "]";
            m.appendReplacement(out, Matcher.quoteReplacement(note));
        }
        m.appendTail(out);
        return out.toString().trim();
    }
}
