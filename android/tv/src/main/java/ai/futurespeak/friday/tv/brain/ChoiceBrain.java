package ai.futurespeak.friday.tv.brain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * The household's chosen brain for this TV: Claude directly, or a model through
 * OpenRouter. Chosen per turn ({@link Chooser}), so kids mode is always Claude. If OpenRouter fails
 * before it has said anything, Claude answers that turn instead, Friday says so in her first
 * words, and the switch is written to the receipts. Every finished OpenRouter reply is receipted
 * with its model and cost. Proposals from either brain go to the same {@code onTool} (the one gate).
 * Pairing's FailoverBrain can wrap this as its "standalone" brain unchanged.
 */
public final class ChoiceBrain implements Brain {
    /** Which model answers this turn (null: Claude). */
    public interface Chooser { ModelChoice.Model now(); }
    /** Where switches and paid replies are written: the TV's receipts. */
    public interface Log {
        void switched(String from, String to, String why);
        void reply(String modelId, String modelName, double costUsd);
    }

    private final Brain claude;
    private final OpenRouterBrain openRouter;
    private final Chooser chooser;
    private final Log log;
    private volatile String lastName = "Claude";

    public ChoiceBrain(Brain claude, OpenRouterBrain openRouter, Chooser chooser, Log log) {
        this.claude = claude; this.openRouter = openRouter; this.chooser = chooser; this.log = log;
    }

    /** The brain answering now, for the "Powered by …" pill. */
    @Override public String displayName() {
        ModelChoice.Model m = chooser.now();
        return m == null ? claude.displayName() : m.name + " via OpenRouter";
    }

    /** Who answered the last turn (after a fallback this is Claude). */
    public String lastAnswered() { return lastName; }

    @Override public boolean ready() { return claude.ready() || (chooser.now() != null && openRouter.ready()); }

    @Override public Turn send(String system, List<Message> history, Listener l) { return send(system, history, null, l); }

    @Override public Turn send(String system, List<Message> history, JSONArray tools, Listener l) {
        ModelChoice.Model m = chooser.now();
        if (m == null) { lastName = claude.displayName(); return claude.send(system, history, tools, l); }
        openRouter.setModel(m.id, m.name);
        Turn[] current = new Turn[1];
        boolean[] spoke = {false}, cancelled = {false};
        current[0] = openRouter.send(system, history, tools, new Listener() {
            @Override public void onText(String d) { spoke[0] = true; l.onText(d); }
            @Override public void onDone(String full, String stop) {
                lastName = m.name + " via OpenRouter";
                if (log != null) log.reply(m.id, m.name, openRouter.lastCostUsd());
                l.onDone(full, stop);
            }
            @Override public void onError(BrainException e) {
                if (spoke[0] || cancelled[0] || !claude.ready()) { l.onError(e); return; }
                // Never silent: say who is answering instead, and receipt the switch.
                if (log != null) log.switched(m.name + " via OpenRouter", claude.displayName(), e.getMessage() == null ? "error" : e.getMessage());
                String note = m.name + " didn't answer, so Claude is answering. ";
                lastName = claude.displayName();
                l.onText(note);
                current[0] = claude.send(fallbackPrompt(system, m), history, tools, new Listener() {
                    @Override public void onText(String d) { l.onText(d); }
                    @Override public void onDone(String full, String stop) { l.onDone(note + full, stop); }
                    @Override public void onError(BrainException e2) { l.onError(e2); }
                    @Override public ToolReply onTool(String name, JSONObject input) { return l.onTool(name, input); }
                });
            }
            @Override public ToolReply onTool(String name, JSONObject input) { return l.onTool(name, input); }
        });
        return () -> { cancelled[0] = true; Turn t = current[0]; if (t != null) t.cancel(); };
    }

    /** The same prompt, with the identity line swapped back to Claude for a fallback turn. */
    static String fallbackPrompt(String system, ModelChoice.Model m) {
        if (system == null) return null;
        String other = ModelChoice.identity(m, false);
        return system.startsWith(other) ? ModelChoice.identity(null, false) + system.substring(other.length()) : system;
    }
}
