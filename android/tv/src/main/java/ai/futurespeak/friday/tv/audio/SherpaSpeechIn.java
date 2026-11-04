package ai.futurespeak.friday.tv.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.io.InputStream;

/**
 * Friday's own speech recognition, on the TV (design decision, 2026-11-02): sherpa-onnx's
 * streaming Zipformer, run in this process from files in the app's private storage. The voice
 * goes from the microphone to the model and nowhere else: this class has no network code at all
 * (a guard test checks), and on this route Friday never starts Google's recognizer. The mic
 * indicator is shown before recording starts and hidden when it stops.
 */
public final class SherpaSpeechIn implements SpeechIn {
    private static final String TAG = "FridayTV";
    public static final int RATE = 16000;

    private static OnlineRecognizer shared;
    private static String loadError;

    /** Loads the model once per process (1–3 s on the TCL). Call off the main thread. */
    public static synchronized OnlineRecognizer recognizer(File filesDir) {
        if (shared != null) return shared;
        File d = OnTvModel.dir(filesDir);
        long t0 = SystemClock.uptimeMillis();
        try {
            OnlineRecognizerConfig cfg = OnlineRecognizerConfig.builder()
                    .setFeatureConfig(FeatureConfig.builder().setSampleRate(RATE).setFeatureDim(80).build())
                    .setOnlineModelConfig(OnlineModelConfig.builder()
                            .setTransducer(OnlineTransducerModelConfig.builder()
                                    .setEncoder(new File(d, "encoder.int8.onnx").getAbsolutePath())
                                    .setDecoder(new File(d, "decoder.int8.onnx").getAbsolutePath())
                                    .setJoiner(new File(d, "joiner.int8.onnx").getAbsolutePath()).build())
                            .setTokens(new File(d, "tokens.txt").getAbsolutePath())
                            .setNumThreads(2).setProvider("cpu").setModelType("zipformer").build())
                    .setEndpointConfig(EndpointConfig.builder()
                            .setRule1(EndpointRule.builder().setMustContainNonSilence(false).setMinTrailingSilence(6.0f).setMinUtteranceLength(0).build())
                            .setRule2(EndpointRule.builder().setMustContainNonSilence(true).setMinTrailingSilence(1.0f).setMinUtteranceLength(0).build())
                            .setRule3(EndpointRule.builder().setMustContainNonSilence(false).setMinTrailingSilence(0.0f).setMinUtteranceLength(25.0f).build())
                            .build())
                    .setEnableEndpoint(true).setDecodingMethod("greedy_search").build();
            shared = new OnlineRecognizer(cfg);
            loadError = null;
            Log.i(TAG, "stt: on-TV model loaded in " + (SystemClock.uptimeMillis() - t0) + " ms");
        } catch (Throwable e) {
            loadError = e.getClass().getSimpleName();
            Log.w(TAG, "stt: on-TV model failed to load (" + loadError + ")");
        }
        return shared;
    }

    public static boolean loaded() { return shared != null; }
    public static String loadError() { return loadError; }

    private final Context ctx;
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean active, finishing;
    private volatile AudioRecord rec;
    private boolean hold;
    private long finishedAt;

    public SherpaSpeechIn(Context c, Events e) { ctx = c.getApplicationContext(); events = e; }

    public void setHoldMode(boolean b) { hold = b; }

    @Override public String label() { return "On this TV (Friday's own, " + OnTvModel.NAME + ")"; }

    @Override public void start() {
        if (active) return;
        active = true; finishing = false; finishedAt = 0;
        events.onMic(true);   // the indicator is up before the microphone opens
        Thread t = new Thread(() -> runMic(), "ontv-stt");
        t.setDaemon(true);
        t.start();
    }

    private void runMic() {
        OnlineRecognizer r = recognizer(ctx.getFilesDir());
        if (r == null) { done(null, "On-TV speech isn't ready on this TV."); return; }
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord a;
        try {
            a = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 6400) * 2);
        } catch (SecurityException e) { done(null, "I need permission to use the microphone."); return; }
        if (a.getState() != AudioRecord.STATE_INITIALIZED) { a.release(); done(null, "The TV's microphone is busy right now. Press OK on Talk to try again."); return; }
        if (!active) { a.release(); done(null, null); return; }
        rec = a;
        a.startRecording();
        decodeLoop(r, (buf) -> a.read(buf, 0, buf.length), false);
    }

    /** Test hook (shell, measurement): raw 16 kHz mono 16-bit PCM from a pipe instead of the microphone. */
    public void startWithAudio(ParcelFileDescriptor pcm) {
        if (active) return;
        active = true; finishing = false; finishedAt = 0;
        Thread t = new Thread(() -> {
            OnlineRecognizer r = recognizer(ctx.getFilesDir());
            if (r == null) { done(null, "On-TV speech isn't ready on this TV."); return; }
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pcm)) {
                decodeLoop(r, (buf) -> {
                    byte[] b = new byte[buf.length * 2];
                    int n = 0, k;
                    while (n < b.length && (k = in.read(b, n, b.length - n)) > 0) n += k;
                    if (n <= 0) return -1;
                    for (int i = 0; i < n / 2; i++) buf[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
                    return n / 2;
                }, true);
            } catch (Exception e) { done(null, "Speech test failed."); }
        }, "ontv-stt-test");
        t.setDaemon(true);
        t.start();
    }

    interface Source { int read(short[] buf) throws Exception; }

    private void decodeLoop(OnlineRecognizer r, Source src, boolean fromPipe) {
        OnlineStream s = r.createStream();
        short[] buf = new short[1600];   // 100 ms
        float[] f = new float[buf.length];
        String last = "";
        boolean heard = false;
        // A little silence first: the streaming model drops the first word when speech starts at sample 0 (measured).
        s.acceptWaveform(new float[RATE * 4 / 10], RATE);
        try {
            while (active) {
                int n;
                try { n = src.read(buf); } catch (Exception e) { n = -1; }
                if (n < 0 || finishing) {
                    if (finishedAt == 0) finishedAt = SystemClock.uptimeMillis();
                    break;
                }
                if (n == 0) continue;
                double sum = 0;
                float[] chunk = n == f.length ? f : new float[n];
                for (int i = 0; i < n; i++) { chunk[i] = buf[i] / 32768f; sum += chunk[i] * chunk[i]; }
                double rms = Math.sqrt(sum / n);
                main.post(() -> events.onLevel(Math.min(1, rms * 8)));
                s.acceptWaveform(chunk, RATE);
                while (r.isReady(s)) r.decode(s);
                String text = r.getResult(s).getText().trim();
                if (!text.isEmpty()) heard = true;
                if (!text.equals(last)) { last = text; final String p = text; main.post(() -> events.onPartial(p)); }
                if (!hold && !fromPipe && r.isEndpoint(s)) { finishedAt = SystemClock.uptimeMillis(); break; }
            }
            closeMic();
            // The tail: whatever was spoken just before the end.
            float[] pad = new float[RATE * 3 / 10];
            s.acceptWaveform(pad, RATE);
            s.inputFinished();
            while (r.isReady(s)) r.decode(s);
            String text = r.getResult(s).getText().trim();
            long ms = finishedAt == 0 ? -1 : SystemClock.uptimeMillis() - finishedAt;
            Log.i(TAG, "stt timing: ontv end->final=" + ms + "ms words=" + OnTvModel.words(text).length);
            if (fromPipe) Log.i(TAG, "stt test result (shell audio only, never the mic): " + text);
            if (!active) { done(null, null); return; }
            done(text.isEmpty() ? null : tidy(text), text.isEmpty() ? "I didn't catch that." : null);
        } finally {
            s.release();
            closeMic();
        }
    }

    /** The model writes upper case without punctuation: sentence case reads better on screen. */
    static String tidy(String t) {
        String s = t.toLowerCase(java.util.Locale.ROOT).trim();
        s = s.replaceAll("\\bi\\b", "I").replaceAll("\\bi'(m|ll|ve|d)\\b", "I'$1");
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void closeMic() {
        AudioRecord a = rec;
        rec = null;
        if (a != null) { try { a.stop(); } catch (Exception ignored) { } a.release(); }
        main.post(() -> events.onMic(false));
    }

    private void done(String text, String nothing) {
        active = false;
        closeMic();
        main.post(() -> {
            if (text != null) events.onFinal(text);
            else if (nothing != null) events.onNothing(nothing);
        });
    }

    @Override public void finish() { finishing = true; }

    @Override public void cancel() { active = false; closeMic(); }

    @Override public void release() { cancel(); }
}
