package ai.futurespeak.friday.tv.story;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Two music beds (ST-D7): "adventure" and "bedtime", each a short instrumental loop made once
 * from a fixed brief and kept as PCM on the TV. Looped under the story with ducking. The
 * fetcher is a seam (ElevenLabs music on the TV); the cache is files.
 */
public final class MusicBeds {
    public static final String ADVENTURE = "adventure", BEDTIME = "bedtime";
    public static final int SECONDS = 60;

    public static String brief(String mood) {
        return BEDTIME.equals(mood)
                ? "A very soft, slow, dreamy lullaby for a child's bedtime story. Gentle music box and warm pads. Instrumental only, no vocals, no drums, quiet and calm throughout, seamless loop."
                : "A light, warm, playful adventure theme for a young child's picture-book story. Gentle woodwinds, soft strings, a little sparkle. Instrumental only, no vocals, never loud, seamless loop.";
    }

    public interface Fetcher { byte[] pcm(String brief, int seconds) throws Exception; }

    private final File dir;
    private final Fetcher fetcher;

    public MusicBeds(File root, Fetcher fetcher) { dir = new File(root, "music"); this.fetcher = fetcher; }

    public File file(String mood) { return new File(dir, mood + ".pcm"); }

    public boolean cached(String mood) { return file(mood).isFile() && file(mood).length() > 44100; }

    public synchronized byte[] cachedPcm(String mood) {
        if (!cached(mood)) return null;
        try { return Files.readAllBytes(file(mood).toPath()); } catch (IOException e) { return null; }
    }

    /** Makes a bed and caches it; only reached under a grown-up's unlock. */
    public synchronized byte[] make(String mood) throws Exception {
        byte[] got = cachedPcm(mood);
        if (got != null) return got;
        byte[] pcm = fetcher.pcm(brief(mood), SECONDS);
        if (pcm == null || pcm.length < 44100) throw new IOException("no music came back");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no music dir");
        File tmp = new File(dir, mood + ".tmp");
        Files.write(tmp.toPath(), trimToLoop(pcm));
        if (!tmp.renameTo(file(mood))) throw new IOException("rename");
        return cachedPcm(mood);
    }

    /** A short fade at both ends so the loop point doesn't click (16-bit mono PCM). */
    static byte[] trimToLoop(byte[] pcm) {
        int frames = pcm.length / 2;
        int fade = Math.min(frames / 2, 22050 / 10);   // 100 ms
        byte[] out = pcm.clone();
        for (int i = 0; i < fade; i++) {
            float g = i / (float) fade;
            scale(out, i, g);
            scale(out, frames - 1 - i, g);
        }
        return out;
    }

    private static void scale(byte[] pcm, int frame, float g) {
        int p = frame * 2;
        if (p + 1 >= pcm.length) return;
        short s = (short) ((pcm[p] & 0xff) | (pcm[p + 1] << 8));
        s = (short) Math.round(s * g);
        pcm[p] = (byte) (s & 0xff);
        pcm[p + 1] = (byte) ((s >> 8) & 0xff);
    }
}
