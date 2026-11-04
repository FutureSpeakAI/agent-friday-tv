package ai.futurespeak.friday.tv.ambient;

import android.view.Gravity;

import java.util.Random;

/** How far the screensaver's content moves each minute so nothing stays on the same pixels. */
final class BurnIn {
    private BurnIn() {}
    static final float RANGE_X_DP = 34, RANGE_Y_DP = 30;   // enough to move every pixel; small enough to stay on screen
    static final int[] CORNERS = {
            Gravity.TOP | Gravity.START, Gravity.TOP | Gravity.END, Gravity.BOTTOM | Gravity.END, Gravity.BOTTOM | Gravity.START};

    /** A random offset in [-range, range], never within 25% of the last spot's sign-free size (0 is avoided). */
    static float offset(Random r, float range) {
        float v = (0.35f + 0.65f * r.nextFloat()) * range;
        return r.nextBoolean() ? v : -v;
    }
}
