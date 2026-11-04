package org.tvprivacy.adbreak;

import static org.junit.Assert.*;

import org.junit.Test;

public class ForegroundTrackerTest {

    /** seq increments exactly once per app change; IME and system UI don't change it. */
    @Test public void seq_onePerAppChange_inRecordedStream() {
        ForegroundTracker t = new ForegroundTracker();
        String[][] stream = {
                {"com.spocky.projengmenu", "com.spocky.projengmenu.ui.home.MainActivity"},     // +1 (launcher)
                {"com.spocky.projengmenu", "com.spocky.projengmenu.ui.home.MainActivity"},     // same app
                {"com.google.android.leanbackime", "android.inputmethodservice.SoftInputWindow"}, // IME
                {"com.android.systemui", "com.android.systemui.volume.VolumeDialog"},          // system UI
                {"com.plexapp.android", "com.plexapp.plex.activities.SplashActivity"},         // +1
                {"com.plexapp.android", "com.plexapp.plex.home.HomeActivity"},                 // same app, new activity
                {"com.plexapp.android", "android.widget.Toast$TN"},                            // toast
                {"org.tvprivacy.adbreak", "android.widget.FrameLayout"},                       // helper overlay
                {"com.android.systemui", "android.app.Dialog"},                                // system UI
                {"ai.futurespeak.friday.tv", "ai.futurespeak.friday.tv.MainActivity"},         // +1
                {"com.google.android.inputmethod.latin", "android.inputmethodservice.SoftInputWindow"}, // IME
                {"com.spocky.projengmenu", "com.spocky.projengmenu.ui.home.MainActivity"},     // +1
        };
        int changes = 0;
        long now = 1000;
        for (String[] e : stream) { if (t.onWindowState(e[0], e[1], now)) changes++; now += 100; }
        assertEquals(4, changes);
        assertEquals(4, t.seq());
        assertEquals("com.spocky.projengmenu", t.pkg());
        assertEquals(2100, t.changedAt());
    }

    @Test public void systemKeyboardsAddedAtRuntime_areIgnored() {
        ForegroundTracker t = new ForegroundTracker();
        t.ignore("com.tcl.ime");
        assertFalse(t.onWindowState("com.tcl.ime", "com.tcl.ime.Keyboard", 1));
        assertEquals(0, t.seq());
        assertEquals("", t.pkg());
    }

    @Test public void helpersOwnScreens_count_overlaysDont() {
        ForegroundTracker t = new ForegroundTracker();
        assertTrue(t.onWindowState("org.tvprivacy.adbreak", "org.tvprivacy.adbreak.KidsHomeActivity", 1));
        assertTrue(t.onWindowState("org.pbskids.video", "org.pbskids.video.MainActivity", 2));
        assertFalse(t.onWindowState("org.tvprivacy.adbreak", "android.widget.FrameLayout", 3));
        assertEquals("org.pbskids.video", t.pkg());
    }

    @Test public void nullAndEmpty_ignored() {
        ForegroundTracker t = new ForegroundTracker();
        assertFalse(t.onWindowState(null, "x", 1));
        assertFalse(t.onWindowState("", "x", 1));
        assertEquals(0, t.seq());
    }
}
