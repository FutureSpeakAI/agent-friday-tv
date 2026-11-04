package ai.futurespeak.friday.tv.history;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The conversation on the TV's private storage (app files dir; not backed up). */
public final class HistoryStore {
    private final File file;

    public HistoryStore(File dir) { this.file = new File(dir, "history.json"); }

    public Conversation load() {
        try {
            if (!file.isFile()) return new Conversation();
            return Conversation.fromJson(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Conversation();
        }
    }

    public synchronized void save(Conversation c) {
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(c.toJson().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(file)) tmp.delete();
    }

    /** Forget: the file is overwritten, then deleted. */
    public synchronized void forget() {
        if (file.isFile()) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(new byte[(int) Math.min(file.length(), 4 * 1024 * 1024)]);
                out.getFD().sync();
            } catch (IOException ignored) { }
            file.delete();
        }
        new File(file.getPath() + ".tmp").delete();
    }

    public boolean exists() { return file.isFile(); }
}
