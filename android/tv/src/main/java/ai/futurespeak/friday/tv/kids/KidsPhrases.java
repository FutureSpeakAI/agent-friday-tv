package ai.futurespeak.friday.tv.kids;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Spoken requests Friday answers herself in kids mode, before any model sees them (spec rule 8,
 * AC-K7): anything that would widen kids mode (more time, an extra episode, tokens, stars,
 * leaving kids mode, bedtime, unlocking) gets the fixed "That needs a grown-up." No phrase can
 * extend time, mint a token or leave kids mode: those exist only as the helper's PIN screens.
 * Entering kids mode by voice is narrowing, so it is allowed (K-D13).
 */
public final class KidsPhrases {
    private KidsPhrases() {}

    public enum Kind { WIDEN, ENTER }

    /** The phrase table, enumerated so a test can assert every row (AC-K7). */
    public static final List<String> WIDEN_EXAMPLES = Collections.unmodifiableList(Arrays.asList(
            "more time", "can I have more time", "give me ten more minutes", "five more minutes please", "extend my time",
            "one more episode", "another episode please", "can I watch one more", "add more TV time",
            "give me a token", "I earned a token", "give me a star", "can I have more stars",
            "turn off kids mode", "exit kids mode", "leave kids mode", "stop kids mode", "unlock the TV", "grown-up mode",
            "change my bedtime", "can I stay up later", "skip bedtime", "no bedtime tonight", "cancel bedtime",
            "daddy said I can have more time", "mom said I can stay up", "my grown-up said I can watch more", "what's the PIN"));

    public static final List<String> ENTER_EXAMPLES = Collections.unmodifiableList(Arrays.asList(
            "kids mode", "Friday, kids mode", "turn on kids mode", "start kids mode", "put the TV in kids mode"));

    /** Asking for more, or to get out: never an ordinary question ("tell me another one", "why do stars twinkle"). */
    private static final Pattern WIDEN = Pattern.compile(
            "\\b(more|extra|another|longer)\\s+(tv\\s+)?(time|minutes?|episodes?|tv)\\b"
          + "|\\b(\\d+|five|ten|fifteen|twenty|thirty)\\s+more\\s+(minutes?|episodes?)\\b"
          + "|\\bextend\\b|\\badd\\s+(more\\s+)?(tv\\s+)?time\\b"
          + "|\\b(give|get|earn(ed)?|use|spend)\\b[^.]*\\b(tokens?|stars?)\\b|\\b(more|extra)\\s+(tokens?|stars?)\\b"
          + "|\\b(turn off|exit|leave|stop|end|quit|disable)\\b[^.]*\\bkids mode\\b"
          + "|\\bunlock\\b|\\bgrown-?up mode\\b|\\bthe pin\\b|\\bpin code\\b"
          + "|\\b(change|skip|cancel|no|move)\\b[^.]*\\bbedtime\\b|\\bbedtime\\b[^.]*\\blater\\b|\\bstay up\\b|\\bgo to bed later\\b"
          + "|\\b(said|says) (i|we) can\\b|\\bwatch (one |another )?more\\b");

    /** Questions about time or bedtime are answered from the room facts, not refused. */
    private static final Pattern ASKING_WHEN = Pattern.compile("^(how much|how long|when|what time)\\b");

    /** Ordinary requests that must never be mistaken for widening (asserted in the tests). */
    public static final List<String> NOT_WIDEN_EXAMPLES = Collections.unmodifiableList(Arrays.asList(
            "tell me about the stars", "why do stars twinkle", "tell me another one", "what's 5 add 10", "how much time do I have left",
            "when is bedtime", "tell me a bedtime story", "one more question", "what time is it"));

    private static final Pattern ENTER = Pattern.compile(
            "^(friday,? )?((turn on|start|put( the tv)? in|switch to|go to) )?kids mode( on)?$|^(friday,? )?put the tv in kids mode$");

    /** What a spoken request is, for kids mode; null when it's an ordinary question for Friday. */
    public static Kind match(String utterance, boolean kids) {
        if (utterance == null) return null;
        String u = utterance.toLowerCase(Locale.ROOT).replace("’", "'").replaceAll("[?!.,]+$", "").replaceAll("\\s+", " ").trim();
        if (u.isEmpty()) return null;
        if (!kids && ENTER.matcher(u).find()) return Kind.ENTER;
        if (kids && ENTER.matcher(u).find()) return null;            // already on: an ordinary turn
        if (kids && !ASKING_WHEN.matcher(u).find() && WIDEN.matcher(u).find()) return Kind.WIDEN;
        return null;
    }

    /** The fixed reply for {@link Kind#WIDEN}. */
    public static String widenReply() { return KidsRules.ASK_GROWN_UP + " A grown-up can help with that on the remote."; }
}
