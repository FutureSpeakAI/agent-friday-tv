package ai.futurespeak.friday.tv.story;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The recurring cast (ST-D10): each member has the name the child gave, a look (said with every
 * picture brief so the dragon stays the same dragon), a voice description, and, once designed,
 * an ElevenLabs voice id; or, with no voice slot, Friday's voice in character (speed and
 * stability from the description). A member is never a real person: the look has no "looks
 * like" and the voice is designed from words, never from a recording. Pure Java.
 */
public final class Cast {
    public static final int MAX_MEMBERS = 8, MAX_NAME = 24, MAX_LOOK = 160, MAX_VOICE = 120;

    public static final class Member {
        public final String name, look, voiceDesc;
        /** A designed ElevenLabs voice id, or "" for Friday's voice in character. */
        public final String voiceId;
        public final float speed, stability;
        public Member(String name, String look, String voiceDesc, String voiceId, float speed, float stability) {
            this.name = name; this.look = look; this.voiceDesc = voiceDesc; this.voiceId = voiceId == null ? "" : voiceId;
            this.speed = speed; this.stability = stability;
        }
        public Member withVoice(String id) { return new Member(name, look, voiceDesc, id, speed, stability); }
        public boolean designed() { return !voiceId.isEmpty(); }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("name", name).put("look", look).put("voice", voiceDesc).put("voice_id", voiceId)
                    .put("speed", speed).put("stability", stability);
        }
        static Member fromJson(JSONObject o) {
            return new Member(o.optString("name"), o.optString("look"), o.optString("voice"), o.optString("voice_id", ""),
                    (float) o.optDouble("speed", 1.0), (float) o.optDouble("stability", 0.5));
        }
    }

    private final List<Member> members = new ArrayList<>();

    public synchronized List<Member> members() { return Collections.unmodifiableList(new ArrayList<>(members)); }

    public synchronized Set<String> names() {
        Set<String> s = new HashSet<>();
        for (Member m : members) s.add(m.name);
        return s;
    }

    public synchronized Member find(String name) {
        if (name == null) return null;
        for (Member m : members) if (m.name.equalsIgnoreCase(name.trim())) return m;
        return null;
    }

    /** Adds or updates a member; a new member beyond the cap is refused (returns false). */
    public synchronized boolean put(Member m) {
        for (int i = 0; i < members.size(); i++) if (members.get(i).name.equalsIgnoreCase(m.name)) { members.set(i, m); return true; }
        if (members.size() >= MAX_MEMBERS) return false;
        members.add(m);
        return true;
    }

    public synchronized void remove(String name) {
        for (int i = members.size() - 1; i >= 0; i--) if (members.get(i).name.equalsIgnoreCase(name)) members.remove(i);
    }

    public synchronized void clear() { members.clear(); }

    /** Friday's voice in character: a child-sized guess from the description's words (ST-D3's fallback). */
    public static float[] styleFor(String voiceDesc) {
        String d = voiceDesc == null ? "" : voiceDesc.toLowerCase(Locale.ROOT);
        float speed = 1.0f, stability = 0.5f;
        if (d.matches(".*\\b(small|tiny|little|high|squeaky|quick|bright|young|mouse|bird)\\b.*")) { speed = 1.1f; stability = 0.35f; }
        if (d.matches(".*\\b(deep|big|gruff|slow|low|old|giant|bear|dragon|rumbl\\w*)\\b.*")) { speed = 0.88f; stability = 0.7f; }
        if (d.matches(".*\\b(sleepy|calm|gentle|soft|whisper\\w*)\\b.*")) { speed = 0.9f; stability = 0.8f; }
        return new float[]{speed, stability};
    }

    /** Checks a member the model proposed; null when it is fine, else the problem. */
    public static String problem(String name, String look, String voiceDesc) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > MAX_NAME || !name.trim().matches("[A-Za-z][A-Za-z' -]*")) return "a cast name is wrong";
        if (look == null || look.trim().isEmpty() || look.length() > MAX_LOOK) return "a cast look is missing or too long";
        if (voiceDesc != null && voiceDesc.length() > MAX_VOICE) return "a voice description is too long";
        if (!StorySafety.textOk(look) || (voiceDesc != null && !StorySafety.textOk(voiceDesc))) return "a cast member isn't for a child";
        if (StorySafety.BRIEF_BAD.matcher(look).find() || (voiceDesc != null && StorySafety.BRIEF_BAD.matcher(voiceDesc).find())) return "a cast member must not be a real person";
        if (voiceDesc != null && voiceDesc.toLowerCase(Locale.ROOT).matches(".*\\b(sounds? like|voice of|like [A-Z][a-z]+)\\b.*")) return "a voice must not copy anyone";
        return null;
    }

    public synchronized JSONObject toJson() {
        try {
            JSONArray a = new JSONArray();
            for (Member m : members) a.put(m.toJson());
            return new JSONObject().put("v", 1).put("members", a);
        } catch (JSONException e) { return new JSONObject(); }
    }

    public static Cast fromJson(JSONObject o) {
        Cast c = new Cast();
        JSONArray a = o == null ? null : o.optJSONArray("members");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            if (m != null && !m.optString("name").isEmpty()) c.put(Member.fromJson(m));
        }
        return c;
    }
}
