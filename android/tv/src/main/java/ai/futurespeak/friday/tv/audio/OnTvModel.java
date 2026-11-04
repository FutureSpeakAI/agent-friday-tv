package ai.futurespeak.friday.tv.audio;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Friday's own speech model on the TV (design decision, 2026-11-02: no Google pack, nothing
 * de-Googled leaves the TV). The model is sherpa-onnx's streaming Zipformer English (20M,
 * int8), pushed from the PC over ADB into the app's private files (the setup wizard's speech step).
 * A {@code manifest.json} names each file with its size and SHA-256; the model counts as
 * installed only when every file matches. Pure Java (no Android), unit-tested.
 */
public final class OnTvModel {
    public static final String DIR = "stt/zipformer-en-20m";
    public static final String NAME = "Zipformer English (20M, int8)";
    /** The only files that may be written here (the shell hook refuses any other name). */
    public static final List<String> FILES = Collections.unmodifiableList(Arrays.asList(
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "manifest.json"));

    public enum State { READY, MISSING, INCOMPLETE, CORRUPT }

    public static final class Status {
        public final State state; public final long bytes; public final String detail;
        Status(State state, long bytes, String detail) { this.state = state; this.bytes = bytes; this.detail = detail; }
        public boolean ready() { return state == State.READY; }
        /** For Settings, Listening. */
        public String line() {
            switch (state) {
                case READY: return "On-TV speech: ready (" + NAME + ", " + Math.round(bytes / 1e6) + " MB)";
                case MISSING: return "On-TV speech: not installed on this TV yet";
                case INCOMPLETE: return "On-TV speech: partly copied (" + detail + ")";
                default: return "On-TV speech: the model files don't match (" + detail + ")";
            }
        }
    }

    private OnTvModel() {}

    public static File dir(File filesDir) { return new File(filesDir, DIR); }

    public static boolean allowedName(String name) { return name != null && FILES.contains(name); }

    /** Checks every file against the manifest (sizes always; hashes when {@code hashes} is true, which reads ~44 MB). */
    public static Status check(File filesDir, boolean hashes) {
        File d = dir(filesDir);
        File man = new File(d, "manifest.json");
        if (!d.isDirectory() || !man.isFile()) return new Status(State.MISSING, 0, "");
        JSONObject m;
        try { m = new JSONObject(new String(Files.readAllBytes(man.toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new Status(State.CORRUPT, 0, "manifest unreadable"); }
        long total = 0;
        for (String f : FILES) {
            if (f.equals("manifest.json")) continue;
            JSONObject e = m.optJSONObject(f);
            if (e == null) return new Status(State.CORRUPT, total, f + " not in manifest");
            File file = new File(d, f);
            if (!file.isFile()) return new Status(State.INCOMPLETE, total, f + " missing");
            if (file.length() != e.optLong("size", -1)) return new Status(State.INCOMPLETE, total, f + " size");
            if (hashes && !e.optString("sha256", "").equalsIgnoreCase(sha256(file))) return new Status(State.CORRUPT, total, f + " hash");
            total += file.length();
        }
        return new Status(State.READY, total, "");
    }

    static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
            StringBuilder s = new StringBuilder();
            for (byte x : md.digest()) s.append(String.format(Locale.ROOT, "%02x", x));
            return s.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Words in a transcript, for comparing recognizers (lowercase, letters, digits and apostrophes). */
    public static String[] words(String s) {
        if (s == null) return new String[0];
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ]", " ").trim();
        return t.isEmpty() ? new String[0] : t.split("\\s+");
    }

    /** Word error rate of {@code got} against {@code want} (Levenshtein over words / words wanted). */
    public static double wer(String want, String got) {
        String[] a = words(want), b = words(got);
        if (a.length == 0) return b.length == 0 ? 0 : 1;
        int[][] d = new int[a.length + 1][b.length + 1];
        for (int i = 0; i <= a.length; i++) d[i][0] = i;
        for (int j = 0; j <= b.length; j++) d[0][j] = j;
        for (int i = 1; i <= a.length; i++)
            for (int j = 1; j <= b.length; j++)
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + (a[i - 1].equals(b[j - 1]) ? 0 : 1));
        return (double) d[a.length][b.length] / a.length;
    }
}
