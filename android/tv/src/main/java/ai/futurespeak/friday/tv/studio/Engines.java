package ai.futurespeak.friday.tv.studio;

import java.io.File;

/** The seams the builder works through, so it can be tested without a network or a TV. */
public final class Engines {
    private Engines() {}

    /** Claude, one non-streamed call at a time, with what it cost in tokens. */
    public interface Writer {
        Reply write(String system, String user, int maxTokens) throws Exception;
        String model();
    }

    public static final class Reply {
        public final String text; public final long in, out, cacheRead, cacheWrite;
        public Reply(String text, long in, long out, long cacheRead, long cacheWrite) {
            this.text = text; this.in = in; this.out = out; this.cacheRead = cacheRead; this.cacheWrite = cacheWrite;
        }
    }

    /** A voice that turns words into 16-bit mono PCM at {@link #RATE}. */
    public interface Speaker {
        byte[] pcm(String text) throws Exception;
        /** "Friday's voice (ElevenLabs)", "the TV's own voice". */
        String name();
        /** True when it costs money per character (the meter counts it). */
        boolean paid();
    }

    public static final int RATE = 22050;

    /** PCM (at {@link #RATE}) to the file the player plays: AAC in .m4a where the TV can, else WAV. */
    public interface Encoder { File encode(File pcm, File outDir) throws Exception; }

    /** How long the made file plays, read back by the platform's own reader; -1 when it can't be read. */
    public interface Verifier { long durationMs(File f); }
}
