package ai.futurespeak.friday.tv.story;

import ai.futurespeak.friday.tv.actions.Receipts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The kids Library's shelf of storybooks (spec §11, ST-D10): {@code files/story/index.json},
 * sealed with the receipts' Keystore key the way Studio's Library is. A tampered or unsigned
 * index reads as empty. Titles come from the TV's own book records at write time. At most 40
 * books; the oldest unplayed is offered for removal first.
 */
public final class Shelf {
    public static final int MAX_BOOKS = 40;

    public static final class Item {
        public final String id, title, state, note;
        public final int pages; public final boolean fresh, bedtime; public final long created; public final double usd;
        Item(String id, String title, String state, int pages, boolean fresh, boolean bedtime, long created, double usd, String note) {
            this.id = id; this.title = title; this.state = state; this.pages = pages; this.fresh = fresh; this.bedtime = bedtime; this.created = created; this.usd = usd; this.note = note;
        }
        public boolean readable() { return pages > 0; }
    }

    private final File root, index, sig;
    private final Receipts.Signer signer;

    public Shelf(File storyRoot, Receipts.Signer signer) {
        root = storyRoot; this.signer = signer;
        index = new File(storyRoot, "index.json");
        sig = new File(storyRoot, "index.sig");
    }

    public File root() { return root; }

    public synchronized List<Item> items() {
        List<Item> out = new ArrayList<>();
        try {
            if (!index.isFile() || !sig.isFile()) return out;
            String body = new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8);
            String s = new String(Files.readAllBytes(sig.toPath()), StandardCharsets.UTF_8).trim();
            if (!s.equals(signer.sign(Receipts.sha256(body)))) return out;
            JSONArray a = new JSONObject(body).getJSONArray("items");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Item(o.getString("id"), o.getString("title"), o.getString("state"), o.optInt("pages"), o.optBoolean("new"),
                        o.optBoolean("bedtime"), o.optLong("created"), o.optDouble("usd", 0), o.optString("note")));
            }
        } catch (IOException | JSONException | RuntimeException e) {
            return new ArrayList<>();
        }
        Collections.sort(out, (x, y) -> Long.compare(y.created, x.created));
        return out;
    }

    public synchronized Item item(String id) {
        for (Item i : items()) if (i.id.equals(id)) return i;
        return null;
    }

    public synchronized boolean full() { return items().size() >= MAX_BOOKS; }

    public synchronized void put(Book b) throws IOException {
        List<Item> keep = new ArrayList<>();
        for (Item i : items()) if (!i.id.equals(b.id())) keep.add(i);
        String title = b.title().isEmpty() ? (b.topic().isEmpty() ? "A story" : "A story about " + b.topic()) : b.title();
        keep.add(new Item(b.id(), title, b.state(), b.pageCount(), Book.DONE.equals(b.state()) && !b.played(), b.bedtime(), b.created(),
                b.spent().usd(), b.problem()));
        write(keep);
    }

    public synchronized void remove(String id) throws IOException {
        List<Item> keep = new ArrayList<>();
        for (Item i : items()) if (!i.id.equals(id)) keep.add(i);
        write(keep);
    }

    private void write(List<Item> items) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("no story dir");
        try {
            JSONArray a = new JSONArray();
            for (Item i : items)
                a.put(new JSONObject().put("id", i.id).put("title", i.title).put("state", i.state).put("pages", i.pages).put("new", i.fresh)
                        .put("bedtime", i.bedtime).put("created", i.created).put("usd", i.usd).put("note", i.note));
            String body = new JSONObject().put("v", 1).put("items", a).toString();
            File t1 = new File(root, "index.tmp"), t2 = new File(root, "sig.tmp");
            Files.write(t1.toPath(), body.getBytes(StandardCharsets.UTF_8));
            Files.write(t2.toPath(), signer.sign(Receipts.sha256(body)).getBytes(StandardCharsets.UTF_8));
            Files.move(t1.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.move(t2.toPath(), sig.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (JSONException e) { throw new IOException("index"); }
    }
}
