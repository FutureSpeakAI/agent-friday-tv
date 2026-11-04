package ai.futurespeak.friday.tv.studio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * One piece being made or made: {@code files/studio/<id>/job.json} plus its chapter files
 * (spec §7, ST-E05). Every finished chapter is written before the next starts, so a job killed
 * mid-way resumes at the first chapter without audio (AC-S3). The record is written atomically.
 */
public final class Job {
    public static final String BUILDING = "building", PAUSED = "paused", DONE = "done", FAILED = "failed", STOPPED = "stopped";

    public final File dir;
    JSONObject o;

    private Job(File dir, JSONObject o) { this.dir = dir; this.o = o; }

    public static Job create(File root, String id, String decision, String principal, StudioRequest r, Storyboard plan,
                             List<StudioSource> sources, Spent est, Spent spentSoFar, String voice, long now) throws IOException {
        if (!id.matches("^[a-z0-9-]{6,64}$")) throw new IOException("bad id");
        File d = new File(root, id);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("no job dir");
        try {
            JSONArray metas = new JSONArray();
            for (int i = 0; i < sources.size(); i++) {
                metas.put(sources.get(i).meta());
                write(new File(d, "src-" + i + ".txt"), sources.get(i).text);
            }
            JSONObject o = new JSONObject().put("id", id).put("decision", decision).put("principal", principal).put("created", now)
                    .put("request", r.toJson()).put("plan", plan.toJson()).put("sources", metas).put("state", BUILDING)
                    .put("done", 0).put("chapters", new JSONArray()).put("est", est.meter("usd_est")).put("spent", spentSoFar.meter("usd"))
                    .put("voice", voice).put("switches", new JSONArray()).put("played", false);
            Job j = new Job(d, o);
            j.save();
            return j;
        } catch (JSONException e) { throw new IOException("job"); }
    }

    public static Job load(File dir) throws IOException {
        File f = new File(dir, "job.json");
        try { return new Job(dir, new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))); }
        catch (JSONException e) { throw new IOException("job.json unreadable"); }
    }

    public synchronized void save() throws IOException {
        File tmp = new File(dir, "job.tmp");
        write(tmp, o.toString());
        Files.move(tmp.toPath(), new File(dir, "job.json").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static void write(File f, String s) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(s.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
    }

    // ---- reading ----------------------------------------------------------------------------

    public String id() { return o.optString("id"); }
    public String decision() { return o.optString("decision"); }
    public String principal() { return o.optString("principal", "family"); }
    public synchronized String state() { return o.optString("state"); }
    public synchronized int done() { return o.optInt("done"); }
    public StudioRequest request() { return StudioRequest.fromJson(o.optJSONObject("request")); }
    public Storyboard plan() { return Storyboard.parse(o.optJSONObject("plan").toString()); }
    public String title() { return o.optJSONObject("plan").optString("title"); }
    public long created() { return o.optLong("created"); }
    public String voice() { return o.optString("voice"); }
    public synchronized Spent spent() { return Spent.fromMeter(o.optJSONObject("spent"), "usd"); }
    public Spent est() { return Spent.fromMeter(o.optJSONObject("est"), "usd_est"); }
    public synchronized boolean played() { return o.optBoolean("played"); }
    public synchronized String problem() { return o.optString("problem", ""); }
    public synchronized boolean reasked() { return o.optBoolean("reasked"); }
    public synchronized long durationMs() { return o.optLong("duration_ms"); }
    public synchronized String file() { return o.optString("file", ""); }
    public synchronized String sha256() { return o.optString("sha256", ""); }

    public synchronized List<String> switches() {
        List<String> out = new ArrayList<>();
        JSONArray a = o.optJSONArray("switches");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    public List<StudioSource> sources() throws IOException {
        List<StudioSource> out = new ArrayList<>();
        JSONArray a = o.optJSONArray("sources");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            String text = new String(Files.readAllBytes(new File(dir, "src-" + i + ".txt").toPath()), StandardCharsets.UTF_8);
            out.add(new StudioSource(m.optString("kind"), m.optString("title"), m.optString("outlet"), m.optString("url"), text));
        }
        return out;
    }

    public File chapterPcm(int i) { return new File(dir, String.format(java.util.Locale.ROOT, "ch%02d.pcm", i + 1)); }
    public File chapterCues(int i) { return new File(dir, String.format(java.util.Locale.ROOT, "ch%02d.cues.json", i + 1)); }
    public File chapterText(int i) { return new File(dir, String.format(java.util.Locale.ROOT, "ch%02d.txt", i + 1)); }

    // ---- writing ----------------------------------------------------------------------------

    synchronized void chapterDone(int i, int words, long ms, String voice) throws IOException {
        try {
            o.getJSONArray("chapters").put(new JSONObject().put("i", i).put("words", words).put("ms", ms).put("voice", voice));
            o.put("done", i + 1);
        } catch (JSONException e) { throw new IOException("job"); }
        save();
    }

    synchronized void spend(Spent add) throws IOException {
        Spent s = spent();
        s.addAll(add);
        try { o.put("spent", s.meter("usd")); } catch (JSONException e) { throw new IOException("job"); }
        save();
    }

    synchronized void switched(String what) throws IOException {
        try { o.getJSONArray("switches").put(what); } catch (JSONException e) { throw new IOException("job"); }
        save();
    }

    synchronized void set(String key, Object value) throws IOException {
        try { o.put(key, value); } catch (JSONException e) { throw new IOException("job"); }
        save();
    }

    synchronized void finish(String state, String problem) throws IOException {
        try { o.put("state", state); if (problem != null) o.put("problem", problem); } catch (JSONException e) { throw new IOException("job"); }
        save();
    }

    public synchronized void markPlayed() {
        try { o.put("played", true); save(); } catch (JSONException | IOException ignored) { }
    }
}
