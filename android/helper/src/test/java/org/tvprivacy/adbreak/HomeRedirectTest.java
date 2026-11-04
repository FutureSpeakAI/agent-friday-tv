package org.tvprivacy.adbreak;

import static org.junit.Assert.*;
import static org.tvprivacy.adbreak.HomeRedirect.Action.*;

import org.junit.Test;

/** 0.8.3: Google home -> Friday Home, with a loop guard that never bounces forever. */
public class HomeRedirectTest {

    @Test public void googleHome_isRedirected_whenProjectivyOff_andHomeMapped() {
        HomeRedirect r = new HomeRedirect();
        assertEquals(REDIRECT, r.onGoogleHome(1_000, false, true, false));
    }

    @Test public void leftAlone_whenProjectivyOn_orNoHomeMapping_orKidsMode() {
        HomeRedirect r = new HomeRedirect();
        assertEquals(NONE, r.onGoogleHome(1_000, true, true, false));
        assertEquals(NONE, r.onGoogleHome(1_000, false, false, false));
        assertEquals(NONE, r.onGoogleHome(1_000, false, true, true));
    }

    @Test public void onlyGoogleHomeActivity_counts_notOtherLauncherScreens() {
        assertTrue(HomeRedirect.isGoogleHome("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.home.HomeActivity"));
        assertFalse(HomeRedirect.isGoogleHome("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.profile.ProfileActivity"));
        assertFalse(HomeRedirect.isGoogleHome("com.spocky.projengmenu", "com.google.android.apps.tv.launcherx.home.HomeActivity"));
    }

    /** Loop guard 1: Friday Home fails to start (never confirmed) -> give up, leave Google's screen, cool down. */
    @Test public void fridayHomeNeverArrives_givesUp_andLeavesGoogleShowing() {
        HomeRedirect r = new HomeRedirect();
        assertEquals(REDIRECT, r.onGoogleHome(0, false, true, false));
        assertEquals(NONE, r.onGoogleHome(1_000, false, true, false));            // still waiting
        assertEquals(GIVE_UP, r.checkPending(3_100));                              // not confirmed in 3 s
        for (long t = 4_000; t < 600_000; t += 5_000) assertEquals(NONE, r.onGoogleHome(t, false, true, false)); // cooldown
        assertEquals(REDIRECT, r.onGoogleHome(3_100 + HomeRedirect.COOLDOWN_MS, false, true, false)); // tries again later
    }

    /** Loop guard 2: Friday starts each time but Google keeps coming back -> at most 3 redirects in 30 s. */
    @Test public void googleKeepsComingBack_atMostThreeRedirects_thenGivesUp() {
        HomeRedirect r = new HomeRedirect();
        int redirects = 0;
        long t = 0;
        for (int i = 0; i < 20; i++, t += 1_000) {
            HomeRedirect.Action a = r.onGoogleHome(t, false, true, false);
            if (a == REDIRECT) { redirects++; r.onFridayFront(); }
            if (a == GIVE_UP) break;
        }
        assertEquals(3, redirects);
        assertTrue(r.coolingDown(t));
        assertTrue(r.lastGiveUpReason().contains("3 times"));
    }

    @Test public void startFailure_givesUpImmediately() {
        HomeRedirect r = new HomeRedirect();
        assertEquals(REDIRECT, r.onGoogleHome(0, false, true, false));
        assertEquals(GIVE_UP, r.onStartFailed(10));
        assertEquals(NONE, r.onGoogleHome(20, false, true, false));
    }

    @Test public void normalUse_backingOutAnHourApart_alwaysRedirects() {
        HomeRedirect r = new HomeRedirect();
        for (long t = 0; t < 10 * 3_600_000L; t += 3_600_000L) {
            assertEquals(REDIRECT, r.onGoogleHome(t, false, true, false));
            r.onFridayFront();
        }
    }

    @Test public void userOpensAnAppBeforeWeAct_cancelsPending_noGiveUp() {
        HomeRedirect r = new HomeRedirect();
        assertEquals(REDIRECT, r.onGoogleHome(0, false, true, false));
        r.cancelPending();
        assertEquals(NONE, r.checkPending(10_000));
        assertEquals(REDIRECT, r.onGoogleHome(60_000, false, true, false));
    }
}
