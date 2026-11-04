package ai.futurespeak.friday.tv.audio;

/** Speech out. Sentences queue in order; {@link #stop()} cuts everything at once (barge-in). */
public interface Voice {
    interface Events {
        /** First audio of a reply is playing. */
        void onSpeaking();
        /** Everything queued has been played (or stopped). */
        void onQuiet();
        /** A voice failed; the caller may fall back to another. */
        void onVoiceError(String why);
        /** Sentence {@code index} of the current reply has started to play (captions follow it). */
        default void onSentence(int index) { }
    }

    String label();
    /** Queues sentence {@code index} (0, 1, 2... within a reply). */
    void speak(String sentence, int index);
    /** No more sentences for this reply; onQuiet follows when the queue drains. */
    void endOfReply();
    void stop();
    boolean busy();
    /** Current speech level 0..1, aligned with what is audible now (for the avatar). */
    double level();
    void release();
}
