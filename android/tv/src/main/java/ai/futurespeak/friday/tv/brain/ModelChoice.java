package ai.futurespeak.friday.tv.brain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Who answers: Claude directly, or one model through OpenRouter, from a short list
 * the owner curates on the PC. Pure and unit-tested. Rules:
 *  - Kids mode is always Claude (OpenRouter's terms are 18+, and the kids prompt is tested on Claude).
 *  - No OpenRouter key, or a model that isn't on the list: Claude.
 *  - The identity line in the prompt names the model that is really answering.
 */
public final class ModelChoice {
    public static final class Model {
        public final String id, name;
        /** Dollars per million tokens, as OpenRouter lists them (shown in Settings). */
        public final double inPerM, outPerM;
        public Model(String id, String name, double inPerM, double outPerM) { this.id = id; this.name = name; this.inPerM = inPerM; this.outPerM = outPerM; }
        public String price() { return String.format(Locale.US, "$%.2f in · $%.2f out per million tokens", inPerM, outPerM); }
    }

    /** The starting list (checked against OpenRouter's catalogue on 2026-11-02, all with tool support). The owner can replace it from the PC. */
    public static final List<Model> DEFAULTS = Collections.unmodifiableList(Arrays.asList(
            new Model("openai/gpt-6-sol", "GPT-6 Sol", 2.00, 10.00),
            new Model("google/gemini-3.8-flash", "Gemini 3.8 Flash", 0.83, 4.13),
            new Model("x-ai/grok-4.7", "Grok 4.7", 2.00, 6.00),
            new Model("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", 0.05, 0.50),
            new Model("mistralai/mistral-large-4-0", "Mistral Large 4", 0.70, 2.10),
            new Model("meta-llama/llama-4-maverick", "Llama 4 Maverick", 0.19, 0.65)));

    /** Empty or null selection means Claude. */
    public static Model serving(String selectedId, List<Model> list, boolean kids, boolean haveKey) {
        if (kids || !haveKey || selectedId == null || selectedId.isEmpty() || list == null) return null;
        for (Model m : list) if (m.id.equals(selectedId)) return m;
        return null;
    }

    /** The prompt's first lines: who is answering, honestly. Null model means Claude. */
    public static String identity(Model m, boolean kids) {
        if (m == null) {
            return "You are Claude, made by Anthropic. You are speaking through the Agent Friday app on a family's "
                    + "TV (Google TV). The app is the start of the Agent Friday TV portal; for now the voice "
                    + "and answers are yours, Claude's. "
                    + (kids ? "If asked who you are, say you are Friday, a computer program on this TV, made with Claude. "
                            : "If asked who you are, say you are Claude, running in the Agent Friday TV app. ");
        }
        return "You are " + m.name + ", a model reached through OpenRouter. You are speaking through the Agent Friday app on a family's "
                + "TV (Google TV); the household chose you to answer for now. You are not Claude; never say you are. "
                + "If asked who you are, say you are " + m.name + ", answering in the Agent Friday TV app through OpenRouter. ";
    }

    /** The curated list as stored (JSON array of {id, name, in, out}); bad entries are dropped. */
    public static List<Model> parse(String json) {
        if (json == null || json.trim().isEmpty()) return DEFAULTS;
        try {
            JSONArray a = new JSONArray(json);
            List<Model> out = new ArrayList<>();
            for (int i = 0; i < a.length() && out.size() < 12; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                if (!id.matches("^[a-z0-9._-]+/[a-z0-9._:-]+$")) continue;
                out.add(new Model(id, o.optString("name", id), o.optDouble("in", 0), o.optDouble("out", 0)));
            }
            return out.isEmpty() ? DEFAULTS : Collections.unmodifiableList(out);
        } catch (Exception e) {
            return DEFAULTS;
        }
    }
}
