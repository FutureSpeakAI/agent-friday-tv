package ai.futurespeak.friday.tv.ambient;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Random;

import ai.futurespeak.friday.tv.cards.PinnedCards;

public class AmbientTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void cardsBecomePlainText() throws Exception {
        assertEquals("1.  Milk\n2.  Eggs", AmbientView.cardText(new JSONObject("{\"type\":\"list\",\"ordered\":true,\"items\":[\"Milk\",\"**Eggs**\"]}")));
        assertEquals("Mon   ·   72°", AmbientView.cardText(new JSONObject("{\"type\":\"table\",\"columns\":[\"d\",\"t\"],\"rows\":[[\"Mon\",\"72°\"]]}")));
        assertEquals("Mon   3", AmbientView.cardText(new JSONObject("{\"type\":\"chart\",\"labels\":[\"Mon\"],\"series\":[{\"name\":\"x\",\"values\":[3]}]}")));
        assertEquals("Hello there", AmbientView.cardText(new JSONObject("{\"type\":\"text\",\"body\":\"Hello __there__\"}")));
    }

    @Test public void burnInAlwaysMovesAndStaysInRange() {
        Random r = new Random(7);
        for (int i = 0; i < 2000; i++) {
            float v = BurnIn.offset(r, 100);
            assertTrue(Math.abs(v) >= 35 && Math.abs(v) <= 100);   // never parked at the centre
        }
    }

    @Test public void pinnedCardsToggleCapAndSkipHtml() throws Exception {
        PinnedCards p = new PinnedCards(tmp.getRoot());
        JSONObject a = new JSONObject("{\"type\":\"text\",\"title\":\"A\",\"body\":\"x\"}");
        assertTrue(p.toggle(a));
        assertTrue(p.isPinned(a));
        assertFalse(p.toggle(a));                     // OK again unpins
        assertFalse(p.isPinned(a));
        assertFalse(p.toggle(new JSONObject("{\"type\":\"html\",\"title\":\"H\",\"html\":\"<p>\"}")));
        for (int i = 0; i < PinnedCards.MAX + 3; i++) p.toggle(new JSONObject().put("type", "text").put("title", "T" + i));
        assertEquals(PinnedCards.MAX, p.list().size());
        assertEquals("T3", p.list().get(0).getString("title"));   // the oldest dropped off
        assertEquals(PinnedCards.MAX, new PinnedCards(tmp.getRoot()).list().size());   // kept on disk
    }

    @Test public void weatherParsesAndDescribes() throws Exception {
        Weather.Now n = Weather.parse("{\"current\":{\"temperature_2m\":71.6,\"weather_code\":2},\"daily\":{\"temperature_2m_max\":[80.4],\"temperature_2m_min\":[58.5]}}");
        assertEquals(72, n.temp);
        ai.futurespeak.friday.tv.Place.set("Riverton", 45.0, -122.0, true);
        assertEquals("Riverton  ·  72°  Partly cloudy  ·  H 80°  L 59°", n.line());
        ai.futurespeak.friday.tv.Place.set("", Double.NaN, Double.NaN, true);
        assertEquals("72°  Partly cloudy  ·  H 80°  L 59°", n.line());   // no place chosen: no name
        assertNull(ai.futurespeak.friday.tv.Place.weatherUrl(true));   // and no request
        assertEquals("Thunderstorms", Weather.describe(95));
    }
}
