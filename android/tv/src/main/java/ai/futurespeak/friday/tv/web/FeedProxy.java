package ai.futurespeak.friday.tv.web;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ai.futurespeak.friday.tv.channels.FeedParser;
import ai.futurespeak.friday.tv.channels.NetPolicy;

/**
 * News for sandboxed pages without giving them the open web. A page asks
 * {@code https://feeds.friday.local/rss?u=<feed url>}; the app fetches that RSS/Atom feed (only
 * from a host the grown-up approved for this page), turns it into plain JSON with
 * {@link FeedParser}, and returns it. Pictures in those stories load through
 * {@code https://feeds.friday.local/img?u=<image url>}, and only images that appeared in a feed
 * this page was given, so the page can't use it to reach anything else.
 */
public final class FeedProxy {
    private static final String TAG = "FridayTV";
    private static final int MAX_FEED = 3 * 1024 * 1024, MAX_IMG = 2 * 1024 * 1024;
    private static final long FRESH_MS = 3 * 60_000L;

    /** Shared by every page: one fetch per feed every few minutes, however many pages ask. */
    private static final Map<String, Object[]> CACHE = Collections.synchronizedMap(new LinkedHashMap<String, Object[]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) { return size() > 40; }
    });

    private final List<String> granted;
    private final Set<String> images = Collections.synchronizedSet(new HashSet<>());
    /** What went wrong last, in plain words, for the "didn't work" message. */
    public volatile String lastProblem = null;

    public FeedProxy(List<String> granted) { this.granted = granted == null ? Collections.emptyList() : granted; }

    public static boolean handles(Uri u) { return NetPolicy.FEEDS_HOST.equals(u.getHost()); }

    public android.webkit.WebResourceResponse serve(Uri u) {
        String target = u.getQueryParameter("u");
        Map<String, String> cors = new HashMap<>();
        cors.put("Access-Control-Allow-Origin", "*");
        if (target == null) return Hardened.response(400, "text/plain", new byte[0], cors);
        String host = hostOf(target);
        if ("/rss".equals(u.getPath()) || "/feed".equals(u.getPath())) {
            if (!granted.contains(host)) {
                lastProblem = "it asked for news from " + host + ", which wasn't approved";
                return json(403, error("This feed wasn't approved: " + host), cors);
            }
            try {
                JSONObject feed = feed(target);
                JSONArray items = feed.optJSONArray("items");
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    String img = items.getJSONObject(i).optString("image", "");
                    if (!img.isEmpty()) images.add(img);
                }
                return json(200, feed, cors);
            } catch (Exception e) {
                lastProblem = "the news feed from " + host + " didn't load (" + plain(e) + ")";
                Log.i(TAG, "feed failed: " + host + " (" + e.getClass().getSimpleName() + ")");
                return json(502, error("Couldn't load " + host), cors);
            }
        }
        if ("/img".equals(u.getPath())) {
            if (!images.contains(target)) return Hardened.response(403, "text/plain", new byte[0], cors);
            try {
                SafeFetch.Result r = SafeFetch.get(target, h -> true, t -> t.startsWith("image/"), MAX_IMG);
                return Hardened.response(r.status, r.contentType.isEmpty() ? "image/jpeg" : r.contentType, r.body, cors);
            } catch (Exception e) {
                return Hardened.response(502, "text/plain", new byte[0], cors);
            }
        }
        return Hardened.response(404, "text/plain", new byte[0], cors);
    }

    private JSONObject feed(String url) throws Exception {
        Object[] hit = CACHE.get(url);
        if (hit != null && System.currentTimeMillis() - (Long) hit[0] < FRESH_MS) return new JSONObject((String) hit[1]);
        // Redirect hops must stay on approved hosts too (some feeds redirect to a CDN or a feeds subdomain).
        SafeFetch.Result r = SafeFetch.get(url, granted::contains,
                t -> t.isEmpty() || t.contains("xml") || t.startsWith("text/"), MAX_FEED);
        if (r.status != 200) throw new java.io.IOException("HTTP " + r.status);
        JSONObject o = FeedParser.parse(r.body, url);
        CACHE.put(url, new Object[]{System.currentTimeMillis(), o.toString()});
        return o;
    }

    private static android.webkit.WebResourceResponse json(int status, JSONObject o, Map<String, String> h) {
        return Hardened.response(status, "application/json", o.toString().getBytes(StandardCharsets.UTF_8), h);
    }

    private static JSONObject error(String m) {
        try { return new JSONObject().put("error", m).put("items", new JSONArray()); } catch (Exception e) { return new JSONObject(); }
    }

    static String hostOf(String url) {
        try { String h = Uri.parse(url).getHost(); return h == null ? "" : h.toLowerCase(Locale.ROOT); } catch (Exception e) { return ""; }
    }

    private static String plain(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof FeedParser.BadFeed) return "it isn't a news feed";
        if (m.contains("host not allowed")) return "it redirects to a site that wasn't approved";
        if (m.startsWith("HTTP")) return "the site answered " + m;
        if (e instanceof java.net.UnknownHostException) return "the site couldn't be found";
        if (e instanceof java.net.SocketTimeoutException) return "the site took too long";
        return "network problem";
    }
}
