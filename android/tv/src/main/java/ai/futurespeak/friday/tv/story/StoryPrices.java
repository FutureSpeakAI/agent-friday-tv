package ai.futurespeak.friday.tv.story;

import ai.futurespeak.friday.tv.actions.Images;
import ai.futurespeak.friday.tv.studio.Prices;
import ai.futurespeak.friday.tv.studio.Spent;

/**
 * What a story costs, before it is told (spec §7), from Studio's price table (Claude per token,
 * ElevenLabs per 1,000 characters) plus the Storyteller's own lines: a picture (kie.ai), a sound
 * (ElevenLabs sound effects, per second), a music bed (per minute) and a voice design. The
 * ElevenLabs credit figures are unverified (spec §10) and the owner can correct them with
 * {@code files/studio/prices.json}; every receipt shows the actual. Pure Java.
 */
public final class StoryPrices {
    private StoryPrices() {}

    /** One ElevenLabs credit in dollars, from a Creator plan's price per credit (estimate). */
    public static final double USD_PER_ELEVEN_CREDIT = 0.00022;
    public static final double SFX_CREDITS_PER_SECOND = 40;
    public static final double MUSIC_CREDITS_PER_MINUTE = 900;
    /** A voice design's previews plus the save, as an estimate; the actual is unknown until it runs. */
    public static final double VOICE_DESIGN_CREDITS_EST = 1000;

    public static final int CLAUDE_IN_PER_PAGE = 1600, CLAUDE_OUT_PER_PAGE = 320, CHARS_PER_PAGE = 330;

    public static double pictureUsd() { return Images.EST_CREDITS * Images.USD_PER_CREDIT; }
    public static double soundUsd(float seconds) { return seconds * SFX_CREDITS_PER_SECOND * USD_PER_ELEVEN_CREDIT; }
    public static double musicUsd(int seconds) { return seconds / 60.0 * MUSIC_CREDITS_PER_MINUTE * USD_PER_ELEVEN_CREDIT; }
    public static double voiceDesignUsd() { return VOICE_DESIGN_CREDITS_EST * USD_PER_ELEVEN_CREDIT; }

    /**
     * The estimate for one story of {@code pages} pages in {@code mode}, plus the one-off lines a
     * first night would add (sounds and beds not yet cached, voices to design).
     */
    public static Spent estimate(int pages, StoryGate.Mode mode, String model, boolean eleven, Prices p,
                                 int soundsMissingSeconds, int bedsMissing, int voicesToDesign) {
        Spent s = new Spent();
        Prices.Claude c = p.claude(model);
        s.add("claude", "pages", pages, c.usd((long) CLAUDE_IN_PER_PAGE * pages, (long) CLAUDE_OUT_PER_PAGE * pages, 0, 0));
        if (eleven) s.add("elevenlabs", "characters", (double) CHARS_PER_PAGE * pages, CHARS_PER_PAGE * pages / 1000.0 * p.elevenPer1k);
        if (mode == StoryGate.Mode.FULL) {
            s.add("kie.ai", "pictures", pages, pictureUsd() * pages);
            if (soundsMissingSeconds > 0) s.add("elevenlabs sounds", "seconds", soundsMissingSeconds, soundUsd(soundsMissingSeconds));
            if (bedsMissing > 0) s.add("elevenlabs music", "beds", bedsMissing, musicUsd(MusicBeds.SECONDS) * bedsMissing);
            if (voicesToDesign > 0) s.add("elevenlabs voices", "designs", voicesToDesign, voiceDesignUsd() * voicesToDesign);
        }
        return s;
    }
}
