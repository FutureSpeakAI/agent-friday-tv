package ai.futurespeak.friday.tv.home;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ai.futurespeak.friday.tv.actions.Catalogue;

/** The launcher slice: Home shows only what the gate may open, and never goes around it. */
public class HomeModelTest {
    static final Catalogue CAT = new Catalogue(Arrays.asList(
            new Catalogue.App("org.xbmc.kodi", "Kodi"),
            new Catalogue.App("com.plexapp.android", "Plex"),
            new Catalogue.App("org.pbskids.video", "PBS KIDS"),
            new Catalogue.App("com.android.tv.settings", "Settings"),
            new Catalogue.App("com.aurora.store", "Aurora Store"),
            new Catalogue.App("com.spocky.projengmenu", "Projectivy Launcher"),
            new Catalogue.App("ai.futurespeak.friday.tv", "Agent Friday")));

    static List<String> pkgs(HomeModel m) { List<String> l = new ArrayList<>(); for (Catalogue.App a : m.apps) l.add(a.pkg); return l; }

    @Test public void familyHomeShowsTheCatalogueFavouritesFirst() {
        HomeModel m = HomeModel.of(CAT.apps(), null, false);
        List<String> p = pkgs(m);
        assertEquals("com.plexapp.android", p.get(0));
        assertEquals("org.pbskids.video", p.get(1));
        assertFalse("never Settings", p.contains("com.android.tv.settings"));
        assertFalse("never an app store", p.contains("com.aurora.store"));
        assertFalse("Friday isn't a tile of herself", p.contains("ai.futurespeak.friday.tv"));
        assertTrue(m.tvSettings);
    }

    @Test public void kidsHomeIsTheKidsListOnly() {
        HomeModel m = HomeModel.of(CAT.apps(), null, true);
        assertEquals(Arrays.asList("org.pbskids.video"), pkgs(m));
        assertFalse("no TV settings tile in kids mode", m.tvSettings);
    }

    /** Remote parity: Home opens apps only through the gate (never startActivity on another app's launch intent). */
    @Test public void homeOpensAppsOnlyThroughTheGate() throws Exception {
        String s = new String(Files.readAllBytes(new File("src/main/java/ai/futurespeak/friday/tv/home/HomeActivity.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(s.contains("actions.fromRemote(\"app.open\""));
        assertFalse("no direct launches of other apps", s.contains("getLaunchIntentForPackage(") || s.matches("(?s).*startActivity\\(\\s*pm\\..*"));
        // The one place Home starts something outside Friday is the TV's settings, behind !kids.
        assertEquals(1, s.split("Settings.ACTION_SETTINGS", -1).length - 1);
    }

    /** Google's launcher must never be disabled, and Home must never claim the system HOME role. */
    @Test public void neverTouchesTheSystemHome() throws Exception {
        String m = new String(Files.readAllBytes(new File("src/main/AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);
        assertFalse("no HOME category: Google's launcher stays the system home", m.contains("android.intent.category.HOME"));
        // Every setup and hardening script in the repository (wizard, extension, scripts).
        try (java.util.stream.Stream<java.nio.file.Path> w = Files.walk(new File("../..").toPath())) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) w::iterator) {
                String n = p.toString().replace('\\', '/');
                if (Files.isDirectory(p) || n.contains("/build/") || n.contains("/.git/") || n.contains("/.gradle/")
                        || n.contains("/tests/") || !n.matches(".*\\.(py|ps1|psm1|sh)$")) continue;
                String t = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                assertFalse(p + " disables Google's launcher", t.matches("(?s).*pm\\s+(disable|uninstall|hide)[^\\n]*launcherx.*"));
            }
        }
    }
}
