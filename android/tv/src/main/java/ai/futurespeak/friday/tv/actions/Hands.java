package ai.futurespeak.friday.tv.actions;

/**
 * What the helper (Ad Break Guard 0.8+, "Agent Friday Helper") can do for Friday. Only
 * {@link ActionGate} may call {@link #launch} and {@link #back} (AC-4, checked by a test).
 */
public interface Hands {
    final class Foreground {
        public final String pkg; public final long seq;
        public Foreground(String pkg, long seq) { this.pkg = pkg; this.seq = seq; }
    }
    final class Launch {
        public final boolean ok; public final String reason; public final long seqBefore;
        public Launch(boolean ok, String reason, long seqBefore) { this.ok = ok; this.reason = reason; this.seqBefore = seqBefore; }
    }

    /** The app in front, or null when the helper doesn't answer. */
    Foreground foreground();

    /** Asks the helper to open {@code pkg}; null when the helper doesn't answer. */
    Launch launch(String pkg, String ref);

    /** One Back press; false when refused or not answering. */
    boolean back(String ref);
}
