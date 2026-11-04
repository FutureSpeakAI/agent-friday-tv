package ai.futurespeak.friday.tv.live;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;

/**
 * WebSocket framing (RFC 6455), client side, just enough for the Gemini Live API: masked text
 * frames out; text, binary, continuation, ping, pong and close in. Pure (streams only), unit-tested.
 * The app has no dependencies, so this replaces a WebSocket library.
 */
public final class WsFrames {
    private WsFrames() {}

    public static final int CONT = 0x0, TEXT = 0x1, BINARY = 0x2, CLOSE = 0x8, PING = 0x9, PONG = 0xA;
    /** A Live message is JSON; even audio chunks are small. Anything larger is refused. */
    public static final int MAX_MESSAGE = 8 * 1024 * 1024;
    private static final SecureRandom RNG = new SecureRandom();

    /** One client frame (always FIN, always masked, as the RFC requires of clients). */
    public static byte[] encode(int opcode, byte[] payload) {
        return encode(opcode, payload, randomMask());
    }

    static byte[] randomMask() { byte[] m = new byte[4]; RNG.nextBytes(m); return m; }

    static byte[] encode(int opcode, byte[] payload, byte[] mask) {
        int n = payload == null ? 0 : payload.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(n + 14);
        out.write(0x80 | (opcode & 0x0F));
        if (n < 126) out.write(0x80 | n);
        else if (n < 65536) { out.write(0x80 | 126); out.write(n >>> 8); out.write(n); }
        else { out.write(0x80 | 127); for (int i = 7; i >= 0; i--) out.write((int) (((long) n) >>> (8 * i))); }
        out.write(mask, 0, 4);
        for (int i = 0; i < n; i++) out.write(payload[i] ^ mask[i & 3]);
        return out.toByteArray();
    }

    /** One whole message from the server (fragments joined), or a control frame. */
    public static final class Message {
        public final int opcode; public final byte[] data;
        Message(int opcode, byte[] data) { this.opcode = opcode; this.data = data; }
    }

    /**
     * Reads the next message. Control frames (ping, pong, close) come back as they arrive, even
     * between the fragments of a data message; data fragments are joined.
     */
    public static Message read(InputStream in) throws IOException {
        ByteArrayOutputStream joined = null;
        int dataOp = -1;
        while (true) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) throw new EOFException("socket closed");
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) len = (readByte(in) << 8) | readByte(in);
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in); }
            if (len < 0 || len > MAX_MESSAGE) throw new IOException("frame too large");
            byte[] mask = masked ? readFully(in, 4) : null;
            byte[] p = readFully(in, (int) len);
            if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
            if (op >= 0x8) return new Message(op, p);   // control frames are never fragmented
            if (op != CONT) { dataOp = op; joined = new ByteArrayOutputStream(); }
            if (joined == null) throw new IOException("continuation without a start");
            if (joined.size() + p.length > MAX_MESSAGE) throw new IOException("message too large");
            joined.write(p, 0, p.length);
            if (fin) return new Message(dataOp, joined.toByteArray());
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("socket closed");
        return b;
    }

    static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) { int r = in.read(b, off, n - off); if (r < 0) throw new EOFException("socket closed"); off += r; }
        return b;
    }

    /** The Sec-WebSocket-Accept a server must return for {@code key}. */
    public static String accept(String key) {
        try {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-1");
            byte[] h = sha.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return java.util.Base64.getEncoder().encodeToString(h);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
