package ai.futurespeak.friday.tv.story;

/**
 * The music bed's gain while someone speaks (SB-E06): down to a quarter (−12 dB) over 300 ms
 * when a line starts, back to full over 300 ms after it ends. Pure arithmetic, tested.
 */
public final class Ducking {
    private Ducking() {}

    public static final float DUCKED = 0.25f, FULL = 1.0f;
    public static final long RAMP_MS = 300;

    /**
     * The bed's gain at {@code now} for a line spoken from {@code lineStart} to {@code lineEnd}
     * (both in the same clock); outside the line, the gain ramps back.
     */
    public static float gain(long now, long lineStart, long lineEnd) {
        if (lineStart < 0) return FULL;
        if (now < lineStart) return FULL;
        if (now < lineStart + RAMP_MS) return FULL - (FULL - DUCKED) * (now - lineStart) / (float) RAMP_MS;
        if (lineEnd < 0 || now <= lineEnd) return DUCKED;
        if (now < lineEnd + RAMP_MS) return DUCKED + (FULL - DUCKED) * (now - lineEnd) / (float) RAMP_MS;
        return FULL;
    }

    /** In decibels, for the test that says "at least −12 dB". */
    public static double db(float gain) { return 20 * Math.log10(Math.max(1e-6, gain)); }
}
