package ai.futurespeak.friday.tv.story;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The TV's own voice as a {@link Seams.Speaker}: free, on the TV, written to a file and
 * resampled to 22.05 kHz, with word timings shared out by letter count (the engine's
 * fallback when ElevenLabs fails, and the whole voice when there is no key).
 */
public final class TvVoice implements Seams.Speaker {
    private final Context ctx;
    private final File tmp;
    private final float speed;
    private TextToSpeech tts;
    private volatile boolean ready;

    public TvVoice(Context c, File tmpDir, float speed) { ctx = c.getApplicationContext(); tmp = tmpDir; this.speed = Math.max(0.7f, Math.min(1.2f, speed)); }

    @Override public String name() { return "the TV's own voice"; }
    @Override public boolean paid() { return false; }

    private synchronized TextToSpeech engine() throws Exception {
        if (tts != null && ready) return tts;
        CountDownLatch up = new CountDownLatch(1);
        final int[] status = {TextToSpeech.ERROR};
        tts = new TextToSpeech(ctx, s -> { status[0] = s; up.countDown(); });
        if (!up.await(10, TimeUnit.SECONDS) || status[0] != TextToSpeech.SUCCESS) { tts = null; throw new java.io.IOException("TV voice unavailable"); }
        tts.setLanguage(Locale.US);
        tts.setSpeechRate(speed);
        ready = true;
        return tts;
    }

    @Override public synchronized Seams.Audio speak(String text, Cast.Member who) throws Exception {
        TextToSpeech t = engine();
        t.setSpeechRate(who == null ? speed : Math.max(0.7f, Math.min(1.2f, speed * who.speed)));
        if (!tmp.isDirectory() && !tmp.mkdirs()) throw new java.io.IOException("no tmp");
        File f = new File(tmp, "story-tv-voice.wav");
        f.delete();
        CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = {false};
        String id = "story-" + System.nanoTime();
        t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String u) { }
            @Override public void onDone(String u) { if (id.equals(u)) { ok[0] = true; done.countDown(); } }
            @Override public void onError(String u) { if (id.equals(u)) done.countDown(); }
        });
        if (t.synthesizeToFile(text, new Bundle(), f, id) != TextToSpeech.SUCCESS) throw new java.io.IOException("TV voice refused");
        if (!done.await(60, TimeUnit.SECONDS) || !ok[0]) throw new java.io.IOException("TV voice timed out");
        byte[] pcm = toRate(Files.readAllBytes(f.toPath()), 22050);
        long ms = pcm.length * 1000L / (22050L * 2);
        return new Seams.Audio(pcm, WordTiming.proportional(text, ms), "tv");
    }

    public void release() { if (tts != null) tts.shutdown(); }

    /** A WAV file's 16-bit PCM (mono, or the first channel), linearly resampled to {@code rate}. */
    static byte[] toRate(byte[] wav, int rate) throws java.io.IOException {
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (wav.length < 44 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) throw new java.io.IOException("not WAV");
        int pos = 12, src = 0, channels = 1, bits = 16;
        int dataOff = -1, dataLen = 0;
        while (pos + 8 <= wav.length) {
            int id = b.getInt(pos), len = b.getInt(pos + 4);
            if (id == 0x20746d66) { channels = b.getShort(pos + 10); src = b.getInt(pos + 12); bits = b.getShort(pos + 22); }
            else if (id == 0x61746164) { dataOff = pos + 8; dataLen = Math.min(len < 0 ? Integer.MAX_VALUE : len, wav.length - pos - 8); break; }
            pos += 8 + Math.max(0, len) + (len & 1);
        }
        if (dataOff < 0 || src <= 0 || bits != 16 || channels < 1) throw new java.io.IOException("unsupported WAV");
        int frames = dataLen / (2 * channels);
        short[] in = new short[frames];
        for (int i = 0; i < frames; i++) in[i] = b.getShort(dataOff + i * 2 * channels);
        int outFrames = src == rate ? frames : (int) ((long) frames * rate / src);
        ByteBuffer o = ByteBuffer.allocate(outFrames * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < outFrames; i++) {
            double x = (double) i * src / rate;
            int a = (int) x; double f = x - a;
            int s0 = in[Math.min(a, frames - 1)], s1 = in[Math.min(a + 1, frames - 1)];
            o.putShort((short) Math.round(s0 + (s1 - s0) * f));
        }
        return o.array();
    }
}
