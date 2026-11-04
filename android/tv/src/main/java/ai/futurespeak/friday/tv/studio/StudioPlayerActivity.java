package ai.futurespeak.friday.tv.studio;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import ai.futurespeak.friday.tv.ui.Brand;
import ai.futurespeak.friday.tv.ui.InputLayer;

/**
 * The Studio player (spec §8.5), native views over the platform's MediaPlayer playing the file
 * on the TV: the title, the chapters, the caption line (on by default; Up hides it), elapsed and
 * total. Left/Right move a chapter, OK pauses, Down shows the sources for this chapter, Back
 * stops. Where it stopped is remembered per piece.
 */
public final class StudioPlayerActivity extends Activity {
    public static final String EXTRA_JOB = "job";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final InputLayer input = new InputLayer(this::route);
    private MediaPlayer player;
    private String id;
    private final List<Long> starts = new ArrayList<>();
    private final List<TextView> chapterViews = new ArrayList<>();
    private final List<List<Integer>> chapterSources = new ArrayList<>();
    private JSONArray sources = new JSONArray();
    private List<Captions.Cue> cues = new ArrayList<>();
    private TextView caption, clock, state, sourcesView;
    private long total;
    private boolean captionsOn = true;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("studio", MODE_PRIVATE);
        id = getIntent().getStringExtra(EXTRA_JOB);
        Studio s = Studio.get();
        Job j = s == null ? null : s.job(id);
        if (j == null || !Job.DONE.equals(j.state())) { finish(); return; }
        File f = new File(j.dir, j.file());
        try {
            // The file must still be the one that was checked and receipted (rule 5).
            if (!f.isFile() || !Builder.sha256(f).equals(j.sha256())) { setContentView(message("This piece's file changed on the TV, so it won't play. Remove it from the Library.")); return; }
            JSONObject script = new JSONObject(new String(Files.readAllBytes(new File(j.dir, "script.json").toPath()), StandardCharsets.UTF_8));
            cues = Captions.parseVtt(new String(Files.readAllBytes(new File(j.dir, "captions.vtt").toPath()), StandardCharsets.UTF_8));
            sources = script.optJSONArray("sources");
            if (sources == null) sources = new JSONArray();
            total = script.optLong("duration_ms", j.durationMs());
            setContentView(build(j, script));
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(mp -> { prefs.edit().remove(key()).apply(); state.setText("Finished"); });
            player.prepare();
            long resume = prefs.getLong(key(), 0);
            if (resume > 0 && resume < total - 5000) player.seekTo((int) resume);
            player.start();
            j.markPlayed();
            s.library().put(j, "");
            main.post(tick);
        } catch (Exception e) {
            setContentView(message("This piece couldn't be played."));
        }
    }

    private String key() { return "pos_" + id; }

    private View message(String s) {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(Brand.SURFACE);
        TextView t = Brand.text(this, s, 24, 0xFFFFFFFF, Brand.body(this));
        t.setGravity(Gravity.CENTER);
        f.addView(t, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    private View build(Job j, JSONObject script) {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0B1022, 0xFF0A0E1A, 0xFF140E2E}));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(64), dp(36), dp(64), dp(30));
        col.addView(Brand.wordmark(this, 15));
        TextView title = Brand.text(this, script.optString("title"), 32, 0xFFFFFFFF, Brand.display(this));
        title.setMaxLines(2);
        col.addView(title);
        TextView meta = Brand.text(this, "Podcast · " + Studio.minutes(total) + " · " + StudioActivity.cap(j.voice())
                + voiceNote(j.switches()), 16, Brand.DIM, Brand.body(this));
        col.addView(meta);
        LinearLayout middle = new LinearLayout(this);
        LinearLayout chapters = new LinearLayout(this);
        chapters.setOrientation(LinearLayout.VERTICAL);
        JSONArray cs = script.optJSONArray("chapters");
        for (int i = 0; cs != null && i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i);
            starts.add(c.optLong("start_ms"));
            List<Integer> src = new ArrayList<>();
            JSONArray sa = c.optJSONArray("sources");
            for (int k = 0; sa != null && k < sa.length(); k++) src.add(sa.optInt(k));
            chapterSources.add(src);
            TextView t = Brand.text(this, (i + 1) + "   " + c.optString("title"), 18, Brand.DIM, Brand.body(this));
            t.setPadding(dp(12), dp(6), dp(12), dp(6));
            t.setMaxLines(1);
            chapters.addView(t);
            chapterViews.add(t);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(520), -2);
        middle.addView(chapters, cl);
        sourcesView = Brand.text(this, "", 16, Brand.TEXT, Brand.body(this));
        sourcesView.setPadding(dp(18), dp(14), dp(18), dp(14));
        sourcesView.setBackground(Brand.glass(this, 16));
        sourcesView.setVisibility(View.GONE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, -2, 1);
        sl.leftMargin = dp(30);
        middle.addView(sourcesView, sl);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, 0, 1);
        ml.topMargin = dp(24);
        col.addView(middle, ml);
        caption = Brand.text(this, "", 28, 0xFFFFFFFF, Brand.body(this));
        caption.setGravity(Gravity.CENTER);
        caption.setMinLines(2);
        caption.setMaxLines(3);
        // A long sentence shrinks to fit three lines rather than being cut off.
        caption.setAutoSizeTextTypeUniformWithConfiguration(18, 28, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        caption.setPadding(dp(30), dp(14), dp(30), dp(14));
        caption.setBackground(Brand.pill(this, 0xB3070A14, 0));
        col.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        state = Brand.text(this, "Playing", 16, Brand.CYAN, Brand.bodyBold(this));
        bottom.addView(state, new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(Brand.text(this, "◀ ▶ chapters   OK pause   ▼ sources   ▲ captions   Back stop", 14, Brand.FAINT, Brand.body(this)));
        clock = Brand.text(this, "", 16, Brand.DIM, Brand.mono(this));
        clock.setPadding(dp(24), 0, 0, 0);
        bottom.addView(clock);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(14);
        col.addView(bottom, bl);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    /** What the subtitle says about the voice: only a real switch to the TV's voice is "parts in the TV's voice". */
    static String voiceNote(List<String> switches) {
        String note = "";
        for (String s : switches) {
            if (s.contains("the TV's voice finished it")) return " · parts in the TV's voice";
            if (s.contains("default ElevenLabs voice")) note = " · Friday's default voice";
        }
        return note;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            MediaPlayer p = player;
            if (p == null) return;
            long now;
            try { now = p.getCurrentPosition(); } catch (IllegalStateException e) { return; }
            Captions.Cue c = Captions.at(cues, now);
            caption.setText(captionsOn && c != null ? c.text : "");
            caption.setVisibility(captionsOn ? View.VISIBLE : View.INVISIBLE);
            clock.setText(Studio.minutes(now) + " / " + Studio.minutes(total));
            int ch = chapterAt(now);
            for (int i = 0; i < chapterViews.size(); i++) {
                TextView t = chapterViews.get(i);
                t.setTextColor(i == ch ? 0xFFFFFFFF : Brand.DIM);
                t.setBackground(i == ch ? Brand.pill(StudioPlayerActivity.this, 0x2200D4FF, Brand.CYAN) : null);
            }
            if (sourcesView.getVisibility() == View.VISIBLE) sourcesView.setText(sourcesFor(ch));
            main.postDelayed(this, 200);
        }
    };

    private int chapterAt(long ms) {
        int ch = 0;
        for (int i = 0; i < starts.size(); i++) if (ms >= starts.get(i)) ch = i;
        return ch;
    }

    private String sourcesFor(int ch) {
        StringBuilder b = new StringBuilder("Sources for this chapter\n");
        List<Integer> src = ch < chapterSources.size() ? chapterSources.get(ch) : new ArrayList<>();
        for (int k : src) {
            JSONObject s = sources.optJSONObject(k);
            if (s == null) continue;
            b.append("\n• ").append(s.optString("title")).append("\n   ").append(s.optString("outlet"));
        }
        return b.toString();
    }

    private void seekChapter(int delta) {
        MediaPlayer p = player;
        if (p == null || starts.isEmpty()) return;
        int ch = chapterAt(p.getCurrentPosition());
        // Left within the first few seconds of a chapter goes to the one before; otherwise to this chapter's start.
        if (delta < 0 && p.getCurrentPosition() - starts.get(ch) > 3000) delta = 0;
        int to = Math.max(0, Math.min(starts.size() - 1, ch + delta));
        p.seekTo(starts.get(to).intValue());
    }

    @Override protected void onPause() {
        super.onPause();
        input.reset();
        MediaPlayer p = player;
        if (p != null) {
            try { prefs.edit().putLong(key(), p.getCurrentPosition()).apply(); p.pause(); } catch (IllegalStateException ignored) { }
            state.setText("Paused");
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
    }

    private int dp(float v) { return Brand.dp(this, v); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) { return input.feed(e); }

    private boolean route(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (e.getRepeatCount() > 0 && k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        MediaPlayer p = player;
        switch (k) {
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_MEDIA_STOP:
                finish(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: seekChapter(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_NEXT: seekChapter(1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                sourcesView.setVisibility(sourcesView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                if (p != null) sourcesView.setText(sourcesFor(chapterAt(p.getCurrentPosition())));
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: captionsOn = !captionsOn; return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (p == null) return true;
                try {
                    if (p.isPlaying()) { p.pause(); state.setText("Paused"); }
                    else { p.start(); state.setText("Playing"); main.removeCallbacks(tick); main.post(tick); }
                } catch (IllegalStateException ignored) { }
                return true;
            default: return true;
        }
    }
}
