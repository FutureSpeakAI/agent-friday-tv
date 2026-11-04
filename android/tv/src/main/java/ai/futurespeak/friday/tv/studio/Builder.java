package ai.futurespeak.friday.tv.studio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Makes a podcast on the TV alone (spec §8.4, slice ST0), chapter by chapter: Claude writes the
 * chapter, Friday's voice speaks it a sentence at a time (the TV's voice takes over if
 * ElevenLabs fails, and says so: rule 6, AC-S4), and the chapter is saved before the next
 * starts (AC-S3). Then the audio is joined, encoded, played back by the platform to check it,
 * hashed, and only then called done (rule 5). Money is tracked per service as it is spent; a
 * job more than a quarter over its estimate stops and asks again (ST-E03). Runs on one
 * background thread.
 */
public final class Builder {
    public interface Progress { void milestone(Job j, String words); }
    /** Raises the card again with the new figure; true only for an explicit yes. */
    public interface Asker { boolean reask(Job j, double spentUsd, double newEstimateUsd); }
    /** Writes the job's outcome receipt (the decision was written by the gate). */
    public interface Outcome { void outcome(Job j, String result, String detail, JSONObject extra); }

    static final int SILENCE_MS = 600;

    private final Engines.Writer writer;
    private final Engines.Speaker friday, tv;
    private final Engines.Encoder encoder;
    private final Engines.Verifier verifier;
    private final Prices prices;
    private final Library library;
    private final Outcome outcome;

    /** @param friday Friday's paid voice, or null when the TV has no ElevenLabs key (then the TV's voice speaks it all) */
    public Builder(Engines.Writer writer, Engines.Speaker friday, Engines.Speaker tv, Engines.Encoder encoder,
                   Engines.Verifier verifier, Prices prices, Library library, Outcome outcome) {
        this.writer = writer; this.friday = friday; this.tv = tv; this.encoder = encoder; this.verifier = verifier;
        this.prices = prices; this.library = library; this.outcome = outcome;
    }

    /** Builds whatever is left of the job. Returns its final state. */
    public String run(Job j, Progress p, Asker asker, AtomicBoolean stop) {
        try {
            if (!Job.BUILDING.equals(j.state())) return j.state();
            Storyboard plan = j.plan();
            StudioRequest r = j.request();
            List<StudioSource> sources = j.sources();
            int n = plan.chapters.size();
            int words = plan.wordsPerChapter(r);
            for (int i = j.done(); i < n; i++) {
                if (stop.get()) return end(j, Job.STOPPED, "Stopped at chapter " + (i + 1) + " of " + n, "stopped");
                say(j, p, "Writing chapter " + (i + 1) + " of " + n);
                String text = chapterText(j, plan, i, words, sources);
                if (text == null) return end(j, Job.FAILED, "Couldn't write chapter " + (i + 1), "failed");
                if (stop.get()) return end(j, Job.STOPPED, "Stopped at chapter " + (i + 1) + " of " + n, "stopped");
                say(j, p, "Speaking chapter " + (i + 1) + " of " + n);
                String spoken = (i == 0 ? Prompts.OPENING + " " : "") + text + (i == n - 1 ? " " + Prompts.CLOSE : "");
                if (!speak(j, i, spoken, stop)) {
                    if (stop.get()) return end(j, Job.STOPPED, "Stopped at chapter " + (i + 1) + " of " + n, "stopped");
                    return end(j, Job.FAILED, "Couldn't speak chapter " + (i + 1), "failed");
                }
                // The quarter rule: a surprise check against what the person approved, never a cap.
                double spent = j.spent().usd(), approved = j.o.optDouble("approved_usd", j.est().usd());
                if (i < n - 1 && Estimate.overQuarter(spent, approved)) {
                    double projected = spent / (i + 1) * n;
                    if (asker == null) {
                        j.finish(Job.PAUSED, null);
                        library.put(j, "Waiting for your OK");
                        return Job.PAUSED;
                    }
                    if (!asker.reask(j, spent, projected)) return end(j, Job.STOPPED, "Stopped: it was costing more than the estimate", "stopped");
                    j.set("approved_usd", projected);
                }
            }
            say(j, p, "Putting it together");
            return assemble(j, plan, sources);
        } catch (IOException | RuntimeException e) {
            try { return end(j, Job.FAILED, "Something went wrong saving it", "failed"); } catch (IOException ignored) { return Job.FAILED; }
        }
    }

    private void say(Job j, Progress p, String words) throws IOException {
        library.put(j, words);
        if (p != null) p.milestone(j, words);
    }

    /** Claude writes the chapter (kept on disk, so a resume never pays for it twice); one retry; overlong text is trimmed at a sentence. */
    private String chapterText(Job j, Storyboard plan, int i, int words, List<StudioSource> sources) throws IOException {
        File f = j.chapterText(i);
        if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        String prev = "";
        if (i > 0 && j.chapterText(i - 1).isFile()) prev = Prompts.ending(new String(Files.readAllBytes(j.chapterText(i - 1).toPath()), StandardCharsets.UTF_8));
        String user = Prompts.chapterUser(plan, i, words, sources, prev);
        String text = null;
        for (int attempt = 0; attempt < 2 && text == null; attempt++) {
            try {
                Engines.Reply rep = writer.write(Prompts.chapterSystem(), user + (attempt > 0 ? "\nKeep it under " + Storyboard.MAX_WORDS + " words." : ""), 3000);
                Spent s = new Spent();
                s.add("claude", "tokens", rep.in + rep.out + rep.cacheRead + rep.cacheWrite, prices.claude(writer.model()).usd(rep.in, rep.out, rep.cacheRead, rep.cacheWrite));
                j.spend(s);
                String t = clean(rep.text);
                if (Storyboard.words(t) < 20) continue;
                if (Storyboard.words(t) > Storyboard.MAX_WORDS && attempt == 0) continue;
                text = trim(t, Storyboard.MAX_WORDS);
            } catch (Exception e) {
                // one more try
            }
        }
        if (text != null) Job.write(f, text);
        return text;
    }

    /** Markdown and stage directions out: only words to be spoken. */
    static String clean(String t) {
        if (t == null) return "";
        return t.replaceAll("(?m)^\\s*#+\\s*", "").replaceAll("\\*+|_{2,}|`", "").replaceAll("\\[[^\\]]{0,60}\\]", "")
                .replaceAll("\\s+", " ").trim();
    }

    /** At most {@code max} words, cut after the last whole sentence that fits. */
    static String trim(String t, int max) {
        if (Storyboard.words(t) <= max) return t;
        StringBuilder b = new StringBuilder();
        for (String s : Captions.pieces(t)) {
            if (Storyboard.words(b.toString()) + Storyboard.words(s) > max) break;
            if (b.length() > 0) b.append(' ');
            b.append(s);
        }
        return b.toString();
    }

    /** Speaks one chapter a sentence at a time into chNN.pcm, with its caption timings. */
    private boolean speak(Job j, int i, String text, AtomicBoolean stop) throws IOException {
        File part = new File(j.dir, j.chapterPcm(i).getName() + ".part");
        List<Captions.Cue> cues = new ArrayList<>();
        long bytes = 0;
        boolean useFriday = friday != null;
        String voiceUsed = useFriday ? friday.name() : tv.name();
        Spent cost = new Spent();
        try (OutputStream out = new FileOutputStream(part)) {
            for (String piece : Captions.pieces(text)) {
                if (stop.get()) return false;
                byte[] pcm = null;
                if (useFriday) {
                    try {
                        pcm = friday.pcm(piece);
                        if (friday.paid()) cost.add("elevenlabs", "characters", piece.length(), piece.length() / 1000.0 * prices.elevenPer1k);
                    } catch (Exception e) {
                        useFriday = false;   // the TV's voice finishes this chapter, and the record says so
                        voiceUsed = friday.name() + " and the TV's voice";
                        j.switched("Chapter " + (i + 1) + ": " + friday.name() + " failed (" + reason(e) + "); the TV's voice finished it");
                    }
                }
                if (pcm == null) {
                    try { pcm = tv.pcm(piece); }
                    catch (Exception e) { j.spend(cost); return false; }
                    cost.add("tv voice", "characters", piece.length(), 0);
                }
                if ((pcm.length & 1) == 1) pcm = java.util.Arrays.copyOf(pcm, pcm.length - 1);
                out.write(pcm);
                long start = Captions.ms(bytes);
                bytes += pcm.length;
                cues.add(new Captions.Cue(start, Captions.ms(bytes), piece));
            }
        }
        j.spend(cost);
        Job.write(j.chapterCues(i), Captions.toJson(cues).toString());
        Files.move(part.toPath(), j.chapterPcm(i).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        j.chapterDone(i, Storyboard.words(text), Captions.ms(bytes), voiceUsed);
        return true;
    }

    static String reason(Exception e) {
        String m = e.getMessage();
        return m != null && m.matches("^HTTP \\d{3}$") ? m : e.getClass().getSimpleName();
    }

    /** Joins the chapters, encodes, checks the file plays for as long as it should, hashes it, and says done. */
    private String assemble(Job j, Storyboard plan, List<StudioSource> sources) throws IOException {
        int n = plan.chapters.size();
        File all = new File(j.dir, "all.pcm");
        List<Captions.Cue> cues = new ArrayList<>();
        JSONArray chapters = new JSONArray();
        long bytes = 0;
        byte[] gap = new byte[(int) ((long) Engines.RATE * 2 * SILENCE_MS / 1000) & ~1];
        try (OutputStream out = new FileOutputStream(all)) {
            for (int i = 0; i < n; i++) {
                if (i > 0) { out.write(gap); bytes += gap.length; }
                long start = Captions.ms(bytes);
                File pcm = j.chapterPcm(i);
                try (InputStream in = new FileInputStream(pcm)) {
                    byte[] buf = new byte[64 * 1024];
                    for (int k; (k = in.read(buf)) > 0; ) { out.write(buf, 0, k); bytes += k; }
                }
                JSONArray cj = new JSONArray(new String(Files.readAllBytes(j.chapterCues(i).toPath()), StandardCharsets.UTF_8));
                cues.addAll(Captions.fromJson(cj, start));
                Storyboard.Chapter c = plan.chapters.get(i);
                JSONArray src = new JSONArray();
                for (int s : c.sources) src.put(s);
                chapters.put(new JSONObject().put("title", c.title).put("start_ms", start).put("sources", src));
            }
        } catch (JSONException e) {
            throw new IOException("cues");
        }
        long totalMs = Captions.ms(bytes);
        Job.write(new File(j.dir, "captions.vtt"), Captions.vtt(cues));
        try {
            JSONArray srcs = new JSONArray();
            for (StudioSource s : sources) srcs.put(s.meta());
            Job.write(new File(j.dir, "script.json"), new JSONObject().put("title", plan.title).put("duration_ms", totalMs)
                    .put("chapters", chapters).put("sources", srcs).put("voice", j.voice()).toString());
        } catch (JSONException e) { throw new IOException("script"); }

        File made;
        try { made = encoder.encode(all, j.dir); }
        catch (Exception e) { return end(j, Job.FAILED, "Couldn't save the audio", "failed"); }
        String why = check(made, totalMs);
        if (why != null) return end(j, Job.FAILED, "The audio didn't come out right (" + why + ")", "failed");
        all.delete();
        for (int i = 0; i < n; i++) j.chapterPcm(i).delete();
        j.set("file", made.getName());
        j.set("sha256", sha256(made));
        j.set("duration_ms", totalMs);
        return end(j, Job.DONE, null, "executed");
    }

    /** Rule 5: a zero-byte file, a wrong header or a file that plays for the wrong length is a failure, not a creation. */
    String check(File f, long expectMs) {
        if (f == null || !f.isFile() || f.length() < 1024) return "empty file";
        byte[] head = new byte[12];
        try (InputStream in = new FileInputStream(f)) { if (in.read(head) < 12) return "short file"; }
        catch (IOException e) { return "unreadable"; }
        boolean m4a = head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
        boolean wav = head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F' && head[8] == 'W';
        if (!m4a && !wav) return "not an audio file";
        long d = verifier.durationMs(f);
        if (d < 0) return "it doesn't play";
        if (Math.abs(d - expectMs) > 2000) return "it plays for " + d / 1000 + " s, not " + expectMs / 1000 + " s";
        return null;
    }

    private String end(Job j, String state, String problem, String result) throws IOException {
        j.finish(state, problem);
        library.put(j, "");
        JSONObject extra = new JSONObject();
        try {
            JSONObject meter = j.spent().meter("usd");
            meter.put("usd_est", Spent.round(j.est().usd(), 4));
            extra.put("meter", meter).put("taint", false).put("job", j.id());
            if (Job.DONE.equals(state)) extra.put("file", j.file()).put("sha256", j.sha256()).put("duration_ms", j.durationMs());
            List<String> sw = j.switches();
            if (!sw.isEmpty()) extra.put("voice_switches", new JSONArray(sw));
        } catch (JSONException ignored) { }
        String detail = Job.DONE.equals(state)
                ? "made a " + j.request().lengthMin + "-minute podcast: " + j.title()
                : problem;
        outcome.outcome(j, result, detail, extra);
        return state;
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            for (int k; (k = in.read(buf)) > 0; ) d.update(buf, 0, k);
            StringBuilder b = new StringBuilder();
            for (byte x : d.digest()) b.append(String.format(java.util.Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException("sha"); }
    }
}
