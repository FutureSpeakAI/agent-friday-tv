package ai.futurespeak.friday.tv.safety;

import org.junit.Test;

import static org.junit.Assert.*;

public class PinTest {
    @Test public void hashVerifies() throws Exception {
        String h = Pin.hash("2468");
        assertTrue(Pin.verify("2468", h));
        assertFalse(Pin.verify("2469", h));
        assertFalse(Pin.verify("", h));
        assertFalse("the PIN itself is never stored", h.contains("2468"));
        assertNotEquals("salted", h, Pin.hash("2468"));
    }

    @Test public void formatIsChecked() throws Exception {
        assertFalse(Pin.validFormat("123"));
        assertFalse(Pin.validFormat("123456789"));
        assertFalse(Pin.validFormat("12a4"));
        assertTrue(Pin.validFormat("0000"));
        try { Pin.hash("12"); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void tamperedHashesFail() throws Exception {
        assertFalse(Pin.verify("2468", "v1$1$AAAA$AAAA"));
        assertFalse(Pin.verify("2468", "garbage"));
        assertFalse(Pin.verify("2468", null));
    }

    @Test public void lockoutGrows() throws Exception {
        assertEquals(0, Pin.lockoutMs(4));
        assertEquals(30_000, Pin.lockoutMs(5));
        assertEquals(60_000, Pin.lockoutMs(6));
        assertEquals(15 * 60_000, Pin.lockoutMs(50));
    }
}
