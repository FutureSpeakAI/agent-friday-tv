package ai.futurespeak.friday.tv.story;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Supplier;

/**
 * The Storyteller's ElevenLabs client, all on {@code api.elevenlabs.io}: narration with word
 * timings (the with-timestamps endpoint, falling back to the plain stream and proportional
 * timing), sound effects, a music bed, and voice design. Only Friday's and the cast's lines go
 * out; never the child's voice (TV-D4). Never logs the key or the words. Everything returns
 * 16-bit mono PCM at 22.05 kHz; a service that answers with MP3 is decoded by the platform.
 */
public final class Eleven {
    public static final String HOST = "https://api.elevenlabs.io";
    static final int MAX_AUDIO = 8 * 1024 * 1024;

    private final Supplier<String> key;
    private final String fridayVoiceId, modelId;
    private final float fridaySpeed;
    private final File tmpDir;

    public Eleven(Supplier<String> key, String fridayVoiceId, String modelId, float fridaySpeed, File tmpDir) {
        this.key = key; this.fridayVoiceId = fridayVoiceId; this.modelId = modelId; this.fridaySpeed = fridaySpeed; this.tmpDir = tmpDir;
    }

    public boolean ready() { String k = key.get(); return k != null && !k.isEmpty(); }

    // ---------------------------------------------------------------- narration -----

    /** Friday's voice, or a cast member's (designed id, or Friday's voice in character). */
    public Seams.Speaker speaker() {
        return new Seams.Speaker() {
            @Override public Seams.Audio speak(String text, Cast.Member who) throws Exception {
                String voice = who != null && who.designed() ? who.voiceId : fridayVoiceId;
                float speed = who == null ? fridaySpeed : clamp(fridaySpeed * who.speed, 0.8f, 1.15f);
                float stability = who == null ? 0.5f : who.stability;
                return tts(text, voice, speed, stability);
            }
            @Override public String name() { return "Friday's voice (ElevenLabs)"; }
            @Override public boolean paid() { return true; }
        };
    }

    Seams.Audio tts(String text, String voiceId, float speed, float stability) throws Exception {
        JSONObject settings = new JSONObject().put("stability", stability).put("similarity_boost", 0.75).put("speed", speed);
        JSONObject body = new JSONObject().put("text", text).put("model_id", modelId).put("voice_settings", settings);
        try {
            Response r = call("POST", "/v1/text-to-speech/" + URLEncoder.encode(voiceId, "UTF-8") + "/with-timestamps?output_format=pcm_22050", body, "application/json");
            if (r.code == 200) {
                JSONObject o = new JSONObject(new String(r.bytes, StandardCharsets.UTF_8));
                byte[] pcm = Base64.decode(o.getString("audio_base64"), Base64.DEFAULT);
                if (pcm.length < 2) throw new IOException("no audio");
                long ms = pcm.length * 1000L / (22050L * 2);
                List<WordTiming.Word> words = WordTiming.fromAlignment(text, o.optJSONObject("alignment"), ms);
                return new Seams.Audio(pcm, words, "elevenlabs");
            }
        } catch (IOException e) {
            // fall through to the plain stream
        }
        Response r = call("POST", "/v1/text-to-speech/" + URLEncoder.encode(voiceId, "UTF-8") + "/stream?output_format=pcm_22050", body, "audio/pcm");
        if (r.code != 200) throw new IOException("ElevenLabs answered " + r.code);
        byte[] pcm = r.bytes;
        if (pcm.length < 2) throw new IOException("no audio");
        long ms = pcm.length * 1000L / (22050L * 2);
        return new Seams.Audio(pcm, WordTiming.proportional(text, ms), "elevenlabs");
    }

    // ---------------------------------------------------------------- sounds and music -----

    /** The sound-effects endpoint, as a {@link SoundPack.Fetcher}. */
    public SoundPack.Fetcher sounds() {
        return (brief, seconds) -> {
            if (!"elevenlabs".equals(ai.futurespeak.friday.tv.Prefs.providersNow().soundProvider)) throw new IOException("sound effects are off (the owner's choice)");
            JSONObject body = new JSONObject().put("text", brief).put("duration_seconds", seconds).put("prompt_influence", 0.4);
            Response r = call("POST", "/v1/sound-generation?output_format=pcm_22050", body, "*/*");
            if (r.code != 200) throw new IOException("sound effects answered " + r.code);
            return toPcm(r.bytes, r.type);
        };
    }

    /** The music endpoint, as a {@link MusicBeds.Fetcher}. */
    public MusicBeds.Fetcher music() {
        return (brief, seconds) -> {
            if (!"elevenlabs".equals(ai.futurespeak.friday.tv.Prefs.providersNow().musicProvider)) throw new IOException("music is off (the owner's choice)");
            JSONObject body = new JSONObject().put("prompt", brief).put("music_length_ms", seconds * 1000).put("force_instrumental", true);
            Response r = call("POST", "/v1/music?output_format=pcm_22050", body, "*/*");
            if (r.code != 200) throw new IOException("music answered " + r.code);
            return toPcm(r.bytes, r.type);
        };
    }

    // ---------------------------------------------------------------- voice design -----

    /** Voice Design: previews from a description, then the first preview saved as a voice (ST-D3). */
    public Seams.VoiceDesigner designer() {
        return (name, description, sample) -> {
            String text = sample == null || sample.length() < 100
                    ? "Hello there! I'm " + name + ", and this is my voice in our story tonight. Shall we see what happens next? Let's go together, step by step, and find out."
                    : sample;
            if (!ai.futurespeak.friday.tv.Prefs.elevenVoiceOn()) throw new IOException("ElevenLabs voices are off (the owner chose the TV voice)");
            JSONObject body = new JSONObject().put("voice_description", description).put("text", text).put("model_id", ai.futurespeak.friday.tv.Prefs.providersNow().designModel);
            Response r = call("POST", "/v1/text-to-voice/design", body, "application/json");
            if (r.code == 404) r = call("POST", "/v1/text-to-voice/create-previews", body, "application/json");
            if (r.code != 200) throw new IOException("voice design answered " + r.code);
            JSONObject o = new JSONObject(new String(r.bytes, StandardCharsets.UTF_8));
            JSONArray previews = o.optJSONArray("previews");
            if (previews == null || previews.length() == 0) throw new IOException("no voice previews");
            String generated = previews.getJSONObject(0).getString("generated_voice_id");
            JSONObject create = new JSONObject().put("voice_name", "Story: " + name).put("voice_description", description).put("generated_voice_id", generated);
            Response c = call("POST", "/v1/text-to-voice", create, "application/json");
            if (c.code == 404) c = call("POST", "/v1/text-to-voice/create-voice-from-preview", create, "application/json");
            if (c.code != 200) throw new IOException("voice save answered " + c.code);
            String id = new JSONObject(new String(c.bytes, StandardCharsets.UTF_8)).optString("voice_id", "");
            if (!id.matches("^[A-Za-z0-9_-]{1,128}$")) throw new IOException("no voice id");
            return id;
        };
    }

    // ---------------------------------------------------------------- plumbing -----

    static final class Response { final int code; final String type; final byte[] bytes; Response(int c, String t, byte[] b) { code = c; type = t == null ? "" : t; bytes = b; } }

    private Response call(String method, String path, JSONObject body, String accept) throws IOException {
        String k = key.get();
        if (k == null || k.isEmpty()) throw new IOException("no key");
        HttpURLConnection c = (HttpURLConnection) new URL(HOST + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(120_000);
            c.setRequestProperty("xi-api-key", k);
            c.setRequestProperty("Accept", accept);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256 * 1024);
            if (in != null) try (InputStream i = in) {
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = i.read(buf)) > 0; ) { bos.write(buf, 0, n); if (bos.size() > MAX_AUDIO) throw new IOException("too long"); }
            }
            return new Response(code, c.getContentType(), bos.toByteArray());
        } finally {
            c.disconnect();
        }
    }

    /** PCM as asked for, or an MP3 the service sent instead, decoded by the platform. */
    byte[] toPcm(byte[] bytes, String contentType) throws Exception {
        if (bytes == null || bytes.length < 4) throw new IOException("empty audio");
        boolean mp3 = (contentType != null && contentType.contains("mpeg")) || (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3')
                || ((bytes[0] & 0xff) == 0xFF && (bytes[1] & 0xE0) == 0xE0);
        if (!mp3) return bytes;
        return decode(bytes);
    }

    private byte[] decode(byte[] mp3) throws Exception {
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) throw new IOException("no tmp");
        File f = new File(tmpDir, "story-" + System.nanoTime() + ".mp3");
        Files.write(f.toPath(), mp3);
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(f.getAbsolutePath());
            int track = -1; MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat m = ex.getTrackFormat(i);
                if (m.getString(MediaFormat.KEY_MIME).startsWith("audio/")) { track = i; fmt = m; break; }
            }
            if (track < 0) throw new IOException("no audio track");
            ex.selectTrack(track);
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            int rate = 44100, channels = 2;
            while (!outDone) {
                if (!inDone) {
                    int ib = dec.dequeueInputBuffer(10_000);
                    if (ib >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ib);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) { dec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { dec.queueInputBuffer(ib, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int ob = dec.dequeueOutputBuffer(info, 10_000);
                if (ob >= 0) {
                    ByteBuffer b = dec.getOutputBuffer(ob);
                    byte[] chunk = new byte[info.size];
                    b.get(chunk);
                    out.write(chunk);
                    dec.releaseOutputBuffer(ob, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                } else if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }
            return monoAtRate(out.toByteArray(), channels, rate, 22050);
        } finally {
            if (dec != null) try { dec.stop(); dec.release(); } catch (Throwable ignored) { }
            ex.release();
            f.delete();
        }
    }

    /** Interleaved 16-bit PCM to mono at {@code rate}, by averaging channels and linear resampling. */
    static byte[] monoAtRate(byte[] pcm, int channels, int src, int rate) {
        int frames = pcm.length / (2 * Math.max(1, channels));
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int ch = 0; ch < channels; ch++) {
                int p = (i * channels + ch) * 2;
                sum += (short) ((pcm[p] & 0xff) | (pcm[p + 1] << 8));
            }
            mono[i] = (short) (sum / channels);
        }
        int outFrames = src == rate ? frames : (int) ((long) frames * rate / src);
        byte[] out = new byte[outFrames * 2];
        for (int i = 0; i < outFrames; i++) {
            double x = (double) i * src / rate;
            int a = (int) x; double f = x - a;
            int s0 = mono[Math.min(a, frames - 1)], s1 = mono[Math.min(a + 1, frames - 1)];
            short s = (short) Math.round(s0 + (s1 - s0) * f);
            out[i * 2] = (byte) (s & 0xff); out[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        return out;
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
