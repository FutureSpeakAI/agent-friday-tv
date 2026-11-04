package ai.futurespeak.friday.tv.ui;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Replays the TCL remote's real key timings (tcl_remote_keys.csv) through the input gate. */
public class InputGateTest {
    static final Map<String, Integer> CODES = new HashMap<>();
    static {
        CODES.put("KEYCODE_DPAD_UP", 19); CODES.put("KEYCODE_DPAD_DOWN", 20); CODES.put("KEYCODE_DPAD_LEFT", 21);
        CODES.put("KEYCODE_DPAD_RIGHT", 22); CODES.put("KEYCODE_DPAD_CENTER", 23); CODES.put("KEYCODE_BACK", 4); CODES.put("KEYCODE_HOME", 3);
    }

    static final class Ev { final long t; final boolean down; final int key; Ev(long t, boolean d, int k) { this.t = t; down = d; key = k; } }

    static List<Ev> recording() throws Exception {
        List<Ev> out = new ArrayList<>();
        long t = 10_000;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                InputGateTest.class.getResourceAsStream("/tcl_remote_keys.csv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.replace("﻿", "").trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split(",");
                t += Long.parseLong(p[0]);
                out.add(new Ev(t, p[1].equals("DOWN"), CODES.get(p[2])));
            }
        }
        return out;
    }

    /** Feeds events in time order, delivering due releases between them, like the Android layer does. */
    static List<InputGate.Out> replay(List<Ev> evs) {
        InputGate g = new InputGate();
        List<InputGate.Out> out = new ArrayList<>();
        for (Ev e : evs) {
            while (g.nextDue() <= e.t) out.addAll(g.due(g.nextDue()));
            out.addAll(g.feed(e.key, e.down, 0, e.t));
        }
        while (g.nextDue() != Long.MAX_VALUE) out.addAll(g.due(g.nextDue()));
        return out;
    }

    @Test public void realRecordingGivesOnePressPerPress() throws Exception {
        List<InputGate.Out> out = replay(recording());
        StringBuilder presses = new StringBuilder();
        for (InputGate.Out o : out) if (o.down && o.repeat == 0) presses.append(o.key).append(' ');
        // OK, Back x3, Up, Down, one held Back (8 raw presses in 1.9 s), Home, a held Left, Left, Right, OK x3.
        assertEquals("23 4 4 4 19 20 4 3 21 21 22 23 23 23 ", presses.toString());
    }

    @Test public void heldBackNeverRepeats() throws Exception {
        List<InputGate.Out> out = replay(recording());
        for (InputGate.Out o : out) if (o.key == 4 || o.key == 23) assertEquals("Back/OK must never repeat: " + out, 0, o.repeat);
    }

    @Test public void heldLeftRepeatsGentlyAfter400ms() throws Exception {
        List<InputGate.Out> out = replay(recording());
        List<Long> leftDowns = new ArrayList<>();
        for (InputGate.Out o : out) if (o.key == 21 && o.down) leftDowns.add(o.time);
        // The first held Left (1.3 s): one press, then 5 repeats. The raw stream had 7 presses.
        long start = leftDowns.get(0);
        int repeats = 0;
        long prev = start;
        for (int i = 1; i < leftDowns.size() - 1; i++) {
            long t = leftDowns.get(i);
            assertTrue("no repeat before 400 ms", t - start >= InputGate.REPEAT_DELAY_MS);
            assertTrue("never faster than every 140 ms", t - prev >= InputGate.REPEAT_EVERY_MS);
            prev = t;
            repeats++;
        }
        assertEquals(5, repeats);
    }

    @Test public void everyDownGetsExactlyOneUp() throws Exception {
        List<InputGate.Out> out = replay(recording());
        Map<Integer, Integer> open = new HashMap<>();
        for (InputGate.Out o : out) {
            if (o.down && o.repeat == 0) { assertNull("a second DOWN before its UP: " + o, open.get(o.key)); open.put(o.key, 1); }
            if (!o.down) { assertNotNull("an UP without its DOWN: " + o, open.remove(o.key)); }
        }
        assertTrue(open.isEmpty());
    }

    @Test public void deliberateDoubleTapIsTwoPressesBurstIsOne() {
        InputGate g = new InputGate();
        assertEquals(1, g.feed(20, true, 0, 0).size());
        g.feed(20, false, 0, 150);
        assertTrue(g.due(330).size() == 1);                         // released 180 ms after the UP
        assertEquals(1, g.feed(20, true, 0, 450).size());           // a second, deliberate press
        g.feed(20, false, 0, 600);
        assertEquals(0, g.feed(20, true, 0, 736).size());           // 136 ms after UP: the same hold
        assertEquals(0, g.due(800).size());                         // still held
    }

    @Test public void systemAutoRepeatIsTreatedAsHold() {
        InputGate g = new InputGate();
        assertEquals(1, g.feed(22, true, 0, 0).size());
        assertEquals(0, g.feed(22, true, 1, 50).size());            // a remote that does send repeats
        assertEquals(1, g.feed(22, true, 2, 450).size());           // first repeat after 400 ms
        assertEquals(0, g.feed(22, true, 3, 500).size());           // too soon (140 ms rate)
        assertEquals(1, g.feed(22, true, 4, 600).size());
    }
}
