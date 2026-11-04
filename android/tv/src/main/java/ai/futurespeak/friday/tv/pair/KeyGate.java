package ai.futurespeak.friday.tv.pair;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one place a TV key is released. {@code SecureStore.get} passes every decrypted key
 * through {@link #release}: while the desktop is the brain ({@link BrainMode.Mode#DESKTOP})
 * nothing is released, so no TV code path can call a provider with a TV key, whatever it
 * is. Standalone and unpaired, keys are released as before.
 *
 * Holding a key back looks to the caller exactly like a key that is not there: voice falls
 * back to the TV's own voice and a tool says it is unavailable, until the desktop-backed
 * path (slice T3) takes that call. Nothing is deleted.
 */
public final class KeyGate {
    private KeyGate() {}

    private static final AtomicInteger withheld = new AtomicInteger();

    public static String release(String name, String value) {
        if (value == null) return null;
        if (!BrainMode.keysAllowed()) {
            withheld.incrementAndGet();
            return null;
        }
        return value;
    }

    /** How many lookups were refused since start: the "What leaves this TV" page and tests read it. */
    public static int withheldCount() { return withheld.get(); }
}
