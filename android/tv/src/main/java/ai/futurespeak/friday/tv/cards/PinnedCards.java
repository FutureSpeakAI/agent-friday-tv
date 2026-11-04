package ai.futurespeak.friday.tv.cards;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Cards pinned for the screensaver (OK on a card pins or unpins it). Text, list, table and
 * chart cards only (interactive HTML cards are not kept). At most {@link #MAX}; oldest drops off.
 * Stored in the app's private files; pure Java, unit-tested.
 */
public final class PinnedCards {
    public static final int MAX = 12;
    private final File file;

    public PinnedCards(File filesDir) { file = new File(filesDir, "pinned_cards.json"); }

    public synchronized List<JSONObject> list() {
        List<JSONObject> out = new ArrayList<>();
        try {
            if (!file.exists()) return out;
            JSONArray a = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) out.add(o); }
        } catch (Exception ignored) { }
        return out;
    }

    public static boolean pinnable(JSONObject card) {
        String t = card == null ? "" : card.optString("type");
        return t.equals("text") || t.equals("list") || t.equals("table") || t.equals("chart");
    }

    static String key(JSONObject c) { return c.optString("type") + "|" + c.optString("title"); }

    public synchronized boolean isPinned(JSONObject card) {
        for (JSONObject o : list()) if (key(o).equals(key(card))) return true;
        return false;
    }

    /** Pins or unpins; returns the new state (true = pinned). */
    public synchronized boolean toggle(JSONObject card) {
        if (!pinnable(card)) return false;
        List<JSONObject> l = list();
        for (int i = 0; i < l.size(); i++) if (key(l.get(i)).equals(key(card))) { l.remove(i); save(l); return false; }
        l.add(card);
        while (l.size() > MAX) l.remove(0);
        save(l);
        return true;
    }

    public synchronized void remove(int index) {
        List<JSONObject> l = list();
        if (index >= 0 && index < l.size()) { l.remove(index); save(l); }
    }

    private void save(List<JSONObject> l) {
        JSONArray a = new JSONArray();
        for (JSONObject o : l) a.put(o);
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { return; }
        try { java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
        catch (Exception e) { tmp.delete(); }
    }
}
