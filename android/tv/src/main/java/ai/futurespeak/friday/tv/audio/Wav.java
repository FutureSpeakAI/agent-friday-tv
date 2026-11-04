package ai.futurespeak.friday.tv.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A 44-byte RIFF/WAVE header for 16-bit mono PCM. */
public final class Wav {
    private Wav() {}

    public static byte[] header(int dataBytes, int sampleRate) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataBytes).put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        b.put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataBytes);
        return b.array();
    }
}
