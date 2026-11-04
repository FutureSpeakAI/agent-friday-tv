package ai.futurespeak.friday.tv.ambient;

import android.content.Context;
import android.view.View;

/**
 * Something that can fill the screensaver's content panel: the hook other parts of the app
 * (Friday News first) plug into. The screensaver asks for one slide per turn and shows it in a
 * glass panel about 480x300 dp; it removes the view at the next turn.
 *
 * Rules: called on the main thread; must return quickly (have slides ready, or return null
 * and the turn goes to something else); no network or heavy work on the main thread; no
 * focusable views (the screensaver isn't interactive); free everything in {@link #release()}.
 */
public interface AmbientSource {
    /** The next slide, or null if there is nothing to show right now. */
    View next(Context c);

    /** The screensaver stopped: drop anything held. */
    default void release() { }
}
