package ai.futurespeak.friday.tv.story;

import java.util.List;

/** The seams the engine works through, so the whole story flow is tested without a network or a TV. */
public final class Seams {
    private Seams() {}

    /** Spoken audio: 16-bit mono PCM at 22.05 kHz, with when each word is said. */
    public static final class Audio {
        public final byte[] pcm; public final List<WordTiming.Word> words; public final long ms; public final String voice;
        public Audio(byte[] pcm, List<WordTiming.Word> words, String voice) {
            this.pcm = pcm; this.words = words; this.voice = voice; this.ms = pcm == null ? 0 : pcm.length * 1000L / (22050L * 2);
        }
    }

    /** A voice. {@code who} is null for Friday herself; a cast member speaks in their designed voice or Friday's voice in character. */
    public interface Speaker {
        Audio speak(String text, Cast.Member who) throws Exception;
        String name();
        boolean paid();
    }

    /** Designs a cast member's voice from words and returns its id (ElevenLabs Voice Design), or throws. */
    public interface VoiceDesigner {
        String design(String name, String description, String sampleLine) throws Exception;
    }

    public interface Clock { long now(); }
}
