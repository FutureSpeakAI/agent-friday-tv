package ai.futurespeak.friday.tv.audio;

import java.util.List;
import java.util.Locale;

/**
 * Spec D4 with a fallback (2026-11-02): Friday listens offline when the TV can, and
 * otherwise uses Google's online recognition, labelled as such. Pure decisions, unit-tested;
 * the Android side is {@link RecognizerSpeechIn}. Every route asks the recognizer for offline,
 * so the TV's offline pack is used whenever it is installed; the routes differ only in what
 * Friday can promise about where the voice goes.
 */
public final class OfflineSpeech {
    private OfflineSpeech() {}

    public enum Route {
        /** Friday's own recognizer (sherpa-onnx, model pushed to the TV): offline, de-Googled, the voice never leaves the TV. */
        ON_TV,
        /** Android's dedicated on-device recognizer (Android 12+): offline, guaranteed. */
        ON_DEVICE,
        /** The TV's recognition service with its English pack confirmed (reported, or measured on this TV). */
        SERVICE_OFFLINE,
        /** Offline not confirmed: the TV's service, preferring offline, may send the voice to Google. */
        CLOUD,
        /** Nothing on this TV can recognize speech. */
        NONE
    }

    public static final String LANGUAGE = "en-US";

    public static Route decide(boolean onDeviceRecognizer, boolean serviceAvailable, List<String> installed) {
        return decide(onDeviceRecognizer, serviceAvailable, installed, false);
    }

    /**
     * @param measuredOffline this exact version of the TV's speech service was measured on this TV
     *                        recognizing English with the network off (this TV's service can't
     *                        report its offline languages; a measurement is the proof)
     */
    public static Route decide(boolean onDeviceRecognizer, boolean serviceAvailable, List<String> installed, boolean measuredOffline) {
        if (onDeviceRecognizer) return Route.ON_DEVICE;
        if (!serviceAvailable) return Route.NONE;
        return has(installed, LANGUAGE) || measuredOffline ? Route.SERVICE_OFFLINE : Route.CLOUD;
    }

    /** "en-US" matches en-US, en_US and en-us; a bare "en" pack covers it too. */
    public static boolean has(List<String> langs, String want) {
        if (langs == null) return false;
        String w = norm(want), base = w.contains("-") ? w.substring(0, w.indexOf('-')) : w;
        for (String l : langs) {
            String n = norm(l);
            if (n.equals(w) || n.equals(base)) return true;
        }
        return false;
    }

    static String norm(String s) { return s == null ? "" : s.trim().replace('_', '-').toLowerCase(Locale.ROOT); }

    public static boolean offline(Route r) { return r == Route.ON_TV || r == Route.ON_DEVICE || r == Route.SERVICE_OFFLINE; }

    /** Words for the person, by route. */
    public static String explain(Route r) {
        switch (r) {
            case ON_TV: return "Friday recognizes your voice herself, on this TV. It never leaves the TV, and no Google service hears it.";
            case ON_DEVICE: return "Your voice is recognized on this TV and never leaves it.";
            case SERVICE_OFFLINE: return "Your voice is recognized on this TV with its offline English pack, and never leaves it.";
            case CLOUD: return "Offline English isn't confirmed on this TV yet, so Google's speech recognition may send your voice to Google to be written down. Only the words go on to the assistant that answers.";
            default: return "This TV has no speech recognizer, so Friday can't listen. You can still use the remote.";
        }
    }

    /** Silence that ends a question (tap to talk), or that never ends it (hold to talk: only letting go does). */
    public static long silenceMs(boolean hold, int pauseMs) {
        return hold ? 15000L : Math.max(600, Math.min(pauseMs, 3000));
    }

    /** Never reopen the microphone within this long of the last open (no open/close storms). */
    public static final long MIN_REOPEN_MS = 1000;

    public static boolean mayOpen(long now, long lastOpen, boolean stillOpen) {
        return !stillOpen && now - lastOpen >= MIN_REOPEN_MS;
    }
}
