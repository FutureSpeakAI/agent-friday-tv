package ai.futurespeak.friday.tv.audio;

/** Speech level for the avatar: RMS of 16-bit PCM, mapped from -50..-10 dBFS onto 0..1. */
public final class Level {
    private Level() {}

    public static double rms(byte[] pcm16le, int offset, int length) {
        int n = length / 2;
        if (n <= 0) return 0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int lo = pcm16le[offset + 2 * i] & 0xff;
            int hi = pcm16le[offset + 2 * i + 1];
            double s = ((hi << 8) | lo) / 32768.0;
            sum += s * s;
        }
        return Math.sqrt(sum / n);
    }

    public static double toAvatar(double rms) {
        if (rms <= 0) return 0;
        double db = 20 * Math.log10(rms);
        double v = (db + 50) / 40.0;
        return Math.max(0, Math.min(1, v));
    }
}
