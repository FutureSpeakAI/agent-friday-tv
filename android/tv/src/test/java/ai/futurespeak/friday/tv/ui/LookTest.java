package ai.futurespeak.friday.tv.ui;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The main Friday screen in Home v2's look, respecting Motion and the 4K GPU. */
public class LookTest {
    private static String src(String rel) throws Exception {
        File f = new File("src/main/" + rel);
        if (!f.exists()) f = new File("../addon-sdk/src/main/" + rel);   // the shared Look lives in the add-on SDK
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
    private static String java(String rel) throws Exception { return src("java/ai/futurespeak/friday/tv/" + rel); }

    @Test public void motionFollowsHomeAndReduceMotionWins() {
        assertEquals(Look.Level.FULL, Look.level(null, false));
        assertEquals(Look.Level.FULL, Look.level("FULL", false));
        assertEquals(Look.Level.CALM, Look.level("CALM", false));
        assertEquals(Look.Level.OFF, Look.level("OFF", false));
        assertEquals("reduce motion always wins", Look.Level.OFF, Look.level("FULL", true));
        assertEquals(0x3300D4FF, Look.alpha(0xFF00D4FF, 0x33));
        assertArrayEquals("the same accents as Customize Home", ai.futurespeak.friday.tv.home.HomeLayout.ACCENTS, Look.ACCENTS);
    }

    @Test public void everyMovementHasAnOffPath() throws Exception {
        String l = java("ui/Look.java");
        for (String m : new String[]{"public static void lift(", "public static void reveal(", "public static void enter(", "public static void exit("}) {
            int i = l.indexOf(m);
            assertTrue(m, i > 0);
            int off = l.indexOf("Level.OFF", i);
            int next = l.indexOf("public static", i + 10);
            assertTrue(m + " checks for off", off > i && (next < 0 || off < next));
        }
        assertFalse("no blur on the 4K GPU", l.contains("RenderEffect") || l.contains("BlurMaskFilter"));
    }

    @Test public void layersArriveAndLeaveSoftlyAndCannotBeUsedWhileLeaving() throws Exception {
        String m = java("MainActivity.java");
        int push = m.indexOf("@Override public void push(Layer l)");
        assertTrue(m.indexOf("Look.enter(l);", push) - push < 700);
        int pop = m.indexOf("@Override public void pop(Layer l)");
        int block = m.indexOf("FOCUS_BLOCK_DESCENDANTS", pop);
        int exit = m.indexOf("Look.exit(l, () -> root.removeView(l));", pop);
        assertTrue("focus is blocked before the fade", block > pop && exit > block);
        assertTrue("closed at once, not after the fade", m.indexOf("l.closed();", pop) < exit);
    }

    @Test public void fullScreenPagesStopTheAvatarRendererButNeverChannelTimers() throws Exception {
        String m = java("MainActivity.java");
        int h = m.indexOf("static boolean hidesStage(Layer l)");
        String body = m.substring(h, m.indexOf("}", h));
        assertTrue(body.contains("SettingsScreen") && body.contains("ChannelLayer"));
        int s = m.indexOf("private void stageFor()");
        String sf = m.substring(s, m.indexOf("\n    }", s));
        assertTrue(sf.contains("avatar.onPause()") && sf.contains("avatar.onResume()"));
        assertFalse("pauseTimers would stop channel pages too", sf.contains(".pauseTimers()"));
        assertTrue("onResume keeps her paused under Settings", m.contains("if (stagePaused) avatar.onPause();"));
        assertTrue("the Home throttle is reused on the main screen", m.contains("avatar.hold(ai.futurespeak.friday.tv.home.HomeActivity.HOLD_MS)"));
    }

    @Test public void theRailUsesOneIconSetAndNeverCoversTheWordmark() throws Exception {
        String m = java("MainActivity.java");
        for (String g : new String[]{"case \"🎙\": return \"voice\";", "case \"📰\": return \"news\";", "case \"◉\": return \"live\";", "case \"▦\": return \"apps\";", "case \"⚙\": return \"settings\";"})
            assertTrue(g, m.contains(g));
        assertTrue(m.contains("new ai.futurespeak.friday.tv.ui.LineIcon.Glyph(glyph, accent, 0xD9FFFFFF)"));
        assertTrue("the wordmark steps aside with the rail", m.contains("wordmark.animate().translationX(show ? dp(RAIL_CLEARANCE_DP) : 0)"));
        String icons = java("ui/LineIcon.java");
        for (String n : new String[]{"case \"news\":", "case \"live\":", "case \"settings\":", "case \"voice\":", "case \"apps\":"}) assertTrue(n, icons.contains(n));
    }

    @Test public void captionsAreDesktopStyle() throws Exception {
        String m = java("MainActivity.java");
        assertTrue(m.contains("stateChip.setBackground(ai.futurespeak.friday.tv.ui.Look.captionBox(this))"));
        assertTrue(m.contains("Look.caption(this, state, s, color)"));
        assertTrue(m.contains("captionBox.setBackground(ai.futurespeak.friday.tv.ui.Look.captionBox(this))"));
    }

    @Test public void settingsUseTheAccentAndRevealNewPagesOnly() throws Exception {
        String p = java("ui/SettingsPage.java");
        assertEquals("no fixed cyan left in settings controls (only the mapping of callers' cyan to the accent)",
                p.indexOf("Brand.CYAN"), p.lastIndexOf("Brand.CYAN"));
        assertTrue(p.contains("color == Brand.CYAN ? A : color"));
        assertFalse(p.contains("00D4FF"));
        String s = java("ui/SettingsScreen.java");
        int show = s.indexOf("private void show(int i)");
        int reveal = s.indexOf("Look.revealChildren(pageCol);", show);
        assertTrue(reveal > show && reveal - show < 400);
        int rebuild = s.indexOf("public void rebuild()");
        assertTrue("a rebuild after a change doesn't replay the entry", s.indexOf("revealChildren", rebuild) < 0 || s.indexOf("revealChildren", rebuild) > s.indexOf("public void refreshSummaries()"));
    }

    @Test public void cardsFollowTheLookAndNameWhoAnswered() throws Exception {
        String css = src("assets/cards/cards.css");
        assertTrue(css.contains("html[data-motion=\"off\"] * { animation: none !important;"));
        assertTrue(css.contains("content: var(--fr-who)"));
        assertFalse("the card never hard-codes Claude", css.contains("content: 'CLAUDE'"));
        String js = src("assets/cards/cards.js");
        assertTrue(js.contains("docEl.setAttribute('data-motion'"));
        assertTrue("only a #rrggbb accent is accepted", js.contains("/^#[0-9a-f]{6}$/i.test(look.accent || '')"));
        String layer = java("ui/CardLayer.java");
        assertTrue(layer.contains("cardView.show(withLook(card));"));
        assertTrue(layer.contains("\"POWERED BY \" + who.toUpperCase(Locale.ROOT)"));
        assertTrue(java("MainActivity.java").contains("ai.futurespeak.friday.tv.ui.CardLayer.who = brain.displayName();"));
    }
}
