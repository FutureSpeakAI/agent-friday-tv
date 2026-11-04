package ai.futurespeak.friday.tv.pair;

/**
 * Who is answering on this TV right now, and so whether the TV may use its own keys.
 *
 * The rule (2026-11-02): every key stays sealed on the TV whether or not it is paired.
 * - Paired and the PC reachable ({@link Mode#DESKTOP}): the TV uses none of its keys; the
 *   desktop makes every call.
 * - Paired and the PC unreachable ({@link Mode#STANDALONE}), or unpaired ({@link Mode#UNPAIRED}):
 *   the TV is fully standalone, Claude plus all its tools, as before pairing existed.
 *
 * The mode is set by {@code brain.FailoverBrain} at the start of every turn and whenever it
 * has to switch; {@link KeyGate} reads it on every key lookup.
 */
public final class BrainMode {
    private BrainMode() {}

    public enum Mode {
        UNPAIRED("Claude"),
        DESKTOP("Friday desktop"),
        STANDALONE("Claude on this TV (PC unreachable)");

        public final String label;
        Mode(String label) { this.label = label; }
    }

    private static volatile Mode current = Mode.UNPAIRED;

    public static Mode current() { return current; }

    public static void set(Mode m) { if (m != null) current = m; }

    /** The TV's own keys may be used only when the desktop is not the brain. */
    public static boolean keysAllowed() { return current != Mode.DESKTOP; }
}
