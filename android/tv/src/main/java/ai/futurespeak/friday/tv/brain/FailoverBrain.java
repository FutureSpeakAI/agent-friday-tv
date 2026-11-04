package ai.futurespeak.friday.tv.brain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.futurespeak.friday.tv.pair.BrainMode;
import ai.futurespeak.friday.tv.pair.BrainMode.Mode;

/**
 * The brain the TV talks to: Friday desktop when paired and reachable, otherwise Claude on
 * this TV. It is always the same object, so {@code Session} keeps its final field.
 *
 * - Chosen per turn. Paired: a quick, pinned check that the PC answers; yes is
 *   {@link Mode#DESKTOP}, no is {@link Mode#STANDALONE}. Unpaired is {@link Mode#UNPAIRED}.
 * - Never silent. {@link #displayName()} names the brain answering now ("Friday desktop",
 *   "Claude on this TV (PC unreachable)", "Claude"), and every switch is reported to
 *   {@link SwitchLog} (the receipt log) with the reason.
 * - If the desktop fails before it has said anything, this turn is answered by Claude on the
 *   TV instead, and that switch is reported. Once the desktop has started speaking, a failure
 *   is reported as an error, never finished by another brain mid-sentence.
 */
public final class FailoverBrain implements Brain {

    /** Whether the TV is paired, and whether the paired PC answers right now. */
    public interface Desktop {
        boolean paired();
        boolean reachable();
    }

    /** Where switches are written: the TV's receipts. */
    public interface SwitchLog { void switched(Mode from, Mode to, String why); }

    private final Brain desktop, standalone;
    private final Desktop link;
    private final SwitchLog log;

    public FailoverBrain(Brain desktop, Brain standalone, Desktop link, SwitchLog log) {
        this.desktop = desktop;
        this.standalone = standalone;
        this.link = link;
        this.log = log;
    }

    /** "Friday desktop"; or the TV's own brain by name, with "on this TV (PC unreachable)"
     *  when a paired PC did not answer ("Claude on this TV (PC unreachable)"). */
    @Override public String displayName() {
        Mode m = BrainMode.current();
        if (m == Mode.DESKTOP) return m.label;
        String own = standalone.displayName();
        return m == Mode.STANDALONE ? own + " on this TV (PC unreachable)" : own;
    }

    @Override public boolean ready() {
        return link.paired() ? (desktop.ready() || standalone.ready()) : standalone.ready();
    }

    /** Decide who answers this turn, and say so if it changed. */
    Mode choose() {
        Mode next;
        String why;
        if (!link.paired()) { next = Mode.UNPAIRED; why = "not paired"; }
        else if (link.reachable()) { next = Mode.DESKTOP; why = "your Friday computer answered"; }
        else { next = Mode.STANDALONE; why = "your Friday computer did not answer"; }
        to(next, why);
        return next;
    }

    private void to(Mode next, String why) {
        Mode prev = BrainMode.current();
        BrainMode.set(next);
        if (prev != next && log != null) log.switched(prev, next, why);
    }

    @Override
    public Turn send(String systemPrompt, List<Message> history, Listener listener) {
        return send(systemPrompt, history, null, listener);
    }

    /**
     * Returns at once, like every brain: the reachability check is network work, so choosing
     * and starting the turn happen on the turn's own thread, never the caller's (the UI's).
     */
    @Override
    public Turn send(String systemPrompt, List<Message> history, JSONArray tools, Listener listener) {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final Turn[] current = new Turn[1];
        Thread t = new Thread(() -> {
            Turn started = start(systemPrompt, history, tools, listener, cancelled, current);
            synchronized (current) { if (current[0] == null) current[0] = started; }
            if (cancelled.get() && started != null) started.cancel();
        }, "failover-turn");
        t.setDaemon(true);
        t.start();
        return () -> {
            cancelled.set(true);
            Turn c;
            synchronized (current) { c = current[0]; }
            if (c != null) c.cancel();
        };
    }

    private Turn start(String systemPrompt, List<Message> history, JSONArray tools, Listener listener,
                       AtomicBoolean cancelled, Turn[] current) {
        if (cancelled.get()) return null;
        if (choose() != Mode.DESKTOP) return standalone.send(systemPrompt, history, tools, listener);

        final AtomicBoolean spoke = new AtomicBoolean(false);
        Listener watch = new Listener() {
            @Override public void onText(String d) { spoke.set(true); listener.onText(d); }
            @Override public void onDone(String full, String stop) { listener.onDone(full, stop); }
            @Override public ToolReply onTool(String name, JSONObject input) {
                spoke.set(true);
                return listener.onTool(name, input);
            }
            @Override public void onError(BrainException e) {
                if (cancelled.get()) return;
                if (spoke.get() || !fallsBack(e)) { listener.onError(e); return; }
                to(Mode.STANDALONE, "your Friday computer stopped answering (" + e.kind + ")");
                Turn next = standalone.send(systemPrompt, history, tools, listener);
                synchronized (current) { current[0] = next; }
            }
        };
        return desktop.send(systemPrompt, history, tools, watch);
    }

    /** Failures where Claude on this TV should answer instead: the PC is off, busy, broken,
     *  or no longer knows this TV. A refusal of the request itself is passed on. */
    static boolean fallsBack(BrainException e) {
        return e.kind == BrainException.Kind.NETWORK || e.kind == BrainException.Kind.NOT_PAIRED
                || e.kind == BrainException.Kind.OVERLOADED || e.kind == BrainException.Kind.SERVER
                || e.kind == BrainException.Kind.AUTH;     // e.g. this TV was removed on the PC
    }
}
