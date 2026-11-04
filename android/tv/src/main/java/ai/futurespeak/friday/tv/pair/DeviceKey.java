package ai.futurespeak.friday.tv.pair;

/**
 * The TV's identity to Friday desktop: a P-256 key whose private half never leaves the TV.
 * On the TV it is {@link KeystoreDeviceKey} (Android Keystore, non-exportable); tests use a
 * software key.
 */
public interface DeviceKey {
    /** The public key as X.509 SubjectPublicKeyInfo DER. */
    byte[] spki();

    /** SHA256withECDSA over msg, DER-encoded. */
    byte[] sign(byte[] msg) throws Exception;

    /** Delete the key: the TV forgets it was ever paired. */
    void forget();
}
