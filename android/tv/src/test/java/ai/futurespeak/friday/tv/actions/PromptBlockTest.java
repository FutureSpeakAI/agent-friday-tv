package ai.futurespeak.friday.tv.actions;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import ai.futurespeak.friday.tv.Session;
import ai.futurespeak.friday.tv.prompt.SystemPrompt;

/** TV-E05: the prompt's "can / cannot" words come from the catalogue, so words and gate can't drift apart. */
public class PromptBlockTest {
    static final Catalogue CAT = new Catalogue(Arrays.asList(
            new Catalogue.App("com.plexapp.android", "Plex"),
            new Catalogue.App("org.pbskids.video", "PBS KIDS"),
            new Catalogue.App("com.android.vending", "Google Play Store"),
            new Catalogue.App("com.aurora.store", "Aurora Store")));

    String prompt(boolean kids) {
        return SystemPrompt.build(SystemPrompt.Mode.GROWN_UP, "Monday", true, CAT.canDo(kids) + "\n" + Catalogue.cannotDo(), kids);
    }

    @Test public void theOldNoAppsLineIsGoneWhenActionsExist() {
        String p = prompt(false);
        assertFalse(p.contains("open apps or change settings"));
        assertTrue(p.contains("Plex"));
        assertTrue(p.contains("app_open"));
        assertFalse("forbidden apps are never offered", p.contains("Play Store"));
        assertFalse("other app stores are never offered", p.contains("Aurora"));
    }

    @Test public void withoutActionsTheOldLineStays() {
        assertTrue(SystemPrompt.build(SystemPrompt.Mode.FAMILY, "Monday", true).contains("open apps or change settings"));
    }

    @Test public void kidsModeListsOnlyTheKidsAppsAndUsesTheKidsPrompt() {
        String p = prompt(true);
        assertTrue(p.contains("PBS KIDS"));
        assertFalse(p.contains("Plex"));
        assertTrue(p.contains("KIDS MODE (on)"));
        assertTrue("kids mode always has the family rules, even if grown-up was chosen", p.contains("FAMILY MODE (on)"));
    }

    @Test public void verdictsBecomeFixedWordsAndHonestNotes() throws Exception {
        JSONObject in = new JSONObject().put("app", "Plex");
        Session.Act a = TvActions.toAct(new ActionGate.Verdict("unverified", "I asked Plex to open; I can't see whether it did.",
                "UNVERIFIED", true, "d1"), "app_open", in);
        assertEquals("I asked Plex to open; I can't see whether it did.", a.say);
        assertTrue(a.endTurn);
        assertTrue(a.note.contains("not confirmed"));
        Session.Act r = TvActions.toAct(new ActionGate.Verdict("refused", "That needs a grown-up.", "REFUSED", false, "d2"), "app_open", in);
        assertFalse(r.endTurn);
        assertTrue(r.note.startsWith("[the TV refused"));
    }
}
