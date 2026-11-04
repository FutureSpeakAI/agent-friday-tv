package ai.futurespeak.friday.tv.audio;

import static org.junit.Assert.*;

import org.junit.Test;

public class AudioGuardsTest {
    @Test public void voiceChunksHoldWholeSamples() {
        // An odd chunk split 16-bit samples across chunks: the static on the TV.
        assertEquals(0, ElevenLabsVoice.CHUNK % 2);
        assertEquals(22050 / 20, ElevenLabsVoice.CHUNK / 2);   // still 50 ms
    }

    @Test public void gainKeepsSamplesInPlace() {
        // 1000, -1000, 0, 20000 little-endian; gain 1 must not move or swap bytes.
        short[] in = {1000, -1000, 0, 20000};
        byte[] b = new byte[in.length * 2];
        for (int i = 0; i < in.length; i++) { b[2 * i] = (byte) in[i]; b[2 * i + 1] = (byte) (in[i] >> 8); }
        ElevenLabsVoice.applyGain(b, 1f);
        for (int i = 0; i < 3; i++) {
            short s = (short) ((b[2 * i + 1] << 8) | (b[2 * i] & 0xff));
            assertTrue("sample " + i + " was " + s, Math.abs(s - in[i]) <= 1);
        }
        short loud = (short) ((b[7] << 8) | (b[6] & 0xff));
        assertTrue(loud > 19000 && loud <= 20000);   // below the knee: untouched
    }

    @Test public void micNeverReopensInAStorm() {
        assertTrue(OfflineSpeech.mayOpen(5000, 3000, false));
        assertFalse(OfflineSpeech.mayOpen(5000, 4500, false));   // 500 ms later: refused
        assertFalse(OfflineSpeech.mayOpen(9000, 3000, true));    // previous recorder still closing
        assertTrue(OfflineSpeech.mayOpen(4000, 3000, false));    // exactly the gap: allowed
    }
}
