package ai.futurespeak.friday.tv.pair;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/** {@link DeviceKey} in the Android Keystore: generated on the TV, never exportable. */
public final class KeystoreDeviceKey implements DeviceKey {
    static final String ALIAS = "friday-desktop-device-v1";

    private KeyStore store() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return ks;
    }

    private void ensure() throws Exception {
        if (store().containsAlias(ALIAS)) return;
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build());
        g.generateKeyPair();
    }

    @Override public byte[] spki() {
        try {
            ensure();
            return store().getCertificate(ALIAS).getPublicKey().getEncoded();
        } catch (Exception e) { throw new IllegalStateException("device key unavailable", e); }
    }

    @Override public byte[] sign(byte[] msg) throws Exception {
        ensure();
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign((PrivateKey) store().getKey(ALIAS, null));
        s.update(msg);
        return s.sign();
    }

    @Override public void forget() {
        try { store().deleteEntry(ALIAS); } catch (Exception ignored) { }
    }
}
