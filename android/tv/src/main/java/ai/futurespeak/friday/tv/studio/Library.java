package ai.futurespeak.friday.tv.studio;

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

import ai.futurespeak.friday.tv.actions.Receipts;

/**
 * The Library row's source of truth: {@code files/studio/index.json}, sealed with the same
 * Keystore key as the receipts (spec §8.6). A tampered or unsigned index reads as empty: the
 * row shows nothing rather than something (AC-S17). Titles come from the TV's own job records,
 * never from a model reply at read time.
 */
public final class Library {
    public static final class Item {
        public final String id, title, state, note, milestone;
        public final int minutes; public final boolean fresh; public final long created;
        Item(String id, String title, String state, int minutes, boolean fresh, String note, String milestone, long created) {
            this.id = id; this.title = title; this.state = state; this.minutes = minutes; this.fresh = fresh; this.note = note; this.milestone = milestone; this.created = created;
        }
        public boolean playable() { return Job.DONE.equals(state); }
    }

    private final File root, index, sig;
    private final Receipts.Signer signer;

    public Library(File studioRoot, Receipts.Signer signer) {
        this.root = studioRoot; this.signer = signer;
        index = new File(studioRoot, "index.json");
        sig = new File(studioRoot, "index.sig");
    }

    public File root() { return root; }

    /** Newest first; empty when the index is missing, unreadable or its seal doesn't match. */
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
                out.add(new Item(o.getString("id"), o.getString("title"), o.getString("state"), o.optInt("minutes"),
                        o.optBoolean("new"), o.optString("note"), o.optString("milestone"), o.optLong("created")));
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

    /** Writes or replaces this job's row, from its record. */
    public synchronized void put(Job j, String milestone) throws IOException {
        List<Item> all = items();
        List<Item> keep = new ArrayList<>();
        for (Item i : all) if (!i.id.equals(j.id())) keep.add(i);
        int minutes;
        try { minutes = j.request().lengthMin; } catch (RuntimeException e) { minutes = 0; }
        String note = "";
        for (String sw : j.switches()) {
            if (sw.contains("the TV's voice finished it")) { note = "Parts are in the TV's voice"; break; }
            if (sw.contains("default ElevenLabs voice")) note = "In Friday's default voice";
        }
        if (Job.FAILED.equals(j.state()) || Job.STOPPED.equals(j.state())) note = j.problem();
        if (Job.PAUSED.equals(j.state())) note = "Waiting for your OK: it's costing more than the estimate";
        keep.add(new Item(j.id(), j.title(), j.state(), minutes, Job.DONE.equals(j.state()) && !j.played(), note, milestone == null ? "" : milestone, j.created()));
        write(keep);
    }

    public synchronized void remove(String id) throws IOException {
        List<Item> keep = new ArrayList<>();
        for (Item i : items()) if (!i.id.equals(id)) keep.add(i);
        write(keep);
    }

    private void write(List<Item> items) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("no studio dir");
        try {
            JSONArray a = new JSONArray();
            for (Item i : items)
                a.put(new JSONObject().put("id", i.id).put("title", i.title).put("state", i.state).put("minutes", i.minutes)
                        .put("new", i.fresh).put("note", i.note).put("milestone", i.milestone).put("created", i.created));
            String body = new JSONObject().put("v", 1).put("items", a).toString();
            File t1 = new File(root, "index.tmp"), t2 = new File(root, "sig.tmp");
            Job.write(t1, body);
            Job.write(t2, signer.sign(Receipts.sha256(body)));
            Files.move(t1.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.move(t2.toPath(), sig.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (JSONException e) { throw new IOException("index"); }
    }
}
