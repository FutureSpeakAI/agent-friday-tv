package ai.futurespeak.friday.tv.kids;

/**
 * Entering kids mode closes any open grown-up window first (KM-E03), and both are receipted
 * with their origin. The helper owns the window (it does the same on its own Home press);
 * Friday does it too when she sees kids mode turn on, so the window can never
 * outlive the switch whichever way it happened. Pure, so it can be tested with fakes.
 */
public final class KidsEntry {
    /** The shared grown-up window (the helper's, through Friday's GrownUp). */
    public interface Window { boolean open(); void lock(); }
    /** Writes one receipt line: what happened, and where it came from. */
    public interface Recorder { void record(String what, String origin); }

    private final Window window;
    private final Recorder recorder;
    private Boolean last;   // null until the first reading

    public KidsEntry(Window window, Recorder recorder) { this.window = window; this.recorder = recorder; }

    /**
     * Called with each fresh reading of kids mode. Returns true when kids mode just turned on.
     * The first reading only sets the baseline, except that if kids mode is already on and a
     * grown-up window is open, the window is still closed (a window must not survive a restart into kids mode).
     */
    public boolean seen(boolean kids, String origin) {
        boolean turnedOn = kids && (last == null || !last);
        boolean first = last == null;
        last = kids;
        if (!turnedOn) return false;
        if (window.open()) {
            window.lock();                              // before anything else
            recorder.record("grown-up: locked (kids on)", origin);
        }
        if (!first) recorder.record("kids: on", origin);
        return !first;
    }
}
