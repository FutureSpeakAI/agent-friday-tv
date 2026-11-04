package ai.futurespeak.friday.tv.actions;

import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Slice S2: "search inside an app", from Friday's own process with the app's own public search
 * entry (spec §8.5 "intent or deep link: first choice"; no D-pad scripts, no typing into other
 * apps). One fixed recipe per app, written by hand and checked on a test TV:
 *
 *  - SmartTube: {@code android.intent.action.SEARCH} with {@code SearchManager.QUERY}
 *    (SearchableActivity, seen in {@code dumpsys package org.smarttube.stable}, v32.68);
 *  - Plex: {@code https://watch.plex.tv/search?q=…} opened in the Plex app (watch.plex.tv is one
 *    of its declared link hosts, v2026.20.0);
 *  - Kodi and Spotify: {@code android.intent.action.SEARCH}, both declare it.
 *
 * Apps without a recipe have no search entry, so the model can't propose one. The query is the
 * person's words: trimmed, control characters removed, at most 100 characters. Pure Java.
 */
public final class SearchRecipes {
    private SearchRecipes() {}

    public static final int MAX_QUERY = 100;

    public static final class Recipe {
        public final String pkg, action, uri;   // uri is null for ACTION_SEARCH recipes
        Recipe(String pkg, String action, String uri) { this.pkg = pkg; this.action = action; this.uri = uri; }
        public boolean isSearchAction() { return uri == null; }
    }

    public static final String ACTION_SEARCH = "android.intent.action.SEARCH";
    public static final String ACTION_VIEW = "android.intent.action.VIEW";

    static final List<String> PACKAGES = Collections.unmodifiableList(Arrays.asList(
            "org.smarttube.stable", "com.plexapp.android", "org.xbmc.kodi", "com.spotify.tv.android"));

    public static boolean has(String pkg) { return PACKAGES.contains(pkg); }

    /** The person's words, made safe to pass on; null when nothing usable is left. */
    public static String clean(String query) {
        if (query == null) return null;
        String q = query.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (q.isEmpty()) return null;
        return q.length() > MAX_QUERY ? q.substring(0, MAX_QUERY).trim() : q;
    }

    static String encode(String q) {
        try { return URLEncoder.encode(q, "UTF-8").replace("+", "%20"); } catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    /** The one recipe for {@code pkg}, or null. */
    public static Recipe recipe(String pkg, String query) {
        String q = clean(query);
        if (q == null || pkg == null) return null;
        switch (pkg) {
            case "org.smarttube.stable":
            case "org.xbmc.kodi":
            case "com.spotify.tv.android":
                return new Recipe(pkg, ACTION_SEARCH, null);
            case "com.plexapp.android":
                return new Recipe(pkg, ACTION_VIEW, "https://watch.plex.tv/search?q=" + encode(q));
            default:
                return null;
        }
    }
}
