package ai.futurespeak.friday.tv.ui;

import android.content.Context;
import android.provider.Settings;

import java.util.function.Function;

/**
 * Reduce motion: the owner's setting in Friday, or (on Auto) Android's "Remove animations".
 * The core app sets {@link #setting} from its own preferences; an add-on gets it from the core
 * through {@link ai.futurespeak.friday.tv.addon.FridayTv}.
 */
public final class Motion {
    private Motion() {}

    /** TRUE = reduce, FALSE = full motion, null (or no setting) = follow Android. */
    public static volatile Function<Context, Boolean> setting;

    public static boolean reduced(Context c) {
        Function<Context, Boolean> s = setting;
        Boolean b = null;
        if (s != null) { try { b = s.apply(c); } catch (Throwable ignored) { } }
        return b != null ? b : systemReduced(c);
    }

    public static boolean systemReduced(Context c) {
        try {
            float a = Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            float t = Settings.Global.getFloat(c.getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE, 1f);
            return a == 0f || t == 0f;
        } catch (Throwable e) {
            return false;
        }
    }
}
