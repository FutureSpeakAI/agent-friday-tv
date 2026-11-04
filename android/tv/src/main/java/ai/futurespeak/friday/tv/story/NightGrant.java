package ai.futurespeak.friday.tv.story;

import ai.futurespeak.friday.tv.actions.Receipts;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * The "Story night" grant (ST-D1): made once by a grown-up with the PIN on a fixed screen, for
 * tonight, 7 days or 30 days, with the per-story estimate on it. Sealed with the receipts'
 * Keystore key: a row with a bad seal reads as no grant (TVAC §8.4). No voice can make one;
 * nothing here takes a PIN (the screen does, through the shared grown-up check). Pure Java
 * over two files.
 */
public final class NightGrant {
    public static final int[] HOURS = {12, 7 * 24, 30 * 24};
    public static final String[] LABELS = {"Tonight", "7 days", "30 days"};

    private final File json, sig;
    private final Receipts.Signer signer;

    public NightGrant(File storyRoot, Receipts.Signer signer) {
        json = new File(storyRoot, "night.json");
        sig = new File(storyRoot, "night.sig");
        this.signer = signer;
    }

    /** Until when the grant runs (wall clock ms), or 0 when there is none or the seal is wrong. */
    public synchronized long until() {
        try {
            if (!json.isFile() || !sig.isFile()) return 0;
            String body = new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8);
            String s = new String(Files.readAllBytes(sig.toPath()), StandardCharsets.UTF_8).trim();
            if (!s.equals(signer.sign(Receipts.sha256(body)))) return 0;
            return new JSONObject(body).optLong("until", 0);
        } catch (IOException | JSONException | RuntimeException e) { return 0; }
    }

    public boolean open(long now) { long u = until(); return u > now && u <= now + 31L * 24 * 3600_000; }

    /** Writes a new grant. Only the fixed screen calls this, after the PIN. */
    public synchronized void make(long now, int hours, double estPerStoryUsd) throws IOException {
        File root = json.getParentFile();
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("no story dir");
        try {
            String body = new JSONObject().put("v", 1).put("made", now).put("hours", hours).put("until", now + hours * 3600_000L)
                    .put("est_usd", estPerStoryUsd).toString();
            File t1 = new File(root, "night.tmp"), t2 = new File(root, "night.sig.tmp");
            Files.write(t1.toPath(), body.getBytes(StandardCharsets.UTF_8));
            Files.write(t2.toPath(), signer.sign(Receipts.sha256(body)).getBytes(StandardCharsets.UTF_8));
            Files.move(t1.toPath(), json.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.move(t2.toPath(), sig.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (JSONException e) { throw new IOException("grant"); }
    }

    public synchronized void revoke() { json.delete(); sig.delete(); }

    public static String words(long until, long now) {
        if (until <= now) return "No story night is open";
        long h = (until - now) / 3600_000L;
        if (h < 1) return "Story night: less than an hour left";
        if (h < 24) return "Story night: about " + h + (h == 1 ? " hour" : " hours") + " left";
        long d = h / 24;
        return "Story night: " + d + (d == 1 ? " day" : " days") + " left";
    }
}
