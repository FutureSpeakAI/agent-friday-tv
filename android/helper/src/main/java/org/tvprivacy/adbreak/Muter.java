package org.tvprivacy.adbreak;

import android.content.Context;
import android.media.AudioManager;

/** Mutes the media stream only if it wasn't already muted, and only ever unmutes what it muted. */
final class Muter {
    private final AudioManager am;
    private final Prefs prefs;

    Muter(Context c, Prefs prefs) {
        this.am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        this.prefs = prefs;
    }

    void mute() {
        if (am.isStreamMute(AudioManager.STREAM_MUSIC)) return; // the viewer muted it; not ours to undo
        prefs.setWeMuted(true);                                  // persist first so a crash still unmutes later
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
    }

    void unmuteIfWeMuted() {
        if (!prefs.weMuted()) return;
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        prefs.setWeMuted(false);
    }
}
