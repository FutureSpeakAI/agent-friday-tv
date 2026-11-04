package ai.futurespeak.friday.tv.actions;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every action Friday takes on the TV leaves a receipt (spec §8.6, slice S0).
 *
 * One append-only log, {@code files/receipts/actions.jsonl}. Each line is a row in the desktop
 * ledger's shape {@code {v, seq, ts, kind, principal, rec, prev, hash, sig}}:
 *  - {@code hash} = SHA-256 over the row's canonical text without hash/sig (which includes
 *    {@code prev}, the previous row's hash), so editing, removing or reordering a line breaks
 *    the chain;
 *  - {@code sig} = HMAC over {@code hash} with a key from the TV's Keystore ({@link Signer}).
 * {@code head.json} holds the last seq and hash, signed too, so cutting lines off the end is
 * caught. Two rows per action: a {@code decision} before anything runs and an {@code outcome}
 * after. A decision with no outcome after {@link #UNCONFIRMED_MS} reads "unconfirmed", never
 * "done". Pure Java (the signer is injected), unit-tested.
 */
public final class Receipts {
    public static final int VERSION = 1;
    public static final long UNCONFIRMED_MS = 60_000;

    public interface Signer { String sign(String hashHex); }
    public interface Clock { long now(); }

    /** Result of {@link #verify()}. */
    public static final class Check {
        public final boolean ok; public final int rows; public final long brokenAtSeq; public final String reason;
        Check(boolean ok, int rows, long at, String reason) { this.ok = ok; this.rows = rows; this.brokenAtSeq = at; this.reason = reason; }
    }

    /** One decision with its outcome (if any), for "What Friday did". */
    public static final class Entry {
        public final JSONObject decision; public final JSONObject outcome; public final String state; public final long ts;
        Entry(JSONObject d, JSONObject o, String state, long ts) { decision = d; outcome = o; this.state = state; this.ts = ts; }
    }

    private final File dir, log, head;
    private final Signer signer;
    private final Clock clock;
    private long seq = 0;
    private String last = "genesis";

    public Receipts(File filesDir, Signer signer, Clock clock) {
        this.dir = new File(filesDir, "receipts");
        this.log = new File(dir, "actions.jsonl");
        this.head = new File(dir, "head.json");
        this.signer = signer;
        this.clock = clock;
        // continue the chain from the last row on disk
        List<JSONObject> rows = rows();
        if (!rows.isEmpty()) {
            JSONObject r = rows.get(rows.size() - 1);
            seq = r.optLong("seq");
            last = r.optString("hash");
        }
    }

    /** Writes a decision row; returns its id (used by the outcome and as the helper's ref). */
    public synchronized String decision(String principal, JSONObject rec) throws IOException {
        String id = "d" + (seq + 1) + "-" + Long.toHexString(clock.now());
        try { rec.put("id", id); } catch (JSONException e) { throw new IOException("rec"); }
        append("decision", principal, rec);
        return id;
    }

    /** Writes the outcome of a decision: executed | verified | unverified | failed | refused | stopped. */
    public synchronized void outcome(String decisionId, String principal, String result, String detail) throws IOException {
        try {
            append("outcome", principal, new JSONObject().put("decision", decisionId).put("result", result).put("detail", detail == null ? "" : detail));
        } catch (JSONException e) { throw new IOException("rec"); }
    }

    /** As above, with extra fields in the outcome (a paid tool's {@code meter}, the turn's {@code taint}). */
    public synchronized void outcome(String decisionId, String principal, String result, String detail, JSONObject extra) throws IOException {
        try {
            JSONObject rec = new JSONObject().put("decision", decisionId).put("result", result).put("detail", detail == null ? "" : detail);
            if (extra != null) for (java.util.Iterator<String> k = extra.keys(); k.hasNext(); ) { String n = k.next(); if (!rec.has(n)) rec.put(n, extra.get(n)); }
            append("outcome", principal, rec);
        } catch (JSONException e) { throw new IOException("rec"); }
    }

    private void append(String kind, String principal, JSONObject rec) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no receipts dir");
        try {
            JSONObject row = new JSONObject();
            row.put("v", VERSION).put("seq", seq + 1).put("ts", clock.now()).put("kind", kind)
                    .put("principal", principal).put("rec", rec).put("prev", last);
            String hash = sha256(canonical(row));
            row.put("hash", hash).put("sig", signer.sign(hash));
            try (FileOutputStream out = new FileOutputStream(log, true)) {
                out.write((row.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            seq++;
            last = hash;
            JSONObject h = new JSONObject().put("seq", seq).put("hash", hash);
            h.put("sig", signer.sign(sha256(h.toString())));
            File tmp = new File(dir, "head.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(h.toString().getBytes(StandardCharsets.UTF_8)); }
            Files.move(tmp.toPath(), head.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (JSONException e) {
            throw new IOException("encode");
        }
    }

    /** The row's text as hashed: everything except hash and sig, keys in a fixed order. */
    static String canonical(JSONObject row) throws JSONException {
        return new JSONObject()
                .put("v", row.get("v")).put("seq", row.get("seq")).put("ts", row.get("ts")).put("kind", row.get("kind"))
                .put("principal", row.get("principal")).put("rec", row.get("rec")).put("prev", row.get("prev"))
                .toString();
    }

    /** Walks the whole chain (AC-1). Any edit, removal, reorder or truncation is a break. */
    public synchronized Check verify() {
        List<String> lines;
        try { lines = log.exists() ? Files.readAllLines(log.toPath(), StandardCharsets.UTF_8) : Collections.emptyList(); }
        catch (IOException e) { return new Check(false, 0, 0, "can't read the log"); }
        String prev = "genesis";
        long expect = 1;
        int n = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            JSONObject r;
            try { r = new JSONObject(line); } catch (JSONException e) { return new Check(false, n, expect, "a line isn't readable"); }
            long s = r.optLong("seq", -1);
            if (s != expect) return new Check(false, n, expect, "a line is missing or out of order");
            if (!prev.equals(r.optString("prev"))) return new Check(false, n, s, "the chain is broken");
            String hash;
            try { hash = sha256(canonical(r)); } catch (JSONException e) { return new Check(false, n, s, "a line is incomplete"); }
            if (!hash.equals(r.optString("hash"))) return new Check(false, n, s, "a line was changed");
            if (!signer.sign(hash).equals(r.optString("sig"))) return new Check(false, n, s, "a signature doesn't match");
            prev = hash;
            expect++;
            n++;
        }
        if (n == 0 && !head.exists()) return new Check(true, 0, 0, "");
        try {
            JSONObject h = new JSONObject(new String(Files.readAllBytes(head.toPath()), StandardCharsets.UTF_8));
            String sig = h.optString("sig");
            JSONObject unsigned = new JSONObject().put("seq", h.optLong("seq")).put("hash", h.optString("hash"));
            if (!signer.sign(sha256(unsigned.toString())).equals(sig)) return new Check(false, n, 0, "the head isn't signed");
            if (h.optLong("seq") != n || !h.optString("hash").equals(prev)) return new Check(false, n, n, "lines were removed from the end");
        } catch (Exception e) {
            return new Check(false, n, 0, "the head is missing");
        }
        return new Check(true, n, 0, "");
    }

    /** Recent decisions with their outcome, newest first (for "What Friday did" and "what did you do?"). */
    public synchronized List<Entry> recent(int max) {
        List<JSONObject> rows = rows();
        java.util.Map<String, JSONObject> outcomes = new java.util.HashMap<>();
        for (JSONObject r : rows) if ("outcome".equals(r.optString("kind"))) {
            JSONObject rec = r.optJSONObject("rec");
            if (rec != null) outcomes.put(rec.optString("decision"), rec);
        }
        List<Entry> out = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0 && out.size() < max; i--) {
            JSONObject r = rows.get(i);
            if (!"decision".equals(r.optString("kind"))) continue;
            JSONObject d = r.optJSONObject("rec");
            if (d == null) continue;
            JSONObject o = outcomes.get(d.optString("id"));
            out.add(new Entry(d, o, state(o, r.optLong("ts"), clock.now()), r.optLong("ts")));
        }
        return out;
    }

    /** AC-2: no outcome yet → "pending" for a minute, then "unconfirmed", never "done". */
    static String state(JSONObject outcome, long decidedAt, long now) {
        if (outcome != null) return outcome.optString("result", "unconfirmed");
        return now - decidedAt >= UNCONFIRMED_MS ? "unconfirmed" : "pending";
    }

    private List<JSONObject> rows() {
        List<JSONObject> out = new ArrayList<>();
        try {
            if (!log.exists()) return out;
            for (String line : Files.readAllLines(log.toPath(), StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) continue;
                try { out.add(new JSONObject(line)); } catch (JSONException ignored) { }
            }
        } catch (IOException ignored) { }
        return out;
    }

    public static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(java.util.Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
