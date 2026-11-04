package ai.futurespeak.friday.tv;

import android.content.Context;
import android.content.SharedPreferences;

import ai.futurespeak.friday.tv.prompt.SystemPrompt;

/** Plain settings (nothing secret; keys live in SecureStore). */
public final class Prefs {
    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    public static final String FAST_MODEL = "claude-sonnet-5-5";
    public static final String DEFAULT_ELEVEN_MODEL = "eleven_flash_v2_5";
    public static final String DEFAULT_ELEVEN_VOICE = "21m00Tcm4TlvDq8ikWAM";   // ElevenLabs stock voice, Friday's default

    public enum Motion { AUTO, ON, OFF }

    private final SharedPreferences p;

    public Prefs(Context c) { p = c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE); }

    /** Where the TV is, for the weather (see {@link Place}); unset until the owner chooses. */
    public String placeName() { return p.getString("place_name", ""); }
    public double placeLat() { return Double.longBitsToDouble(p.getLong("place_lat", Double.doubleToLongBits(Double.NaN))); }
    public double placeLon() { return Double.longBitsToDouble(p.getLong("place_lon", Double.doubleToLongBits(Double.NaN))); }
    public boolean placeFahrenheit() { return p.getBoolean("place_f", true); }
    public void setPlace(String name, double lat, double lon, boolean fahrenheit) {
        p.edit().putString("place_name", name == null ? "" : name.trim()).putLong("place_lat", Double.doubleToLongBits(lat))
                .putLong("place_lon", Double.doubleToLongBits(lon)).putBoolean("place_f", fahrenheit).apply();
        Place.set(name, lat, lon, fahrenheit);
    }
    public void clearPlace() {
        p.edit().remove("place_name").remove("place_lat").remove("place_lon").remove("place_f").apply();
        Place.set("", Double.NaN, Double.NaN, true);
    }

    // ---- providers: which service and model does each job (docs/PROVIDERS.md); never a key ----
    private static volatile ai.futurespeak.friday.tv.addon.Providers providersNow = ai.futurespeak.friday.tv.addon.Providers.defaults();
    /** The owner's provider and model choices, as loaded at start or last imported. */
    public static ai.futurespeak.friday.tv.addon.Providers providersNow() { return providersNow; }
    public ai.futurespeak.friday.tv.addon.Providers providers() { return ai.futurespeak.friday.tv.addon.Providers.parse(p.getString("providers", "")); }
    public void setProviders(ai.futurespeak.friday.tv.addon.Providers v) { p.edit().putString("providers", v.toJson().toString()).apply(); providersNow = v; }
    public void loadProviders() { providersNow = providers(); }
    /** The quick model (kids mode, Storyteller, checks). */
    public static String fastModel() { return providersNow.fastModel; }
    /** False when the owner chose the TV's own voice, even with an ElevenLabs key. */
    public static boolean elevenVoiceOn() { return "elevenlabs".equals(providersNow.voiceProvider); }

    public String model() { return p.getString("model", providersNow.textModel); }
    public void setModel(String m) { p.edit().putString("model", m).apply(); }
    public void setModelIfUnset(String m) { if (!p.contains("model")) setModel(m); }

    public SystemPrompt.Mode mode() {
        return "GROWN_UP".equals(p.getString("mode", "FAMILY")) ? SystemPrompt.Mode.GROWN_UP : SystemPrompt.Mode.FAMILY;
    }
    public void setMode(SystemPrompt.Mode m) { p.edit().putString("mode", m.name()).apply(); }

    public boolean voiceOn() { return p.getBoolean("voice", true); }
    public void setVoiceOn(boolean b) { p.edit().putBoolean("voice", b).apply(); }

    public Motion motion() {
        try { return Motion.valueOf(p.getString("motion", "AUTO")); } catch (Exception e) { return Motion.AUTO; }
    }
    public void setMotion(Motion m) { p.edit().putString("motion", m.name()).apply(); }

    /**
     * Spec D4: the speech service version ("pkg@version") that was measured on this TV recognizing
     * English with no network traffic. A different version needs measuring again.
     */
    /** The offline-English pack offer (audio.PackFlow): offered once, request time for "in flight". */
    public ai.futurespeak.friday.tv.audio.PackFlow.State packState() {
        try { return ai.futurespeak.friday.tv.audio.PackFlow.State.valueOf(p.getString("pack_state", "NEW")); }
        catch (Exception e) { return ai.futurespeak.friday.tv.audio.PackFlow.State.NEW; }
    }
    public long packRequestedAt() { return p.getLong("pack_requested_at", 0); }
    public void setPack(ai.futurespeak.friday.tv.audio.PackFlow.State s, long at) {
        p.edit().putString("pack_state", s.name()).putLong("pack_requested_at", at).commit();
    }

    public String sttOfflineVerifiedFor() { return p.getString("stt_offline_verified_for", null); }
    public void setSttOfflineVerifiedFor(String v) { p.edit().putString("stt_offline_verified_for", v).remove("stt").apply(); }

    public int structure() { return p.getInt("structure", 0); }
    public void setStructure(int i) { p.edit().putInt("structure", i).apply(); }
    /** T5: the Gemini Live session limit in minutes (5–30; a privacy limit, not a spend cap). */
    public int liveMinutes() { return ai.futurespeak.friday.tv.live.LiveRules.minutes(p.getInt("live_minutes", 0)); }
    /** T5: who Talk talks to: Friday (false) or Gemini Live (true). A voice option, no PIN (2026-11-02). */
    /** Friday's own on-TV speech (default) or Google's recognizer (design decision, 2026-11-02). */
    public boolean sttOnTv() { return p.getBoolean("stt_ontv", true); }
    public void setSttOnTv(boolean on) { p.edit().putBoolean("stt_ontv", on).apply(); }
    public boolean talkWithGemini() { return p.getBoolean("talk_gemini", false); }
    public void setTalkWithGemini(boolean on) { p.edit().putBoolean("talk_gemini", on).apply(); }
    public void setLiveMinutes(int m) { p.edit().putInt("live_minutes", ai.futurespeak.friday.tv.live.LiveRules.minutes(m)).apply(); }

    /** Picture quality for Friday's 3D scene: auto (measured on this GPU), high, ultra. */
    public String quality() {
        String q = p.getString("quality", "auto");
        return q.equals("high") || q.equals("ultra") ? q : "auto";
    }
    public void setQuality(String q) { p.edit().putString("quality", q).apply(); }

    public String elevenVoice() { String v = p.getString("eleven_voice", ""); return v.isEmpty() ? DEFAULT_ELEVEN_VOICE : v; }
    public void setElevenVoice(String v) { if (v != null && v.matches("^[A-Za-z0-9_-]{1,128}$")) p.edit().putString("eleven_voice", v).apply(); }
    public String elevenModel() { String v = p.getString("eleven_model", ""); return v.isEmpty() ? providersNow.voiceModel : v; }
    public void setElevenModel(String v) { if (v != null && v.matches("^[A-Za-z0-9_.-]{1,64}$")) p.edit().putString("eleven_model", v).apply(); }

    // ---- Voice settings (no PIN) -------------------------------------------------------
    /** ElevenLabs voices offered in Voice settings (ElevenLabs premade voices), then the TV's own. */
    public static final String[][] VOICES = {
            {"hpp4J3VqNfWAUOO0d1Us", "Bella · bright, warm"},
            {"EXAVITQu4vr4xnSDxMaL", "Sarah · mature, reassuring"},
            {"Xb7hH8MSUJpSbSDYk0k2", "Alice · clear, British"},
            {"pFZP5JQG7iQjIQuC4Bku", "Lily · velvety, British"},
            {"XrExE9yKIg1WjnnlVkGX", "Matilda · upbeat, knowledgeable"},
            {"cgSgspJ2msm6clMCkdW9", "Jessica · playful, bright"},
            {"tv", "TV voice · offline, built in"}};
    public static final String TV_VOICE = "tv";
    public enum TalkMode { TAP, HOLD }

    public float voiceSpeed() { return p.getFloat("voice_speed", 1.0f); }
    public void setVoiceSpeed(float v) { p.edit().putFloat("voice_speed", v).apply(); }
    public float voiceVolume() { return p.getFloat("voice_volume", 0.85f); }
    public void setVoiceVolume(float v) { p.edit().putFloat("voice_volume", v).apply(); }
    public boolean captions() { return p.getBoolean("captions", true); }
    public void setCaptions(boolean b) { p.edit().putBoolean("captions", b).apply(); }
    /** Pause that ends a question, in ms (listening sensitivity). */
    public int listenPauseMs() { return p.getInt("listen_pause", 1100); }
    public void setListenPauseMs(int ms) { p.edit().putInt("listen_pause", ms).apply(); }
    public TalkMode talkMode() {
        try { return TalkMode.valueOf(p.getString("talk_mode", "TAP")); } catch (Exception e) { return TalkMode.TAP; }
    }
    public void setTalkMode(TalkMode m) { p.edit().putString("talk_mode", m.name()).apply(); }

    // ---- Channels on the rail -------------------------------------------------------------
    /** Pinned channel ids, in order: they come first on the rail. */
    public java.util.List<String> pinnedChannels() { return list("pinned_channels"); }
    public void setPinnedChannels(java.util.List<String> ids) { putList("pinned_channels", ids); }

    // ---- Screensaver (Friday's ambient mode) ------------------------------------------
    public boolean ssClock() { return p.getBoolean("ss_clock", true); }
    public void setSsClock(boolean b) { p.edit().putBoolean("ss_clock", b).apply(); }
    public boolean ssWeather() { return p.getBoolean("ss_weather", true); }
    public void setSsWeather(boolean b) { p.edit().putBoolean("ss_weather", b).apply(); }
    public boolean ssCards() { return p.getBoolean("ss_cards", true); }
    public void setSsCards(boolean b) { p.edit().putBoolean("ss_cards", b).apply(); }

    /** Friday News slides (when the News module provides them). */
    public boolean ssNews() { return p.getBoolean("ss_news", true); }
    public void setSsNews(boolean b) { p.edit().putBoolean("ss_news", b).apply(); }
    /** 0 = mostly news, 1 = even, 2 = mostly cards and channels. */
    public int ssMix() { return Math.max(0, Math.min(2, p.getInt("ss_mix", 1))); }
    public void setSsMix(int i) { p.edit().putInt("ss_mix", i).apply(); }
    /** Channels shown in the screensaver (ids). */
    public java.util.List<String> ssChannels() { return list("ss_channels"); }
    public void setSsChannels(java.util.List<String> ids) { putList("ss_channels", ids); }
    /** 0 = drifting, 1 = clock corner, 2 = centred. */
    public int ssLayout() { return Math.max(0, Math.min(2, p.getInt("ss_layout", 0))); }
    public void setSsLayout(int i) { p.edit().putInt("ss_layout", i).apply(); }
    /** Seconds each card or channel stays up. */
    public int ssRotateSec() { int s = p.getInt("ss_rotate", 40); return s == 20 || s == 40 || s == 90 ? s : 40; }
    public void setSsRotateSec(int s) { p.edit().putInt("ss_rotate", s).apply(); }
    /** 0 = full brightness, 1 = dimmed, 2 = night. */
    public int ssDim() { return Math.max(0, Math.min(2, p.getInt("ss_dim", 1))); }
    public void setSsDim(int i) { p.edit().putInt("ss_dim", i).apply(); }

    private java.util.List<String> list(String key) {
        String s = p.getString(key, "");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String x : s.split(",")) if (x.matches("^[a-f0-9]{12}$") && !out.contains(x)) out.add(x);
        return out;
    }
    private void putList(String key, java.util.List<String> ids) {
        StringBuilder b = new StringBuilder();
        for (String x : ids) if (x != null && x.matches("^[a-f0-9]{12}$")) { if (b.length() > 0) b.append(','); b.append(x); }
        p.edit().putString(key, b.toString()).apply();
    }

    /** The avatar quality rung auto settled on, per TV model (-1 = never measured here). */
    public int avatarTier() { return p.getInt("avatar_tier_" + android.os.Build.MODEL, -1); }
    public void setAvatarTier(int level) { if (level >= 0 && level <= 4) p.edit().putInt("avatar_tier_" + android.os.Build.MODEL, level).apply(); }

    public String pinHash() { return p.getString("pin", null); }
    public void setPinHash(String h) { p.edit().putString("pin", h).putInt("pin_fail", 0).putLong("pin_lock", 0).apply(); }
    public int pinFailures() { return p.getInt("pin_fail", 0); }
    public void setPinFailures(int n) { p.edit().putInt("pin_fail", n).apply(); }
    public long pinLockedUntil() { return p.getLong("pin_lock", 0); }
    public void setPinLockedUntil(long t) { p.edit().putLong("pin_lock", t).apply(); }
}
