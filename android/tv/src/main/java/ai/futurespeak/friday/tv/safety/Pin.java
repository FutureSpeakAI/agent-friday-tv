package ai.futurespeak.friday.tv.safety;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The grown-up PIN: 4 to 8 digits, kept only as a salted PBKDF2 hash. After 5 wrong tries
 * the pad locks for 30 seconds, doubling each further lockout (cap 15 minutes).
 */
public final class Pin {
    public static final int ITERATIONS = 60_000;
    public static final int MAX_FREE_TRIES = 5;

    private Pin() {}

    public static boolean validFormat(String pin) {
        return pin != null && pin.matches("^[0-9]{4,8}$");
    }

    /** "v1$iterations$salt$hash", all Base64. */
    public static String hash(String pin) {
        if (!validFormat(pin)) throw new IllegalArgumentException("PIN must be 4 to 8 digits");
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return "v1$" + ITERATIONS + "$" + b64(salt) + "$" + b64(derive(pin, salt, ITERATIONS));
    }

    public static boolean verify(String pin, String stored) {
        if (pin == null || stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !"v1".equals(p[0])) return false;
        try {
            int it = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] want = Base64.getDecoder().decode(p[3]);
            return MessageDigest.isEqual(want, derive(pin, salt, it));
        } catch (Exception e) {
            return false;
        }
    }

    /** Milliseconds the pad stays locked after {@code failures} wrong tries in a row. */
    public static long lockoutMs(int failures) {
        if (failures < MAX_FREE_TRIES) return 0;
        int extra = failures - MAX_FREE_TRIES;                 // 0 -> 30 s, 1 -> 60 s, ...
        long ms = 30_000L << Math.min(extra, 5);
        return Math.min(ms, 15 * 60_000L);
    }

    private static byte[] derive(String pin, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
}
