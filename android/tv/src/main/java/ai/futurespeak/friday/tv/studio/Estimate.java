package ai.futurespeak.friday.tv.studio;

/**
 * What a podcast will cost and how long it will take, from the request and the price table
 * (spec §9), before anything is built. Errs high: thinking tokens are counted, prompt caching
 * is not. The plan's own Claude turn (already spent when the card shows) is added by the
 * caller. Pure Java.
 */
public final class Estimate {
    /** Characters per spoken word, spaces included. */
    static final double CHARS_PER_WORD = 6.0;
    /** Tokens per word of English, for what Claude writes. */
    static final double TOKENS_PER_WORD = 1.4;
    /** Thinking Claude may do per call at low effort (billed as output). */
    static final int THINKING = 800;
    /** The fixed opening and close, in characters. */
    static final int FIXED_CHARS = 170;

    public final Spent cost;
    public final int minutes;

    Estimate(Spent cost, int minutes) { this.cost = cost; this.minutes = minutes; }

    public double usd() { return cost.usd(); }

    /**
     * @param sourceChars characters of source text the writer reads (each chapter call reads them again)
     * @param eleven      true when Friday's ElevenLabs voice will speak it; false for the TV's own voice (free)
     */
    public static Estimate podcast(StudioRequest r, int sourceChars, int chapters, String model, boolean eleven, Prices p) {
        int words = r.words();
        long srcTokens = Math.min(sourceChars, StudioSource.MAX_CHARS) / 4;
        long inTok = (long) chapters * (srcTokens + 1500);
        long outTok = (long) (words * TOKENS_PER_WORD) + (long) chapters * THINKING;
        Spent s = new Spent();
        s.add("claude", "tokens", inTok + outTok, p.claude(model).usd(inTok, outTok, 0, 0));
        long chars = (long) (words * CHARS_PER_WORD) + FIXED_CHARS;
        if (eleven) s.add("elevenlabs", "characters", chars, chars / 1000.0 * p.elevenPer1k);
        else s.add("tv voice", "characters", chars, 0);
        // Time: Claude writes about 45 tokens a second; ElevenLabs makes a minute of speech in about 8 seconds;
        // the TV's voice in about 15; plus a few seconds a chapter of round trips.
        double secs = outTok / 45.0 + r.lengthMin * (eleven ? 8 : 15) + chapters * 4;
        return new Estimate(s, (int) Math.max(1, Math.ceil(secs / 60.0)));
    }

    /** ST-E03: the running actual is more than a quarter over the estimate (a surprise check, never a cap). */
    public static boolean overQuarter(double actualUsd, double estUsd) {
        return actualUsd > estUsd * 1.25 + 0.005;
    }
}
