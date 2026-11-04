package ai.futurespeak.friday.tv.story;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Three tracks on the TV (SB-E06): narration (what is being read, with its position for the
 * read-along), a music bed looped underneath and ducked while a line plays, and one-shot
 * sounds. All 16-bit mono PCM at 22.05 kHz, platform {@link AudioTrack}s, no library.
 */
public final class Mixer {
    public interface Progress { void at(long ms); void done(); }

    static final int RATE = 22050;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService voiceThread = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "story-voice"); t.setDaemon(true); t.setPriority(Thread.MAX_PRIORITY); return t; });
    private final ExecutorService bedThread = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "story-bed"); t.setDaemon(true); return t; });
    private final ExecutorService shots = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "story-shot"); t.setDaemon(true); return t; });

    private final Object voiceLock = new Object();
    private AudioTrack voice;
    private final AtomicLong voiceGen = new AtomicLong();
    private volatile long lineStart = -1, lineEnd = -1;

    private final Object bedLock = new Object();
    private AudioTrack bed;
    private volatile byte[] bedPcm;
    private volatile boolean bedOn = false, released = false;
    private volatile float bedLevel = 0.45f;

    private static AudioTrack track(int mode, int bytes, boolean speech) {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(speech ? AudioAttributes.CONTENT_TYPE_SPEECH : AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                mode == AudioTrack.MODE_STATIC ? Math.max(bytes, min) : Math.max(min * 4, 64 * 1024), mode, 0);
    }

    // ---------------------------------------------------------------- narration -----

    /** Plays {@code pcm}, reporting the position every 50 ms on the main thread, then done. Cuts anything already playing. */
    public void say(byte[] pcm, Progress p) {
        long gen = voiceGen.incrementAndGet();
        stopVoice();
        if (pcm == null || pcm.length < 2) { main.post(p::done); return; }
        voiceThread.execute(() -> {
            if (gen != voiceGen.get() || released) return;
            AudioTrack t;
            synchronized (voiceLock) {
                voice = track(AudioTrack.MODE_STREAM, 0, true);
                t = voice;
            }
            lineStart = now(); lineEnd = -1;
            try {
                t.play();
                int pos = 0, chunk = 8 * 1024;
                long started = now();
                while (pos < pcm.length && gen == voiceGen.get() && !released) {
                    int n = Math.min(chunk, pcm.length - pos);
                    int w = t.write(pcm, pos, n);
                    if (w <= 0) break;
                    pos += w;
                    long ms = Math.min(pcm.length, pos) * 1000L / (RATE * 2L);
                    long headMs = t.getPlaybackHeadPosition() * 1000L / RATE;
                    long report = Math.min(ms, headMs);
                    main.post(() -> { if (gen == voiceGen.get()) p.at(report); });
                }
                // let the tail play out, reporting the head position
                long total = pcm.length * 1000L / (RATE * 2L);
                while (gen == voiceGen.get() && !released) {
                    long headMs = t.getPlaybackHeadPosition() * 1000L / RATE;
                    main.post(() -> { if (gen == voiceGen.get()) p.at(Math.min(total, headMs)); });
                    if (headMs >= total - 40 || now() - started > total + 3000) break;
                    Thread.sleep(50);
                }
            } catch (Throwable ignored) {
            } finally {
                lineEnd = now();
                synchronized (voiceLock) { if (voice == t) { try { t.stop(); } catch (Throwable ignored) { } t.release(); voice = null; } }
                if (gen == voiceGen.get() && !released) main.post(p::done);
            }
        });
    }

    public void stopVoice() {
        voiceGen.incrementAndGet();
        synchronized (voiceLock) {
            if (voice != null) { try { voice.pause(); voice.flush(); voice.stop(); } catch (Throwable ignored) { } voice.release(); voice = null; }
        }
        lineEnd = now();
    }

    public boolean speaking() { synchronized (voiceLock) { return voice != null; } }

    // ---------------------------------------------------------------- bed -----

    /** Loops {@code pcm} underneath until {@link #stopBed()}; replaces any bed playing. */
    public void bed(byte[] pcm, float level) {
        bedLevel = Math.max(0f, Math.min(1f, level));
        bedPcm = pcm;
        if (bedOn) return;
        bedOn = true;
        bedThread.execute(() -> {
            AudioTrack t = null;
            try {
                while (bedOn && !released) {
                    byte[] b = bedPcm;
                    if (b == null || b.length < 2) { Thread.sleep(200); continue; }
                    if (t == null) {
                        synchronized (bedLock) { bed = track(AudioTrack.MODE_STREAM, 0, false); t = bed; }
                        t.play();
                    }
                    int pos = 0, chunk = 4 * 1024;
                    while (pos < b.length && bedOn && b == bedPcm && !released) {
                        float g = Ducking.gain(now(), lineStart, speaking() ? -1 : lineEnd) * bedLevel;
                        t.setVolume(g);
                        int n = Math.min(chunk, b.length - pos);
                        int w = t.write(b, pos, n);
                        if (w <= 0) break;
                        pos += w;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                synchronized (bedLock) { if (bed != null) { try { bed.stop(); } catch (Throwable ignored) { } bed.release(); bed = null; } }
            }
        });
    }

    public void stopBed() {
        bedOn = false;
        synchronized (bedLock) { if (bed != null) { try { bed.pause(); bed.flush(); } catch (Throwable ignored) { } } }
    }

    public void setBedLevel(float level) { bedLevel = Math.max(0f, Math.min(1f, level)); }

    // ---------------------------------------------------------------- one-shots -----

    /** Plays a short sound over everything (a cue, or a silly button). */
    public void shot(byte[] pcm, float level) {
        if (pcm == null || pcm.length < 2 || released) return;
        shots.execute(() -> {
            AudioTrack t = null;
            try {
                t = track(AudioTrack.MODE_STATIC, pcm.length, false);
                t.write(pcm, 0, pcm.length);
                t.setVolume(Math.max(0f, Math.min(1f, level)));
                t.play();
                Thread.sleep(pcm.length * 1000L / (RATE * 2L) + 150);
            } catch (Throwable ignored) {
            } finally {
                if (t != null) { try { t.stop(); } catch (Throwable ignored) { } t.release(); }
            }
        });
    }

    public void release() {
        released = true;
        stopVoice();
        stopBed();
        voiceThread.shutdownNow();
        bedThread.shutdownNow();
        shots.shutdownNow();
    }

    static long now() { return android.os.SystemClock.elapsedRealtime(); }
}
