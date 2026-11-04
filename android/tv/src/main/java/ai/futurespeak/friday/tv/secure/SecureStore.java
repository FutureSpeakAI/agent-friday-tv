package ai.futurespeak.friday.tv.secure;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * API keys on the TV: AES-256-GCM under a key that lives in the Android Keystore and can
 * never be exported. Only ciphertext is written (app-private preferences, excluded from
 * backup). Values are never logged.
 */
public final class SecureStore {
    public static final String ANTHROPIC = "anthropic";
    public static final String ELEVENLABS = "elevenlabs";
    // Tool keys, on the TV before pairing by design (2026-11-02).
    public static final String FIRECRAWL = "firecrawl";
    public static final String KIE = "kie";
    public static final String OPENROUTER = "openrouter";
    public static final String GEMINI = "gemini";
    /** Every name the import may write, the status lists and the delete clears. Nothing else is accepted. */
    public static final String[] ALL = {ANTHROPIC, ELEVENLABS, FIRECRAWL, KIE, OPENROUTER, GEMINI};

    private static final String ALIAS = "fridaytv.secrets.v1";
    private static final String PREFS = "secure";
    private final SharedPreferences prefs;

    public SecureStore(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void put(String name, String value) {
        if (value == null || value.isEmpty()) { remove(name); return; }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[1 + iv.length + ct.length];
            blob[0] = (byte) iv.length;
            System.arraycopy(iv, 0, blob, 1, iv.length);
            System.arraycopy(ct, 0, blob, 1 + iv.length, ct.length);
            prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).commit();
        } catch (Exception e) {
            throw new IllegalStateException("could not seal secret (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** The key, if the TV may use it now: while paired with a reachable Friday desktop the
     *  TV uses none of its keys (pair/KeyGate), though they stay sealed here. */
    public synchronized String get(String name) {
        return ai.futurespeak.friday.tv.pair.KeyGate.release(name, open(name));
    }

    private String open(String name) {
        String s = prefs.getString(name, null);
        if (s == null) return null;
        try {
            byte[] blob = Base64.decode(s, Base64.NO_WRAP);
            int ivLen = blob[0];
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, blob, 1, ivLen));
            byte[] pt = c.doFinal(blob, 1 + ivLen, blob.length - 1 - ivLen);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;   // unreadable (e.g. Keystore reset): treated as missing
        }
    }

    /** Whether the key is stored here, whoever the brain is right now. */
    public boolean has(String name) { return open(name) != null; }

    public synchronized void remove(String name) { prefs.edit().remove(name).commit(); }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }
}
