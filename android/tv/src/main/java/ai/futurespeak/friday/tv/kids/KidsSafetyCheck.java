package ai.futurespeak.friday.tv.kids;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import ai.futurespeak.friday.tv.brain.ClaudeDirect;

/**
 * The Claude kid-safety check {@link KidsContent} asks about a topic a channel looks up in kids
 * mode: one short call to the kids-mode model (Sonnet, K-D8), low effort, answer SAFE or UNSAFE.
 * Only the topic's words are sent, never anything about the child. Never logs the key or the topic.
 */
public final class KidsSafetyCheck implements KidsContent.Checker {
    private static final String TAG = "FridayKids";
    private final Supplier<String> key;
    private final String model;
    private volatile int calls;

    public KidsSafetyCheck(Supplier<String> key, String model) { this.key = key; this.model = model; }

    public int calls() { return calls; }

    @Override public Boolean safe(String topic) {
        String k = key.get();
        if (k == null || k.isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            calls++;
            JSONObject body = new JSONObject().put("model", model).put("max_tokens", 1000)
                    .put("thinking", new JSONObject().put("type", "adaptive"))
                    .put("output_config", new JSONObject().put("effort", "low"))
                    .put("system", KidsContent.CHECK_SYSTEM)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", KidsContent.checkUser(topic))));
            c = (HttpURLConnection) new URL(ClaudeDirect.DEFAULT_BASE + "/v1/messages").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(6000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("x-api-key", k);
            c.setRequestProperty("anthropic-version", ClaudeDirect.API_VERSION);
            try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            if (c.getResponseCode() != 200) { Log.w(TAG, "kid-safety check: HTTP " + c.getResponseCode()); return null; }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) { byte[] b = new byte[8192]; for (int n; (n = in.read(b)) > 0; ) bos.write(b, 0, n); }
            JSONObject r = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            if ("refusal".equals(r.optString("stop_reason"))) return false;
            StringBuilder text = new StringBuilder();
            JSONArray content = r.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject blk = content.optJSONObject(i);
                if (blk != null && "text".equals(blk.optString("type"))) text.append(blk.optString("text"));
            }
            return KidsContent.parse(text.toString());
        } catch (Exception e) {
            Log.w(TAG, "kid-safety check failed (" + e.getClass().getSimpleName() + ")");
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
