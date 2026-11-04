package ai.futurespeak.friday.tv.story;

/**
 * The latency ladder (spec §6, SB-E04, SB-E05), as rules: what is made ahead while a page plays,
 * what waits for the choice, and when a late picture gets a shadow page. Pure Java, so the
 * counts and thresholds are tested without a network.
 */
public final class Prefetch {
    private Prefetch() {}

    /** The picture is asked for the moment the arrow is pressed; a bridging line covers it. */
    public static final long BRIDGE_MS = 0;
    /** A picture later than this shows the shadow page until it lands. */
    public static final long SHADOW_MS = 20_000;
    /** A picture later than this is given up on for this page; the next page tries again. */
    public static final long GIVE_UP_MS = 45_000;

    /** How many next-page texts to write while this page plays: one per branch, or one. */
    public static int textsAhead(StoryPage current) {
        if (current == null || current.ending) return 0;
        return current.hasChoices() ? 2 : 1;
    }

    /** How many first-line voice clips to make ahead: the same as the texts (they are short). */
    public static int firstLinesAhead(StoryPage current) { return textsAhead(current); }

    /** Pictures are never made ahead of a choice: exactly one, for the branch that was chosen. */
    public static int picturesAhead(StoryPage current) { return 0; }

    public static int picturesAfterChoice() { return 1; }

    public enum PictureState { WAITING, SHADOW, GIVEN_UP, READY, FAILED }

    /** What the page should show {@code elapsedMs} after its picture was asked for. */
    public static PictureState pictureState(long elapsedMs, boolean arrived, boolean failed) {
        if (arrived) return PictureState.READY;
        if (failed) return PictureState.FAILED;
        if (elapsedMs >= GIVE_UP_MS) return PictureState.GIVEN_UP;
        if (elapsedMs >= SHADOW_MS) return PictureState.SHADOW;
        return PictureState.WAITING;
    }
}
