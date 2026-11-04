package ai.futurespeak.friday.tv.studio;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shell-only checks on a real TV or emulator, with no network and no spend (the PC's
 * {@code content call ... --method studio}). "card" shows the fixed card for a sample plan
 * (the answer is thrown away); "piece" makes a short piece from fixed text in the TV's own voice
 * through the real encoder and playback check (spec §12: the TCL's AAC encoder is unverified).
 * The test piece is titled as one and writes no receipts.
 */
public final class StudioDemo {
    private StudioDemo() {}
    private static final String TAG = "FridayStudio";

    static final String TEXT = "This is a test piece, made on the TV with no network. It checks that the TV's own voice, "
            + "the audio encoder and the player all work. Each sentence becomes one caption line, timed by the audio itself.";

    public static String run(Context c, String what) {
        Studio s = Studio.get();
        if (s == null) return "studio not started";
        try {
            StudioRequest r = StudioRequest.fromJson(new org.json.JSONObject().put("source_ref", "news:0000000000000000").put("length_min", 2));
            Storyboard plan = Storyboard.parse("{\"title\":\"Studio test piece\",\"chapters\":[{\"title\":\"What this checks\",\"summary\":\"The voice, the encoder and the player.\",\"sources\":[0]},"
                    + "{\"title\":\"How captions work\",\"summary\":\"Timed by the audio.\",\"sources\":[0]}]}");
            StudioSource src = new StudioSource("news", "A test story", "the TV", "", TEXT);
            if ("card".equals(what) || "steer".equals(what)) {
                Spent before = new Spent();
                before.add("claude", "tokens", 2600, 0.02);
                Estimate est = Estimate.podcast(r.withLength(10), 6000, 8, "claude-opus-5-5", true, s.prices());
                StudioPort.Prepared p = new StudioPort.Prepared(r.withLength(10), Collections.singletonList(src),
                        Storyboard.parse(plan.toJson().toString().replace("Studio test piece", "The ferry vote, explained")), null, est, before,
                        "steer".equals(what), "the page", "Friday's voice (ElevenLabs)");
                c.startActivity(new Intent(c, StudioActivity.class).setAction(StudioActivity.ACTION_LIBRARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                new Thread(() -> Log.i(TAG, "demo card answered " + s.approve(p)), "studio-demo").start();
                return "card shown (answer ignored)";
            }
            if (what.startsWith("kidscheck:")) {
                // Kids safety: the word list and the real Claude kid-safety check on topics, without kids mode on.
                StringBuilder out = new StringBuilder();
                for (String topic : what.substring("kidscheck:".length()).split("\\|")) {
                    String hit = ai.futurespeak.friday.tv.kids.KidsContent.wordListHit(topic);
                    ai.futurespeak.friday.tv.kids.KidsContent.Checker ch = ai.futurespeak.friday.tv.kids.KidsContent.checker;
                    Boolean claude = ch == null ? null : ch.safe(topic);
                    out.append(topic).append(": list ").append(hit == null ? "-" : "BLOCK").append(", Claude ")
                       .append(claude == null ? "no answer" : claude ? "SAFE" : "UNSAFE").append("; ");
                }
                return out.toString();
            }
            if ("voice".equals(what)) {
                // One word through Friday's ElevenLabs voice, as Studio asks for it: the saved voice, then the default. No sound.
                ai.futurespeak.friday.tv.secure.SecureStore store = new ai.futurespeak.friday.tv.secure.SecureStore(c);
                ai.futurespeak.friday.tv.Prefs p = new ai.futurespeak.friday.tv.Prefs(c);
                StringBuilder out = new StringBuilder("model " + p.elevenModel() + "; ");
                for (String v : new String[]{p.elevenVoice(), ai.futurespeak.friday.tv.Prefs.DEFAULT_ELEVEN_VOICE}) {
                    String got;
                    try { got = new Voices.Friday(() -> store.get(ai.futurespeak.friday.tv.secure.SecureStore.ELEVENLABS), v, p.elevenModel()).pcm("Hello.").length + " bytes"; }
                    catch (Exception e) { got = Builder.reason(e); }
                    out.append(v.equals(ai.futurespeak.friday.tv.Prefs.DEFAULT_ELEVEN_VOICE) ? "default voice: " : "saved voice: ").append(got).append("; ");
                }
                return out.toString();
            }
            if ("piece".equals(what)) {
                String id = "test-" + Long.toString(System.currentTimeMillis(), 36);
                Job j = Job.create(s.root(), id, "test", "family", r, plan, Collections.singletonList(src), new Spent(), new Spent(),
                        "the TV's own voice", System.currentTimeMillis());
                Engines.Writer fixed = new Engines.Writer() {
                    @Override public Engines.Reply write(String system, String user, int max) { return new Engines.Reply(TEXT, 0, 0, 0, 0); }
                    @Override public String model() { return "none"; }
                };
                Voices.Tv tv = new Voices.Tv(c, new File(c.getCacheDir(), "studio"));
                new Thread(() -> {
                    long t0 = System.currentTimeMillis();
                    String state = new Builder(fixed, null, tv, Media.ENCODER, Media.VERIFIER, s.prices(), s.library(), (job, res, d, x) -> { })
                            .run(j, null, null, new AtomicBoolean());
                    Job done = s.job(id);
                    Log.i(TAG, "demo piece " + state + " in " + (System.currentTimeMillis() - t0) + " ms; file " + (done == null ? "?" : done.file())
                            + ", " + (done == null ? 0 : done.durationMs()) + " ms" + (done == null || done.problem().isEmpty() ? "" : "; " + done.problem()));
                    tv.release();
                }, "studio-demo").start();
                return "making " + id + " (see logcat FridayStudio)";
            }
        } catch (Exception e) {
            return "failed: " + e.getClass().getSimpleName();
        }
        return "use card, steer or piece";
    }
}
