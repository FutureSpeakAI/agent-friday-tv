package ai.futurespeak.friday.tv.brain;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The TV talking straight to the Claude API (Messages, streamed).
 * API version 2026-10-21: the key goes in "Authorization: Bearer", and no sampling
 * parameters are sent (they are an error in that version).
 */
public final class ClaudeDirect implements Brain {
    public static final String API_VERSION = "2026-10-21";
    public static final String DEFAULT_BASE = "https://api.anthropic.com";

    /** Where the key comes from (the TV's Keystore-backed store). Never cached here. */
    public interface KeySource { String anthropicKey(); }

    /**
     * How one request reaches the brain, with its own authentication. Null means the Claude
     * API with the TV's key; {@code FridayDesktopClient} sends the same requests to the paired
     * Friday desktop (pinned TLS, device session), which answers in the same stream format.
     */
    public interface Transport {
        boolean ready();
        HttpURLConnection open(String path) throws IOException;
    }

    private final KeySource keys;
    private final Transport transport;
    private final String baseUrl;
    private volatile String model;
    private final int maxTokens;

    public ClaudeDirect(Transport transport, String model, int maxTokens) {
        this.keys = null;
        this.transport = transport;
        this.model = model;
        this.baseUrl = null;
        this.maxTokens = maxTokens;
    }

    public ClaudeDirect(KeySource keys, String model, String baseUrl, int maxTokens) {
        this.transport = null;
        this.keys = keys;
        this.model = model;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
        this.maxTokens = maxTokens;
    }

    public void setModel(String m) { if (m != null && !m.trim().isEmpty()) model = m.trim(); }
    public String model() { return model; }

    @Override public String displayName() { return "Claude"; }

    @Override public boolean ready() {
        if (transport != null) return transport.ready();
        String k = keys.anthropicKey();
        return k != null && !k.isEmpty();
    }

    /** The request body. Package-private so tests can read exactly what is sent. */
    static final String EFFORT = "low";
    /** Room for thinking plus a full channel page (a rich page is 3-8k tokens). */
    public static final int MAX_TOKENS = 32000;

    static JSONObject body(String model, int maxTokens, String system, List<Message> history) throws JSONException {
        return body(model, maxTokens, system, history, null, null);
    }

    /**
     * @param tools offered tools (from the catalogue), or null
     * @param extra raw messages after the history (this turn's tool_use / tool_result rounds), or null
     */
    static JSONObject body(String model, int maxTokens, String system, List<Message> history, JSONArray tools, JSONArray extra) throws JSONException {
        JSONArray msgs = new JSONArray();
        for (Message m : normalize(history)) msgs.put(new JSONObject().put("role", m.role).put("content", m.text));
        if (extra != null) for (int i = 0; i < extra.length(); i++) msgs.put(extra.get(i));
        JSONObject b = new JSONObject()
                .put("model", model)
                .put("max_tokens", maxTokens)
                .put("stream", true)
                // Opus 5.5 thinks adaptively and its thinking counts against max_tokens: at the
                // default (medium) effort a channel request spent all 8k tokens thinking and the
                // page was cut off. Low effort keeps spoken replies quick; the TV also asks for a
                // larger ceiling (see MainActivity) so a whole channel page always fits.
                .put("thinking", new JSONObject().put("type", "adaptive"))
                .put("output_config", new JSONObject().put("effort", EFFORT))
                .put("messages", msgs);
        if (system != null && !system.isEmpty()) b.put("system", system);
        if (tools != null && tools.length() > 0) b.put("tools", tools);
        return b;
    }

    /**
     * The API wants user/assistant turns alternating and starting with the user. Drops
     * leading assistant turns and empty ones, and joins repeats of the same role.
     */
    static List<Message> normalize(List<Message> in) {
        List<Message> out = new ArrayList<>();
        for (Message m : in) {
            if (m.text.trim().isEmpty()) continue;
            if (out.isEmpty() && !Message.USER.equals(m.role)) continue;
            if (!out.isEmpty() && out.get(out.size() - 1).role.equals(m.role)) {
                Message prev = out.remove(out.size() - 1);
                out.add(new Message(m.role, prev.text + "\n\n" + m.text, m.at));
            } else out.add(m);
        }
        return out;
    }

    /** How many times one spoken turn may go round tool use and back before it must answer. */
    static final int MAX_TOOL_ROUNDS = 3;

    @Override
    public Turn send(String systemPrompt, List<Message> history, Listener listener) {
        return send(systemPrompt, history, null, listener);
    }

    @Override
    public Turn send(String systemPrompt, List<Message> history, JSONArray tools, Listener listener) {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final HttpURLConnection[] conn = new HttpURLConnection[1];
        final List<Message> snapshot = new ArrayList<>(history);
        Thread t = new Thread(() -> run(systemPrompt, snapshot, tools, listener, cancelled, conn), "claude-turn");
        t.setDaemon(true);
        t.start();
        return () -> {
            cancelled.set(true);
            HttpURLConnection c = conn[0];
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        };
    }

    private void run(String system, List<Message> history, JSONArray tools, Listener l, AtomicBoolean cancelled, HttpURLConnection[] holder) {
        String key = transport != null ? "" : keys.anthropicKey();
        if (transport == null && (key == null || key.isEmpty())) {
            l.onError(new BrainException(BrainException.Kind.NO_KEY, 0, "no Anthropic key"));
            return;
        }
        JSONArray extra = new JSONArray();
        StringBuilder said = new StringBuilder();
        try {
            for (int round = 0; ; round++) {
                Round r = post(key, body(model, maxTokens, system, history, tools, extra), l, cancelled, holder);
                if (r == null || cancelled.get()) return;
                if (r.text.length() > 0) {
                    if (said.length() > 0 && !Character.isWhitespace(said.charAt(said.length() - 1))) said.append(' ');
                    said.append(r.text);
                }
                if (!"tool_use".equals(r.stop) || tools == null || round >= MAX_TOOL_ROUNDS) {
                    l.onDone(said.toString(), r.stop);
                    return;
                }
                // The brain proposed one or more actions: each goes to the TV (the gate), in order.
                JSONArray results = new JSONArray();
                boolean end = false;
                for (int i = 0; i < r.blocks.length(); i++) {
                    JSONObject b = r.blocks.getJSONObject(i);
                    if (!"tool_use".equals(b.optString("type"))) continue;
                    String toModel;
                    if (end) toModel = "NOT RUN: the TV already acted on an earlier request in this turn.";
                    else {
                        JSONObject input = b.optJSONObject("input");
                        ToolReply tr = l.onTool(b.optString("name"), input == null ? new JSONObject() : input);
                        toModel = tr == null ? "REFUSED by the TV." : tr.toModel;
                        end = tr != null && tr.endTurn;
                    }
                    results.put(new JSONObject().put("type", "tool_result").put("tool_use_id", b.optString("id")).put("content", toModel));
                    if (cancelled.get()) return;
                }
                if (end) { l.onDone(said.toString(), "tool_use"); return; }
                extra.put(new JSONObject().put("role", "assistant").put("content", r.blocks));
                extra.put(new JSONObject().put("role", "user").put("content", results));
            }
        } catch (JSONException e) {
            if (!cancelled.get()) l.onError(new BrainException(BrainException.Kind.OTHER, 0, "bad json"));
        }
    }

    /** One request and its streamed reply; null when an error was already reported (or cancelled). */
    private Round post(String key, JSONObject body, Listener l, AtomicBoolean cancelled, HttpURLConnection[] holder) throws JSONException {
        HttpURLConnection c = null;
        try {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            if (transport != null) {
                c = transport.open("/v1/messages");   // the paired desktop: its own pinned session, no TV key
            } else {
                c = (HttpURLConnection) new URL(baseUrl + "/v1/messages").openConnection();
                c.setRequestProperty("Authorization", "Bearer " + key);
            }
            holder[0] = c;
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            c.setRequestProperty("anthropic-version", API_VERSION);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("accept", "text/event-stream");
            try (OutputStream os = c.getOutputStream()) { os.write(payload); }
            int status = c.getResponseCode();
            if (status != 200) {
                String type = errorType(c.getErrorStream());
                if (!cancelled.get()) l.onError(BrainException.fromStatus(status, type));
                return null;
            }
            return round(c.getInputStream(), l, cancelled);
        } catch (IOException e) {
            if (!cancelled.get()) l.onError(new BrainException(BrainException.Kind.NETWORK, 0, "network: " + e.getClass().getSimpleName()));
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** One streamed reply: its text, why it stopped, and its content blocks as received (sent back unchanged after tool use). */
    static final class Round {
        final String text, stop;
        final JSONArray blocks;
        Round(String text, String stop, JSONArray blocks) { this.text = text; this.stop = stop; this.blocks = blocks; }
    }

    /** Reads the event stream and reports the end with onDone. Package-private for tests. */
    static void stream(InputStream in, Listener l, AtomicBoolean cancelled) throws IOException, JSONException {
        Round r = round(in, l, cancelled);
        if (r != null && !cancelled.get()) l.onDone(r.text, r.stop);
    }

    /**
     * Reads one event stream. Text streams to {@code onText}. Content blocks are rebuilt in order
     * (thinking with its signature, redacted thinking, text, tool_use with its parsed input) so a
     * tool_use round can be sent back unchanged. Returns null when an error was reported or the
     * turn was cancelled. A stream cut off part-way never yields a tool call: its stop is "incomplete".
     */
    static Round round(InputStream in, Listener l, AtomicBoolean cancelled) throws IOException, JSONException {
        SseParser sse = new SseParser();
        StringBuilder all = new StringBuilder();
        java.util.TreeMap<Integer, JSONObject> blocks = new java.util.TreeMap<>();
        java.util.Map<Integer, StringBuilder> json = new java.util.HashMap<>();
        String stop = null;
        boolean ended = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            loop:
            while ((line = r.readLine()) != null) {
                if (cancelled.get()) return null;
                SseParser.Event e = sse.line(line);
                if (e == null || e.data.isEmpty()) continue;
                JSONObject d = new JSONObject(e.data);
                String type = d.optString("type", e.name);
                int idx = d.optInt("index", 0);
                switch (type) {
                    case "content_block_start": {
                        JSONObject cb = d.optJSONObject("content_block");
                        if (cb != null) blocks.put(idx, new JSONObject(cb.toString()));
                        break;
                    }
                    case "content_block_delta": {
                        JSONObject delta = d.optJSONObject("delta");
                        if (delta == null) break;
                        JSONObject b = blocks.get(idx);
                        switch (delta.optString("type")) {
                            case "text_delta": {
                                String s = delta.optString("text", "");
                                if (!s.isEmpty()) {
                                    all.append(s);
                                    l.onText(s);
                                    if (b != null) b.put("text", b.optString("text", "") + s);
                                }
                                break;
                            }
                            case "thinking_delta":
                                if (b != null) b.put("thinking", b.optString("thinking", "") + delta.optString("thinking", ""));
                                break;
                            case "signature_delta":
                                if (b != null) b.put("signature", b.optString("signature", "") + delta.optString("signature", ""));
                                break;
                            case "input_json_delta": {
                                StringBuilder sb = json.get(idx);
                                if (sb == null) { sb = new StringBuilder(); json.put(idx, sb); }
                                sb.append(delta.optString("partial_json", ""));
                                break;
                            }
                            default:
                                break;
                        }
                        break;
                    }
                    case "content_block_stop": {
                        JSONObject b = blocks.get(idx);
                        if (b != null && "tool_use".equals(b.optString("type"))) {
                            StringBuilder sb = json.get(idx);
                            String s = sb == null ? "" : sb.toString().trim();
                            b.put("input", s.isEmpty() ? new JSONObject() : new JSONObject(s));
                        }
                        break;
                    }
                    case "message_delta": {
                        JSONObject delta = d.optJSONObject("delta");
                        if (delta != null && !delta.isNull("stop_reason")) stop = delta.optString("stop_reason", null);
                        break;
                    }
                    case "error": {
                        JSONObject err = d.optJSONObject("error");
                        String et = err == null ? null : err.optString("type", null);
                        l.onError(BrainException.fromStatus("overloaded_error".equals(et) ? 529 : 500, et));
                        return null;
                    }
                    case "message_stop":
                        ended = true;
                        break loop;
                    default:
                        break;  // message_start, ping
                }
            }
        }
        if (cancelled.get()) return null;
        JSONArray out = new JSONArray();
        for (JSONObject b : blocks.values()) {
            if ("text".equals(b.optString("type")) && b.optString("text", "").isEmpty()) continue;  // the API refuses empty text blocks
            out.put(b);
        }
        if (ended) return new Round(all.toString(), stop == null ? "end_turn" : stop, out);
        if (all.length() > 0) return new Round(all.toString(), "incomplete", out);
        l.onError(new BrainException(BrainException.Kind.NETWORK, 0, "stream ended early"));
        return null;
    }

    private static String errorType(InputStream err) {
        if (err == null) return null;
        try (InputStream in = err) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0 && bos.size() < 65536) bos.write(buf, 0, n);
            JSONObject o = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            JSONObject e = o.optJSONObject("error");
            return e == null ? null : e.optString("type", null);
        } catch (Exception ignored) {
            return null;
        }
    }
}
