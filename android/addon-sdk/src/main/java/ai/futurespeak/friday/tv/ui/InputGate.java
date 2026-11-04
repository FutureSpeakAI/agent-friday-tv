package ai.futurespeak.friday.tv.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One remote press = one key event, for the whole app. Pure logic (no Android types), tested
 * by replaying the TCL remote's real timings (src/test/resources/tcl_remote_keys.csv).
 *
 * What a TCL Google TV remote really sends (recorded 2026-11-02): no auto-repeat at all. A
 * held button arrives as a train of separate DOWN/UP pairs; between one UP and the next DOWN
 * of the same key there are 5 to 136 ms. Deliberate presses of the same key are at least
 * ~300 ms apart. So:
 *
 *  - A DOWN within {@link #BURST_GAP_MS} of the same key's UP (or any DOWN while the key is
 *    already down, or a DOWN with a repeat count) continues the same physical hold.
 *  - Each hold gives exactly one logical DOWN (repeatCount 0) and one logical UP, delivered
 *    {@link #BURST_GAP_MS} after the last physical UP, once it's sure the hold has ended.
 *  - Only the arrows repeat, and only for a real hold: after {@link #REPEAT_DELAY_MS}, one
 *    logical DOWN (repeatCount 1, 2, ...) every {@link #REPEAT_EVERY_MS}, never faster.
 */
public final class InputGate {
    public static final long BURST_GAP_MS = 180;
    public static final long REPEAT_DELAY_MS = 400;
    public static final long REPEAT_EVERY_MS = 140;

    /** A logical event to dispatch. */
    public static final class Out {
        public final int key; public final boolean down; public final int repeat; public final long time;
        Out(int key, boolean down, int repeat, long time) { this.key = key; this.down = down; this.repeat = repeat; this.time = time; }
        @Override public String toString() { return (down ? "D" : "U") + key + (repeat > 0 ? "r" + repeat : ""); }
    }

    private static final class Hold {
        boolean physicallyDown, logicallyDown;
        long start, lastUp = Long.MIN_VALUE / 2, lastRepeat;
        int repeats;
    }

    private final Map<Integer, Hold> holds = new HashMap<>();

    /** Arrow keys (KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT = 19..22) may repeat on a real hold. */
    public static boolean repeats(int key) { return key >= 19 && key <= 22; }

    /**
     * Feed one physical event. Returns the logical events to dispatch now (usually zero or one).
     * Call {@link #due(long)} when {@link #nextDue()} comes up to deliver pending releases.
     */
    public List<Out> feed(int key, boolean down, int repeatCount, long t) {
        List<Out> out = new ArrayList<>();
        Hold h = holds.get(key);
        if (h == null) { h = new Hold(); holds.put(key, h); }
        if (down) {
            boolean continues = h.logicallyDown && (h.physicallyDown || repeatCount > 0 || t - h.lastUp < BURST_GAP_MS);
            h.physicallyDown = true;
            if (!continues) {
                if (h.logicallyDown) out.add(new Out(key, false, 0, t));   // a stale hold that never got its release
                h.logicallyDown = true;
                h.start = t;
                h.lastRepeat = t;
                h.repeats = 0;
                out.add(new Out(key, true, 0, t));
            } else if (repeats(key) && t - h.start >= REPEAT_DELAY_MS && t - h.lastRepeat >= REPEAT_EVERY_MS) {
                h.lastRepeat = t;
                out.add(new Out(key, true, ++h.repeats, t));
            }
        } else {
            h.physicallyDown = false;
            h.lastUp = t;
        }
        return out;
    }

    /** The earliest time a pending release (or nothing: Long.MAX_VALUE) is due. */
    public long nextDue() {
        long best = Long.MAX_VALUE;
        for (Hold h : holds.values()) if (h.logicallyDown && !h.physicallyDown) best = Math.min(best, h.lastUp + BURST_GAP_MS);
        return best;
    }

    /** Releases every hold whose quiet period has passed by {@code now}. */
    public List<Out> due(long now) {
        List<Out> out = new ArrayList<>();
        for (Map.Entry<Integer, Hold> e : holds.entrySet()) {
            Hold h = e.getValue();
            if (h.logicallyDown && !h.physicallyDown && now - h.lastUp >= BURST_GAP_MS) {
                h.logicallyDown = false;
                out.add(new Out(e.getKey(), false, 0, h.lastUp + BURST_GAP_MS));
            }
        }
        return out;
    }

    /** Forget everything (the app lost focus mid-hold). */
    public void reset() { holds.clear(); }
}
