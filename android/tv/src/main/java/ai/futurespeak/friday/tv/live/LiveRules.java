package ai.futurespeak.friday.tv.live;

import java.util.Locale;

/**
 * When a Gemini Live session must end, as plain rules. Unit-tested.
 * Ends on: Back, saying "stop", the session limit, 60 s of silence, the screen turning off
 * or the TV sleeping, kids mode changing, Friday leaving the front, or Google ending it.
 * On any ending the mic closes at once and the banner goes.
 */
public final class LiveRules {
    private LiveRules() {}

    public enum End { BACK, STOP_WORD, LIMIT, SILENCE, SCREEN_OFF, KIDS_CHANGED, LEFT_FRIDAY, GOOGLE, ERROR }

    public static final long SILENCE_MS = 60_000;
    /** The time limit is a setting, off by default (2026-11-02: no PIN, so no forced cap); when set, 5 to 30 minutes. */
    public static final int MIN_MINUTES = 5, MAX_MINUTES = 30, DEFAULT_MINUTES = 0;

    /** 0 means no limit. */
    public static int minutes(int setting) { return setting <= 0 ? 0 : Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, setting)); }

    /** The checks run every second while Live is on; null means carry on. */
    public static End check(long now, long startedAt, long lastVoiceAt, int limitMinutes, boolean kidsAtStart, boolean kidsNow) {
        if (kidsNow != kidsAtStart) return End.KIDS_CHANGED;
        if (minutes(limitMinutes) > 0 && now - startedAt >= minutes(limitMinutes) * 60_000L) return End.LIMIT;
        if (now - lastVoiceAt >= SILENCE_MS) return End.SILENCE;
        return null;
    }

    /** "Stop", "stop it", "Stop talking.", "OK, stop" end it; "don't stop" and "stop sign" don't. */
    public static boolean stopWord(String heard) {
        if (heard == null) return false;
        String h = heard.toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]", " ").replaceAll("\\s+", " ").trim();
        return h.matches("^(ok |okay |please |gemini |friday )?(stop|stop it|stop talking|stop now|stop please|stop listening|end|goodbye|bye)( please| now| gemini| friday)?$");
    }

    /** The mic opens only while the banner is on screen and the session hasn't ended. */
    public static boolean mayOpenMic(boolean bannerShowing, boolean ended) { return bannerShowing && !ended; }

    public static String endWords(End e) {
        switch (e) {
            case BACK: case STOP_WORD: return "Live conversation ended.";
            case LIMIT: return "That's the end of this live conversation (time limit).";
            case SILENCE: return "Live conversation ended after a minute of quiet.";
            case SCREEN_OFF: return "Live conversation ended because the screen turned off.";
            case KIDS_CHANGED: return "Live conversation ended because kids mode changed.";
            case LEFT_FRIDAY: return "Live conversation ended because Friday left the screen.";
            case GOOGLE: return "Google ended the live conversation.";
            default: return "The live conversation stopped.";
        }
    }

    /** Estimated cost shown on the start card and kept in receipts (an estimate: Google bills audio by tokens). */
    public static final double USD_PER_MINUTE = 0.03;
    public static double estimate(long ms) { return Math.round(ms / 60000.0 * USD_PER_MINUTE * 1000) / 1000.0; }
}
