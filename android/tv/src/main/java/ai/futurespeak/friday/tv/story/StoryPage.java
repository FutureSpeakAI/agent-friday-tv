package ai.futurespeak.friday.tv.story;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One page of a story, as data (spec SB-E01): one to four lines (the narrator or a cast member
 * speaking), a picture brief, at most one sound cue from the pack, zero or two choices, and
 * whether it is the ending. The model writes the words; the TV holds everything else. A page
 * that breaks the shape is rejected whole, never rendered in part (rule 2). Pure Java.
 */
public final class StoryPage {
    public static final int MAX_LINES = 4, MAX_BRIEF = 200, MAX_LABEL = 30, MAX_HINT = 80, MAX_TITLE = 60, MAX_WHO = 24;
    public static final int MAX_WORDS_YOUNG = 60, MAX_WORDS_OLDER = 90;
    public static final String NARRATOR = "narrator";

    public static final class Line {
        public final String who, text;
        public Line(String who, String text) { this.who = who; this.text = text; }
        public boolean narrator() { return NARRATOR.equals(who); }
    }

    public static final class Choice {
        public final String label, hint;
        public Choice(String label, String hint) { this.label = label; this.hint = hint; }
    }

    public static final class Rejected extends Exception {
        public Rejected(String why) { super(why); }
    }

    public final String title;
    public final List<Line> lines;
    public final String brief;
    /** A sound from the pack, or null. */
    public final String sound;
    public final List<Choice> choices;
    public final boolean ending;

    StoryPage(String title, List<Line> lines, String brief, String sound, List<Choice> choices, boolean ending) {
        this.title = title;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        this.brief = brief;
        this.sound = sound;
        this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
        this.ending = ending;
    }

    /** Everything spoken on this page, in order. */
    public String text() {
        StringBuilder s = new StringBuilder();
        for (Line l : lines) { if (s.length() > 0) s.append(' '); s.append(l.text); }
        return s.toString();
    }

    public boolean hasChoices() { return choices.size() == 2; }

    public int words() { return words(text()); }

    public static int words(String text) {
        if (text == null) return 0;
        String t = text.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    public static int maxWords(boolean olderBand) { return olderBand ? MAX_WORDS_OLDER : MAX_WORDS_YOUNG; }

    /**
     * Checks a page the model wrote. {@code cast} are the names that may speak; {@code sounds} the
     * pack's names; {@code heroName} may never appear in the picture brief (rule 4).
     */
    public static StoryPage parse(JSONObject o, Set<String> cast, Set<String> sounds, boolean olderBand, String heroName) throws Rejected {
        if (o == null) throw new Rejected("no page");
        for (String k : new String[]{"lines", "picture"}) if (!o.has(k)) throw new Rejected("the page has no " + k);
        String title = cap(o.optString("title", ""), MAX_TITLE);
        JSONArray la = o.optJSONArray("lines");
        if (la == null || la.length() < 1 || la.length() > MAX_LINES) throw new Rejected("a page has 1 to " + MAX_LINES + " lines");
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < la.length(); i++) {
            JSONObject l = la.optJSONObject(i);
            if (l == null) throw new Rejected("a line isn't an object");
            String who = l.optString("who", NARRATOR).trim();
            String text = l.optString("text", "").trim();
            if (text.isEmpty()) throw new Rejected("an empty line");
            if (who.isEmpty() || who.length() > MAX_WHO) throw new Rejected("a speaker name is wrong");
            if (!NARRATOR.equalsIgnoreCase(who) && !containsIgnoreCase(cast, who)) throw new Rejected("'" + who + "' isn't in this story's cast");
            if (StorySafety.hasLink(text)) throw new Rejected("a line has a link");
            if (!StorySafety.textOk(text)) throw new Rejected("a line isn't for a child");
            lines.add(new Line(NARRATOR.equalsIgnoreCase(who) ? NARRATOR : canon(cast, who), text));
        }
        int words = 0;
        for (Line l : lines) words += words(l.text);
        if (words > maxWords(olderBand)) throw new Rejected("the page is too long (" + words + " words; at most " + maxWords(olderBand) + ")");
        String brief = o.optString("picture", "").trim();
        if (brief.isEmpty() || brief.length() > MAX_BRIEF) throw new Rejected("the picture brief is missing or too long");
        String briefProblem = StorySafety.briefProblem(brief, heroName);
        if (briefProblem != null) throw new Rejected(briefProblem);
        String sound = o.isNull("sound") ? null : o.optString("sound", "").trim().toLowerCase(Locale.ROOT);
        if (sound != null && sound.isEmpty()) sound = null;
        if (sound != null && !sounds.contains(sound)) throw new Rejected("'" + sound + "' isn't a sound in the pack");
        JSONArray ca = o.optJSONArray("choices");
        List<Choice> choices = new ArrayList<>();
        if (ca != null && ca.length() != 0) {
            if (ca.length() != 2) throw new Rejected("a page has no choices or exactly two");
            for (int i = 0; i < 2; i++) {
                JSONObject c = ca.optJSONObject(i);
                if (c == null) throw new Rejected("a choice isn't an object");
                String label = c.optString("label", "").trim(), hint = c.optString("hint", "").trim();
                if (label.isEmpty() || label.length() > MAX_LABEL) throw new Rejected("a choice label is missing or too long");
                if (hint.length() > MAX_HINT) throw new Rejected("a choice hint is too long");
                if (StorySafety.hasLink(label) || StorySafety.hasLink(hint) || !StorySafety.textOk(label + " " + hint)) throw new Rejected("a choice isn't for a child");
                choices.add(new Choice(label, hint));
            }
        }
        boolean ending = o.optBoolean("ending", false);
        if (ending && !choices.isEmpty()) throw new Rejected("the ending has no choices");
        if (StorySafety.hasLink(title) || !StorySafety.textOk(title)) throw new Rejected("the title isn't for a child");
        return new StoryPage(title, lines, brief, sound, choices, ending);
    }

    /** The model's reply, which may wrap the JSON in a code fence or prose. */
    public static JSONObject json(String reply) throws Rejected {
        if (reply == null) throw new Rejected("empty reply");
        String s = reply.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) throw new Rejected("no JSON in the reply");
        try { return new JSONObject(s.substring(a, b + 1)); }
        catch (JSONException e) { throw new Rejected("the reply isn't valid JSON"); }
    }

    public JSONObject toJson() {
        try {
            JSONArray la = new JSONArray();
            for (Line l : lines) la.put(new JSONObject().put("who", l.who).put("text", l.text));
            JSONArray ca = new JSONArray();
            for (Choice c : choices) ca.put(new JSONObject().put("label", c.label).put("hint", c.hint));
            return new JSONObject().put("title", title).put("lines", la).put("picture", brief)
                    .put("sound", sound == null ? JSONObject.NULL : sound).put("choices", ca).put("ending", ending);
        } catch (JSONException e) { return new JSONObject(); }
    }

    /** Reads a page the TV itself saved (already checked when it was made). */
    public static StoryPage fromSaved(JSONObject o) {
        List<Line> lines = new ArrayList<>();
        JSONArray la = o.optJSONArray("lines");
        for (int i = 0; la != null && i < la.length(); i++) {
            JSONObject l = la.optJSONObject(i);
            if (l != null) lines.add(new Line(l.optString("who", NARRATOR), l.optString("text", "")));
        }
        List<Choice> choices = new ArrayList<>();
        JSONArray ca = o.optJSONArray("choices");
        for (int i = 0; ca != null && i < ca.length(); i++) {
            JSONObject c = ca.optJSONObject(i);
            if (c != null) choices.add(new Choice(c.optString("label", ""), c.optString("hint", "")));
        }
        return new StoryPage(o.optString("title", ""), lines, o.optString("picture", ""),
                o.isNull("sound") ? null : o.optString("sound", null), choices, o.optBoolean("ending", false));
    }

    static boolean containsIgnoreCase(Set<String> set, String s) {
        for (String x : set) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }

    static String canon(Set<String> set, String s) {
        for (String x : set) if (x.equalsIgnoreCase(s)) return x;
        return s;
    }

    static String cap(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n); }
}
