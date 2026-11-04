package ai.futurespeak.friday.tv.actions;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Slice S2 (spec §8.5 rows 3 and the tier-2 "Play" line): what's playing, and play / pause /
 * skip. Two ways in, best first:
 *  - the helper's media-session reader,
 *    which can read the state back, so outcomes can be "verified";
 *  - a media key from Friday itself ({@code AudioManager.dispatchMediaKeyEvent}, public, no
 *    permission), which reaches the app that last played but can't read anything back, so
 *    outcomes stay "unverified" and the words say so.
 * Only {@link ActionGate} may call {@link #send} (checked by a test).
 */
public interface Player {
    /** The commands Friday may send. Nothing else exists. */
    List<String> COMMANDS = Collections.unmodifiableList(Arrays.asList("pause", "resume", "next", "previous", "rewind", "forward"));

    /** What a media session reports. Title and artist are untrusted data (spec §9.3). */
    final class Now {
        public final String pkg, state, title, artist;
        public final long positionMs;
        public Now(String pkg, String state, String title, String artist, long positionMs) {
            this.pkg = pkg == null ? "" : pkg; this.state = state == null ? "none" : state;
            this.title = title == null ? "" : title; this.artist = artist == null ? "" : artist; this.positionMs = positionMs;
        }
        public boolean playing() { return "playing".equals(state) || "buffering".equals(state); }
    }

    /** True when {@link #now} can read sessions (the helper's reader is there). */
    boolean canRead();

    /** The active session, or null when nothing is known. */
    Now now();

    /** Sends one command to {@code pkg}'s session (or, for the key path, to whoever last played). False when it couldn't be sent. */
    boolean send(String command, String pkg, String ref);

    /** The state a command should lead to, for verification: pause → paused, resume → playing; the rest only change the position or item. */
    static String expected(String command) {
        switch (command) {
            case "pause": return "paused";
            case "resume": return "playing";
            default: return null;
        }
    }

    /** The command that undoes {@code command} (spec §8.6 undo table), or null. */
    static String undo(String command) {
        switch (command) {
            case "pause": return "resume";
            case "resume": return "pause";
            case "next": return "previous";
            case "rewind": return "forward";
            case "forward": return "rewind";
            default: return null;
        }
    }

    /** People say many things: "stop" means pause, "play"/"continue"/"unpause" mean resume, "skip" means next. */
    static String command(String said) {
        if (said == null) return null;
        String s = said.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z ]", "");
        switch (s) {
            case "pause": case "stop": case "hold": return "pause";
            case "resume": case "play": case "continue": case "unpause": case "keep going": return "resume";
            case "next": case "skip": case "skip ahead": case "next episode": case "next video": case "next song": return "next";
            case "previous": case "back one": case "previous episode": case "previous video": case "last song": return "previous";
            case "rewind": case "go back a bit": return "rewind";
            case "forward": case "fast forward": case "ahead": return "forward";
            default: return COMMANDS.contains(s) ? s : null;
        }
    }

    /** A session state name from Android's PlaybackState int (kept here so the helper and Friday agree). */
    static String stateName(int s) {
        switch (s) {
            case 3: return "playing";
            case 2: return "paused";
            case 1: return "stopped";
            case 6: case 8: return "buffering";
            case 7: return "error";
            default: return "none";
        }
    }
}
