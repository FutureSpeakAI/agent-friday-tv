package org.tvprivacy.adbreak;

import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;

/**
 * Audio fallback for ads with no on-screen label.
 * Reads only the LEVEL (peak/RMS) of the TV's own output mix via Visualizer session 0,
 * ten times a second, in memory. No microphone, no recording, no storage, no network.
 *
 * Heuristic: a near-silent gap (hard cut) followed by 3 s that are clearly louder than the
 * programme's rolling 5-minute median => "probable ad break". The next gap followed by a
 * return to programme loudness, or a 3-minute cap, ends it.
 */
final class AudioWatcher {
    interface Listener { void onGuessStart(String why); void onGuessEnd(String why); }

    static final int SILENCE_MB = -6000;   // -60 dB, in millibels
    static final int RISE_MB = 650;        // +6.5 dB over programme median to start (raised in 0.6: 4 dB fired on ordinary scene cuts)
    static final int MIN_GAP_SAMPLES = 5;  // >= ~500 ms of near-silence (was 3)
    static final int MIN_BASELINE = 120;   // 2 minutes of programme before guessing (was 60 s)
    static final int BACK_MB = 200;        // within +2 dB of median to end
    static final long CAP_MS = 180_000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private Visualizer viz;
    private boolean running;
    private final int[] ring = new int[300];
    private int ringN, ringI;
    private long lastRingAt;
    private int quiet;
    private long quietStart;
    private boolean afterGap;
    private long gapEndedAt;
    private long sum;
    private int n;
    boolean guessing;
    private long guessStart;
    private long lastHealthLog;
    String lastError = "";

    private final Visualizer.MeasurementPeakRms m = new Visualizer.MeasurementPeakRms();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { step(); }
            catch (Throwable t) { Log.e(AdGuardService.TAG, "audio guess error (" + t.getClass().getSimpleName() + "); stopping"); stop(); return; }
            h.postDelayed(this, 100);
        }
    };

    AudioWatcher(Listener l) { listener = l; }

    boolean isRunning() { return running; }

    boolean start() {
        if (running) return true;
        try {
            viz = new Visualizer(0);
            viz.setEnabled(false);
            viz.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS);
            viz.setEnabled(true);
            running = true;
            h.post(tick);
            Log.i(AdGuardService.TAG, "audio guess started");
            return true;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName();
            Log.w(AdGuardService.TAG, "audio guess unavailable (" + lastError + ")");
            release();
            return false;
        }
    }

    void stop() {
        boolean was = running;
        running = false;
        h.removeCallbacks(tick);
        release();
        quiet = 0; afterGap = false;
        if (guessing) { guessing = false; listener.onGuessEnd("audio watch stopped"); }
        if (was) Log.i(AdGuardService.TAG, "audio guess stopped");
    }

    private void release() {
        try { if (viz != null) { viz.setEnabled(false); viz.release(); } } catch (Throwable ignored) { }
        viz = null;
    }

    private int median() {
        int[] c = Arrays.copyOf(ring, ringN);
        Arrays.sort(c);
        return c[c.length / 2];
    }

    private void step() {
        if (viz == null) return;
        viz.getMeasurementPeakRms(m);
        int rms = m.mRms;
        long now = SystemClock.elapsedRealtime();

        if (now - lastHealthLog > 60_000) {
            lastHealthLog = now;
            Log.i(AdGuardService.TAG, "audio level rms=" + rms / 100 + "dB median=" + (ringN > 0 ? median() / 100 + "dB" : "n/a") + " samples=" + ringN);
        }

        // Programme baseline: 1 sample per second, not while an ad is guessed.
        if (!guessing && !afterGap && rms > SILENCE_MB && now - lastRingAt >= 1000) {
            ring[ringI] = rms; ringI = (ringI + 1) % ring.length; ringN = Math.min(ring.length, ringN + 1); lastRingAt = now;
        }

        if (rms <= SILENCE_MB) {
            if (quiet == 0) quietStart = now;
            quiet++;
            return;
        }
        if (quiet >= MIN_GAP_SAMPLES) {   // >= ~500 ms of near silence just ended: a hard cut
            afterGap = true; gapEndedAt = now; sum = 0; n = 0;
            Log.i(AdGuardService.TAG, "audio gap " + (now - quietStart) + "ms");
        }
        quiet = 0;

        if (afterGap) {
            sum += rms; n++;
            if (now - gapEndedAt >= 4000) {   // judge 4 s after the cut (was 3 s)
                afterGap = false;
                if (ringN < MIN_BASELINE) return;   // need two minutes of programme first
                int mean = (int) (sum / Math.max(1, n)), med = median();
                if (!guessing && mean >= med + RISE_MB) {
                    guessing = true; guessStart = now;
                    listener.onGuessStart("louder after cut: " + (mean - med) / 100 + "dB over median");
                } else if (guessing && mean <= med + BACK_MB) {
                    guessing = false;
                    listener.onGuessEnd("back to programme level after cut");
                }
            }
        }
        if (guessing && now - guessStart > CAP_MS) {
            guessing = false;
            listener.onGuessEnd("3-minute cap");
        }
    }
}
