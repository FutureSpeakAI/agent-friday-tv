package ai.futurespeak.friday.tv.studio;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.futurespeak.friday.tv.Prefs;
import ai.futurespeak.friday.tv.actions.Receipts;
import ai.futurespeak.friday.tv.secure.SecureStore;

/**
 * Friday Studio on the TV: the {@link StudioPort} the gate asks, and the one
 * background thread that builds. It adds no service and no permission (ST-E05): a piece is
 * made while Friday is running, saved a chapter at a time, and picked up again the next time
 * Friday opens (AC-S3). The screen ({@link StudioActivity}) shows the card and the progress.
 */
public final class Studio implements StudioPort {
    private static final String TAG = "FridayStudio";

    /** What the screen does for Studio. Every method but progress blocks until the person answers. */
    public interface Screen {
        int pickLength(StudioRequest r, int[] lengths, String builtFrom);
        boolean approve(Prepared p);
        boolean reask(Job j, double spentUsd, double newEstimateUsd);
        void progress(Job j, String words);
    }

    private static volatile Studio instance;

    public static synchronized Studio init(Context c, Receipts receipts) {
        if (instance == null) instance = new Studio(c.getApplicationContext(), receipts);
        return instance;
    }

    /** Null until {@code TvActions} has made it (the receipts must be the gate's own). */
    public static Studio get() { return instance; }

    private final Context ctx;
    private final Receipts receipts;
    private final SecureStore store;
    private final Library library;
    private final File root;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "studio-build"); t.setDaemon(true); return t; });
    private final Set<String> running = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConcurrentHashMap<String, AtomicBoolean> stops = new ConcurrentHashMap<>();
    private volatile Screen screen;
    private Voices.Tv tvVoice;

    private Studio(Context c, Receipts receipts) {
        ctx = c; this.receipts = receipts;
        store = new SecureStore(c);
        root = new File(c.getFilesDir(), "studio");
        library = new Library(root, new ai.futurespeak.friday.tv.actions.KeystoreSigner());
    }

    public Library library() { return library; }
    public File root() { return root; }

    // ---- the port ----------------------------------------------------------------------------

    @Override public boolean ready() {
        String k = store.get(SecureStore.ANTHROPIC);
        return k != null && !k.isEmpty();
    }

    boolean fridayVoice() {
        String k = store.get(SecureStore.ELEVENLABS);
        return k != null && !k.isEmpty();
    }

    String voiceName() { return fridayVoice() ? "Friday's voice (ElevenLabs)" : "the TV's own voice"; }

    private Engines.Writer writer() {
        return new StudioClaude(() -> store.get(SecureStore.ANTHROPIC), new Prefs(ctx).model());
    }

    Prices prices() { return Prices.load(new File(root, "prices.json")); }

    @Override public int pickLength(StudioRequest r, int[] lengths, String builtFrom) {
        Screen s = screen(true);
        return s == null ? 0 : s.pickLength(r, lengths, builtFrom);
    }

    @Override public Prepared prepare(StudioRequest r, List<StudioSource> sources, Spent spentBefore, String builtFrom) {
        Spent spent = new Spent();
        if (spentBefore != null) spent.addAll(spentBefore);
        Prices prices = prices();
        Screen sc = screen;
        boolean tainted = false;
        String taintFrom = null;
        for (StudioSource s : sources) {
            if (StudioRequest.triesToSteer(s.text) || StudioRequest.triesToSteer(s.title)) { tainted = true; taintFrom = s.outlet; }
        }
        int chars = 0;
        for (StudioSource s : sources) chars += Math.min(StudioSource.MAX_CHARS, s.text.length());
        if (sc != null) sc.progress(null, "Planning it");
        Engines.Writer w = writer();
        Planner.Result plan = Planner.plan(r, sources, w, prices);
        spent.addAll(plan.spent);
        if (plan.plan == null) return Prepared.failed(r, plan.problem, spent);
        Estimate est = Estimate.podcast(r, chars, plan.plan.chapters.size(), w.model(), fridayVoice(), prices);
        return new Prepared(r, sources, plan.plan, null, est, spent, tainted, taintFrom, voiceName(), builtFrom);
    }

    @Override public boolean approve(Prepared p) {
        Screen s = screen(true);
        return s != null && s.approve(p);
    }

    @Override public void start(Prepared p, String decisionId, String principal) {
        try {
            String id = "pc-" + Long.toString(System.currentTimeMillis(), 36) + "-" + Integer.toHexString((int) (SystemClock.elapsedRealtimeNanos() & 0xffff));
            Job j = Job.create(root, id, decisionId, principal, p.request, p.plan, p.sources, p.total(), p.spentBefore, p.voice, System.currentTimeMillis());
            library.put(j, "Starting");
            submit(j);
        } catch (IOException e) {
            Log.w(TAG, "couldn't start the job (" + e.getMessage() + ")");
            try { receipts.outcome(decisionId, principal, "failed", "couldn't save the job on the TV"); } catch (IOException ignored) { }
        }
    }

    // ---- building ----------------------------------------------------------------------------

    private void submit(Job j) {
        if (!running.add(j.id())) return;
        AtomicBoolean stop = new AtomicBoolean(false);
        stops.put(j.id(), stop);
        worker.submit(() -> {
            try {
                Builder b = new Builder(writer(), fridayVoice() ? fridaySpeaker(j) : null,
                        tvVoice(), Media.ENCODER, Media.VERIFIER, prices(), library, this::outcome);
                Screen s = screen;
                String state = b.run(j, (job, words) -> { Screen sc = screen; if (sc != null) sc.progress(job, words); },
                        s == null ? null : (job, spent, est) -> { Screen sc = screen(true); return sc != null && sc.reask(job, spent, est); }, stop);
                Log.i(TAG, "job " + j.id() + " " + state);
                Screen sc = screen;
                if (sc != null) sc.progress(j, "");
            } catch (RuntimeException e) {
                Log.w(TAG, "job " + j.id() + " crashed (" + e.getClass().getSimpleName() + ")");
            } finally {
                running.remove(j.id());
                stops.remove(j.id());
            }
        });
    }

    /**
     * Friday's ElevenLabs voice for this job: the voice chosen in Settings, or, when ElevenLabs no longer has it
     * (HTTP 404, found on the TCL 2026-11-02), Friday's default ElevenLabs voice rather than the TV's own. The
     * switch is recorded on the piece (rule 6: no silent fallback). The check is one word, once per job.
     */
    private Engines.Speaker fridaySpeaker(Job j) {
        Prefs p = new Prefs(ctx);
        Voices.Friday chosen = new Voices.Friday(() -> store.get(SecureStore.ELEVENLABS), p.elevenVoice(), p.elevenModel());
        if (Prefs.DEFAULT_ELEVEN_VOICE.equals(p.elevenVoice()) || j.done() > 0) return chosen;
        try { chosen.pcm("Hi."); return chosen; }
        catch (Exception e) {
            if (!"HTTP 404".equals(Builder.reason(e))) return chosen;   // anything else: the builder's own fallback handles it
            try { j.switched("The ElevenLabs voice chosen in Settings wasn't found (HTTP 404), so Friday's default ElevenLabs voice spoke it"); }
            catch (IOException ignored) { }
            return new Voices.Friday(() -> store.get(SecureStore.ELEVENLABS), Prefs.DEFAULT_ELEVEN_VOICE, p.elevenModel());
        }
    }

    private synchronized Engines.Speaker tvVoice() {
        if (tvVoice == null) tvVoice = new Voices.Tv(ctx, new File(ctx.getCacheDir(), "studio"));
        return tvVoice;
    }

    private void outcome(Job j, String result, String detail, JSONObject extra) {
        try { receipts.outcome(j.decision(), j.principal(), result, detail, extra); }
        catch (IOException e) { Log.w(TAG, "outcome receipt failed"); }
    }

    /** AC-S3: a piece interrupted while building carries on from its next chapter. Call when Friday comes to the front. */
    public void resumeUnfinished() {
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File d : dirs) {
            try {
                Job j = Job.load(d);
                if (Job.BUILDING.equals(j.state()) && !running.contains(j.id())) { Log.i(TAG, "resuming " + j.id() + " at chapter " + (j.done() + 1)); submit(j); }
            } catch (IOException ignored) { }
        }
    }

    /** A paused job (more than a quarter over): the person said yes on the card again. */
    public void approveMore(String id, double newApprovedUsd) {
        try {
            Job j = Job.load(new File(root, id));
            if (!Job.PAUSED.equals(j.state())) return;
            j.set("approved_usd", newApprovedUsd);
            j.finish(Job.BUILDING, null);
            submit(j);
        } catch (IOException ignored) { }
    }

    /** "Stop making it": the job stops before its next sentence, keeps what it spent, and says so. */
    public void stop(String id) {
        AtomicBoolean s = stops.get(id);
        if (s != null) s.set(true);
        else try {
            Job j = Job.load(new File(root, id));
            if (Job.PAUSED.equals(j.state())) {
                j.finish(Job.STOPPED, "Stopped: it was costing more than the estimate");
                library.put(j, "");
                outcome(j, "stopped", j.problem(), new JSONObject().put("meter", j.spent().meter("usd")).put("job", j.id()));
            }
        } catch (Exception ignored) { }
    }

    public boolean building(String id) { return running.contains(id); }

    public Job job(String id) {
        try { return id != null && id.matches("^[a-z0-9-]{6,64}$") ? Job.load(new File(root, id)) : null; }
        catch (IOException e) { return null; }
    }

    /** "Remove from TV": deletes the piece and its row; receipted. A piece still building is stopped first. */
    public void remove(String id) {
        if (id == null || !id.matches("^[a-z0-9-]{6,64}$")) return;
        stop(id);
        File d = new File(root, id);
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
        try {
            library.remove(id);
            String rid = receipts.decision("family", new JSONObject().put("action", "studio.remove").put("args", new JSONObject().put("job", id))
                    .put("tier", 0).put("origin", "remote").put("who_may", "ok").put("policy", "record"));
            receipts.outcome(rid, "family", "executed", "removed from the TV");
        } catch (Exception ignored) { }
    }

    // ---- the screen ----------------------------------------------------------------------------

    public void attach(Screen s) { screen = s; }
    public void detach(Screen s) { if (screen == s) screen = null; }

    /** The screen, starting it if Friday's voice path asked and no Studio screen is up yet (waits up to 6 s). */
    private Screen screen(boolean start) {
        Screen s = screen;
        if (s != null || !start) return s;
        ctx.startActivity(new Intent(ctx, StudioActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        for (int i = 0; i < 60 && screen == null; i++) SystemClock.sleep(100);
        return screen;
    }

    /** "Studio today" on the Spend page: the actual cost of every piece that ended today, from the receipts alone (AC-S2). */
    public static double todayUsd(List<Receipts.Entry> entries, long since) {
        double t = 0;
        for (Receipts.Entry e : entries) {
            if (e.ts < since || e.outcome == null || !"studio.make".equals(e.decision.optString("action"))) continue;
            JSONObject m = e.outcome.optJSONObject("meter");
            if (m != null) t += m.optDouble("usd", 0);
        }
        return t;
    }

    static String minutes(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
