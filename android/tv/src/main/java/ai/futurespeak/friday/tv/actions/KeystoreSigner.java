package ai.futurespeak.friday.tv.actions;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** Signs receipt hashes with an HMAC key that lives in the TV's Android Keystore (never exported). */
public final class KeystoreSigner implements Receipts.Signer {
    private static final String ALIAS = "friday-receipts-hmac-v1";
    private final SecretKey key;

    public KeystoreSigner() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(ALIAS)) {
                KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
                g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build());
                g.generateKey();
            }
            key = (SecretKey) ks.getKey(ALIAS, null);
        } catch (Exception e) {
            throw new IllegalStateException("receipt key unavailable", e);
        }
    }

    @Override public String sign(String hashHex) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(key);
            byte[] d = m.doFinal(hashHex.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(java.util.Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sign", e);
        }
    }
}
