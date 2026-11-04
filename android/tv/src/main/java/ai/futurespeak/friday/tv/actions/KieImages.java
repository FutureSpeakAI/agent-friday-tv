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

import ai.futurespeak.friday.tv.cards.CardSpec;
import ai.futurespeak.friday.tv.cards.HostRules;
import ai.futurespeak.friday.tv.web.SafeFetch;

/**
 * Nano Banana 2 through kie.ai's market API (https://docs.kie.ai/market/google/nanobanana2):
 * {@code POST /api/v1/jobs/createTask} with model {@code nano-banana-2}, then
 * {@code GET /api/v1/jobs/recordInfo?taskId=} until {@code success} or {@code fail}, then the
 * picture is fetched from its result address through {@link SafeFetch} (https, public host,
 * image types only, size-capped). No callback address: the TV has nothing listening.
 */
public final class KieImages implements Images {
    public static final String BASE = "https://api.kie.ai";
    static final String MODEL = ai.futurespeak.friday.tv.addon.Providers.DEFAULT_IMAGE_MODEL;
    /** The owner's image model (Providers), defaulting to {@link #MODEL}. */
    static String model() { return ai.futurespeak.friday.tv.Prefs.providersNow().imageModel; }
    static final long POLL_MS = 2500, LIMIT_MS = 150_000;
    private static final int MAX_IMAGE = 12 * 1024 * 1024;

    public interface Sleeper { void sleep(long ms) throws InterruptedException; }
    public interface Fetch { byte[] get(String url) throws IOException; }

    private final Supplier<String> key;
    private final String base;
    private final Sleeper sleeper;
    private final Fetch fetch;

    public KieImages(Supplier<String> key) {
        this(key, BASE, Thread::sleep, url -> {
            String safe = CardSpec.safeImageUrl(url);
            if (safe == null) throw new IOException("result address not allowed");
            SafeFetch.Result r = SafeFetch.get(safe, HostRules::isPublicHostname, t -> t.startsWith("image/") && !t.equals("image/svg+xml"), MAX_IMAGE);
            if (r.status != 200) throw new IOException("picture answered " + r.status);
            return r.body;
        });
    }

    KieImages(Supplier<String> key, String base, Sleeper sleeper, Fetch fetch) {
        this.key = key; this.base = base; this.sleeper = sleeper; this.fetch = fetch;
    }

    /** Ready with a key, unless the owner switched generated pictures off (Providers: images "none"). */
    @Override public boolean ready() {
        if (!"kie".equals(ai.futurespeak.friday.tv.Prefs.providersNow().imageProvider)) return false;
        String k = key.get(); return k != null && !k.isEmpty();
    }
    @Override public String service() { return "kie.ai · Nano Banana 2"; }

    static JSONObject createBody(String prompt) throws JSONException {
        return new JSONObject().put("model", model()).put("input", new JSONObject()
                .put("prompt", prompt).put("aspect_ratio", "16:9").put("resolution", "1K").put("output_format", "png"));
    }

    @Override public Result create(String prompt, Progress progress) {
        double credits = 0;
        try {
            JSONObject c = call("POST", "/api/v1/jobs/createTask", createBody(prompt));
            if (c.optInt("code") != 200) return Result.fail(words(c.optInt("code"), c.optString("msg")), 0);
            String task = c.getJSONObject("data").getString("taskId");
            if (progress != null) progress.at(0, "Queued");
            long waited = 0;
            while (waited <= LIMIT_MS) {
                sleeper.sleep(POLL_MS);
                waited += POLL_MS;
                JSONObject r = call("GET", "/api/v1/jobs/recordInfo?taskId=" + java.net.URLEncoder.encode(task, "UTF-8"), null);
                if (r.optInt("code") != 200) return Result.fail(words(r.optInt("code"), r.optString("msg")), credits);
                JSONObject d = r.getJSONObject("data");
                credits = d.optDouble("creditsConsumed", credits);
                String state = d.optString("state");
                if (progress != null) progress.at(d.optInt("progress", -1), stateWords(state));
                if ("fail".equals(state)) return Result.fail("couldn't make it" + (d.optString("failMsg").isEmpty() ? "" : ": " + d.optString("failMsg")), credits);
                if ("success".equals(state)) {
                    JSONArray urls = new JSONObject(d.optString("resultJson", "{}")).optJSONArray("resultUrls");
                    if (urls == null || urls.length() == 0) return Result.fail("no picture came back", credits);
                    byte[] bytes = fetch.get(urls.getString(0));
                    String mime = Images.sniff(bytes);
                    if (mime == null) return Result.fail("what came back isn't a picture", credits);
                    return new Result(true, null, bytes, mime, credits > 0 ? credits : EST_CREDITS, model());
                }
            }
            return Result.fail("it took too long", credits);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("stopped", credits);
        } catch (Exception e) {
            return Result.fail(e instanceof IOException && e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), credits);
        }
    }

    static String stateWords(String s) {
        switch (s == null ? "" : s) {
            case "waiting": case "queuing": return "Queued";
            case "generating": return "Drawing";
            case "success": return "Done";
            default: return "Working";
        }
    }

    static String words(int code, String msg) {
        switch (code) {
            case 401: return "key refused";
            case 402: return "out of credits";
            case 429: return "too many requests";
            case 422: return "rejected";
            default: return "kie.ai answered " + code;
        }
    }

    private JSONObject call(String method, String path, JSONObject body) throws IOException, JSONException {
        String k = key.get();
        if (k == null || k.isEmpty()) throw new IOException("no key");
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Authorization", "Bearer " + k);
            if (body != null) {
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(payload.length);
                c.setRequestProperty("content-type", "application/json");
                try (OutputStream os = c.getOutputStream()) { os.write(payload); }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return new JSONObject().put("code", status);
            try (InputStream i = in) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = i.read(buf)) > 0) { if (bos.size() + n > 1_000_000) throw new IOException("too large"); bos.write(buf, 0, n); }
                String s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                try { JSONObject o = new JSONObject(s); if (!o.has("code")) o.put("code", status); return o; }
                catch (JSONException e) { return new JSONObject().put("code", status); }
            }
        } finally {
            c.disconnect();
        }
    }
}
