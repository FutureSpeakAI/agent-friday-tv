package ai.futurespeak.friday.tv.story;

import ai.futurespeak.friday.tv.studio.Spent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A storybook on the TV (spec §11): {@code files/story/books/<id>/book.json} plus one audio
 * file and one picture per page. Every page is written as soon as it is made, so a story cut
 * short (the TV asleep, Friday frozen) opens again at the last page with a recap. The record is
 * written atomically.
 */
public final class Book {
    public static final String MAKING = "making", DONE = "done", STOPPED = "stopped";

    public final File dir;
    private final JSONObject o;

    private Book(File dir, JSONObject o) { this.dir = dir; this.o = o; }

    public static Book create(File root, String id, String topic, String mode, String band, String hero, boolean bedtime,
                              int pagesTarget, String decision, String principal) throws IOException {
        File dir = new File(root, "books" + File.separator + id);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no book dir");
        try {
            JSONObject o = new JSONObject().put("v", 1).put("id", id).put("topic", topic == null ? "" : topic).put("title", "")
                    .put("mode", mode).put("band", band == null ? "" : band).put("hero", hero == null ? "" : hero).put("bedtime", bedtime)
                    .put("pages_target", pagesTarget).put("decision", decision == null ? "" : decision).put("principal", principal)
                    .put("state", MAKING).put("created", System.currentTimeMillis()).put("pages", new JSONArray()).put("cast", new JSONArray())
                    .put("spent", new Spent().meter("usd")).put("recap", "").put("played", false).put("problem", "");
            Book b = new Book(dir, o);
            b.save();
            return b;
        } catch (JSONException e) { throw new IOException("book"); }
    }

    public static Book load(File dir) throws IOException {
        try { return new Book(dir, new JSONObject(new String(Files.readAllBytes(new File(dir, "book.json").toPath()), StandardCharsets.UTF_8))); }
        catch (JSONException e) { throw new IOException("book.json"); }
    }

    public synchronized void save() throws IOException {
        File tmp = new File(dir, "book.tmp");
        Files.write(tmp.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), new File(dir, "book.json").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public String id() { return o.optString("id"); }
    public synchronized String title() { return o.optString("title"); }
    public String topic() { return o.optString("topic"); }
    public String mode() { return o.optString("mode"); }
    public String band() { return o.optString("band"); }
    public String hero() { return o.optString("hero"); }
    public boolean bedtime() { return o.optBoolean("bedtime"); }
    public int pagesTarget() { return o.optInt("pages_target", Bedtime.NORMAL_PAGES); }
    public String decision() { return o.optString("decision"); }
    public String principal() { return o.optString("principal", "family"); }
    public long created() { return o.optLong("created"); }
    public synchronized String state() { return o.optString("state"); }
    public synchronized String recap() { return o.optString("recap"); }
    public synchronized boolean played() { return o.optBoolean("played"); }
    public synchronized String problem() { return o.optString("problem"); }
    public synchronized Spent spent() { return Spent.fromMeter(o.optJSONObject("spent"), "usd"); }

    public synchronized void setTitle(String t) { put("title", t); }
    public synchronized void setState(String s) { put("state", s); }
    public synchronized void setRecap(String r) { put("recap", r); }
    public synchronized void setProblem(String p) { put("problem", p); }
    public synchronized void markPlayed() { put("played", true); }
    public synchronized void setSpent(Spent s) { try { o.put("spent", s.meter("usd")); } catch (JSONException ignored) { } }
    public synchronized void setCast(Cast c) { try { o.put("cast", c.toJson().optJSONArray("members")); } catch (JSONException ignored) { } }

    public synchronized Cast cast() {
        try { return Cast.fromJson(new JSONObject().put("members", o.optJSONArray("cast"))); } catch (JSONException e) { return new Cast(); }
    }

    private void put(String k, Object v) { try { o.put(k, v); } catch (JSONException ignored) { } }

    /** One made page: the page itself, where its audio and picture are, the word timings, the voices used. */
    public static final class Made {
        public final StoryPage page; public final String audio, picture, note; public final JSONArray words; public final long durationMs; public final String chosen;
        Made(StoryPage page, String audio, String picture, JSONArray words, long durationMs, String note, String chosen) {
            this.page = page; this.audio = audio; this.picture = picture; this.words = words; this.durationMs = durationMs; this.note = note; this.chosen = chosen;
        }
    }

    public synchronized int pageCount() { JSONArray a = o.optJSONArray("pages"); return a == null ? 0 : a.length(); }

    public synchronized List<Made> pages() {
        List<Made> out = new ArrayList<>();
        JSONArray a = o.optJSONArray("pages");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p == null) continue;
            out.add(new Made(StoryPage.fromSaved(p.optJSONObject("page")), p.optString("audio"), p.optString("picture"),
                    p.optJSONArray("words"), p.optLong("duration_ms"), p.optString("note"), p.optString("chosen")));
        }
        return out;
    }

    public synchronized List<StoryPage> storyPages() {
        List<StoryPage> out = new ArrayList<>();
        for (Made m : pages()) out.add(m.page);
        return out;
    }

    /** Adds a made page and writes the book (every page is saved before the next starts). */
    public synchronized void addPage(StoryPage page, String audioFile, String pictureId, JSONArray words, long durationMs, String note, String chosen) throws IOException {
        try {
            o.getJSONArray("pages").put(new JSONObject().put("page", page.toJson()).put("audio", audioFile == null ? "" : audioFile)
                    .put("picture", pictureId == null ? "" : pictureId).put("words", words == null ? new JSONArray() : words)
                    .put("duration_ms", durationMs).put("note", note == null ? "" : note).put("chosen", chosen == null ? "" : chosen));
        } catch (JSONException e) { throw new IOException("page"); }
        save();
    }

    /** The picture of page {@code i} (a file in the book's folder) is recorded after it lands, which may be later than the text. */
    public synchronized void setPicture(int i, String pictureId) throws IOException {
        JSONArray a = o.optJSONArray("pages");
        if (a == null || i < 0 || i >= a.length()) return;
        try { a.getJSONObject(i).put("picture", pictureId == null ? "" : pictureId); } catch (JSONException e) { throw new IOException("picture"); }
        save();
    }

    public File audioFile(int i) { return new File(dir, String.format(java.util.Locale.ROOT, "page%02d.pcm", i + 1)); }
    public File pictureFile(int i) { return new File(dir, String.format(java.util.Locale.ROOT, "page%02d.jpg", i + 1)); }

    public synchronized JSONObject toJson() { return o; }
}
