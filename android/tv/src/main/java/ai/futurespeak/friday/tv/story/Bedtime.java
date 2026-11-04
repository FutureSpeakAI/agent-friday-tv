package ai.futurespeak.friday.tv.story;

/**
 * Bedtime arithmetic (spec §4.8, SB-E07): whether the house is winding down, how many pages are
 * left before the TV says goodnight, and how far the picture dims on each page. Pure Java,
 * tested with a fake clock.
 */
public final class Bedtime {
    private Bedtime() {}

    public static final int MIN_PAGES = 2, NORMAL_PAGES = 10, BEDTIME_PAGES = 7, MAX_PAGES = 12;
    /** A page takes about this long to play, for the pages-left sum. */
    public static final long PAGE_MS = 40_000;

    /** True once the helper's wind-down time has passed (KIDS K2's T−15), or the bedtime tile was pressed. */
    public static boolean windingDown(long now, long windDownAt) { return windDownAt > 0 && now >= windDownAt; }

    /** No story starts after T−0 (rule in §4.8). */
    public static boolean tooLate(long now, long bedtimeAt) { return bedtimeAt > 0 && now >= bedtimeAt; }

    /**
     * How many pages a story may still have: the normal number, or at bedtime as many as fit before
     * T−0 at the measured page rate, never fewer than two (a page and an ending).
     */
    public static int pagesFor(long now, long bedtimeAt, boolean bedtime) {
        if (!bedtime) return NORMAL_PAGES;
        if (bedtimeAt <= 0 || bedtimeAt <= now) return MIN_PAGES;
        long fit = (bedtimeAt - now) / PAGE_MS;
        return (int) Math.max(MIN_PAGES, Math.min(BEDTIME_PAGES, fit));
    }

    /** 0 = full brightness, 1 = the night look: one step per page in bedtime mode. */
    public static float dim(int pageIndex, int pagesTotal, boolean bedtime) {
        if (!bedtime || pagesTotal <= 1) return 0f;
        return Math.max(0f, Math.min(1f, pageIndex / (float) (pagesTotal - 1)));
    }

    /** The planner's instruction for the pages that remain. */
    public static String steer(int pagesLeft, boolean bedtime) {
        if (pagesLeft <= 1) return bedtime ? "This is the last page: a calm, sleepy, happy ending. Everyone settles down to sleep. End with 'The end. Goodnight.'"
                                           : "This is the last page: a kind, happy ending. End with 'The end.'";
        return (bedtime ? "Bedtime mode: keep it calm and slow, no big surprises, steer gently toward sleep. " : "")
                + "About " + pagesLeft + " pages are left, counting the ending.";
    }
}
