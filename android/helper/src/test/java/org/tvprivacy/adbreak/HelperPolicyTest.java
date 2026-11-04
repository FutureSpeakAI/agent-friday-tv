package org.tvprivacy.adbreak;

import static org.junit.Assert.*;

import org.junit.Test;

/** The helper API's "tests that could fail", plus the helper's own checks. */
public class HelperPolicyTest {

    // --- the five promised tests (four here; the foreground one is in ForegroundTrackerTest) ---

    @Test public void unknownPackage_isNotInstalled() {
        assertEquals(HelperPolicy.NOT_INSTALLED, HelperPolicy.refuse("com.example.nothere", false, false, false, false));
    }

    @Test public void tvSettings_isForbidden() {
        assertEquals(HelperPolicy.FORBIDDEN, HelperPolicy.refuse("com.android.tv.settings", true, true, false, false));
    }

    @Test public void kidsMode_refusesPlex_allowsPbsKids() {
        assertEquals(HelperPolicy.KIDS, HelperPolicy.refuse("com.plexapp.android", true, true, true, false));
        assertNull(HelperPolicy.refuse("org.pbskids.video", true, true, true, false));
    }

    @Test(expected = SecurityException.class) public void callerWithoutPermission_getsSecurityException() {
        HelperPolicy.enforce(false, false);
    }

    // --- our own ---

    @Test public void callerWithPermission_orSelf_passes() {
        HelperPolicy.enforce(false, true);
        HelperPolicy.enforce(true, false);
    }

    @Test public void denylist_coversSystemStoreInstallersAndSelf() {
        for (String p : new String[] {"com.android.settings", "com.google.android.packageinstaller", "com.android.packageinstaller",
                "com.google.android.permissioncontroller", "com.android.vending", "com.android.systemui", "org.tvprivacy.adbreak"}) {
            assertEquals(p, HelperPolicy.FORBIDDEN, HelperPolicy.refuse(p, true, true, false, false));
        }
    }

    @Test public void forbiddenWins_evenIfNotInstalled_orInKidsMode() {
        assertEquals(HelperPolicy.FORBIDDEN, HelperPolicy.refuse("com.android.tv.settings", false, false, true, true));
    }

    @Test public void noLauncher() {
        assertEquals(HelperPolicy.NO_LAUNCHER, HelperPolicy.refuse("com.example.service", true, false, false, false));
    }

    @Test public void malformedOrMissingPackage_isNotInstalled() {
        assertEquals(HelperPolicy.NOT_INSTALLED, HelperPolicy.refuse(null, true, true, false, false));
        assertEquals(HelperPolicy.NOT_INSTALLED, HelperPolicy.refuse("", true, true, false, false));
        assertEquals(HelperPolicy.NOT_INSTALLED, HelperPolicy.refuse("plex", true, true, false, false));
        assertEquals(HelperPolicy.NOT_INSTALLED, HelperPolicy.refuse("com.plexapp.android/.Main", true, true, false, false));
        assertEquals(HelperPolicy.NOT_INSTALLED, HelperPolicy.refuse("intent://x#Intent;end", true, true, false, false));
    }

    @Test public void allowed_whenAllClear() {
        assertNull(HelperPolicy.refuse("com.plexapp.android", true, true, false, false));
    }

    @Test public void busy_onlyWithinOneSecond_andOnlyUntilTheAppIsInFront() {
        assertTrue(HelperPolicy.busy(1_500, 1_000, "com.plexapp.android", "com.spocky.projengmenu"));
        assertFalse(HelperPolicy.busy(1_500, 1_000, "com.plexapp.android", "com.plexapp.android"));
        assertFalse(HelperPolicy.busy(2_000, 1_000, "com.plexapp.android", "com.spocky.projengmenu"));
        assertFalse(HelperPolicy.busy(1_500, 0, null, "x"));
        assertEquals(HelperPolicy.BUSY, HelperPolicy.refuse("com.spotify.tv.android", true, true, false, true));
    }

    @Test public void statusKids_failsClosed() {
        assertTrue(HelperPolicy.kidsFlag(false, false));   // service not running -> kids on
        assertTrue(HelperPolicy.kidsFlag(true, true));
        assertFalse(HelperPolicy.kidsFlag(true, false));
    }

    @Test public void receiptRef_isSanitisedAndCapped() {
        assertEquals("r-123", HelperPolicy.cleanRef("r-123"));
        assertEquals("abc", HelperPolicy.cleanRef("a\nb c"));
        assertEquals("-", HelperPolicy.cleanRef(null));
        assertEquals(64, HelperPolicy.cleanRef(new String(new char[200]).replace('\0', 'x')).length());
    }

    @Test public void kidsList_isPbsKidsAndFriday_only() {
        assertEquals(2, HelperPolicy.KIDS_APPS.size());
        assertTrue(HelperPolicy.KIDS_APPS.contains("org.pbskids.video"));
        assertTrue(HelperPolicy.KIDS_APPS.contains("ai.futurespeak.friday.tv"));
    }

    @Test public void kidsMode_allowsFriday_viaLaunch() {
        assertNull(HelperPolicy.refuse("ai.futurespeak.friday.tv", true, true, true, false));
        assertEquals(HelperPolicy.KIDS, HelperPolicy.refuse("com.spotify.tv.android", true, true, true, false));
    }

    @Test public void kidsMode_buttonTargets_useTheSameList() {
        assertTrue(HelperPolicy.kidsAllowedTarget("ai.futurespeak.friday.tv/.MainActivity|listen=true"));   // the mic button
        assertTrue(HelperPolicy.kidsAllowedTarget("org.pbskids.video"));
        assertFalse(HelperPolicy.kidsAllowedTarget("com.plexapp.android"));
        assertFalse(HelperPolicy.kidsAllowedTarget("adbreak:settings"));
        assertFalse(HelperPolicy.kidsAllowedTarget("com.spocky.projengmenu/.ui.sidePanels.settings.SettingsActivity"));
        assertFalse(HelperPolicy.kidsAllowedTarget("ai.futurespeak.friday.tv.evil/.X"));
        assertFalse(HelperPolicy.kidsAllowedTarget(null));
    }

    /** Kids mode's own allow list (AdGuardService) is built from KIDS_APPS, not a second copy. */
    @Test public void kidsMode_andLaunch_shareOneList() throws java.io.IOException {
        java.nio.file.Path p = java.nio.file.Paths.get("src/main/java/org/tvprivacy/adbreak/AdGuardService.java");
        if (!java.nio.file.Files.exists(p)) p = java.nio.file.Paths.get("app").resolve(p);
        String s = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(s.contains("KIDS_OK_PKGS.addAll(HelperPolicy.KIDS_APPS)"));
        assertTrue(s.contains("HelperPolicy.kidsAllowedTarget(m.target)"));
        assertFalse("kids list duplicated in AdGuardService", s.contains("\"org.pbskids.video\"") || s.contains("\"ai.futurespeak.friday.tv\""));
    }
}
