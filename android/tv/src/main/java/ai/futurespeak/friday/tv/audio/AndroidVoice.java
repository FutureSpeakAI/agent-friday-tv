package ai.futurespeak.friday.tv.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The TV's own text-to-speech engine: the fallback when there is no ElevenLabs key or
 * ElevenLabs fails. It gives no audio samples, so the avatar's level pulses on each word
 * (onRangeStart) and decays between words.
 */
public final class AndroidVoice implements Voice {
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger seq = new AtomicInteger();
    private TextToSpeech tts;
    private volatile boolean ready = false, busy = false, endQueued = false;
    private volatile int pending = 0;
    private volatile long lastWord = 0;
    private final java.util.List<String> early = new java.util.ArrayList<>();
    private final java.util.List<Integer> earlyIdx = new java.util.ArrayList<>();

    /** Utterance ids are "s<index>.<seq>". */
    static int parseIndex(String id) {
        try { return Integer.parseInt(id.substring(1, id.indexOf('.'))); } catch (Exception e) { return -1; }
    }

    public AndroidVoice(Context c, Events events) {
        this.events = events;
        tts = new TextToSpeech(c.getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) { main.post(() -> events.onVoiceError("no text-to-speech engine")); return; }
            tts.setLanguage(Locale.US);
            tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    lastWord = System.currentTimeMillis();
                    int i = parseIndex(id);
                    if (i >= 0) main.post(() -> events.onSentence(i)); main.post(() -> { if (busy) events.onSpeaking(); }); }
                @Override public void onDone(String id) { done(); }
                @Override public void onError(String id) { done(); }
                @Override public void onStop(String id, boolean interrupted) { }
                @Override public void onRangeStart(String id, int s, int e, int frame) { lastWord = System.currentTimeMillis(); }
            });
            ready = true;
            boolean ended = endQueued;
            java.util.List<String> queued;
            java.util.List<Integer> qi;
            synchronized (early) { queued = new java.util.ArrayList<>(early); qi = new java.util.ArrayList<>(earlyIdx); early.clear(); earlyIdx.clear(); }
            for (int k = 0; k < queued.size(); k++) speak(queued.get(k), qi.get(k));
            if (ended) endOfReply();
        });
    }

    @Override public String label() { return "TV voice"; }
    private volatile float volume = 0.85f;
    public void setVolume(float v) { volume = v; }
    public void setSpeed(float v) { if (tts != null) tts.setSpeechRate(v); }

    @Override public void speak(String sentence, int index) {
        if (sentence == null || sentence.trim().isEmpty()) return;
        busy = true;
        endQueued = false;
        if (!ready) { synchronized (early) { early.add(sentence); earlyIdx.add(index); } return; }
        pending++;
        Bundle b = new Bundle();
        b.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
        tts.speak(sentence, TextToSpeech.QUEUE_ADD, b, "s" + index + "." + seq.incrementAndGet());
    }

    private void done() {
        pending = Math.max(0, pending - 1);
        if (pending == 0 && endQueued) quiet();
    }

    private void quiet() {
        busy = false;
        endQueued = false;
        main.post(events::onQuiet);
    }

    @Override public void endOfReply() {
        endQueued = true;
        if (ready && pending == 0 && early.isEmpty()) quiet();
    }

    @Override public void stop() {
        synchronized (early) { early.clear(); earlyIdx.clear(); }
        pending = 0;
        endQueued = false;
        if (tts != null) tts.stop();
        if (busy) { busy = false; main.post(events::onQuiet); }
    }

    @Override public boolean busy() { return busy; }

    @Override public double level() {
        if (!busy) return 0;
        long since = System.currentTimeMillis() - lastWord;
        return Math.max(0, 0.75 - since / 400.0);
    }

    @Override public void release() {
        stop();
        if (tts != null) tts.shutdown();
        tts = null;
    }
}
