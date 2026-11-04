package ai.futurespeak.friday.tv.addon;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * Which AI service does each job, and with which model: the owner's choice, never a key.
 * Bring your own keys; anything without a key falls back to something that works on the TV.
 *
 * <pre>
 * { "text":   { "provider": "anthropic" | "openrouter", "model": "claude-opus-5-5", "fast_model": "claude-sonnet-5-5" },
 *   "voice":  { "provider": "elevenlabs" | "tv", "voice_id": "…", "model": "eleven_flash_v2_5", "design_model": "eleven_multilingual_ttv_v2" },
 *   "sound":  { "provider": "elevenlabs" | "none" },
 *   "music":  { "provider": "elevenlabs" | "none" },
 *   "images": { "provider": "kie" | "none", "model": "nano-banana-2" },
 *   "video":  { "provider": "kie" | "none", "model": "" } }
 * </pre>
 *
 * Anything missing or malformed takes the default, so a half-written file still works. Pure Java;
 * shared by the core app (which holds the keys) and add-ons (which only read what's available).
 */
public final class Providers {
    public static final String DEFAULT_TEXT_MODEL = "claude-opus-5-5";
    public static final String DEFAULT_FAST_MODEL = "claude-sonnet-5-5";
    public static final String DEFAULT_VOICE_MODEL = "eleven_flash_v2_5";
    public static final String DEFAULT_DESIGN_MODEL = "eleven_multilingual_ttv_v2";
    public static final String DEFAULT_IMAGE_MODEL = "nano-banana-2";

    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9_.:/-]{1,96}");
    private static final Pattern VOICE_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    public final String textProvider, textModel, fastModel;
    public final String voiceProvider, voiceId, voiceModel, designModel;
    public final String soundProvider, musicProvider;
    public final String imageProvider, imageModel;
    public final String videoProvider, videoModel;

    private Providers(JSONObject o) {
        JSONObject t = sub(o, "text"), v = sub(o, "voice"), s = sub(o, "sound"), m = sub(o, "music"), i = sub(o, "images"), w = sub(o, "video");
        textProvider = pick(t, "provider", "anthropic", "anthropic", "openrouter");
        textModel = model(t, "model", DEFAULT_TEXT_MODEL);
        fastModel = model(t, "fast_model", DEFAULT_FAST_MODEL);
        voiceProvider = pick(v, "provider", "elevenlabs", "elevenlabs", "tv");
        String vid = v.optString("voice_id", "");
        voiceId = VOICE_ID.matcher(vid).matches() ? vid : "";
        voiceModel = model(v, "model", DEFAULT_VOICE_MODEL);
        designModel = model(v, "design_model", DEFAULT_DESIGN_MODEL);
        soundProvider = pick(s, "provider", "elevenlabs", "elevenlabs", "none");
        musicProvider = pick(m, "provider", "elevenlabs", "elevenlabs", "none");
        imageProvider = pick(i, "provider", "kie", "kie", "none");
        imageModel = model(i, "model", DEFAULT_IMAGE_MODEL);
        videoProvider = pick(w, "provider", "kie", "kie", "none");
        videoModel = model(w, "model", "");
    }

    public static Providers defaults() { return new Providers(new JSONObject()); }

    public static Providers parse(String json) {
        try { return new Providers(json == null || json.trim().isEmpty() ? new JSONObject() : new JSONObject(json)); }
        catch (Exception e) { return defaults(); }
    }

    /** Which keys are on the TV (names only). */
    public interface Keys { boolean has(String provider); }

    /** What actually happens for each job, given the keys present. */
    public static final class Plan {
        /** "anthropic", "openrouter", or "none" (Friday can't answer until a key is added). */
        public final String text;
        /** "elevenlabs" or "tv" (the TV's own text-to-speech). */
        public final String voice;
        /** "elevenlabs" or "none" (silence; no sound effects). */
        public final String sound, music;
        /** "kie" or "stills" (no generated pictures: an add-on shows its own still images, or none). */
        public final String images;
        /** "kie" or "stills" (no clips: the still picture with a slow drift). */
        public final String video;

        Plan(String text, String voice, String sound, String music, String images, String video) {
            this.text = text; this.voice = voice; this.sound = sound; this.music = music; this.images = images; this.video = video;
        }
    }

    public Plan plan(Keys k) {
        String text = textProvider.equals("openrouter") && k.has("openrouter") ? "openrouter"
                : k.has("anthropic") ? "anthropic" : k.has("openrouter") ? "openrouter" : "none";
        boolean eleven = k.has("elevenlabs");
        boolean kie = k.has("kie");
        return new Plan(text,
                voiceProvider.equals("elevenlabs") && eleven ? "elevenlabs" : "tv",
                soundProvider.equals("elevenlabs") && eleven ? "elevenlabs" : "none",
                musicProvider.equals("elevenlabs") && eleven ? "elevenlabs" : "none",
                imageProvider.equals("kie") && kie ? "kie" : "stills",
                videoProvider.equals("kie") && kie && !videoModel.isEmpty() ? "kie" : "stills");
    }

    public JSONObject toJson() {
        try {
            return new JSONObject()
                    .put("text", new JSONObject().put("provider", textProvider).put("model", textModel).put("fast_model", fastModel))
                    .put("voice", new JSONObject().put("provider", voiceProvider).put("voice_id", voiceId).put("model", voiceModel).put("design_model", designModel))
                    .put("sound", new JSONObject().put("provider", soundProvider))
                    .put("music", new JSONObject().put("provider", musicProvider))
                    .put("images", new JSONObject().put("provider", imageProvider).put("model", imageModel))
                    .put("video", new JSONObject().put("provider", videoProvider).put("model", videoModel));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject sub(JSONObject o, String k) { JSONObject x = o.optJSONObject(k); return x == null ? new JSONObject() : x; }

    private static String pick(JSONObject o, String key, String dflt, String... allowed) {
        String v = o.optString(key, dflt);
        for (String a : allowed) if (a.equals(v)) return v;
        return dflt;
    }

    private static String model(JSONObject o, String key, String dflt) {
        String v = o.optString(key, dflt).trim();
        if (v.isEmpty()) return dflt;
        return MODEL.matcher(v).matches() ? v : dflt;
    }
}
