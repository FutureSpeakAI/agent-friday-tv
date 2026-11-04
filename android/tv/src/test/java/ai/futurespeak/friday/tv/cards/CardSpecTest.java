package ai.futurespeak.friday.tv.cards;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class CardSpecTest {
    @Test public void acceptsEachTypeAndArrays() throws Exception {
        List<JSONObject> c = CardSpec.parse("[{\"type\":\"text\",\"title\":\"T\",\"body\":\"hi\"},"
                + "{\"type\":\"list\",\"items\":[\"a\",\"b\"]},"
                + "{\"type\":\"table\",\"columns\":[\"x\"],\"rows\":[[\"1\"],[\"2\"]]},"
                + "{\"type\":\"chart\",\"kind\":\"line\",\"labels\":[\"a\"],\"series\":[{\"name\":\"s\",\"values\":[1,\"x\",3]}]},"
                + "{\"type\":\"image\",\"url\":\"https://upload.wikimedia.org/a.png\"},"
                + "{\"type\":\"html\",\"html\":\"<p>x</p>\"}]");
        assertEquals(6, c.size());
        assertEquals(0.0, c.get(3).getJSONArray("series").getJSONObject(0).getJSONArray("values").getDouble(1), 0);
    }

    @Test public void wrappedInCardsKeyOrFenceWorks() throws Exception {
        assertEquals(1, CardSpec.parse("{\"cards\":[{\"type\":\"text\",\"body\":\"x\"}]}").size());
        assertEquals(1, CardSpec.parse("```json\n{\"type\":\"text\",\"body\":\"x\"}\n```").size());
    }

    @Test public void unsafeImagesAreDropped() throws Exception {
        assertTrue(CardSpec.parse("{\"type\":\"image\",\"url\":\"http://example.com/a.png\"}").isEmpty());
        assertTrue(CardSpec.parse("{\"type\":\"image\",\"url\":\"https://192.168.1.1/a.png\"}").isEmpty());
        assertTrue(CardSpec.parse("{\"type\":\"image\",\"url\":\"https://user:pw@example.com/a.png\"}").isEmpty());
        assertTrue(CardSpec.parse("{\"type\":\"image\",\"url\":\"https://router.lan/a.png\"}").isEmpty());
        assertTrue(CardSpec.parse("{\"type\":\"image\",\"url\":\"javascript:alert(1)\"}").isEmpty());
        assertTrue(CardSpec.parse("{\"type\":\"image\",\"url\":\"https://example.com:8443/a.png\"}").isEmpty());
    }

    @Test public void unknownTypesAndJunkAreDropped() throws Exception {
        assertTrue(CardSpec.parse("{\"type\":\"launch_app\",\"pkg\":\"x\"}").isEmpty());
        assertTrue(CardSpec.parse("not json").isEmpty());
        assertTrue(CardSpec.parse("{\"type\":\"text\",\"body\":\"\"}").isEmpty());
    }

    @Test public void limitsAreEnforced() throws Exception {
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < 500; i++) items.append(i == 0 ? "" : ",").append("\"i").append(i).append('"');
        items.append(']');
        JSONObject c = CardSpec.parse("{\"type\":\"list\",\"items\":" + items + "}").get(0);
        assertEquals(CardSpec.MAX_ITEMS, c.getJSONArray("items").length());
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 20; i++) many.append(i == 0 ? "" : ",").append("{\"type\":\"text\",\"body\":\"x\"}");
        assertEquals(CardSpec.MAX_CARDS, CardSpec.parse(many + "]").size());
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < CardSpec.MAX_HTML + 10; i++) big.append('x');
        assertTrue(CardSpec.parse(new JSONObject().put("type", "html").put("html", big.toString()).toString()).isEmpty());
    }

    @Test public void hostRules() throws Exception {
        assertTrue(HostRules.isPublicHostname("api.open-meteo.com"));
        assertFalse(HostRules.isPublicHostname("localhost"));
        assertFalse(HostRules.isPublicHostname("10.0.0.1"));
        assertFalse(HostRules.isPublicHostname("[::1]"));
        assertFalse(HostRules.isPublicHostname("tv.local"));
        assertFalse(HostRules.isPublicHostname("printer.home.arpa"));
        assertFalse(HostRules.isPublicHostname("bad_host.com"));
    }
}
