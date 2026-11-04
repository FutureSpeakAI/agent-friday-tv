package ai.futurespeak.friday.tv.ui;

/**
 * Where the caption window should sit so the sentence being spoken stays readable.
 * Pure arithmetic on line numbers (the TextView's Layout supplies them), so it is unit-tested.
 */
public final class CaptionFollow {
    private CaptionFollow() {}

    /**
     * @param top       first visible line now
     * @param visible   lines that fit in the caption window
     * @param start     line where the spoken sentence starts
     * @param end       line where it ends
     * @param lineCount lines in the whole caption
     * @return the first line to show
     */
    public static int top(int top, int visible, int start, int end, int lineCount) {
        visible = Math.max(1, visible);
        if (start >= top && end < top + visible) return clamp(top, visible, lineCount);   // already in view
        return clamp(start, visible, lineCount);                                          // bring it to the top
    }

    /** One page further on (dir = +1) or back (dir = -1), keeping a line of overlap. */
    public static int page(int top, int visible, int dir, int lineCount) {
        int step = Math.max(1, visible - 1);
        return clamp(top + dir * step, visible, lineCount);
    }

    public static boolean moreBelow(int top, int visible, int lineCount) { return top + visible < lineCount; }

    public static boolean moreAbove(int top) { return top > 0; }

    static int clamp(int top, int visible, int lineCount) {
        return Math.max(0, Math.min(top, Math.max(0, lineCount - visible)));
    }
}
