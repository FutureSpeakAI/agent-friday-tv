package ai.futurespeak.friday.tv.studio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captions from the audio itself (spec §8.4): narration is spoken a sentence at a time, so each
 * caption's time is the length of the PCM spoken before it, exact to the sample. Also the
 * player's small WebVTT reader (ST-E07). Pure Java.
 */
public final class Captions {
    private Captions() {}

    public static final class Cue {
        public final long start, end; public final String text;
        public Cue(long start, long end, String text) { this.start = start; this.end = end; this.text = text; }
    }

    /** Sentences, with very short ones joined to the next, none over about 240 characters. */
    public static List<String> pieces(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = Pattern.compile("[^.!?]+(?:[.!?]+[\"”’')\\]]*|$)").matcher(text.replaceAll("\\s+", " ").trim());
        StringBuilder cur = new StringBuilder();
        while (m.find()) {
            String s = m.group().trim();
            if (s.isEmpty()) continue;
            while (s.length() > 240) {                       // a run-on sentence: split at a comma or space
                int cut = s.lastIndexOf(", ", 240);
                if (cut < 80) cut = s.lastIndexOf(' ', 240);
                if (cut < 40) cut = 240;
                flush(out, cur);
                out.add(s.substring(0, cut + (s.charAt(cut) == ',' ? 1 : 0)).trim());
                s = s.substring(cut + 1).trim();
            }
            if (cur.length() > 0 && cur.length() + s.length() > 240) flush(out, cur);
            if (cur.length() > 0) cur.append(' ');
            cur.append(s);
            if (cur.length() >= 40) flush(out, cur);
        }
        flush(out, cur);
        return out;
    }

    private static void flush(List<String> out, StringBuilder cur) {
        if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
    }

    public static long ms(long pcmBytes) { return pcmBytes * 1000L / (Engines.RATE * 2L); }

    public static JSONArray toJson(List<Cue> cues) {
        JSONArray a = new JSONArray();
        try { for (Cue c : cues) a.put(new JSONObject().put("s", c.start).put("e", c.end).put("t", c.text)); }
        catch (JSONException ignored) { }
        return a;
    }

    public static List<Cue> fromJson(JSONArray a, long offset) {
        List<Cue> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(new Cue(o.optLong("s") + offset, o.optLong("e") + offset, o.optString("t")));
        }
        return out;
    }

    public static String vtt(List<Cue> cues) {
        StringBuilder b = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            b.append(i + 1).append('\n').append(stamp(c.start)).append(" --> ").append(stamp(c.end)).append('\n')
             .append(c.text.replace("-->", "→")).append("\n\n");
        }
        return b.toString();
    }

    static String stamp(long ms) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000);
    }

    private static final Pattern TIMING = Pattern.compile("^(\\d{2,}):(\\d{2}):(\\d{2})\\.(\\d{3})\\s+-->\\s+(\\d{2,}):(\\d{2}):(\\d{2})\\.(\\d{3})");

    /** A small WebVTT reader: timings and text, nothing else (no styling, no positioning). */
    public static List<Cue> parseVtt(String s) {
        List<Cue> out = new ArrayList<>();
        if (s == null || !s.startsWith("WEBVTT")) return out;
        String[] lines = s.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TIMING.matcher(lines[i].trim());
            if (!m.find()) continue;
            long a = at(m, 1), e = at(m, 5);
            StringBuilder t = new StringBuilder();
            while (i + 1 < lines.length && !lines[i + 1].trim().isEmpty()) { if (t.length() > 0) t.append(' '); t.append(lines[++i].trim()); }
            if (e > a) out.add(new Cue(a, e, t.toString()));
        }
        return out;
    }

    private static long at(Matcher m, int g) {
        return Long.parseLong(m.group(g)) * 3_600_000 + Long.parseLong(m.group(g + 1)) * 60_000 + Long.parseLong(m.group(g + 2)) * 1000 + Long.parseLong(m.group(g + 3));
    }

    /** The cue showing at this time, or null. */
    public static Cue at(List<Cue> cues, long ms) {
        int lo = 0, hi = cues.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Cue c = cues.get(mid);
            if (ms < c.start) hi = mid - 1; else if (ms >= c.end) lo = mid + 1; else return c;
        }
        return null;
    }
}
