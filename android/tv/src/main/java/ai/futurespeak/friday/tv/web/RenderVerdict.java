package ai.futurespeak.friday.tv.web;

/**
 * Did a channel page really come up? Decides from what the page showed a few seconds after
 * loading, and says what went wrong in plain words. Pure logic, unit-tested.
 */
public final class RenderVerdict {
    private RenderVerdict() {}

    /** Below this much visible text, with no pictures or drawing, the page counts as blank. */
    static final int MIN_TEXT = 12;

    /**
     * @param textChars  visible text characters on the page
     * @param media      visible canvas/svg/img/video elements
     * @param jsError    first script error, or null
     * @param blocked    first site the page tried to reach that it may not, or null
     * @param feed       a news-feed problem, or null
     * @return null when the page shows something; otherwise the reason
     */
    public static String problem(int textChars, int media, String jsError, String blocked, String feed) {
        boolean blank = textChars < MIN_TEXT && media == 0;
        if (!blank) return null;
        if (jsError != null && !jsError.isEmpty()) return "its page has a script error (" + jsError + ")";
        if (feed != null) return feed;
        if (blocked != null) return "it tried to load " + blocked + ", which isn't allowed";
        return "its page came up blank";
    }
}
