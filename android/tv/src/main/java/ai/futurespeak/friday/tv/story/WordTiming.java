package ai.futurespeak.friday.tv.story;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * When each word is spoken, for the read-along highlight (spec §4.7). From ElevenLabs'
 * character alignment where the voice gives one; otherwise shared out in proportion to the
 * words' letters over the audio's length (the TV's voice, or a voice without alignment).
 * Pure Java.
 */
public final class WordTiming {
    public static final class Word {
        public final int start, end;      // character offsets in the text
        public final long fromMs, toMs;
        public Word(int start, int end, long fromMs, long toMs) { this.start = start; this.end = end; this.fromMs = fromMs; this.toMs = toMs; }
    }

    private WordTiming() {}

    /** The words of {@code text} as character spans. */
    static List<int[]> spans(String text) {
        List<int[]> out = new ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            int s = i;
            while (i < n && !Character.isWhitespace(text.charAt(i))) i++;
            if (i > s) out.add(new int[]{s, i});
        }
        return out;
    }

    /** Timings shared out by letter count over {@code durationMs}. */
    public static List<Word> proportional(String text, long durationMs) {
        List<int[]> sp = spans(text);
        List<Word> out = new ArrayList<>();
        int letters = 0;
        for (int[] s : sp) letters += (s[1] - s[0]) + 1;
        if (letters == 0 || durationMs <= 0) return out;
        long at = 0;
        for (int i = 0; i < sp.size(); i++) {
            int[] s = sp.get(i);
            long len = Math.max(1, durationMs * ((s[1] - s[0]) + 1) / letters);
            long to = i == sp.size() - 1 ? durationMs : Math.min(durationMs, at + len);   // the last word ends with the audio
            out.add(new Word(s[0], s[1], at, to));
            at += len;
        }
        return out;
    }

    /**
     * From ElevenLabs' {@code alignment}: {@code characters[]}, {@code character_start_times_seconds[]},
     * {@code character_end_times_seconds[]}. The alignment's characters are matched to the text in
     * order; where they do not line up, the proportional timing is returned instead.
     */
    public static List<Word> fromAlignment(String text, JSONObject alignment, long durationMs) {
        if (alignment == null) return proportional(text, durationMs);
        try {
            JSONArray chars = alignment.getJSONArray("characters");
            JSONArray starts = alignment.getJSONArray("character_start_times_seconds");
            JSONArray ends = alignment.getJSONArray("character_end_times_seconds");
            if (chars.length() != starts.length() || chars.length() != ends.length() || chars.length() == 0) return proportional(text, durationMs);
            StringBuilder aligned = new StringBuilder();
            for (int i = 0; i < chars.length(); i++) aligned.append(chars.getString(i));
            if (!aligned.toString().replaceAll("\\s+", "").equals(text.replaceAll("\\s+", ""))) return proportional(text, durationMs);
            // Walk both strings together, skipping whitespace on each side.
            List<int[]> sp = spans(text);
            List<Word> out = new ArrayList<>();
            int ai = 0;
            for (int[] s : sp) {
                long from = -1, to = 0;
                for (int t = s[0]; t < s[1]; t++) {
                    while (ai < chars.length() && Character.isWhitespace(chars.getString(ai).charAt(0))) ai++;
                    if (ai >= chars.length()) break;
                    if (from < 0) from = Math.round(starts.getDouble(ai) * 1000);
                    to = Math.round(ends.getDouble(ai) * 1000);
                    ai++;
                }
                if (from < 0) return proportional(text, durationMs);
                out.add(new Word(s[0], s[1], from, Math.max(from, to)));
            }
            return out;
        } catch (JSONException | RuntimeException e) {
            return proportional(text, durationMs);
        }
    }

    /** The word being spoken at {@code ms}, or -1. */
    public static int wordAt(List<Word> words, long ms) {
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            if (ms >= w.fromMs && ms < w.toMs) return i;
            if (ms < w.fromMs) return i == 0 ? -1 : i - 1;
        }
        return words.isEmpty() ? -1 : words.size() - 1;
    }

    public static JSONArray toJson(List<Word> words) {
        JSONArray a = new JSONArray();
        try { for (Word w : words) a.put(new JSONObject().put("s", w.start).put("e", w.end).put("f", w.fromMs).put("t", w.toMs)); }
        catch (JSONException ignored) { }
        return a;
    }

    public static List<Word> fromJson(JSONArray a) {
        List<Word> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(new Word(o.optInt("s"), o.optInt("e"), o.optLong("f"), o.optLong("t")));
        }
        return Collections.unmodifiableList(out);
    }
}
