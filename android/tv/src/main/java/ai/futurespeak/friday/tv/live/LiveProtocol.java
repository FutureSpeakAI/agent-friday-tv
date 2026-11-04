package ai.futurespeak.friday.tv.live;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemini Live's messages (BidiGenerateContent, as Google's own google-genai SDK sends them):
 * {@code setup}, {@code realtime_input.audio} (16 kHz PCM), {@code tool_response}; from the server
 * {@code setupComplete}, {@code serverContent} (24 kHz PCM, transcriptions, interrupted,
 * turnComplete), {@code toolCall}, {@code goAway}, {@code usageMetadata}. Pure, unit-tested.
 */
public final class LiveProtocol {
    private LiveProtocol() {}

    public static final String HOST = "generativelanguage.googleapis.com";
    public static final String PATH = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    /** Checked on 2026-11-02: the general-availability Live model. */
    public static final String MODEL = "models/gemini-3.8-live";
    public static final int IN_RATE = 16000, OUT_RATE = 24000;

    /** The first message: model, spoken answers, the TV's instruction, transcriptions (for "stop" and captions), and the catalogue's tools. */
    public static JSONObject setup(String instruction, JSONArray claudeTools) throws JSONException {
        JSONObject s = new JSONObject().put("model", MODEL)
                .put("generationConfig", new JSONObject().put("responseModalities", new JSONArray().put("AUDIO")))
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", instruction))))
                .put("inputAudioTranscription", new JSONObject())
                .put("outputAudioTranscription", new JSONObject());
        JSONArray decl = new JSONArray();
        if (claudeTools != null) for (int i = 0; i < claudeTools.length(); i++) {
            JSONObject t = claudeTools.getJSONObject(i);
            JSONObject d = new JSONObject().put("name", t.getString("name")).put("description", t.optString("description"));
            JSONObject schema = t.optJSONObject("input_schema");
            if (schema != null && schema.optJSONObject("properties") != null && schema.getJSONObject("properties").length() > 0) d.put("parameters", schema);
            decl.put(d);
        }
        if (decl.length() > 0) s.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", decl)));
        return new JSONObject().put("setup", s);
    }

    public static JSONObject audio(byte[] pcm16k, int len) throws JSONException {
        String b64 = java.util.Base64.getEncoder().encodeToString(len == pcm16k.length ? pcm16k : java.util.Arrays.copyOf(pcm16k, len));
        return new JSONObject().put("realtime_input", new JSONObject().put("audio",
                new JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=" + IN_RATE)));
    }

    public static JSONObject audioEnd() throws JSONException {
        return new JSONObject().put("realtime_input", new JSONObject().put("audioStreamEnd", true));
    }

    public static JSONObject toolResponse(String id, String name, String toModel) throws JSONException {
        return new JSONObject().put("tool_response", new JSONObject().put("functionResponses", new JSONArray()
                .put(new JSONObject().put("id", id).put("name", name).put("response", new JSONObject().put("result", toModel)))));
    }

    /** What one server message means for the TV. */
    public static final class Event {
        public boolean setupComplete, interrupted, turnComplete, goAway;
        public final List<byte[]> audio = new ArrayList<>();
        public String heard = "", said = "";
        public final List<String[]> calls = new ArrayList<>();   // {id, name, argsJson}
        public long tokens = -1;
        public String error;
    }

    public static Event parse(String json) {
        Event e = new Event();
        JSONObject m;
        try { m = new JSONObject(json); } catch (JSONException x) { e.error = "unreadable message"; return e; }
        if (m.has("setupComplete")) e.setupComplete = true;
        if (m.has("goAway")) e.goAway = true;
        JSONObject usage = m.optJSONObject("usageMetadata");
        if (usage != null) e.tokens = usage.optLong("totalTokenCount", -1);
        JSONObject sc = m.optJSONObject("serverContent");
        if (sc != null) {
            e.interrupted = sc.optBoolean("interrupted", false);
            e.turnComplete = sc.optBoolean("turnComplete", false);
            JSONObject in = sc.optJSONObject("inputTranscription");
            if (in != null) e.heard = in.optString("text", "");
            JSONObject out = sc.optJSONObject("outputTranscription");
            if (out != null) e.said = out.optString("text", "");
            JSONObject turn = sc.optJSONObject("modelTurn");
            JSONArray parts = turn == null ? null : turn.optJSONArray("parts");
            if (parts != null) for (int i = 0; i < parts.length(); i++) {
                JSONObject inline = parts.optJSONObject(i) == null ? null : parts.optJSONObject(i).optJSONObject("inlineData");
                if (inline != null && inline.optString("mimeType", "").startsWith("audio/pcm")) {
                    try { e.audio.add(java.util.Base64.getDecoder().decode(inline.optString("data", ""))); } catch (IllegalArgumentException ignored) { }
                }
            }
        }
        JSONObject tc = m.optJSONObject("toolCall");
        JSONArray fcs = tc == null ? null : tc.optJSONArray("functionCalls");
        if (fcs != null) for (int i = 0; i < fcs.length(); i++) {
            JSONObject f = fcs.optJSONObject(i);
            if (f == null) continue;
            JSONObject args = f.optJSONObject("args");
            e.calls.add(new String[]{f.optString("id"), f.optString("name"), args == null ? "{}" : args.toString()});
        }
        return e;
    }
}
