package ai.futurespeak.friday.tv.addon;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class AddonSpecTest {
    static Map<String, String> m(String... kv) {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) a.put(kv[i], kv[i + 1]);
        return a;
    }

    static final String PKG = "ai.futurespeak.friday.tv.news";

    @Test public void aGoodDeclarationParses() {
        AddonSpec s = AddonSpec.parse(PKG, m("contract", "1", "id", "news", "title", "News", "icon", "📰", "kids", "true", "triggers", "news Headlines news"));
        assertNotNull(s);
        assertEquals("news", s.id);
        assertTrue(s.kids);
        assertEquals(java.util.Arrays.asList("news", "headlines"), s.triggers);
        assertEquals("addon:news", s.key());
    }

    @Test public void anythingOddIsRefused() {
        assertNull("newer contract", AddonSpec.parse(PKG, m("contract", "2", "id", "news", "title", "News")));
        assertNull("no contract", AddonSpec.parse(PKG, m("id", "news", "title", "News")));
        assertNull("bad id", AddonSpec.parse(PKG, m("contract", "1", "id", "News!", "title", "News")));
        assertNull("no title", AddonSpec.parse(PKG, m("contract", "1", "id", "news", "title", " \u0007 ")));
        assertNull("long title", AddonSpec.parse(PKG, m("contract", "1", "id", "news", "title", "A title that is far too long for a tile")));
        assertNull("kids must be true or false", AddonSpec.parse(PKG, m("contract", "1", "id", "news", "title", "News", "kids", "yes")));
        assertNull("trigger not a word", AddonSpec.parse(PKG, m("contract", "1", "id", "news", "title", "News", "triggers", "news .*")));
        assertNull("the core is never an add-on", AddonSpec.parse(Contract.CORE_PACKAGE, m("contract", "1", "id", "news", "title", "News")));
        assertNull("bad package", AddonSpec.parse("news", m("contract", "1", "id", "news", "title", "News")));
    }

    @Test public void kidsDefaultsToFalseAndIconFallsBack() {
        AddonSpec s = AddonSpec.parse(PKG, m("contract", "1", "id", "news", "title", "News", "icon", "TOOLONGICON"));
        assertFalse(s.kids);
        assertEquals("✦", s.icon);
    }

    @Test public void triggersMatchWholeWordsOnly() {
        AddonSpec s = AddonSpec.parse(PKG, m("contract", "1", "id", "news", "title", "News", "triggers", "news headlines"));
        assertTrue(s.mightWant("Show me the news."));
        assertTrue(s.mightWant("read me the HEADLINES"));
        assertFalse(s.mightWant("what's newsworthy"));
        assertFalse(s.mightWant(null));
    }
}
