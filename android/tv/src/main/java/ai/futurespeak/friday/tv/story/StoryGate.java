package ai.futurespeak.friday.tv.story;

import ai.futurespeak.friday.tv.kids.KidsPhrases;
import ai.futurespeak.friday.tv.kids.KidsRules;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Who may make what (spec §5, §10): in kids mode with no grown-up unlock, only "words and
 * voice" (an ordinary kids turn: Claude pages and Friday's voice, nothing paid beyond that); with
 * the grown-up window or a story night open, "full" (pictures, cast voices, sounds, music).
 * Nothing a child says widens this: a spoken ask for more gets the kids reply. Pure Java.
 */
public final class StoryGate {
    private StoryGate() {}

    public enum Mode { WORDS, FULL }

    /** What the room is like at the moment of deciding. */
    public static final class Room {
        public final boolean kids, grownUpOpen, nightOpen, haveClaude, haveEleven, haveKie;
        public Room(boolean kids, boolean grownUpOpen, boolean nightOpen, boolean haveClaude, boolean haveEleven, boolean haveKie) {
            this.kids = kids; this.grownUpOpen = grownUpOpen; this.nightOpen = nightOpen;
            this.haveClaude = haveClaude; this.haveEleven = haveEleven; this.haveKie = haveKie;
        }
        public boolean unlocked() { return grownUpOpen || nightOpen; }
    }

    /** The mode this room allows, or null when no story can be told (no Claude key). */
    public static Mode allowed(Room r) {
        if (r == null || !r.haveClaude) return null;
        if (r.kids && !r.unlocked()) return Mode.WORDS;
        return (r.haveEleven && r.haveKie) ? Mode.FULL : Mode.WORDS;
    }

    /** Full mode is on offer only when it is both allowed and the keys are there. */
    public static boolean fullOffered(Room r) { return allowed(r) == Mode.FULL; }

    /**
     * A spoken request inside the story that would widen things (more pictures, a PIN, more time,
     * the internet) gets the kids reply and goes nowhere near the planner. Null means "fine, pass it on".
     */
    public static String refuse(String utterance, boolean kids) {
        if (utterance == null) return null;
        if (kids && KidsPhrases.match(utterance, true) == KidsPhrases.Kind.WIDEN) return KidsRules.ASK_GROWN_UP;
        String u = utterance.toLowerCase(java.util.Locale.ROOT);
        if (u.matches(".*\\b(use|put|add|make it look like|with) (my|a|the) (photo|picture of me|selfie|face)\\b.*") || u.matches(".*\\blook like me\\b.*"))
            return "I draw pretend people here, never real ones.";
        return null;
    }

    /** The decision receipt for a story (one per story; the outcome carries the actual meter). */
    public static JSONObject decisionRec(String topic, Mode mode, boolean bedtime, String band, JSONObject meterEst, boolean kids) throws JSONException {
        return new JSONObject().put("action", "story.make").put("title", "Tell a story").put("tier", mode == Mode.FULL ? 2 : 1)
                .put("args", new JSONObject().put("topic", topic == null ? "" : topic).put("mode", mode.name().toLowerCase(java.util.Locale.ROOT))
                        .put("bedtime", bedtime).put("band", band == null ? "" : band))
                .put("origin", "remote").put("who_may", kids ? "kids" : "family").put("policy", mode == Mode.FULL ? "unlocked" : "announce")
                .put("sends", mode == Mode.FULL ? "the story's words to Claude; Friday's and the cast's lines to ElevenLabs; picture descriptions to kie.ai"
                                                : "the story's words to Claude; Friday's lines to ElevenLabs (or nothing, with the TV's voice)")
                .put("meter", meterEst);
    }
}
