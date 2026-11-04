package ai.futurespeak.friday.tv.kids;

import ai.futurespeak.friday.tv.Prefs;

/**
 * Kid Friday's voice and brain settings (K-D7, K-D8): the same voice, a little slower; the
 * faster Claude for a child who is waiting. Grown-up and family modes keep their own settings.
 */
public final class KidsVoice {
    private KidsVoice() {}

    /** Slower than the family setting by a step, never faster than 0.9 and never below 0.75. */
    public static final float KIDS_MAX_SPEED = 0.9f;

    public static float speed(float familySpeed, boolean kids) {
        if (!kids) return familySpeed;
        return Math.max(0.75f, Math.min(KIDS_MAX_SPEED, familySpeed - 0.1f));
    }

    /** Which Claude answers: Sonnet in kids mode (K-D8), the family's choice otherwise. */
    public static String model(String familyModel, boolean kids) { return kids ? Prefs.fastModel() : familyModel; }
}
