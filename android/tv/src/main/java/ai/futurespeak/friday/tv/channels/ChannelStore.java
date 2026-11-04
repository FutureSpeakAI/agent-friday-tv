package ai.futurespeak.friday.tv.channels;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Saved channels, one JSON file each, in the app's private files dir. */
public final class ChannelStore {
    public static final int MAX_CHANNELS = 24;
    private final File dir;

    public ChannelStore(File filesDir) {
        this.dir = new File(filesDir, "channels");
    }

    public synchronized List<ChannelSpec> list() {
        List<ChannelSpec> out = new ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs == null) return out;
        for (File f : fs) {
            try {
                ChannelSpec c = ChannelSpec.fromJson(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
                if (c.id != null && f.getName().equals(c.id + ".json")) out.add(c);
            } catch (Exception ignored) { }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.createdAt, b.createdAt));
        return out;
    }

    public synchronized ChannelSpec get(String id) {
        if (!validId(id)) return null;
        for (ChannelSpec c : list()) if (c.id.equals(id)) return c;
        return null;
    }

    /** Saves an approved proposal with the hosts the grown-up granted. Returns the stored channel. */
    public synchronized ChannelSpec save(ChannelSpec proposal, List<String> granted) throws IOException {
        if (list().size() >= MAX_CHANNELS) throw new IOException("too many channels");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no channels dir");
        ChannelSpec saved = proposal.withId(newId(), granted);
        File f = new File(dir, saved.id + ".json");
        File tmp = new File(dir, saved.id + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(saved.toJson().toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (org.json.JSONException e) {
            throw new IOException("encode");
        }
        if (!tmp.renameTo(f)) throw new IOException("rename");
        return saved;
    }

    /** Renames a saved channel (1 to 40 characters). Returns the updated channel, or null. */
    public synchronized ChannelSpec rename(String id, String title) throws IOException {
        if (!validId(id)) return null;
        String t = title == null ? "" : title.replaceAll("[\\p{Cntrl}]", "").trim();
        if (t.isEmpty()) return null;
        if (t.length() > 40) t = t.substring(0, 40).trim();
        ChannelSpec c = get(id);
        if (c == null) return null;
        ChannelSpec renamed = new ChannelSpec(c.id, t, c.emoji, c.description, c.html, c.requestedHosts, c.grantedHosts, c.createdAt);
        File f = new File(dir, id + ".json");
        File tmp = new File(dir, id + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(renamed.toJson().toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (org.json.JSONException e) {
            throw new IOException("encode");
        }
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return renamed;
    }

    public synchronized boolean delete(String id) {
        if (!validId(id)) return false;
        return new File(dir, id + ".json").delete();
    }

    static boolean validId(String id) { return id != null && id.matches("^[a-f0-9]{12}$"); }

    private static String newId() {
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }
}
