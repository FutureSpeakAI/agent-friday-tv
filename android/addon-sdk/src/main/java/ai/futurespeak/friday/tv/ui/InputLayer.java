package ai.futurespeak.friday.tv.ui;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;
import java.util.function.Predicate;

/**
 * The app's single key entry point. Every screen's Activity sends each raw key
 * event here from {@code dispatchKeyEvent} and does nothing else with raw keys:
 *
 *     private final InputLayer input = new InputLayer(this::route);
 *     @Override public boolean dispatchKeyEvent(KeyEvent e) { return input.feed(e); }
 *     private boolean route(KeyEvent e) { ... one handler per key ...; return super.dispatchKeyEvent(e); }
 *
 * {@link InputGate} turns the remote's bursts into one logical press; {@code route} sees only
 * logical events, so a key reaches exactly one handler once (native view, layer or WebView).
 * Keys that aren't remote buttons (e.g. a keyboard typing into a text field) pass straight through.
 */
public final class InputLayer {
    private static final String TAG = "FridayInput";
    private final InputGate gate = new InputGate();
    private final Predicate<KeyEvent> route;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable flush = this::flushDue;
    /** Logs each physical vs logical event (adb logcat -s FridayInput) for checking on the TV. */
    public static volatile boolean trace = true;

    public InputLayer(Predicate<KeyEvent> route) { this.route = route; }

    /** Remote buttons that burst: D-pad, OK/Enter, Back, the voice/search keys, media keys. */
    static boolean gated(int k) {
        return (k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_CENTER) || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE
                || k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST
                || k == KeyEvent.KEYCODE_ASSIST || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY
                || k == KeyEvent.KEYCODE_MEDIA_PAUSE || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || k == KeyEvent.KEYCODE_MEDIA_REWIND;
    }

    public boolean feed(KeyEvent e) {
        int k = e.getKeyCode();
        if (!gated(k) || (e.getAction() != KeyEvent.ACTION_DOWN && e.getAction() != KeyEvent.ACTION_UP)) return route.test(e);
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        List<InputGate.Out> outs = gate.feed(k, down, e.getRepeatCount(), e.getEventTime());
        if (trace) Log.v(TAG, "raw " + (down ? "D" : "U") + " " + KeyEvent.keyCodeToString(k) + " rc=" + e.getRepeatCount() + " -> " + outs);
        boolean result = true;   // a raw event that is part of a burst is swallowed here
        for (InputGate.Out o : outs) {
            if (o.down && down) {
                // The press itself (or an arrow repeat on a real hold): route the original event
                // and return its result, so Android's own focus movement still runs on arrows.
                if (trace) Log.i(TAG, "key DOWN " + KeyEvent.keyCodeToString(k) + (o.repeat > 0 ? " repeat " + o.repeat : ""));
                result = route.test(e);
            } else {
                send(o, e);
            }
        }
        schedule();
        return result;
    }

    private void send(InputGate.Out o, KeyEvent like) {
        long now = SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(now, now, o.down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, o.key, o.repeat,
                like.getMetaState(), like.getDeviceId(), like.getScanCode(), like.getFlags() | KeyEvent.FLAG_FROM_SYSTEM, like.getSource());
        if (trace) Log.i(TAG, "key " + (o.down ? "DOWN " : "UP ") + KeyEvent.keyCodeToString(o.key) + (o.repeat > 0 ? " repeat " + o.repeat : ""));
        route.test(ev);
    }

    private void schedule() {
        h.removeCallbacks(flush);
        long due = gate.nextDue();
        if (due != Long.MAX_VALUE) h.postAtTime(flush, due + 1);
    }

    private void flushDue() {
        List<InputGate.Out> outs = gate.due(SystemClock.uptimeMillis());
        for (InputGate.Out o : outs) {
            long now = SystemClock.uptimeMillis();
            KeyEvent ev = new KeyEvent(now, now, KeyEvent.ACTION_UP, o.key, 0);
            if (trace) Log.i(TAG, "key UP " + KeyEvent.keyCodeToString(o.key));
            route.test(ev);
        }
        schedule();
    }

    /** The window lost focus mid-hold: drop the state (no stray releases later). */
    public void reset() { h.removeCallbacks(flush); gate.reset(); }
}
