package org.tvprivacy.adbreak;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final int OFF = 0, FULL = 1, PLACARD_ONLY = 2;
    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getSharedPreferences("adbreak", Context.MODE_PRIVATE); }

    /** Every app defaults to ON (mute + placard). */
    int mode(String pkg) { return sp.getInt("mode_" + pkg, FULL); }
    void setMode(String pkg, int m) { sp.edit().putInt("mode_" + pkg, m).apply(); }

    boolean weMuted() { return sp.getBoolean("we_muted", false); }
    void setWeMuted(boolean b) { sp.edit().putBoolean("we_muted", b).commit(); }

    /** Open SmartTube whenever the stock YouTube app is launched. Off by default; SmartTube is a third-party app you install yourself. */
    boolean redirectYouTube() { return sp.getBoolean("redirect_youtube", false); }
    void setRedirectYouTube(boolean b) { sp.edit().putBoolean("redirect_youtube", b).apply(); }

    /** Audio guess for unlabeled ads: 0 off, 1 suggest (corner badge only, default), 2 act (placard + app's mute setting). */
    int audioGuess() { return sp.getInt("audio_guess", 1); }
    void setAudioGuess(int v) { sp.edit().putInt("audio_guess", v).apply(); }
    static String audioLabel(int v) { return v == 2 ? "Act (placard, mute per app)" : v == 1 ? "Suggest only (small badge)" : "Off"; }

    static int next(int m) { return m == FULL ? PLACARD_ONLY : m == PLACARD_ONLY ? OFF : FULL; }

    static String label(int m) {
        return m == FULL ? "Mute + placard" : m == PLACARD_ONLY ? "Placard only (no mute)" : "Off";
    }
}
