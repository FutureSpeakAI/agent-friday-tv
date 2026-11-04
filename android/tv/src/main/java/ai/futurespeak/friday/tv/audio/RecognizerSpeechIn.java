package ai.futurespeak.friday.tv.audio;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Friday's ears (spec D4, with an online fallback). Android's SpeechRecognizer, always
 * asked for offline ({@code EXTRA_PREFER_OFFLINE}): Android's on-device recognizer when the TV
 * has one, otherwise the TV's recognition service, which uses its offline English pack when
 * installed and Google online otherwise (route CLOUD, labelled on screen). The app never records
 * audio itself.
 */
public final class RecognizerSpeechIn implements SpeechIn {
    private static final String TAG = "FridayTV";

    private final Context ctx;
    private final Events events;
    private final OfflineSpeech.Route route;
    private SpeechRecognizer rec;
    private boolean micOpen = false, active = false;
    private boolean hold = false;
    private int pauseMs = 1100;
    private long startedAt, finishedAt, firstPartialAt;
    private static volatile long lastOpen = -OfflineSpeech.MIN_REOPEN_MS;

    private RecognizerSpeechIn(Context c, Events e, OfflineSpeech.Route route) {
        this.ctx = c.getApplicationContext();
        this.events = e;
        this.route = route;
    }

    /** Every route but NONE produces a recognizer; all of them ask for offline. */
    public static RecognizerSpeechIn create(Context c, Events e, OfflineSpeech.Route route) {
        if (route == OfflineSpeech.Route.NONE) throw new IllegalArgumentException("no recognizer");
        return new RecognizerSpeechIn(c, e, route);
    }

    public void setHoldMode(boolean b) { hold = b; }
    public void setPauseMs(int ms) { pauseMs = ms; }

    public static boolean onDeviceAvailable(Context c) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try { return SpeechRecognizer.isOnDeviceRecognitionAvailable(c); } catch (Throwable t) { return false; }
    }

    public static boolean serviceAvailable(Context c) {
        try { return SpeechRecognizer.isRecognitionAvailable(c); } catch (Throwable t) { return false; }
    }

    /** The installed version of the TV's recognition service (what an offline measurement is tied to), or null. */
    public static String serviceVersion(Context c) {
        try {
            String s = android.provider.Settings.Secure.getString(c.getContentResolver(), "voice_recognition_service");
            String pkg = s == null || !s.contains("/") ? null : s.substring(0, s.indexOf('/'));
            if (pkg == null) {
                List<android.content.pm.ResolveInfo> r = c.getPackageManager().queryIntentServices(new Intent("android.speech.RecognitionService"), 0);
                if (r.isEmpty()) return null;
                pkg = r.get(0).serviceInfo.packageName;
            }
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, 0);
            return pkg + "@" + pi.versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- What the TV's service can do offline (Android 13+) -------------------------------------

    /** The service's answer: installed (offline-ready) languages, ones downloading, ones only online; error when it couldn't say. */
    public static final class Support {
        public final List<String> installed, pending, online;
        public final String error;
        Support(List<String> installed, List<String> pending, List<String> online, String error) {
            this.installed = installed; this.pending = pending; this.online = online; this.error = error;
        }
    }

    public interface SupportCallback { void done(Support s); }

    static Intent intent(boolean preferOffline) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, OfflineSpeech.LANGUAGE);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline);
        return i;
    }

    /** Asks the TV's recognition service which languages it has offline. Must be called on the main thread; answers on it. */
    public static void checkSupport(Context c, SupportCallback cb) {
        if (Build.VERSION.SDK_INT < 33) { cb.done(new Support(null, null, null, "Android " + Build.VERSION.SDK_INT + " can't report offline languages")); return; }
        final SpeechRecognizer r;
        try { r = SpeechRecognizer.createSpeechRecognizer(c.getApplicationContext()); }
        catch (Throwable t) { cb.done(new Support(null, null, null, t.getClass().getSimpleName())); return; }
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final boolean[] answered = {false};
        Runnable timeout = () -> { if (!answered[0]) { answered[0] = true; try { r.destroy(); } catch (Throwable ignored) { } cb.done(new Support(null, null, null, "no answer")); } };
        h.postDelayed(timeout, 5000);
        try {
            r.checkRecognitionSupport(intent(true), c.getMainExecutor(), new android.speech.RecognitionSupportCallback() {
                @Override public void onSupportResult(android.speech.RecognitionSupport s) {
                    if (answered[0]) return;
                    answered[0] = true; h.removeCallbacks(timeout);
                    try { r.destroy(); } catch (Throwable ignored) { }
                    cb.done(new Support(new ArrayList<>(s.getInstalledOnDeviceLanguages()), new ArrayList<>(s.getPendingOnDeviceLanguages()),
                            new ArrayList<>(s.getOnlineLanguages()), null));
                }
                @Override public void onError(int error) {
                    if (answered[0]) return;
                    answered[0] = true; h.removeCallbacks(timeout);
                    try { r.destroy(); } catch (Throwable ignored) { }
                    cb.done(new Support(null, null, null, "error " + error));
                }
            });
        } catch (Throwable t) {
            answered[0] = true; h.removeCallbacks(timeout);
            try { r.destroy(); } catch (Throwable ignored) { }
            cb.done(new Support(null, null, null, t.getClass().getSimpleName()));
        }
    }

    /** Asks the TV to download its offline English pack (Android 13+). Main thread. */
    public static boolean requestPack(Context c) {
        if (Build.VERSION.SDK_INT < 33) return false;
        SpeechRecognizer r = null;
        try {
            r = SpeechRecognizer.createSpeechRecognizer(c.getApplicationContext());
            r.triggerModelDownload(intent(true));
            Log.i(TAG, "stt: asked the TV for its offline " + OfflineSpeech.LANGUAGE + " pack");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "stt: pack request failed (" + t.getClass().getSimpleName() + ")");
            return false;
        } finally {
            SpeechRecognizer rr = r;
            if (rr != null) new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> { try { rr.destroy(); } catch (Throwable ignored) { } }, 3000);
        }
    }

    // ---- Listening -------------------------------------------------------------------------------

    @Override public String label() {
        return route == OfflineSpeech.Route.ON_DEVICE ? "On this TV (Android on-device)"
                : route == OfflineSpeech.Route.SERVICE_OFFLINE ? "On this TV (offline English)" : "Google speech (online unless the offline pack is installed)";
    }

    private SpeechRecognizer recognizer() {
        if (rec == null) {
            rec = route == OfflineSpeech.Route.ON_DEVICE && Build.VERSION.SDK_INT >= 31
                    ? SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                    : SpeechRecognizer.createSpeechRecognizer(ctx);
            rec.setRecognitionListener(listener);
        }
        return rec;
    }

    private int retries = 0;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override public void start() {
        long now = SystemClock.uptimeMillis();
        if (!OfflineSpeech.mayOpen(now, lastOpen, active)) {
            Log.i(TAG, "mic: open refused (too soon or still open)");
            events.onNothing("One moment… press OK on Talk again.");
            return;
        }
        retries = 0;
        startFresh();
    }

    private android.os.ParcelFileDescriptor testAudio;

    /**
     * Shell test hook only (Android 13+): recognize 16 kHz mono 16-bit PCM from {@code pcm} instead
     * of the microphone, through exactly the same offline recognizer and listener. Used to time
     * recognition on the TV without anyone speaking.
     */
    public void startWithAudio(android.os.ParcelFileDescriptor pcm) {
        testAudio = pcm;
        retries = 1;   // no mic-busy retry: the stream can only be read once
        startFresh();
    }

    /** A new recognizer for every question: a stale one can stay busy after the app was paused. */
    private void startFresh() {
        if (rec != null) { try { rec.destroy(); } catch (Throwable ignored) {} rec = null; }
        Intent i = intent(true);
        if (testAudio != null && Build.VERSION.SDK_INT >= 33) {
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, testAudio);
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT);
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000);
            testAudio = null;
        }
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        long silence = OfflineSpeech.silenceMs(hold, pauseMs);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, Math.max(500, silence - 300));
        if (hold) i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60000L);
        active = true;
        startedAt = SystemClock.uptimeMillis();
        lastOpen = startedAt;
        finishedAt = 0; firstPartialAt = 0;
        mic(true);   // shown from the moment we ask for the mic, not only once it is confirmed
        try {
            recognizer().startListening(i);
        } catch (Throwable t) {
            active = false;
            mic(false);
            events.onNothing("Speech recognition isn't available on this TV.");
        }
    }

    @Override public void finish() {
        if (!active) return;
        finishedAt = SystemClock.uptimeMillis();
        try { if (rec != null) rec.stopListening(); } catch (Throwable ignored) {}
        mic(false);
    }

    @Override public void cancel() {
        active = false;
        main.removeCallbacksAndMessages(null);
        try { if (rec != null) rec.cancel(); } catch (Throwable ignored) {}
        mic(false);
    }

    @Override public void release() {
        cancel();
        if (rec != null) { try { rec.destroy(); } catch (Throwable ignored) {} rec = null; }
    }

    private void mic(boolean open) {
        if (micOpen == open) return;
        micOpen = open;
        events.onMic(open);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) {
            if (active) { mic(true); Log.i(TAG, "stt timing: ready " + (SystemClock.uptimeMillis() - startedAt) + " ms after Talk"); }
        }
        @Override public void onBeginningOfSpeech() { if (active) mic(true); }
        @Override public void onRmsChanged(float db) { events.onLevel(Math.max(0, Math.min(1, (db + 2) / 12.0))); }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { if (finishedAt == 0) finishedAt = SystemClock.uptimeMillis(); mic(false); }
        @Override public void onError(int error) {
            Log.i(TAG, "speech error " + error + " (" + route + ") retry " + retries);
            if (!active) { mic(false); return; }
            // The microphone was busy (another app, or a recognizer that had not let go yet):
            // try once more with a fresh recognizer before saying anything.
            if ((error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == 11 /* SERVER_DISCONNECTED */) && retries < 1) {
                retries++;
                main.postDelayed(() -> { if (active) startFresh(); }, 600);
                return;
            }
            mic(false);
            active = false;
            switch (error) {
                case 12: case 13:   // ERROR_LANGUAGE_NOT_SUPPORTED / _UNAVAILABLE: the offline pack is missing
                    events.onNothing("The TV's offline English speech pack isn't ready, so I didn't send your voice anywhere. A grown-up can download it under Settings, Listening.");
                    break;
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    events.onNothing("I didn't catch that."); break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    events.onNothing("I need permission to use the microphone."); break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    // Held to offline, a network error means the service wanted the internet: Friday doesn't allow that.
                    events.onNothing("The TV's speech recognizer wanted the internet, and Friday keeps your voice on the TV. Try again in a moment."); break;
                case SpeechRecognizer.ERROR_AUDIO:
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                case SpeechRecognizer.ERROR_CLIENT:
                    events.onNothing("The TV's microphone is busy right now. Press OK on Talk to try again."); break;
                default:
                    events.onNothing("Speech recognition stopped (code " + error + ").");
            }
        }
        @Override public void onResults(Bundle b) {
            mic(false);
            if (!active) return;
            active = false;
            long now = SystemClock.uptimeMillis();
            Log.i(TAG, "stt timing: final " + (finishedAt > 0 ? (now - finishedAt) + " ms after you stopped" : "?")
                    + ", first words " + (firstPartialAt > 0 ? (firstPartialAt - startedAt) + " ms after Talk" : "none")
                    + ", whole question " + (now - startedAt) + " ms");
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String t = r == null || r.isEmpty() ? "" : r.get(0).trim();
            if (t.isEmpty()) events.onNothing("I didn't catch that.");
            else events.onFinal(t);
        }
        @Override public void onPartialResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty() && active) {
                if (firstPartialAt == 0 && !r.get(0).trim().isEmpty()) firstPartialAt = SystemClock.uptimeMillis();
                events.onPartial(r.get(0));
            }
        }
        @Override public void onEvent(int type, Bundle b) { }
    };

    /** A stand-in when the TV can't listen offline: pressing Talk explains why instead of opening the mic. */
    public static SpeechIn unavailable(OfflineSpeech.Route route, Events events) {
        return new SpeechIn() {
            @Override public String label() { return "Not listening (" + route + ")"; }
            @Override public void start() { events.onNothing(OfflineSpeech.explain(route)); }
            @Override public void finish() { }
            @Override public void cancel() { }
            @Override public void release() { }
        };
    }
}
