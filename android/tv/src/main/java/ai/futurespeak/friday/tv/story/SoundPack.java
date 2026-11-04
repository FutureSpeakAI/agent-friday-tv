package ai.futurespeak.friday.tv.story;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sound pack (ST-D7): a fixed list of short sounds, each made once from a fixed brief and
 * kept as PCM on the TV, so a story's sounds cost nothing after the first night. Five silly
 * sounds for the row on Up, ten cues a page may name. Fetching is a seam; the cache is files.
 */
public final class SoundPack {
    public static final class Sound {
        public final String name, brief; public final float seconds;
        Sound(String name, String brief, float seconds) { this.name = name; this.brief = brief; this.seconds = seconds; }
    }

    /** Makes one sound's 22.05 kHz mono PCM from its brief (ElevenLabs sound effects on the TV). */
    public interface Fetcher { byte[] pcm(String brief, float seconds) throws Exception; }

    public static final List<Sound> SILLY = Collections.unmodifiableList(java.util.Arrays.asList(
            new Sound("toot", "a short, silly cartoon trumpet toot", 1.5f),
            new Sound("boing", "a bouncy cartoon spring boing", 1.2f),
            new Sound("giggle", "a child's happy giggle, short", 1.8f),
            new Sound("roar", "a friendly, not scary, small dragon roar", 1.8f),
            new Sound("sparkle", "a magical twinkling sparkle chime", 2.0f)));

    public static final List<Sound> CUES = Collections.unmodifiableList(java.util.Arrays.asList(
            new Sound("creak", "an old wooden door creaking open slowly", 2.0f),
            new Sound("splash", "a playful splash into a pond", 1.5f),
            new Sound("wind", "a soft gentle breeze through leaves", 3.0f),
            new Sound("steps", "small quick footsteps on a wooden floor", 2.0f),
            new Sound("chime", "a gentle magic chime", 1.8f),
            new Sound("yawn", "a big cute sleepy yawn", 2.0f),
            new Sound("hoot", "a soft owl hoot at night", 1.8f),
            new Sound("rumble", "a soft distant friendly thunder rumble", 2.5f),
            new Sound("bell", "a small bright bell ring", 1.5f),
            new Sound("snore", "a tiny cute snore", 2.0f)));

    public static List<Sound> all() {
        List<Sound> a = new ArrayList<>(SILLY);
        a.addAll(CUES);
        return a;
    }

    public static Set<String> names() {
        Set<String> s = new java.util.LinkedHashSet<>();
        for (Sound x : all()) s.add(x.name);
        return s;
    }

    public static Sound find(String name) {
        for (Sound s : all()) if (s.name.equals(name)) return s;
        return null;
    }

    /** Seconds in the whole pack, for the one-off estimate on the card. */
    public static float totalSeconds() {
        float t = 0;
        for (Sound s : all()) t += s.seconds;
        return t;
    }

    private final File dir;
    private final Fetcher fetcher;
    private final Map<String, byte[]> memory = new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) { return size() > 8; }
    };

    public SoundPack(File root, Fetcher fetcher) { this.dir = new File(root, "sounds"); this.fetcher = fetcher; }

    public File file(String name) { return new File(dir, name + ".pcm"); }

    public boolean cached(String name) { return find(name) != null && file(name).isFile() && file(name).length() > 1000; }

    public boolean allCached() {
        for (Sound s : all()) if (!cached(s.name)) return false;
        return true;
    }

    /** Sounds not yet on the TV (the ones a first night would make). */
    public List<Sound> missing() {
        List<Sound> m = new ArrayList<>();
        for (Sound s : all()) if (!cached(s.name)) m.add(s);
        return m;
    }

    /** The sound's PCM from the TV; null when it isn't cached (never fetched here). */
    public synchronized byte[] cachedPcm(String name) {
        if (!cached(name)) return null;
        byte[] b = memory.get(name);
        if (b != null) return b;
        try { b = Files.readAllBytes(file(name).toPath()); memory.put(name, b); return b; }
        catch (IOException e) { return null; }
    }

    /** Makes one sound and caches it. Only a grown-up's unlock reaches here (the engine checks). */
    public synchronized byte[] make(String name) throws Exception {
        Sound s = find(name);
        if (s == null) throw new IOException("no such sound");
        byte[] got = cachedPcm(name);
        if (got != null) return got;
        byte[] pcm = fetcher.pcm(s.brief, s.seconds);
        if (pcm == null || pcm.length < 1000) throw new IOException("no sound came back");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no sounds dir");
        File tmp = new File(dir, name + ".tmp");
        Files.write(tmp.toPath(), pcm);
        if (!tmp.renameTo(file(name))) throw new IOException("rename");
        memory.put(name, pcm);
        return pcm;
    }
}
