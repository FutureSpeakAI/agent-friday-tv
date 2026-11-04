package ai.futurespeak.friday.tv.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Friday's ElevenLabs voice, streamed as raw PCM (22.05 kHz, 16-bit mono) into an AudioTrack.
 * A fetch thread downloads sentence N+1 while sentence N plays, so there is no gap between
 * sentences. The speech level is computed per 50 ms chunk and matched to the playback head,
 * so the avatar moves with what is audible, not with what was downloaded.
 */
public final class ElevenLabsVoice implements Voice {
    private static final String TAG = "FridayTV";
    private static final int RATE = 22050;
    /** 50 ms of 16-bit mono. Must be even: an odd size split samples across chunks, and the
     *  volume step then rebuilt every other chunk from mismatched bytes (heard as static). */
    static final int CHUNK = RATE / 20 * 2;
    /** When set (and a file named "dump-voice" exists in it), the exact PCM written to the
     *  speaker is saved there as voice-dump.pcm for inspection over adb. */
    public static volatile java.io.File debugDir;
    private java.io.FileOutputStream dump;

    public interface Failure { void failed(String why, List<String> unspoken, List<Integer> indexes, boolean replyEnded); }

    private static final class Item { final int gen, index; final String text; final boolean end; Item(int g, String t, boolean e, int i) { gen = g; text = t; end = e; index = i; } }
    private static final class Chunk { final int gen, index; final byte[] pcm; final boolean end; Chunk(int g, byte[] p, boolean e, int i) { gen = g; pcm = p; end = e; index = i; } }

    private final Supplier<String> key;
    private final String voiceId, modelId;
    private final Events events;
    private final Failure failure;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger gen = new AtomicInteger();
    private final LinkedBlockingQueue<Item> sentences = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<Chunk> audio = new LinkedBlockingQueue<>(400);
    private final Object trackLock = new Object();
    private AudioTrack track;
    private volatile HttpURLConnection current;
    private volatile boolean busy = false, released = false;
    private long framesWritten = 0;
    private final long[] lvFrame = new long[256];
    private final float[] lvValue = new float[256];
    private int lvCount = 0;
    private final Thread fetcher, player;
    /** Voice volume 0..1 (separate from the TV's volume) and speaking speed 0.7..1.2. */
    private volatile float gain = 0.85f, speed = 1.0f;
    public void setVolume(float v) { gain = Math.max(0.05f, Math.min(1f, v)); }
    public void setSpeed(float v) { speed = Math.max(0.7f, Math.min(1.2f, v)); }

    public ElevenLabsVoice(Supplier<String> key, String voiceId, String modelId, Events events, Failure failure) {
        this.key = key;
        this.voiceId = voiceId;
        this.modelId = modelId;
        this.events = events;
        this.failure = failure;
        fetcher = new Thread(() -> { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); fetchLoop(); }, "voice-fetch");
        player = new Thread(this::playLoop, "voice-play");
        fetcher.setDaemon(true);
        player.setDaemon(true);
        fetcher.start();
        player.start();
    }

    @Override public String label() { return "ElevenLabs"; }

    @Override public void speak(String sentence, int index) {
        if (sentence == null || sentence.trim().isEmpty()) return;
        busy = true;
        sentences.add(new Item(gen.get(), sentence, false, index));
    }

    @Override public void endOfReply() {
        sentences.add(new Item(gen.get(), null, true, -1));
    }

    @Override public boolean busy() { return busy; }

    @Override public void stop() {
        gen.incrementAndGet();
        sentences.clear();
        audio.clear();
        HttpURLConnection c = current;
        if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        synchronized (trackLock) {
            if (track != null) {
                try { track.pause(); track.flush(); } catch (Throwable ignored) {}
            }
            framesWritten = 0;
            lvCount = 0;
            resetHead = true;
        }
        if (busy) { busy = false; main.post(events::onQuiet); }
    }

    private boolean resetHead = false;

    @Override public double level() {
        synchronized (trackLock) {
            if (track == null || lvCount == 0 || !busy) return 0;
            long head;
            try { head = track.getPlaybackHeadPosition() & 0xffffffffL; } catch (Throwable t) { return 0; }
            float v = 0;
            int n = Math.min(lvCount, lvFrame.length);
            for (int i = 0; i < n; i++) {
                int idx = (lvCount - 1 - i) % lvFrame.length;
                if (lvFrame[idx] <= head) { v = lvValue[idx]; break; }
            }
            return v;
        }
    }

    @Override public void release() {
        released = true;
        stop();
        fetcher.interrupt();
        player.interrupt();
        synchronized (trackLock) {
            if (track != null) { try { track.release(); } catch (Throwable ignored) {} track = null; }
        }
    }

    // ---- fetch: sentence -> PCM chunks ------------------------------------------------
    private void fetchLoop() {
        while (!released) {
            Item it;
            try { it = sentences.take(); } catch (InterruptedException e) { return; }
            if (it.gen != gen.get()) continue;
            if (it.end) { audio.offer(new Chunk(it.gen, null, true, -1)); continue; }
            String k = key.get();
            if (k == null || k.isEmpty()) { fail(it, "no ElevenLabs key"); continue; }
            HttpURLConnection c = null;
            try {
                String url = "https://api.elevenlabs.io/v1/text-to-speech/" + URLEncoder.encode(voiceId, "UTF-8")
                        + "/stream?output_format=pcm_22050";
                c = (HttpURLConnection) new URL(url).openConnection();
                current = c;
                c.setRequestMethod("POST");
                c.setConnectTimeout(8000);
                c.setReadTimeout(20000);
                c.setDoOutput(true);
                c.setRequestProperty("xi-api-key", k);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Accept", "audio/pcm");
                JSONObject req = new JSONObject().put("text", it.text).put("model_id", modelId);
                if (Math.abs(speed - 1f) > 0.01f) req.put("voice_settings", new JSONObject().put("speed", speed));
                byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) { os.write(body); }
                int code = c.getResponseCode();
                if (code != 200) { fail(it, "ElevenLabs answered " + code); continue; }
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[CHUNK];
                    int fill = 0, n;
                    while ((n = in.read(buf, fill, CHUNK - fill)) > 0) {
                        if (it.gen != gen.get()) break;
                        fill += n;
                        if (fill == CHUNK) { audio.put(new Chunk(it.gen, buf, false, it.index)); buf = new byte[CHUNK]; fill = 0; }
                    }
                    if (fill > 1 && it.gen == gen.get()) {
                        byte[] last = new byte[fill & ~1];
                        System.arraycopy(buf, 0, last, 0, last.length);
                        audio.put(new Chunk(it.gen, last, false, it.index));
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (it.gen == gen.get()) fail(it, "ElevenLabs unreachable (" + e.getClass().getSimpleName() + ")");
            } finally {
                current = null;
                if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Hands this sentence and everything still queued to the fallback voice. */
    private void fail(Item it, String why) {
        Log.w(TAG, "voice: " + why);
        List<String> rest = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        rest.add(it.text);
        idx.add(it.index);
        boolean ended = false;
        List<Item> drained = new ArrayList<>();
        sentences.drainTo(drained);
        for (Item x : drained) {
            if (x.gen != it.gen) continue;
            if (x.end) ended = true; else { rest.add(x.text); idx.add(x.index); }
        }
        int g = it.gen;
        boolean endedF = ended;
        main.post(() -> { if (g == gen.get()) failure.failed(why, rest, idx, endedF); });
        // let what was already fetched play out, then go quiet
        audio.offer(new Chunk(g, null, true, -1));
    }

    // ---- play: PCM chunks -> speaker ---------------------------------------------------
    private void playLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        int startedGen = -1, lastIndex = -1;
        long buffered = 0;
        final long PREBUFFER = RATE / 4;   // 250 ms written before play(): no underrun crackle at the start
        while (!released) {
            Chunk ch;
            try { ch = audio.take(); } catch (InterruptedException e) { return; }
            if (ch.gen != gen.get()) continue;
            if (ch.end) {
                synchronized (trackLock) { if (track != null && track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) try { track.play(); } catch (Throwable ignored) {} }
                waitDrained(ch.gen);
                synchronized (trackLock) {
                    if (track != null) Log.i(TAG, "voice: reply played, " + framesWritten + " frames, underruns " + track.getUnderrunCount());
                }
                if (dump != null) { try { dump.close(); } catch (Exception ignored) {} dump = null; }
                if (ch.gen == gen.get() && sentences.isEmpty() && audio.isEmpty()) {
                    busy = false;
                    startedGen = -1;
                    lastIndex = -1;
                    main.post(events::onQuiet);
                }
                continue;
            }
            AudioTrack t = ensureTrack();
            if (t == null) continue;
            if (startedGen != ch.gen) {
                startedGen = ch.gen;
                buffered = 0;
                main.post(events::onSpeaking);
                java.io.File d = debugDir;
                if (dump == null && d != null && new java.io.File(d, "dump-voice").exists()) {
                    try { dump = new java.io.FileOutputStream(new java.io.File(d, "voice-dump.pcm")); } catch (Exception ignored) {}
                }
            }
            if (ch.index != lastIndex) {          // a new sentence is starting to play: captions follow it
                lastIndex = ch.index;
                int i = ch.index, g = ch.gen;
                main.post(() -> { if (g == gen.get()) events.onSentence(i); });
            }
            applyGain(ch.pcm, gain);
            if (dump != null) { try { dump.write(ch.pcm); } catch (Exception e) { dump = null; } }
            float rms = (float) Level.toAvatar(Level.rms(ch.pcm, 0, ch.pcm.length));
            synchronized (trackLock) {
                int idx = lvCount % lvFrame.length;
                lvFrame[idx] = framesWritten;
                lvValue[idx] = rms;
                lvCount++;
                framesWritten += ch.pcm.length / 2;
            }
            int off = 0;
            while (off < ch.pcm.length && ch.gen == gen.get()) {
                int w = t.write(ch.pcm, off, ch.pcm.length - off);
                if (w <= 0) break;
                off += w;
            }
            buffered += ch.pcm.length / 2;
            if (buffered >= PREBUFFER && t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                try { t.play(); } catch (Throwable ignored) {}
            }
        }
    }

    private void waitDrained(int g) {
        for (int i = 0; i < 600 && g == gen.get(); i++) {   // at most ~12 s
            synchronized (trackLock) {
                if (track == null) return;
                long head = track.getPlaybackHeadPosition() & 0xffffffffL;
                if (head >= framesWritten) return;
            }
            try { Thread.sleep(20); } catch (InterruptedException e) { return; }
        }
    }

    private AudioTrack ensureTrack() {
        synchronized (trackLock) {
            if (track != null && resetHead) {
                try { track.release(); } catch (Throwable ignored) {}
                track = null;
            }
            resetHead = false;
            if (track == null) {
                int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(Math.max(min * 4, RATE * 2 / 2))   // about half a second
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                framesWritten = 0;
                lvCount = 0;
            }
            return track;
        }
    }

    /**
     * Scales 16-bit PCM by the voice volume with a soft knee above 0.8 of full scale.
     * ElevenLabs audio peaks close to full scale; on the TCL's processed output path that
     * clipped audibly, so speech never reaches full scale here.
     */
    static void applyGain(byte[] pcm, float g) {
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            double x = ((short) ((pcm[i + 1] << 8) | (pcm[i] & 0xff))) / 32768.0 * g;
            double a = Math.abs(x);
            if (a > 0.8) a = 0.8 + 0.2 * Math.tanh((a - 0.8) / 0.2);
            int v = (int) Math.round(Math.copySign(a, x) * 32767.0);
            pcm[i] = (byte) v;
            pcm[i + 1] = (byte) (v >> 8);
        }
    }
}
