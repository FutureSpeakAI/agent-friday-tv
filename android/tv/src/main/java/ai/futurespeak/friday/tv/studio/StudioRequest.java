package ai.futurespeak.friday.tv.studio;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * What the person asked Studio to make (spec §8.1), held by the gate. The plan's model never sets
 * any of this: kind, length and voices come from the request and the TV's pickers only (rule 4,
 * AC-S1). Slice 1 makes one kind: a podcast in one voice, 2, 5, 10 or 15 minutes (S-D12).
 * Pure Java.
 */
public final class StudioRequest {
    public static final String PODCAST = "podcast";
    public static final int[] LENGTHS = {2, 5, 10, 15};
    /** Spoken words per minute for Friday's voice (the desktop's measured rate is about the same). */
    public static final int WPM = 150;

    public final String kind;
    /** 0 until the person picks one on the TV (nothing pre-selected beyond what was said). */
    public final int lengthMin;
    public final int voices;
    /** A public web address to read, or null. */
    public final String url;
    /** A source the TV already holds (a News story), "news:<id>", or null. */
    public final String sourceRef;

    StudioRequest(String kind, int lengthMin, int voices, String url, String sourceRef) {
        this.kind = kind; this.lengthMin = lengthMin; this.voices = voices; this.url = url; this.sourceRef = sourceRef;
    }

    /** The request, or why it can't be made (fixed words for the person). */
    public static final class Parsed {
        public final StudioRequest request; public final String problem;
        Parsed(StudioRequest r, String p) { request = r; problem = p; }
    }

    public static Parsed from(JSONObject args) {
        if (args == null) args = new JSONObject();
        String kind = args.optString("kind", PODCAST).trim().toLowerCase(java.util.Locale.ROOT);
        if (kind.isEmpty()) kind = PODCAST;
        if (!PODCAST.equals(kind)) return new Parsed(null, "So far I can only make podcasts on the TV.");
        int len = args.optInt("length_min", 0);
        if (len != 0 && !allowed(len)) {
            return new Parsed(null, len > 15 ? "Podcasts on the TV go up to 15 minutes." : "Pick 2, 5, 10 or 15 minutes.");
        }
        int voices = args.optInt("voices", 1);
        if (voices == 2) return new Parsed(null, "Two voices come a little later; I can make it with one.");
        if (voices != 1) return new Parsed(null, "A podcast has one voice for now.");
        String url = args.optString("url", "").trim();
        String ref = args.optString("source_ref", "").trim();
        if (url.isEmpty() == ref.isEmpty()) return new Parsed(null, "Tell me which page or News story to make it from.");
        if (!ref.isEmpty() && !ref.matches("^news:[a-f0-9]{6,40}$")) return new Parsed(null, "I can't find that story.");
        return new Parsed(new StudioRequest(PODCAST, len, 1, url.isEmpty() ? null : url, ref.isEmpty() ? null : ref), null);
    }

    public static boolean allowed(int len) {
        for (int l : LENGTHS) if (l == len) return true;
        return false;
    }

    public StudioRequest withLength(int len) {
        if (!allowed(len)) throw new IllegalArgumentException("length " + len);
        return new StudioRequest(kind, len, voices, url, sourceRef);
    }

    public int words() { return lengthMin * WPM; }

    /** How many chapters a piece of this length has: {min, max} (ST-E02: at most 220 words a chapter, 12 chapters). */
    public static int[] chapterRange(int len) {
        switch (len) {
            case 2: return new int[]{2, 3};
            case 5: return new int[]{4, 6};
            case 10: return new int[]{7, 10};
            case 15: return new int[]{11, 12};
            default: return new int[]{1, 1};
        }
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject().put("kind", kind).put("length_min", lengthMin).put("voices", voices);
            if (url != null) o.put("url", url);
            if (sourceRef != null) o.put("source_ref", sourceRef);
            return o;
        } catch (JSONException e) { return new JSONObject(); }
    }

    public static StudioRequest fromJson(JSONObject o) {
        Parsed p = from(o);
        if (p.request == null) throw new IllegalArgumentException(p.problem);
        return p.request;
    }

    // ---- sources that try to steer the plan (TV-E08) ------------------------------------------

    /** A source sentence aimed at whoever makes the piece: a length, a provider, a voice, a price. */
    private static final Pattern STEER = Pattern.compile("(?is)"
            + "\\b(make|turn|set|change|render|produce|extend|stretch)\\b[^.\\n]{0,60}\\b(this|it|the podcast|the episode|the video|the piece)\\b[^.\\n]{0,40}"
            + "\\b(\\d{2,}|sixty|ninety|thirty|forty|fifty|an? hour|two hours)\\s*(minutes?|mins?|hours?)\\b"
            + "|\\b(use|with|via)\\s+(seedance|kling|veo|higgsfield|kie|sora|eleven ?labs|lyria)\\b"
            + "|\\b(ai|assistant|podcast maker|friday|claude)\\b[^.\\n]{0,40}\\b(must|should|shall)\\b[^.\\n]{0,60}\\b(minutes?|voice|cost|spend|budget)\\b");

    /** True when a source's text tries to change how the piece is made; it changes nothing, and taints the turn. */
    public static boolean triesToSteer(String text) {
        if (text == null || text.isEmpty()) return false;
        return ai.futurespeak.friday.tv.actions.Web.instructionShaped(text) || STEER.matcher(text).find();
    }
}
