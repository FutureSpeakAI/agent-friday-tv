package ai.futurespeak.friday.tv.audio;

/** Speech to text, push-to-talk. All callbacks arrive on the main thread. */
public interface SpeechIn {
    interface Events {
        /** The microphone is open (true) or closed (false). Drives the on-screen mic indicator. */
        void onMic(boolean open);
        void onPartial(String text);
        void onFinal(String text);
        /** Nothing usable was heard, or recognition failed. {@code why} is fit to show. */
        void onNothing(String why);
        /** Input level 0..1 while listening. */
        void onLevel(double level);
    }

    /** "On-device (Android)", "Google speech service", "ElevenLabs Scribe (cloud)". */
    String label();
    /** Opens the mic and starts listening. */
    void start();
    /** The talk button was released: stop capturing and finish recognition. */
    void finish();
    /** Abandon: close the mic, deliver nothing. */
    void cancel();
    void release();
}
