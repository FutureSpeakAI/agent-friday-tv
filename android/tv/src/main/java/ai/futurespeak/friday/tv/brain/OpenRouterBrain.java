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
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * A second brain through OpenRouter's chat completions (OpenAI-style,
 * streamed). Same system prompt, same history, and the same catalogue as tools: Claude's tool
 * shapes are converted to function tools, and every tool call goes to the listener's
 * {@code onTool}, which is the TV's one ActionGate. A different model gets no different powers.
 * The cost OpenRouter reports for each reply is kept ({@link #lastCostUsd}) for its receipt.
 */
public final class OpenRouterBrain implements Brain {
    public static final String BASE = "https://openrouter.ai/api/v1";
    static final int MAX_TOOL_ROUNDS = 3;

    private final Supplier<String> key;
    private final String base;
    private volatile String model, name;
    private final int maxTokens;
    private volatile double lastCostUsd = 0;

    public OpenRouterBrain(Supplier<String> key, String model, String name, int maxTokens) { this(key, BASE, model, name, maxTokens); }
    OpenRouterBrain(Supplier<String> key, String base, String model, String name, int maxTokens) {
        this.key = key; this.base = base; this.model = model; this.name = name; this.maxTokens = maxTokens;
    }

    public void setModel(String id, String displayName) { model = id; name = displayName; }
    public String model() { return model; }
    /** What OpenRouter charged for the last finished reply, in dollars (all rounds of it). */
    public double lastCostUsd() { return lastCostUsd; }

    @Override public String displayName() { return name + " via OpenRouter"; }
    @Override public boolean ready() { String k = key.get(); return k != null && !k.isEmpty() && model != null; }

    @Override public Turn send(String systemPrompt, List<Message> history, Listener listener) { return send(systemPrompt, history, null, listener); }

    /** Claude-shaped tools ({name, description, input_schema}) as OpenAI function tools. */
    static JSONArray functions(JSONArray claudeTools) throws JSONException {
        JSONArray out = new JSONArray();
        if (claudeTools == null) return out;
        for (int i = 0; i < claudeTools.length(); i++) {
            JSONObject t = claudeTools.getJSONObject(i);
            out.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                    .put("name", t.getString("name")).put("description", t.optString("description"))
                    .put("parameters", t.optJSONObject("input_schema") == null ? new JSONObject().put("type", "object") : t.getJSONObject("input_schema"))));
        }
        return out;
    }

    static JSONObject body(String model, int maxTokens, String system, List<Message> history, JSONArray tools, JSONArray extra) throws JSONException {
        JSONArray msgs = new JSONArray();
        if (system != null && !system.isEmpty()) msgs.put(new JSONObject().put("role", "system").put("content", system));
        for (Message m : ClaudeDirect.normalize(history)) msgs.put(new JSONObject().put("role", m.role).put("content", m.text));
        if (extra != null) for (int i = 0; i < extra.length(); i++) msgs.put(extra.get(i));
        JSONObject b = new JSONObject().put("model", model).put("stream", true).put("max_tokens", maxTokens).put("messages", msgs);
        JSONArray f = functions(tools);
        if (f.length() > 0) b.put("tools", f);
        return b;
    }

    @Override public Turn send(String system, List<Message> history, JSONArray tools, Listener l) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        HttpURLConnection[] conn = new HttpURLConnection[1];
        List<Message> snapshot = new ArrayList<>(history);
        Thread t = new Thread(() -> run(system, snapshot, tools, l, cancelled, conn), "openrouter-turn");
        t.setDaemon(true);
        t.start();
        return () -> { cancelled.set(true); HttpURLConnection c = conn[0]; if (c != null) try { c.disconnect(); } catch (Throwable ignored) { } };
    }

    /** One streamed round: its text, why it stopped, its tool calls (id, name, arguments), and its cost. */
    static final class Round {
        final StringBuilder text = new StringBuilder();
        String finish;
        final TreeMap<Integer, String[]> calls = new TreeMap<>();   // index -> {id, name, args}
        double cost;
        boolean ended;
    }

    private void run(String system, List<Message> history, JSONArray tools, Listener l, AtomicBoolean cancelled, HttpURLConnection[] holder) {
        String k = key.get();
        if (k == null || k.isEmpty()) { l.onError(new BrainException(BrainException.Kind.NO_KEY, 0, "no OpenRouter key")); return; }
        JSONArray extra = new JSONArray();
        StringBuilder said = new StringBuilder();
        double cost = 0;
        try {
            for (int round = 0; ; round++) {
                Round r = post(k, body(model, maxTokens, system, history, tools, extra), l, cancelled, holder);
                if (r == null || cancelled.get()) return;
                cost += r.cost;
                if (r.text.length() > 0) { if (said.length() > 0 && !Character.isWhitespace(said.charAt(said.length() - 1))) said.append(' '); said.append(r.text); }
                boolean wantsTools = "tool_calls".equals(r.finish) && !r.calls.isEmpty();
                if (!wantsTools || tools == null || round >= MAX_TOOL_ROUNDS) {
                    lastCostUsd = cost;
                    l.onDone(said.toString(), !r.ended ? "incomplete" : wantsTools ? "tool_use" : "length".equals(r.finish) ? "max_tokens" : "end_turn");
                    return;
                }
                JSONArray calls = new JSONArray(), results = new JSONArray();
                boolean end = false;
                for (String[] c : r.calls.values()) {
                    calls.put(new JSONObject().put("id", c[0]).put("type", "function").put("function", new JSONObject().put("name", c[1]).put("arguments", c[2].isEmpty() ? "{}" : c[2])));
                    String toModel;
                    if (end) toModel = "NOT RUN: the TV already acted on an earlier request in this turn.";
                    else {
                        JSONObject args;
                        try { args = c[2].trim().isEmpty() ? new JSONObject() : new JSONObject(c[2]); } catch (JSONException e) { args = null; }
                        if (args == null) toModel = "REFUSED by the TV: the arguments were not valid JSON.";
                        else { ToolReply tr = l.onTool(c[1], args); toModel = tr == null ? "REFUSED by the TV." : tr.toModel; end = tr != null && tr.endTurn; }
                    }
                    results.put(new JSONObject().put("role", "tool").put("tool_call_id", c[0]).put("content", toModel));
                    if (cancelled.get()) return;
                }
                if (end) { lastCostUsd = cost; l.onDone(said.toString(), "tool_use"); return; }
                extra.put(new JSONObject().put("role", "assistant").put("content", r.text.length() == 0 ? JSONObject.NULL : r.text.toString()).put("tool_calls", calls));
                for (int i = 0; i < results.length(); i++) extra.put(results.get(i));
            }
        } catch (JSONException e) {
            if (!cancelled.get()) l.onError(new BrainException(BrainException.Kind.OTHER, 0, "bad json"));
        }
    }

    private Round post(String k, JSONObject body, Listener l, AtomicBoolean cancelled, HttpURLConnection[] holder) {
        HttpURLConnection c = null;
        try {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(base + "/chat/completions").openConnection();
            holder[0] = c;
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            c.setRequestProperty("Authorization", "Bearer " + k);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("accept", "text/event-stream");
            c.setRequestProperty("X-Title", "Agent Friday TV");
            try (OutputStream os = c.getOutputStream()) { os.write(payload); }
            int status = c.getResponseCode();
            if (status != 200) {
                if (!cancelled.get()) l.onError(status == 402 ? new BrainException(BrainException.Kind.OTHER, 402, "out of credits")
                        : BrainException.fromStatus(status, null));
                return null;
            }
            return round(c.getInputStream(), l, cancelled);
        } catch (IOException e) {
            if (!cancelled.get()) l.onError(new BrainException(BrainException.Kind.NETWORK, 0, "network: " + e.getClass().getSimpleName()));
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** Reads OpenRouter's stream: "data: {chunk}" lines (comments ignored) until "data: [DONE]". Package-private for tests. */
    static Round round(InputStream in, Listener l, AtomicBoolean cancelled) throws IOException {
        Round r = new Round();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (cancelled.get()) return null;
                if (!line.startsWith("data:")) continue;   // ": OPENROUTER PROCESSING" keep-alives and blank lines
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) { r.ended = true; break; }
                JSONObject d;
                try { d = new JSONObject(data); } catch (JSONException e) { continue; }
                if (d.has("error")) {
                    JSONObject e = d.optJSONObject("error");
                    l.onError(new BrainException(BrainException.Kind.SERVER, e == null ? 0 : e.optInt("code"), e == null ? "error" : e.optString("message", "error")));
                    return null;
                }
                JSONObject usage = d.optJSONObject("usage");
                if (usage != null) r.cost = usage.optDouble("cost", r.cost);
                JSONArray ch = d.optJSONArray("choices");
                if (ch == null || ch.length() == 0) continue;
                JSONObject c0 = ch.optJSONObject(0);
                if (c0 == null) continue;
                if (!c0.isNull("finish_reason") && c0.has("finish_reason")) r.finish = c0.optString("finish_reason");
                JSONObject delta = c0.optJSONObject("delta");
                if (delta == null) continue;
                String text = delta.isNull("content") ? "" : delta.optString("content", "");
                if (!text.isEmpty()) { r.text.append(text); l.onText(text); }
                JSONArray tc = delta.optJSONArray("tool_calls");
                if (tc != null) for (int i = 0; i < tc.length(); i++) {
                    JSONObject t = tc.optJSONObject(i);
                    if (t == null) continue;
                    int idx = t.optInt("index", i);
                    String[] cur = r.calls.get(idx);
                    if (cur == null) { cur = new String[]{"", "", ""}; r.calls.put(idx, cur); }
                    if (t.has("id") && !t.optString("id").isEmpty()) cur[0] = t.optString("id");
                    JSONObject fn = t.optJSONObject("function");
                    if (fn != null) {
                        if (fn.has("name") && !fn.optString("name").isEmpty()) cur[1] = fn.optString("name");
                        cur[2] = cur[2] + fn.optString("arguments", "");
                    }
                }
            }
        }
        if (cancelled.get()) return null;
        if (!r.ended && r.text.length() == 0) { l.onError(new BrainException(BrainException.Kind.NETWORK, 0, "stream ended early")); return null; }
        // A cut-off stream never runs a tool call.
        if (!r.ended) r.calls.clear();
        return r;
    }
}
