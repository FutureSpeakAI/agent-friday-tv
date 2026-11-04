package org.tvprivacy.adbreak;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Kids slice 1 (K2, K-D5): the bedtime schedule, wind-down and lockout rules, as plain Java.
 *
 * Times are minutes after midnight, -1 = not set. Nothing is pre-set (K-D5).
 * A "night" is named by its evening. Weekend nights are Friday and Saturday evenings; their
 * morning (Saturday and Sunday) uses the weekend morning time.
 * Wind-down/bedtime before 12:00 mean after midnight. The morning is always the next day.
 *
 * Lockout (spec section 6.3, with T = wind-down; if only bedtime is set, T = bedtime):
 *  - warnings at T-15, T-5, T-0 ("last one: this episode can finish");
 *  - after T: lock once playback has been stopped for 30 s, or at T+25 min, or at bedtime,
 *    whichever comes first;
 *  - the lockout lasts until the morning (or, with no morning set, until 12:00 next day or a
 *    grown-up leaves kids mode);
 *  - a grown-up Extend (PIN) lifts it until extendUntil; then it applies again.
 */
final class KidsSchedule {
    static final long MIN = 60_000L;
    static final long LAST_ONE_CEILING_MS = 25 * MIN;
    static final long IDLE_TO_LOCK_MS = 30_000L;
    static final int NOON = 12 * 60;

    static final class Times {
        final int wind, bed, morning;
        Times(int wind, int bed, int morning) { this.wind = wind; this.bed = bed; this.morning = morning; }
        boolean any() { return wind >= 0 || bed >= 0; }
        static final Times NONE = new Times(-1, -1, -1);
    }

    final Times weekday, weekend;
    final ZoneId zone;

    KidsSchedule(Times weekday, Times weekend, ZoneId zone) { this.weekday = weekday; this.weekend = weekend; this.zone = zone; }

    static boolean weekendEvening(LocalDate evening) {
        DayOfWeek d = evening.getDayOfWeek();
        return d == DayOfWeek.FRIDAY || d == DayOfWeek.SATURDAY;
    }

    Times timesFor(LocalDate evening) { return weekendEvening(evening) ? weekend : weekday; }

    /** The night we are in: last night if it's before last night's morning boundary, else tonight. */
    LocalDate eveningOf(long nowMs) {
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        LocalDate yesterday = now.toLocalDate().minusDays(1);
        Times y = timesFor(yesterday);
        int boundary = y.morning >= 0 ? y.morning : NOON;
        int nowMin = now.getHour() * 60 + now.getMinute();
        return nowMin < boundary ? yesterday : now.toLocalDate();
    }

    private long at(LocalDate evening, int minute, boolean nextDay) {
        LocalDate d = nextDay ? evening.plusDays(1) : evening;
        return d.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli();
    }

    /** Wall-clock ms; -1 if not set. Times before noon belong to after midnight. */
    long windDownAt(long nowMs) { LocalDate e = eveningOf(nowMs); Times t = timesFor(e); return t.wind < 0 ? -1 : at(e, t.wind, t.wind < NOON); }
    long bedtimeAt(long nowMs) { LocalDate e = eveningOf(nowMs); Times t = timesFor(e); return t.bed < 0 ? -1 : at(e, t.bed, t.bed < NOON); }
    long morningAt(long nowMs) {
        LocalDate e = eveningOf(nowMs); Times t = timesFor(e);
        return (t.morning < 0 || !t.any()) ? -1 : at(e, t.morning, true);
    }
    /** End of tonight's lockout: the morning, or 12:00 next day if no morning is set. -1 if no schedule. */
    long nightEnds(long nowMs) {
        LocalDate e = eveningOf(nowMs); Times t = timesFor(e);
        if (!t.any()) return -1;
        return at(e, t.morning >= 0 ? t.morning : NOON, true);
    }

    /** T: wind-down if set, else bedtime, else -1. */
    long t0(long nowMs) { long w = windDownAt(nowMs); return w >= 0 ? w : bedtimeAt(nowMs); }

    /** Key for "which night" (used to remember warnings and the lockout). */
    String nightKey(long nowMs) { return eveningOf(nowMs).toString(); }

    /**
     * Should the lockout be in force now?
     * @param idleSinceMs wall-clock ms since when nothing has been playing (or -1 if playing now)
     */
    boolean lockNow(long nowMs, long idleSinceMs, long extendUntilMs) {
        long t = t0(nowMs);
        if (t < 0) return false;
        long end = nightEnds(nowMs);
        if (nowMs < t || nowMs >= end) return false;
        if (extendUntilMs > nowMs) return false;
        long bed = bedtimeAt(nowMs);
        if (bed >= 0 && nowMs >= bed) return true;
        if (nowMs >= t + LAST_ONE_CEILING_MS) return true;
        if (idleSinceMs >= 0 && nowMs - Math.max(idleSinceMs, t) >= IDLE_TO_LOCK_MS) return true;
        // An extension that has run out: lock again straight away.
        return extendUntilMs > 0 && extendUntilMs >= t && extendUntilMs <= nowMs;
    }

    /** Which wind-down warning is due now: 15, 5, 0, or -1. The caller remembers which it has shown. */
    int warningDue(long nowMs) {
        long t = t0(nowMs);
        if (t < 0) return -1;
        long d = t - nowMs;
        if (d <= 0 && d > -LAST_ONE_CEILING_MS) return 0;
        if (d > 0 && d <= 5 * MIN) return 5;
        if (d > 5 * MIN && d <= 15 * MIN) return 15;
        return -1;
    }
}
