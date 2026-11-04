package ai.futurespeak.friday.tv.studio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The plan's fixed shape (ST-E02), validated like {@code CardSpec}: a title and 1 to 12
 * chapters, each with a short title, a one-line summary and the sources it draws on (indices
 * into the inputs). The model writes the words; the TV holds the parameters. If the model's
 * reply says a kind or length of its own that differs from the request, the plan is refused
 * (AC-S1): a plan never widens a request. Pure Java.
 */
public final class Storyboard {
    public static final int MAX_CHAPTERS = 12, MAX_TITLE = 80, MAX_SUMMARY = 300;
    /** A chapter's narration may run this many words (ST-E02); the writer is asked for less. */
    public static final int MAX_WORDS = 220;

    public static final class Chapter {
        public final String title, summary;
        public final List<Integer> sources;
        Chapter(String title, String summary, List<Integer> sources) { this.title = title; this.summary = summary; this.sources = sources; }
    }

    public final String title;
    public final List<Chapter> chapters;
    /** What the model's reply claimed about kind and length, if anything (never used, only compared). */
    final String saidKind; final int saidLength;

    Storyboard(String title, List<Chapter> chapters, String saidKind, int saidLength) {
        this.title = title; this.chapters = Collections.unmodifiableList(chapters); this.saidKind = saidKind; this.saidLength = saidLength;
    }

    /** Parses the model's reply (a JSON object, possibly inside a ```json fence). Throws with a short reason. */
    public static Storyboard parse(String reply) {
        if (reply == null) throw new IllegalArgumentException("empty plan");
        String s = reply.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalArgumentException("the plan wasn't JSON");
        try {
            JSONObject o = new JSONObject(s.substring(a, b + 1));
            String title = clean(o.optString("title", ""));
            JSONArray cs = o.optJSONArray("chapters");
            List<Chapter> out = new ArrayList<>();
            if (cs != null) for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.optJSONObject(i);
                if (c == null) throw new IllegalArgumentException("chapter " + (i + 1) + " isn't an object");
                List<Integer> src = new ArrayList<>();
                JSONArray sa = c.optJSONArray("sources");
                if (sa != null) for (int k = 0; k < sa.length(); k++) src.add(sa.optInt(k, -1));
                out.add(new Chapter(clean(c.optString("title", "")), clean(c.optString("summary", "")), src));
            }
            return new Storyboard(title, out, o.has("kind") ? o.optString("kind", "") : null, o.optInt("length_min", 0));
        } catch (JSONException e) {
            throw new IllegalArgumentException("the plan wasn't valid JSON");
        }
    }

    /** AC-S1: the plan tried to set a kind or length other than the request's. Null when it didn't. */
    public String paramsChanged(StudioRequest r) {
        if (saidKind != null && !saidKind.trim().isEmpty() && !saidKind.trim().equalsIgnoreCase(r.kind)) return "the plan changed the kind to " + clip(saidKind, 20);
        if (saidLength != 0 && saidLength != r.lengthMin) return "the plan changed the length to " + saidLength + " minutes";
        return null;
    }

    /** ST-E02: what's wrong with this plan's shape for this request, or null. */
    public String problem(StudioRequest r, int sourceCount) {
        if (title.isEmpty()) return "no title";
        if (title.length() > MAX_TITLE) return "the title is too long";
        int[] range = StudioRequest.chapterRange(r.lengthMin);
        if (chapters.size() < range[0] || chapters.size() > range[1] || chapters.size() > MAX_CHAPTERS)
            return chapters.size() + " chapters for " + r.lengthMin + " minutes (want " + range[0] + " to " + range[1] + ")";
        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            if (c.title.isEmpty() || c.title.length() > MAX_TITLE) return "chapter " + (i + 1) + " has no usable title";
            if (c.summary.length() > MAX_SUMMARY) return "chapter " + (i + 1) + "'s summary is too long";
            if (c.sources.isEmpty()) return "chapter " + (i + 1) + " names no source";
            for (int s : c.sources) if (s < 0 || s >= sourceCount) return "chapter " + (i + 1) + " names a source that doesn't exist";
        }
        return null;
    }

    /** Words a chapter's narration should run for this request. */
    public int wordsPerChapter(StudioRequest r) {
        return Math.min(MAX_WORDS - 20, Math.max(60, r.words() / Math.max(1, chapters.size())));
    }

    public JSONObject toJson() {
        try {
            JSONArray cs = new JSONArray();
            for (Chapter c : chapters) {
                JSONArray s = new JSONArray();
                for (int k : c.sources) s.put(k);
                cs.put(new JSONObject().put("title", c.title).put("summary", c.summary).put("sources", s));
            }
            return new JSONObject().put("title", title).put("chapters", cs);
        } catch (JSONException e) { return new JSONObject(); }
    }

    static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\p{Cntrl}&&[^\n]]", "").replaceAll("\\s+", " ").trim();
    }

    static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    /** Counts words the way the length check does. */
    public static int words(String text) {
        if (text == null) return 0;
        String t = text.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }
}
