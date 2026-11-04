package ai.futurespeak.friday.tv.kids;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Kids mode's fixed words, from one table (spec KM-E12): what Friday cannot do in kids mode
 * (the prompt's lines and the kids home's line are both generated from {@link #CANNOT}), the
 * disclosure, the reading bands and the report markers. Nothing here comes from a model.
 */
public final class KidsRules {
    private KidsRules() {}

    /** One thing kids mode does not allow, in the brain's words and in the child's. */
    public static final class Rule {
        public final String key, forPrompt, forHome;
        Rule(String key, String forPrompt, String forHome) { this.key = key; this.forPrompt = forPrompt; this.forHome = forHome; }
    }

    public static final List<Rule> CANNOT = Collections.unmodifiableList(Arrays.asList(
            new Rule("apps", "open apps or shows that are not on the kids list", "open other apps"),
            new Rule("settings", "change any setting, the PIN, bedtime or kids mode", "change settings or bedtime"),
            new Rule("tools", "use the web, make pictures, video or music, or start a live conversation", "look things up online or make pictures"),
            new Rule("time", "give more TV time, extra episodes, stars or tokens", "give more TV time"),
            new Rule("screens", "see what other apps are showing", null)));

    /** The fixed reply to anything that needs a grown-up. Never a PIN pad from voice. */
    public static final String ASK_GROWN_UP = "That needs a grown-up.";

    public static final String DISCLOSURE_FOR_PROMPT =
            "A child is talking with you. If you are asked who or what you are, say you are Friday, a computer program, not a person.";
    /** Small type on the kids home (spec §6.9 and §5). */
    public static final String DISCLOSURE_FOR_HOME =
            "Friday is a computer program. What you say goes to Claude; no voice recording leaves this TV.";

    /** The kids home's one line about limits, generated from {@link #CANNOT}. */
    public static String homeCannotLine() {
        StringBuilder s = new StringBuilder("A grown-up can help with things Friday can't do here, like ");
        int n = 0;
        for (Rule r : CANNOT) if (r.forHome != null) n++;
        int i = 0;
        for (Rule r : CANNOT) {
            if (r.forHome == null) continue;
            s.append(i == 0 ? "" : i == n - 1 ? " or " : ", ").append(r.forHome);
            i++;
        }
        return s.append(".").toString();
    }

    /** Reading bands; a band is stored, an age never is (KM-E05). */
    public enum Band {
        YOUNG("5 to 7"), MIDDLE("8 to 10"), OLDER("11 to 13");
        public final String ages;
        Band(String ages) { this.ages = ages; }
        public static Band parse(String s) { try { return s == null ? null : valueOf(s); } catch (IllegalArgumentException e) { return null; } }
    }

    /** The only report markers (KM-E07). Anything else is dropped and logged. */
    public static final List<String> MARKS = Collections.unmodifiableList(Arrays.asList("answer_given", "steered_away", "please_look"));
}
