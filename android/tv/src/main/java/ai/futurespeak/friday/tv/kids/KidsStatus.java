package ai.futurespeak.friday.tv.kids;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Kids mode as the helper reports it (Ad Break Guard owns the clock, the schedule, the
 * lockout and the PIN; Friday only reads them). Built from the reply of the helper's
 * {@code kidsStatus} or, from a helper that predates it, {@code status}: then the
 * schedule fields are unknown. Pure: no Android types, so it can be tested.
 */
public final class KidsStatus {
    public static final long UNKNOWN = -1;

    /** Kids mode is on (fail closed: true when the helper doesn't answer). */
    public final boolean kids;
    /** Daily TV time left in ms; {@link Long#MAX_VALUE} = no limit; {@link #UNKNOWN}. */
    public final long remainingMs;
    /** Wall-clock times of today's wind-down and bedtime, and tomorrow's morning; {@link #UNKNOWN} if none set. */
    public final long windDownAt, bedtimeAt, morningAt;
    public final boolean quiet, lockedOut;

    public KidsStatus(boolean kids, long remainingMs, long windDownAt, long bedtimeAt, long morningAt, boolean quiet, boolean lockedOut) {
        this.kids = kids; this.remainingMs = remainingMs; this.windDownAt = windDownAt; this.bedtimeAt = bedtimeAt;
        this.morningAt = morningAt; this.quiet = quiet; this.lockedOut = lockedOut;
    }

    /** The helper didn't answer: kids mode assumed on, nothing else known. */
    public static final KidsStatus FAIL_CLOSED = new KidsStatus(true, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, false, false);

    /** From a reply's values (a Bundle's keys, copied into a map). Missing keys are unknown. */
    public static KidsStatus from(Map<String, Object> m) {
        if (m == null) return FAIL_CLOSED;
        return new KidsStatus(bool(m, "kids", true), num(m, "remainingMs"), num(m, "windDownAt"), num(m, "bedtimeAt"),
                num(m, "morningAt"), bool(m, "quiet", false), bool(m, "lockedOut", false));
    }

    private static boolean bool(Map<String, Object> m, String k, boolean d) { Object v = m.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    private static long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (!(v instanceof Number)) return UNKNOWN;
        long n = ((Number) v).longValue();
        return n < 0 && n != UNKNOWN ? UNKNOWN : n;
    }

    // --------------------------------------------------------------- words -----

    /** Time left in a child's words ("about an hour"), or null if unknown. */
    public static String timeLeftWords(long remainingMs) {
        if (remainingMs == UNKNOWN || remainingMs < 0) return null;
        if (remainingMs == Long.MAX_VALUE) return "no time limit today";
        long min = Math.round(remainingMs / 60_000.0);
        if (min <= 0) return "no TV time left today";
        if (min <= 5) return "just a few minutes";
        if (min < 50) return "about " + (Math.round(min / 5.0) * 5) + " minutes";
        if (min < 75) return "about an hour";
        if (min < 105) return "about an hour and a half";
        return "about " + Math.round(min / 60.0) + " hours";
    }

    /** "Wind-down at 7:15 PM", or "Wind-down in 5 minutes" when it's close; null if none set. */
    public static String windDownWords(long now, long windDownAt, TimeZone tz) {
        if (windDownAt == UNKNOWN || windDownAt <= 0) return null;
        long left = windDownAt - now;
        if (left <= 0) return "Wind-down time";
        if (left <= 15 * 60_000L) {
            long m = Math.max(1, Math.round(left / 60_000.0));
            return "Wind-down in " + m + (m == 1 ? " minute" : " minutes");
        }
        SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.US);
        f.setTimeZone(tz);
        return "Wind-down at " + f.format(new Date(windDownAt));
    }

    /**
     * Which wind-down warning Friday should say now, if any: 15, 5 or 0 (minutes before), each
     * once. {@code alreadySaid} is the last warning said for this wind-down (or -1). Returns -1
     * when nothing is due. The helper's own strip says the same; Friday says it only while she
     * is in front (spec §6.3).
     */
    public static int warningDue(long now, long windDownAt, int alreadySaid) {
        if (windDownAt == UNKNOWN || windDownAt <= 0) return -1;
        long left = windDownAt - now;
        if (left < -60_000L) return -1;                 // long past: the helper's lockout has it
        int due = left <= 0 ? 0 : left <= 5 * 60_000L ? 5 : left <= 15 * 60_000L ? 15 : -1;
        if (due < 0) return -1;
        if (alreadySaid >= 0 && alreadySaid <= due) return -1;   // this one (or a later one) was said
        return due;
    }

    /** What Friday says for a warning: kind, and never a promise of more time. */
    public static String warningLine(int minutes) {
        if (minutes == 15) return "Fifteen minutes till wind-down.";
        if (minutes == 5) return "Five minutes till wind-down. Time to pick the last thing.";
        if (minutes == 0) return "It's wind-down time. Let's get ready for bed.";
        return null;
    }
}
