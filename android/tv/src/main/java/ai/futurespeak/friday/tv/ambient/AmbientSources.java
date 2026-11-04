package ai.futurespeak.friday.tv.ambient;

import android.content.Context;

import java.util.function.Function;

/**
 * Where content sources register for the screensaver. Friday News registers itself here
 * from {@code FridayApp.onCreate}:
 *
 *     AmbientSources.news = ctx -> new NewsAmbientSource(ctx);
 *
 * Until it does, the Screensaver settings say News is on its way and the turn goes elsewhere.
 */
public final class AmbientSources {
    private AmbientSources() {}

    /** Makes the News source for one screensaver run, or null when News isn't built in. */
    public static volatile Function<Context, AmbientSource> news;
}
