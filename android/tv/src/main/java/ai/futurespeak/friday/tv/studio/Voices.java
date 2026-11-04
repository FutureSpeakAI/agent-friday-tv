package ai.futurespeak.friday.tv.studio;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Studio's two voices: Friday's (ElevenLabs, paid per character) and the TV's own (free). Both give 22.05 kHz PCM. */
public final class Voices {
    private Voices() {}

    /** Friday's voice: the same ElevenLabs voice and model as conversation, fetched whole per sentence. Never logs the key. */
    public static final class Friday implements Engines.Speaker {
        private final Supplier<String> key;
        private final String voiceId, modelId;
        private final JSONObject settings;

        public Friday(Supplier<String> key, String voiceId, String modelId) { this(key, voiceId, modelId, null); }

        /** @param settings ElevenLabs voice_settings (stability, style, speed…), or null for the voice's own */
        public Friday(Supplier<String> key, String voiceId, String modelId, JSONObject settings) {
            this.key = key; this.voiceId = voiceId; this.modelId = modelId; this.settings = settings;
        }

        @Override public String name() { return "Friday's voice (ElevenLabs)"; }
        @Override public boolean paid() { return true; }

        @Override public byte[] pcm(String text) throws Exception {
            String k = key.get();
            if (k == null || k.isEmpty()) throw new java.io.IOException("no key");
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.elevenlabs.io/v1/text-to-speech/"
                    + URLEncoder.encode(voiceId, "UTF-8") + "/stream?output_format=pcm_22050").openConnection();
            try {
                c.setRequestMethod("POST");
                c.setConnectTimeout(8000);
                c.setReadTimeout(30000);
                c.setDoOutput(true);
                c.setRequestProperty("xi-api-key", k);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Accept", "audio/pcm");
                JSONObject req = new JSONObject().put("text", text).put("model_id", modelId);
                if (settings != null) req.put("voice_settings", settings);
                try (OutputStream os = c.getOutputStream()) { os.write(req.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = c.getResponseCode();
                if (code != 200) throw new java.io.IOException("HTTP " + code);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(256 * 1024);
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[16 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) { bos.write(buf, 0, n); if (bos.size() > 8 * 1024 * 1024) throw new java.io.IOException("too long"); }
                }
                byte[] b = bos.toByteArray();
                if (b.length < 2) throw new java.io.IOException("no audio");
                return b;
            } finally {
                c.disconnect();
            }
        }
    }

    /** The TV's own voice (Android text-to-speech), written to a file and resampled to 22.05 kHz. */
    public static final class Tv implements Engines.Speaker {
        private final Context ctx;
        private final File tmp;
        private TextToSpeech tts;
        private volatile boolean ready;

        public Tv(Context c, File tmpDir) { ctx = c.getApplicationContext(); tmp = tmpDir; }

        @Override public String name() { return "the TV's own voice"; }
        @Override public boolean paid() { return false; }

        private synchronized TextToSpeech engine() throws Exception {
            if (tts != null && ready) return tts;
            CountDownLatch up = new CountDownLatch(1);
            final int[] status = {TextToSpeech.ERROR};
            tts = new TextToSpeech(ctx, s -> { status[0] = s; up.countDown(); });
            if (!up.await(10, TimeUnit.SECONDS) || status[0] != TextToSpeech.SUCCESS) { tts = null; throw new java.io.IOException("TV voice unavailable"); }
            tts.setLanguage(Locale.US);
            ready = true;
            return tts;
        }

        @Override public synchronized byte[] pcm(String text) throws Exception {
            TextToSpeech t = engine();
            if (!tmp.isDirectory() && !tmp.mkdirs()) throw new java.io.IOException("no tmp");
            File f = new File(tmp, "tv-voice.wav");
            f.delete();
            CountDownLatch done = new CountDownLatch(1);
            final boolean[] ok = {false};
            String id = "studio-" + System.nanoTime();
            t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String u) { }
                @Override public void onDone(String u) { if (id.equals(u)) { ok[0] = true; done.countDown(); } }
                @Override public void onError(String u) { if (id.equals(u)) done.countDown(); }
            });
            if (t.synthesizeToFile(text, new Bundle(), f, id) != TextToSpeech.SUCCESS) throw new java.io.IOException("TV voice refused");
            if (!done.await(60, TimeUnit.SECONDS) || !ok[0]) throw new java.io.IOException("TV voice timed out");
            return toRate(Files.readAllBytes(f.toPath()), Engines.RATE);
        }

        public void release() { if (tts != null) tts.shutdown(); }
    }

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
        if (src == rate) {
            ByteBuffer o = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (short s : in) o.putShort(s);
            return o.array();
        }
        int outFrames = (int) ((long) frames * rate / src);
        ByteBuffer o = ByteBuffer.allocate(outFrames * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < outFrames; i++) {
            double x = (double) i * src / rate;
            int a = (int) x;
            double f = x - a;
            int s0 = in[Math.min(a, frames - 1)], s1 = in[Math.min(a + 1, frames - 1)];
            o.putShort((short) Math.round(s0 + (s1 - s0) * f));
        }
        return o.array();
    }
}
