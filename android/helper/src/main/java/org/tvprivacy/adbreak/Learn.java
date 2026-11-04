package org.tvprivacy.adbreak;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * On-device learning from the viewer's verdicts (BACK = not an ad, OK = it is an ad).
 * Stored per (app, rule): confirmed and corrected counts. Nothing leaves the TV.
 *
 * Policy for a rule in an app:
 *   corrected >= 3 and corrected > confirmed        -> DEMOTED (placard only, never mutes)
 *   corrected >= 6 and corrected > 2 x confirmed    -> DISABLED (ignored in that app)
 *   confirmed catching up (confirmed >= corrected)  -> back to normal
 */
final class Learn {
    static final int NORMAL = 0, DEMOTED = 1, DISABLED = 2;
    private final SharedPreferences sp;

    Learn(Context c) { sp = c.getSharedPreferences("learn", Context.MODE_PRIVATE); }

    private static String k(String app, String rule) { return app + "|" + rule; }

    void verdict(String app, String rule, boolean isAd) {
        String key = (isAd ? "tp:" : "fp:") + k(app, rule);
        sp.edit().putInt(key, sp.getInt(key, 0) + 1)
                .putInt(isAd ? "total_tp" : "total_fp", sp.getInt(isAd ? "total_tp" : "total_fp", 0) + 1).apply();
    }

    int state(String app, String rule) {
        int tp = sp.getInt("tp:" + k(app, rule), 0), fp = sp.getInt("fp:" + k(app, rule), 0);
        if (fp >= 6 && fp > 2 * tp) return DISABLED;
        if (fp >= 3 && fp > tp) return DEMOTED;
        return NORMAL;
    }

    int confirmed() { return sp.getInt("total_tp", 0); }
    int corrected() { return sp.getInt("total_fp", 0); }

    /** Short summary of rules currently demoted or disabled, for the settings screen. */
    String adjusted() {
        StringBuilder s = new StringBuilder();
        for (String key : sp.getAll().keySet()) {
            if (!key.startsWith("fp:")) continue;
            String ar = key.substring(3); int bar = ar.indexOf('|');
            int st = state(ar.substring(0, bar), ar.substring(bar + 1));
            if (st != NORMAL) s.append(ar.substring(bar + 1)).append(" in ").append(ar.substring(0, bar)).append(st == DISABLED ? ": off" : ": placard only").append('\n');
        }
        return s.toString().trim();
    }

    void reset() { sp.edit().clear().apply(); }
}
