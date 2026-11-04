package ai.futurespeak.friday.tv.actions;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * The fallback way to play and pause (spec §4.3, AC-17): a media key through
 * {@code AudioManager.dispatchMediaKeyEvent}. Public, no permission; it reaches "the current
 * media key event consumer" (the app that last played). It can't read anything back, so every
 * outcome through it is "unverified" and Friday says so.
 */
public final class KeyPlayer implements Player {
    private final AudioManager audio;

    public KeyPlayer(Context c) { audio = (AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE); }

    static int keyFor(String command) {
        switch (command) {
            case "pause": return KeyEvent.KEYCODE_MEDIA_PAUSE;
            case "resume": return KeyEvent.KEYCODE_MEDIA_PLAY;
            case "next": return KeyEvent.KEYCODE_MEDIA_NEXT;
            case "previous": return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case "rewind": return KeyEvent.KEYCODE_MEDIA_REWIND;
            case "forward": return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            default: return -1;
        }
    }

    @Override public boolean canRead() { return false; }
    @Override public Now now() { return null; }

    @Override public boolean send(String command, String pkg, String ref) {
        int k = keyFor(command);
        if (k < 0 || audio == null) return false;
        long t = SystemClock.uptimeMillis();
        try {
            audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, k, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, k, 0));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
