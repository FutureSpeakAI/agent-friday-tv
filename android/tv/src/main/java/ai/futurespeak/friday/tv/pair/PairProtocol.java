package ai.futurespeak.friday.tv.pair;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * The arithmetic of pairing with Friday desktop, byte for byte the same as the desktop's
 * {@code tv_gateway/pairing.py} (a shared test vector pins both).
 *
 * The TV met a certificate on its TLS connection; {@code pin} is the SHA-256 of that
 * certificate's SubjectPublicKeyInfo. The desktop committed to its nonce Nd before seeing
 * the TV's Nt, so a machine in the middle, which shows each side a different key, makes the
 * two screens show different digits and gets one guess at matching them.
 */
public final class PairProtocol {
    private PairProtocol() {}

    static final byte[] SAS_CONTEXT = "friday-tv-pair-v1|".getBytes(StandardCharsets.US_ASCII);
    static final byte[] COMMIT_CONTEXT = "friday-tv-pair-commit-v1|".getBytes(StandardCharsets.US_ASCII);
    static final byte[] SESSION_CONTEXT = "friday-tv-session-v1|".getBytes(StandardCharsets.US_ASCII);

    /** Hex SHA-256 of H(context || pin || device key || nd): the desktop's commitment. */
    public static String commitment(String pinHex, byte[] deviceSpki, byte[] nd) {
        return hex(sha256(COMMIT_CONTEXT, unhex(pinHex), deviceSpki, nd));
    }

    /** True only when the revealed Nd matches what the desktop committed to. */
    public static boolean commitmentHolds(String pinHex, byte[] deviceSpki, byte[] nd, String commitHex) {
        byte[] want = unhex(commitHex), got = unhex(commitment(pinHex, deviceSpki, nd));
        return MessageDigest.isEqual(want, got);
    }

    /** The six digits both screens show. */
    public static String sas(String pinHex, byte[] deviceSpki, byte[] nt, byte[] nd) {
        byte[] h = sha256(SAS_CONTEXT, unhex(pinHex), deviceSpki, nt, nd);
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xff);
        long code = Long.remainderUnsigned(v, 1_000_000L);
        return String.format(java.util.Locale.ROOT, "%06d", code);
    }

    /** "067201" is shown and spoken as "067 201". */
    public static String spoken(String code) { return code.substring(0, 3) + " " + code.substring(3); }

    /** What the TV signs to open a session: context || nonce || "|" || device id. */
    public static byte[] sessionMessage(String nonce, String deviceId) {
        return concat(SESSION_CONTEXT, nonce.getBytes(StandardCharsets.US_ASCII),
                "|".getBytes(StandardCharsets.US_ASCII), deviceId.getBytes(StandardCharsets.US_ASCII));
    }

    /** A device's id on the desktop: the first 16 hex of the SHA-256 of its public key. */
    public static String deviceId(byte[] spki) { return hex(sha256(spki)).substring(0, 16); }

    public static String spkiPin(byte[] spki) { return hex(sha256(spki)); }

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        if (s == null || (s.length() & 1) != 0) throw new IllegalArgumentException("bad hex");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static boolean sameBytes(byte[] a, byte[] b) { return Arrays.equals(a, b); }
}
