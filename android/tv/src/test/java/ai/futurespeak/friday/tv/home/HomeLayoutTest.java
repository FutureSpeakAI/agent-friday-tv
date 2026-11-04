package ai.futurespeak.friday.tv.home;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ai.futurespeak.friday.tv.actions.Catalogue;

/** Customization can rearrange Home, never widen it: only catalogue apps, Talk always reachable, kids fixed. */
public class HomeLayoutTest {
    static final List<Catalogue.App> ALLOWED = Arrays.asList(
            new Catalogue.App("com.plexapp.android", "Plex"), new Catalogue.App("org.pbskids.video", "PBS KIDS"),
            new Catalogue.App("com.spotify.tv.android", "Spotify"), new Catalogue.App("org.xbmc.kodi", "Kodi"));

    static List<String> pkgs(List<Catalogue.App> l) { List<String> o = new ArrayList<>(); for (Catalogue.App a : l) o.add(a.pkg); return o; }

    @Test public void pinsComeFirstInOrder() {
        HomeLayout l = new HomeLayout();
        l.pinFirst("org.xbmc.kodi");
        l.pinFirst("com.spotify.tv.android");
        assertEquals(Arrays.asList("com.spotify.tv.android", "org.xbmc.kodi", "com.plexapp.android", "org.pbskids.video"), pkgs(l.order(ALLOWED)));
    }

    @Test public void aPinCanNeverAddAnAppTheGateDoesNotAllow() {
        HomeLayout l = new HomeLayout();
        l.pins.add("com.android.vending");
        l.pins.add("com.android.tv.settings");
        assertFalse(pkgs(l.order(ALLOWED)).contains("com.android.vending"));
        assertEquals(ALLOWED.size(), l.order(ALLOWED).size());
    }

    @Test public void moveLeftAndRightStick() {
        HomeLayout l = new HomeLayout();
        l.move(ALLOWED, "org.xbmc.kodi", -1);
        assertEquals(Arrays.asList("com.plexapp.android", "org.pbskids.video", "org.xbmc.kodi", "com.spotify.tv.android"), pkgs(l.order(ALLOWED)));
        l.move(ALLOWED, "com.plexapp.android", 1);
        assertEquals("org.pbskids.video", pkgs(l.order(ALLOWED)).get(0));
        l.move(ALLOWED, "org.pbskids.video", -1);   // already first: nothing happens
        assertEquals("org.pbskids.video", pkgs(l.order(ALLOWED)).get(0));
    }

    @Test public void hideAndShowAgain() {
        HomeLayout l = new HomeLayout();
        l.hide("com.plexapp.android");
        assertFalse(pkgs(l.order(ALLOWED)).contains("com.plexapp.android"));
        l.unhideAll();
        assertTrue(pkgs(l.order(ALLOWED)).contains("com.plexapp.android"));
    }

    @Test public void rowsReorderAndTalkCanNeverAllBeHidden() {
        HomeLayout l = new HomeLayout();
        l.moveRow("apps", -1);
        assertEquals("apps", l.rowOrder().get(0));
        l.hiddenRows.addAll(HomeLayout.ROWS);
        assertEquals(Arrays.asList("hero"), l.visibleRows());
        l.rows = new ArrayList<>(Arrays.asList("channels"));   // an old or partial save
        assertEquals(4, l.rowOrder().size());
    }

    @Test public void kidsLayoutIsFixed() {
        HomeLayout k = HomeLayout.kidsFixed();
        assertEquals(Arrays.asList("hero", "apps"), k.visibleRows());
        assertEquals(HomeLayout.Motion.CALM, k.motion);
        // And Home's model ignores a grown-up's layout in kids mode.
        HomeLayout grown = new HomeLayout();
        grown.hide("org.pbskids.video");
        assertEquals(Arrays.asList("org.pbskids.video"), pkgs(HomeModel.of(ALLOWED, null, true, grown).apps));
    }

    @Test public void stageSizesFollowTheDesktop() {
        HomeLayout l = new HomeLayout();
        assertEquals(0.30f, l.stage(), 0.001);
        l.avatar = false;
        assertEquals(0f, l.stage(), 0.001);
    }
}
