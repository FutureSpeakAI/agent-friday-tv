package ai.futurespeak.friday.tv.kids;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Kids mode's own settings on Friday's side (the helper keeps the clock and schedule): only the
 * reading band for now. A band is kept; an age never is (KM-E05). Changing it is a grown-up
 * setting (behind the PIN on the settings page).
 */
public final class KidsSettings {
    private final SharedPreferences p;

    public KidsSettings(Context c) { p = c.getApplicationContext().getSharedPreferences("kids", Context.MODE_PRIVATE); }

    /** The reading band, or null until a grown-up picks one (none is pre-selected). */
    public KidsRules.Band band() { return KidsRules.Band.parse(p.getString("band", null)); }
    public void setBand(KidsRules.Band b) {
        if (b == null) p.edit().remove("band").apply(); else p.edit().putString("band", b.name()).apply();
    }

    /** The next band in the settings row's cycle: none → 5 to 7 → 8 to 10 → 11 to 13 → none. */
    public static KidsRules.Band next(KidsRules.Band b) {
        if (b == null) return KidsRules.Band.YOUNG;
        KidsRules.Band[] all = KidsRules.Band.values();
        return b.ordinal() + 1 < all.length ? all[b.ordinal() + 1] : null;
    }
}
