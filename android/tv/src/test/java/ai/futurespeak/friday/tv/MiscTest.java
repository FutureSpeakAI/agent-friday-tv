package ai.futurespeak.friday.tv;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import ai.futurespeak.friday.tv.audio.Level;
import ai.futurespeak.friday.tv.audio.Wav;
import ai.futurespeak.friday.tv.brain.SseParser;
import ai.futurespeak.friday.tv.prompt.SystemPrompt;

import static org.junit.Assert.*;

public class MiscTest {
    @Test public void sseJoinsDataLinesAndIgnoresComments() throws Exception {
        SseParser p = new SseParser();
        assertNull(p.line(": keep-alive"));
        assertNull(p.line("event: content_block_delta"));
        assertNull(p.line("data: {\"a\":"));
        assertNull(p.line("data: 1}"));
        SseParser.Event e = p.line("");
        assertEquals("content_block_delta", e.name);
        assertEquals("{\"a\":\n1}", e.data);
        assertNull(p.line(""));
    }

    @Test public void levelTracksLoudness() throws Exception {
        byte[] silent = new byte[2000];
        assertEquals(0, Level.toAvatar(Level.rms(silent, 0, silent.length)), 0);
        ByteBuffer b = ByteBuffer.allocate(2000).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 1000; i++) b.putShort((short) (i % 2 == 0 ? 16000 : -16000));
        double loud = Level.toAvatar(Level.rms(b.array(), 0, 2000));
        assertTrue(loud > 0.9);
        ByteBuffer q = ByteBuffer.allocate(2000).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 1000; i++) q.putShort((short) (i % 2 == 0 ? 300 : -300));
        double soft = Level.toAvatar(Level.rms(q.array(), 0, 2000));
        assertTrue(soft > 0 && soft < loud);
    }

    @Test public void wavHeader() throws Exception {
        ByteBuffer h = ByteBuffer.wrap(Wav.header(32000, 16000)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(44, h.capacity());
        assertEquals(36 + 32000, h.getInt(4));
        assertEquals(16000, h.getInt(24));
        assertEquals(32000, h.getInt(28));
        assertEquals(32000, h.getInt(40));
    }

    @Test public void familyModeIsTheKidSafePrompt() throws Exception {
        String fam = SystemPrompt.build(SystemPrompt.Mode.FAMILY, "Sunday", true);
        String adult = SystemPrompt.build(SystemPrompt.Mode.GROWN_UP, "Sunday", true);
        assertTrue(fam.contains("FAMILY MODE (on)"));
        assertTrue(fam.contains("Never ask for personal information"));
        assertFalse(adult.contains("FAMILY MODE"));
        for (String p : new String[]{fam, adult}) {
            assertTrue(p.contains("<card>"));
            assertTrue(p.contains("<channel>"));
            assertTrue(p.contains("You are Claude"));
            assertFalse("models read the plain name, never the mark", p.contains("™"));
        }
    }
}
