package ai.futurespeak.friday.tv.brain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * The seam between the TV and whatever answers. Today: {@link ClaudeDirect} (the Claude API).
 * Later: {@link FridayDesktopClient} (Friday desktop on the LAN, paired with a code).
 *
 * The TV never trusts a brain with anything but words. A brain returns text; the TV parses
 * cards and channel proposals out of it ({@code reply.ReplyParser}) and every grant or
 * approval is asked by the TV's own fixed UI. A brain cannot spend money, send messages,
 * or change settings, because the TV gives it no way to. Since slice S1 a brain may
 * <em>propose</em> a catalogue action (a tool call); {@link Listener#onTool} hands it to the
 * TV's {@code ActionGate}, which decides, keeps receipts and words the result. The brain only
 * ever gets that fixed result back.
 */
public interface Brain {

    /** One streamed reply. {@link #cancel()} stops it (barge-in); no callbacks follow a cancel. */
    interface Turn { void cancel(); }

    interface Listener {
        /** A piece of the reply as it arrives. Called on a background thread. */
        void onText(String delta);
        /** The whole reply. {@code stopReason} as the brain reports it ("end_turn", "max_tokens", ...). */
        void onDone(String fullText, String stopReason);
        void onError(BrainException e);

        /**
         * The brain proposes an action. Called on the turn's background thread, and blocks until
         * the TV has decided (and, for an app, verified). The default refuses: a listener that
         * doesn't route proposals to the gate can never be made to act.
         */
        default ToolReply onTool(String name, JSONObject input) {
            return new ToolReply("REFUSED by the TV: actions aren't available here.", false);
        }
    }

    /** What the TV tells the brain about a proposal, in the TV's fixed words. */
    final class ToolReply {
        public final String toModel;
        /** True when the TV already acted and spoke (for example another app is now in front): the turn ends here. */
        public final boolean endTurn;
        public ToolReply(String toModel, boolean endTurn) { this.toModel = toModel; this.endTurn = endTurn; }
    }

    /** Shown on screen: "Claude", "Friday desktop". */
    String displayName();

    /** True when this brain can take a turn now (key present, paired, ...). */
    boolean ready();

    /**
     * Starts a reply to {@code history} (oldest first; the last message is the user's).
     * Returns at once; the reply streams to {@code listener}.
     */
    Turn send(String systemPrompt, List<Message> history, Listener listener);

    /** As above, offering {@code tools} (generated from the catalogue). Brains without tool use ignore them. */
    default Turn send(String systemPrompt, List<Message> history, JSONArray tools, Listener listener) {
        return send(systemPrompt, history, listener);
    }
}
