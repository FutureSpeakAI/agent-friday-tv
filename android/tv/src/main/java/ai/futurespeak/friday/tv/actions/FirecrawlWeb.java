package ai.futurespeak.friday.tv.actions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Firecrawl v2 (https://docs.firecrawl.dev): {@code POST /v2/search} for results (title, url,
 * description; no page scraping, so 2 credits per 10 results) and {@code POST /v2/scrape} for one
 * page as markdown (main content only, 1 credit). The key comes from the TV's Keystore-sealed
 * store on every call and is never logged. Only this one host is ever called.
 */
public final class FirecrawlWeb implements Web {
    public static final String BASE = "https://api.firecrawl.dev/v2";
    static final int SEARCH_LIMIT = 5;
    private static final int MAX_BYTES = 2_000_000;

    private final Supplier<String> key;
    private final String base;

    public FirecrawlWeb(Supplier<String> key) { this(key, BASE); }
    FirecrawlWeb(Supplier<String> key, String base) { this.key = key; this.base = base; }

    @Override public boolean ready() { String k = key.get(); return k != null && !k.isEmpty(); }

    static JSONObject searchBody(String query) throws JSONException {
        return new JSONObject().put("query", query).put("limit", SEARCH_LIMIT).put("sources", new JSONArray().put("web"));
    }

    static JSONObject scrapeBody(String url) throws JSONException {
        return new JSONObject().put("url", url).put("formats", new JSONArray().put("markdown"))
                .put("onlyMainContent", true).put("timeout", 30000);
    }

    @Override public Result search(String query) {
        try {
            JSONObject r = post("/search", searchBody(query));
            return parseSearch(query, r);
        } catch (HttpError e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            return Result.fail(e.getClass().getSimpleName());
        }
    }

    @Override public Result read(String url) {
        try {
            JSONObject r = post("/scrape", scrapeBody(url));
            return parseScrape(url, r);
        } catch (HttpError e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            return Result.fail(e.getClass().getSimpleName());
        }
    }

    static Result parseSearch(String query, JSONObject r) {
        if (!r.optBoolean("success", false)) return Result.fail("not successful");
        JSONObject data = r.optJSONObject("data");
        JSONArray web = data == null ? r.optJSONArray("data") : data.optJSONArray("web");
        StringBuilder s = new StringBuilder();
        int n = 0;
        if (web != null) for (int i = 0; i < web.length(); i++) {
            JSONObject w = web.optJSONObject(i);
            if (w == null) continue;
            n++;
            s.append(n).append(". ").append(w.optString("title")).append("\n   ").append(w.optString("url"))
             .append("\n   ").append(w.optString("description")).append("\n");
        }
        double credits = r.has("creditsUsed") ? r.optDouble("creditsUsed", CREDITS_SEARCH) : CREDITS_SEARCH;
        return new Result(true, null, "Search: " + query, "", n == 0 ? "(no results)" : s.toString().trim(), credits);
    }

    static Result parseScrape(String url, JSONObject r) {
        if (!r.optBoolean("success", false)) return Result.fail("not successful");
        JSONObject data = r.optJSONObject("data");
        if (data == null) return Result.fail("no data");
        JSONObject meta = data.optJSONObject("metadata");
        String title = meta == null ? "" : meta.optString("title", "");
        String src = meta == null ? url : meta.optString("sourceURL", meta.optString("url", url));
        double credits = meta != null && meta.has("creditsUsed") ? meta.optDouble("creditsUsed", CREDITS_READ) : CREDITS_READ;
        int status = meta == null ? 200 : meta.optInt("statusCode", 200);
        if (status >= 400) return new Result(false, "page answered " + status, title, src, "", credits);
        return new Result(true, null, title, src, data.optString("markdown", ""), credits);
    }

    static final class HttpError extends IOException { HttpError(String m) { super(m); } }

    private JSONObject post(String path, JSONObject body) throws IOException, JSONException {
        String k = key.get();
        if (k == null || k.isEmpty()) throw new HttpError("no key");
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(45000);
            c.setDoOutput(true);
            c.setInstanceFollowRedirects(false);
            c.setFixedLengthStreamingMode(payload.length);
            c.setRequestProperty("Authorization", "Bearer " + k);
            c.setRequestProperty("content-type", "application/json");
            try (OutputStream os = c.getOutputStream()) { os.write(payload); }
            int code = c.getResponseCode();
            if (code != 200) {
                String why = code == 401 ? "key refused" : code == 402 ? "out of credits" : code == 429 ? "too many requests" : code == 408 ? "timeout" : "http " + code;
                throw new HttpError(why);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) { if (bos.size() + n > MAX_BYTES) throw new HttpError("too large"); bos.write(buf, 0, n); }
                return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }
}
