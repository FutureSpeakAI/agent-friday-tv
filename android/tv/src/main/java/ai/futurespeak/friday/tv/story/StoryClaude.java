package ai.futurespeak.friday.tv.story;

import ai.futurespeak.friday.tv.brain.ClaudeDirect;
import ai.futurespeak.friday.tv.studio.Engines;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * The story's writer: one non-streamed Messages API call per page, with its token usage so the
 * receipt carries the actual cost. Same host, key and API version as the conversation's brain;
 * Sonnet at low effort (K-D8), because a child is waiting. Never logs the key or the words.
 */
public final class StoryClaude implements Engines.Writer {
    private final Supplier<String> key;
    private final String model;

    public StoryClaude(Supplier<String> key, String model) { this.key = key; this.model = model; }

    @Override public String model() { return model; }

    @Override public Engines.Reply write(String system, String user, int maxTokens) throws Exception {
        String k = key.get();
        if (k == null || k.isEmpty()) throw new java.io.IOException("no key");
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("thinking", new JSONObject().put("type", "adaptive"))
                .put("output_config", new JSONObject().put("effort", "low"))
                .put("system", new JSONArray().put(new JSONObject().put("type", "text").put("text", system)
                        .put("cache_control", new JSONObject().put("type", "ephemeral"))))
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", user)))));
        HttpURLConnection c = (HttpURLConnection) new URL(ClaudeDirect.DEFAULT_BASE + "/v1/messages").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(90_000);
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("x-api-key", k);
            c.setRequestProperty("anthropic-version", ClaudeDirect.API_VERSION);
            try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) { bos.write(buf, 0, n); if (bos.size() > 4 * 1024 * 1024) throw new java.io.IOException("too long"); }
            }
            JSONObject r = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            if ("refusal".equals(r.optString("stop_reason"))) throw new java.io.IOException("declined");
            StringBuilder text = new StringBuilder();
            JSONArray content = r.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject b = content.optJSONObject(i);
                if (b != null && "text".equals(b.optString("type"))) text.append(b.optString("text"));
            }
            JSONObject u = r.optJSONObject("usage");
            if (u == null) u = new JSONObject();
            return new Engines.Reply(text.toString(), u.optLong("input_tokens"), u.optLong("output_tokens"),
                    u.optLong("cache_read_input_tokens"), u.optLong("cache_creation_input_tokens"));
        } finally {
            c.disconnect();
        }
    }
}
