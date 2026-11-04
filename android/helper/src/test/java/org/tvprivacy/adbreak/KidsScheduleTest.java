package org.tvprivacy.adbreak;

import static org.junit.Assert.*;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** K-D5 schedule, wind-down and lockout (AC-K6 timing rules), with a fake clock. */
public class KidsScheduleTest {
    static final ZoneId Z = ZoneId.of("America/Winnipeg");   // a US/Canada Central zone with the same daylight-saving rules
    static final KidsSchedule.Times WEEKDAY = new KidsSchedule.Times(19 * 60, 19 * 60 + 30, 7 * 60);      // 7:00 / 7:30 pm / 7:00 am
    static final KidsSchedule.Times WEEKEND = new KidsSchedule.Times(20 * 60, 21 * 60, 8 * 60);           // 8:00 / 9:00 pm / 8:00 am
    static final KidsSchedule S = new KidsSchedule(WEEKDAY, WEEKEND, Z);

    static long t(int y, int mo, int d, int h, int mi) { return LocalDateTime.of(y, mo, d, h, mi).atZone(Z).toInstant().toEpochMilli(); }
    static long t(int y, int mo, int d, int h, int mi, int s) { return LocalDateTime.of(y, mo, d, h, mi, s).atZone(Z).toInstant().toEpochMilli(); }

    // 2026-11-02 is a Monday; 11-06 Friday; 11-07 Saturday; 11-08 Sunday.

    @Test public void nothingPreset_noSchedule_noLockout() {
        KidsSchedule none = new KidsSchedule(KidsSchedule.Times.NONE, KidsSchedule.Times.NONE, Z);
        long now = t(2026, 11, 2, 21, 0);
        assertEquals(-1, none.windDownAt(now));
        assertEquals(-1, none.bedtimeAt(now));
        assertEquals(-1, none.morningAt(now));
        assertEquals(-1, none.t0(now));
        assertFalse(none.lockNow(now, now - 600_000, 0));
        assertEquals(-1, none.warningDue(now));
    }

    @Test public void weekdayTimes() {
        long now = t(2026, 11, 2, 18, 0);
        assertEquals(t(2026, 11, 2, 19, 0), S.windDownAt(now));
        assertEquals(t(2026, 11, 2, 19, 30), S.bedtimeAt(now));
        assertEquals(t(2026, 11, 3, 7, 0), S.morningAt(now));
    }

    /** The weekend-ignored mutant must turn this red. */
    @Test public void fridayAndSaturdayNights_useWeekendTimes_sundayDoesnt() {
        assertEquals(t(2026, 11, 6, 21, 0), S.bedtimeAt(t(2026, 11, 6, 18, 0)));   // Friday night
        assertEquals(t(2026, 11, 7, 21, 0), S.bedtimeAt(t(2026, 11, 7, 18, 0)));   // Saturday night
        assertEquals(t(2026, 11, 8, 19, 30), S.bedtimeAt(t(2026, 11, 8, 18, 0)));  // Sunday night: weekday
        assertEquals(t(2026, 11, 8, 8, 0), S.morningAt(t(2026, 11, 7, 22, 0)));    // Saturday night -> Sunday 8:00
        assertEquals(t(2026, 11, 7, 8, 0), S.morningAt(t(2026, 11, 7, 6, 0)));     // 6 am Saturday is still Friday night
    }

    @Test public void afterMidnight_isStillLastNight() {
        long night = t(2026, 11, 3, 2, 0);   // 2 am Tuesday
        assertEquals(LocalDate.of(2026, 11, 2), S.eveningOf(night));
        assertTrue(S.lockNow(night, -1, 0));
        assertFalse(S.lockNow(t(2026, 11, 3, 7, 0), -1, 0));   // morning: over
    }

    @Test public void lockout_atBedtime_evenIfPlaying() {
        assertFalse(S.lockNow(t(2026, 11, 2, 19, 20), -1, 0));      // after T, still playing, < 25 min, before bedtime
        assertTrue(S.lockNow(t(2026, 11, 2, 19, 30), -1, 0));
    }

    @Test public void lastOneCanFinish_ceiling25min() {
        KidsSchedule windOnly = new KidsSchedule(new KidsSchedule.Times(19 * 60, -1, 7 * 60), WEEKEND, Z);
        assertFalse(windOnly.lockNow(t(2026, 11, 2, 19, 24, 59), -1, 0));
        assertTrue(windOnly.lockNow(t(2026, 11, 2, 19, 25, 5), -1, 0));     // AC-K6: within 25 min + 5 s
    }

    @Test public void stoppedFor30s_afterT_locks() {
        KidsSchedule windOnly = new KidsSchedule(new KidsSchedule.Times(19 * 60, -1, 7 * 60), WEEKEND, Z);
        long idle = t(2026, 11, 2, 19, 5, 0);
        assertFalse(windOnly.lockNow(t(2026, 11, 2, 19, 5, 29), idle, 0));
        assertTrue(windOnly.lockNow(t(2026, 11, 2, 19, 5, 31), idle, 0));     // AC-K6: within 35 s of pausing
        // idle that started before T counts from T
        assertFalse(windOnly.lockNow(t(2026, 11, 2, 19, 0, 20), t(2026, 11, 2, 18, 50), 0));
        assertTrue(windOnly.lockNow(t(2026, 11, 2, 19, 0, 31), t(2026, 11, 2, 18, 50), 0));
    }

    @Test public void onlyBedtimeSet_isT() {
        KidsSchedule bedOnly = new KidsSchedule(new KidsSchedule.Times(-1, 20 * 60, -1), WEEKEND, Z);
        long now = t(2026, 11, 2, 19, 50);
        assertEquals(t(2026, 11, 2, 20, 0), bedOnly.t0(now));
        assertEquals(-1, bedOnly.morningAt(now));
        assertTrue(bedOnly.lockNow(t(2026, 11, 2, 20, 0), -1, 0));
        assertTrue(bedOnly.lockNow(t(2026, 11, 3, 11, 59), -1, 0));     // no morning set: until noon
        assertFalse(bedOnly.lockNow(t(2026, 11, 3, 12, 0), -1, 0));
    }

    @Test public void extend_liftsThenRelocks() {
        long now = t(2026, 11, 2, 19, 40);
        long until = now + 10 * 60_000L;
        assertFalse(S.lockNow(now, -1, until));
        assertFalse(S.lockNow(now + 9 * 60_000L, -1, until));
        assertTrue(S.lockNow(until, -1, until));
        assertTrue(S.lockNow(until + 60_000, -1, until));
    }

    @Test public void warnings_15_5_0() {
        assertEquals(-1, S.warningDue(t(2026, 11, 2, 18, 40)));
        assertEquals(15, S.warningDue(t(2026, 11, 2, 18, 45)));
        assertEquals(15, S.warningDue(t(2026, 11, 2, 18, 54)));
        assertEquals(5, S.warningDue(t(2026, 11, 2, 18, 55)));
        assertEquals(0, S.warningDue(t(2026, 11, 2, 19, 0)));
        assertEquals(0, S.warningDue(t(2026, 11, 2, 19, 20)));
    }
}
