package org.tvprivacy.adbreak;

import static org.junit.Assert.*;

import org.junit.Test;

/** Launcher step L2: Home goes to Friday Home only when Projectivy's accessibility service is off. */
public class HomeKeyTest {
    static final String PROJECTIVY = "com.spocky.projengmenu/com.spocky.projengmenu.services.ProjectivyAccessibilityService";
    static final String ADBREAK = "org.tvprivacy.adbreak/org.tvprivacy.adbreak.AdGuardService";

    @Test public void projectivyOn_bothServices_leavesHomeAlone() {
        assertFalse(HomeKey.take(3, true, PROJECTIVY + ":" + ADBREAK));
        assertFalse(HomeKey.take(3, true, ADBREAK + ":" + PROJECTIVY));
    }

    @Test public void projectivyOff_mapped_takesHome() {
        assertTrue(HomeKey.take(3, true, ADBREAK));
    }

    @Test public void notMapped_neverTakesHome() {
        assertFalse(HomeKey.take(3, false, ADBREAK));
        assertFalse(HomeKey.take(3, false, null));
    }

    @Test public void otherKeys_neverTreatedAsHome() {
        assertFalse(HomeKey.take(4, true, ADBREAK));       // BACK
        assertFalse(HomeKey.take(4137, true, ADBREAK));    // TCL Home is the kids key, not Home
    }

    @Test public void projectivyDetection() {
        assertFalse(HomeKey.projectivyOn(null));
        assertFalse(HomeKey.projectivyOn(""));
        assertFalse(HomeKey.projectivyOn(ADBREAK));
        assertTrue(HomeKey.projectivyOn(PROJECTIVY));
        assertFalse(HomeKey.projectivyOn("com.spocky.projengmenu.other/x"));
    }

    @Test public void homeTarget_isAllowedInKidsMode() {
        assertTrue(HelperPolicy.kidsAllowedTarget("ai.futurespeak.friday.tv/.Home"));
    }

    @Test public void home_cannotBeRemappedFromTheTvScreen_butIsntHardBlocked() {
        assertFalse(KeyMap.remappableOnTv(3));
        assertFalse(KeyMap.isHardProtected(3));
        assertTrue(KeyMap.isHardProtected(4));            // BACK stays untouchable
    }
}
