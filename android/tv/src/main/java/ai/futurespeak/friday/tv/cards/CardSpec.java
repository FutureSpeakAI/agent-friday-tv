package ai.futurespeak.friday.tv.cards;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks and trims the cards Claude sends before anything is drawn. Accepts one card
 * object, an array of cards, or {"cards":[...]}. Unknown types are dropped; every string is
 * length-capped; image URLs must be plain https. The result is what the card renderer gets.
 */
public final class CardSpec {
    public static final int MAX_CARDS = 8;
    public static final int MAX_HTML = 150_000;
    static final int MAX_TEXT = 6000, MAX_TITLE = 120, MAX_ITEMS = 100, MAX_ROWS = 200, MAX_COLS = 12,
            MAX_SERIES = 6, MAX_POINTS = 100, MAX_CELL = 300;

    private CardSpec() {}

    public static List<JSONObject> parse(String json) {
        List<JSONObject> out = new ArrayList<>();
        if (json == null) return out;
        Object root;
        try { root = new JSONTokener(stripFence(json)).nextValue(); }
        catch (JSONException e) { return out; }
        JSONArray arr = null;
        if (root instanceof JSONArray) arr = (JSONArray) root;
        else if (root instanceof JSONObject) {
            JSONObject o = (JSONObject) root;
            if (o.optJSONArray("cards") != null) arr = o.optJSONArray("cards");
            else { arr = new JSONArray(); arr.put(o); }
        }
        if (arr == null) return out;
        for (int i = 0; i < arr.length() && out.size() < MAX_CARDS; i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            try {
                JSONObject v = one(c);
                if (v != null) out.add(v);
            } catch (JSONException ignored) { }
        }
        return out;
    }

    /** Claude sometimes wraps JSON in a ```json fence. */
    public static String stripFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (nl > 0 && end > nl) t = t.substring(nl + 1, end).trim();
        }
        return t;
    }

    static JSONObject one(JSONObject c) throws JSONException {
        String type = c.optString("type", "text");
        JSONObject v = new JSONObject().put("type", type).put("title", cap(c.optString("title", ""), MAX_TITLE));
        switch (type) {
            case "text":
                String body = cap(c.optString("body", c.optString("text", "")), MAX_TEXT);
                if (body.isEmpty()) return null;
                return v.put("body", body);
            case "list": {
                JSONArray items = strings(c.optJSONArray("items"), MAX_ITEMS, MAX_CELL * 2);
                if (items.length() == 0) return null;
                return v.put("items", items).put("ordered", c.optBoolean("ordered", false));
            }
            case "table": {
                JSONArray cols = strings(c.optJSONArray("columns"), MAX_COLS, MAX_CELL);
                JSONArray rows = new JSONArray();
                JSONArray in = c.optJSONArray("rows");
                if (in != null) for (int i = 0; i < in.length() && rows.length() < MAX_ROWS; i++) {
                    JSONArray r = in.optJSONArray(i);
                    if (r != null) rows.put(strings(r, MAX_COLS, MAX_CELL));
                }
                if (rows.length() == 0) return null;
                return v.put("columns", cols).put("rows", rows);
            }
            case "image": {
                // A picture the TV made and saved itself (slice T3): referred to by id only, never a path or address.
                String gen = c.optString("gen", "");
                if (!gen.isEmpty()) {
                    if (!gen.matches("^[a-z0-9-]{6,64}$")) return null;
                    return v.put("gen", gen).put("caption", cap(c.optString("caption", ""), MAX_CELL));
                }
                String url = safeImageUrl(c.optString("url", ""));
                if (url == null) return null;
                return v.put("url", url).put("caption", cap(c.optString("caption", ""), MAX_CELL));
            }
            case "chart": {
                String kind = c.optString("kind", "bar");
                if (!kind.equals("bar") && !kind.equals("line") && !kind.equals("pie")) kind = "bar";
                JSONArray labels = strings(c.optJSONArray("labels"), MAX_POINTS, 60);
                JSONArray series = new JSONArray();
                JSONArray in = c.optJSONArray("series");
                if (in != null) for (int i = 0; i < in.length() && series.length() < MAX_SERIES; i++) {
                    JSONObject s = in.optJSONObject(i);
                    if (s == null) continue;
                    JSONArray vals = new JSONArray();
                    JSONArray raw = s.optJSONArray("values");
                    if (raw != null) for (int j = 0; j < raw.length() && vals.length() < MAX_POINTS; j++) {
                        double d = raw.optDouble(j, Double.NaN);
                        vals.put(Double.isFinite(d) ? d : 0);
                    }
                    if (vals.length() > 0) series.put(new JSONObject().put("name", cap(s.optString("name", ""), 60)).put("values", vals));
                }
                if (series.length() == 0) return null;
                return v.put("kind", kind).put("labels", labels).put("series", series);
            }
            case "html": {
                String html = c.optString("html", "");
                if (html.isEmpty() || html.length() > MAX_HTML) return null;
                return v.put("html", html);
            }
            default:
                return null;
        }
    }

    static String cap(String s, int n) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    static JSONArray strings(JSONArray in, int maxItems, int maxLen) {
        JSONArray out = new JSONArray();
        if (in == null) return out;
        for (int i = 0; i < in.length() && out.length() < maxItems; i++) {
            Object o = in.opt(i);
            if (o == null || o == JSONObject.NULL) out.put("");
            else out.put(cap(String.valueOf(o), maxLen));
        }
        return out;
    }

    /** https only, a host, no user:password, no odd ports. Returns the normalised URL or null. */
    public static String safeImageUrl(String s) {
        try {
            URI u = new URI(s.trim());
            if (!"https".equalsIgnoreCase(u.getScheme())) return null;
            if (u.getHost() == null || u.getRawUserInfo() != null) return null;
            if (u.getPort() != -1 && u.getPort() != 443) return null;
            if (!HostRules.isPublicHostname(u.getHost())) return null;
            return u.toASCIIString();
        } catch (Exception e) {
            return null;
        }
    }
}
