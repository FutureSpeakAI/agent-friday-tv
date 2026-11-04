package org.tvprivacy.adbreak;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;

/** AC-K5 (fake clock): watching counts; talking and learning don't; Count everything widens it. */
public class KidsClockTest {
    static final String PBS = "org.pbskids.video", FRIDAY = "ai.futurespeak.friday.tv", PLEX = "com.plexapp.android";

    /** Replays 5-second ticks: {top, interactive}. Returns counted ms, as AdGuardService's tick does. */
    static long replay(boolean countEverything, Object[][] ticks) {
        long used = 0;
        for (Object[] t : ticks) if (KidsClock.counts(true, (Boolean) t[1], (String) t[0], countEverything)) used += 5_000;
        return used;
    }

    static final Object[][] EVENING = {
            {PBS, true}, {PBS, true}, {PBS, true},          // 15 s watching
            {FRIDAY, true}, {FRIDAY, true},                 // 10 s with Friday (a story)
            {PBS, false}, {PBS, false},                     // screen off: 10 s
            {"org.tvprivacy.adbreak", true},                // the helper's own screen
            {PBS, true},                                    // 5 s watching
    };

    @Test public void watchingOnly_byDefault() {
        assertEquals(20_000, replay(false, EVENING));
    }

    @Test public void countEverything_addsFridayTime() {
        assertEquals(30_000, replay(true, EVENING));
    }

    @Test public void screenOff_neverCounts() {   // the isInteractive mutant must turn this red
        assertFalse(KidsClock.counts(true, false, PBS, false));
        assertFalse(KidsClock.counts(true, false, FRIDAY, true));
    }

    @Test public void kidsOff_orOffListApp_neverCounts() {
        assertFalse(KidsClock.counts(false, true, PBS, true));
        assertFalse(KidsClock.counts(true, true, PLEX, true));
        assertFalse(KidsClock.counts(true, true, null, true));
    }

    @Test public void remaining_limitPlusExtensions() {
        assertEquals(Long.MAX_VALUE, KidsClock.remainingMs(0, 0, 999_999));
        assertEquals(120 * 60_000L - 60_000, KidsClock.remainingMs(120, 0, 60_000));
        assertEquals(10 * 60_000L, KidsClock.remainingMs(120, 10 * 60_000L, 120 * 60_000L));
        assertEquals(0, KidsClock.remainingMs(120, 0, 200 * 60_000L));
        assertEquals(0, KidsClock.remainingMs(120, -99_999_999L, 120 * 60_000L));   // negative bonus ignored
    }

    /** AC-K3: entering kids mode closes the grown-up window first. */
    @Test public void enter_locksGrownUpWindow_first() {
        assertEquals(Arrays.asList("lockGrownUp", "kidsOn", "bounce"), KidsClock.enterPlan(true, true, false));
        assertEquals(Arrays.asList("kidsOn", "bounce"), KidsClock.enterPlan(true, false, false));
        assertEquals(Arrays.asList("bounce"), KidsClock.enterPlan(true, false, true));
        assertEquals(Arrays.asList("needPin"), KidsClock.enterPlan(false, true, false));
    }
}
