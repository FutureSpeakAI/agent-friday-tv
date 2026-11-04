package ai.futurespeak.friday.tv.addon;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.InputStream;

import ai.futurespeak.friday.tv.ui.Motion;

/**
 * An add-on's side of the contract. Every call is safe to make when Friday is missing, too old,
 * or has not enabled this add-on: it returns the quiet answer (no state, no voice, false), so an
 * add-on always runs on its own. Calls that touch the provider block briefly; make them off the
 * main thread, except {@link #install}.
 */
public final class FridayTv {
    private FridayTv() {}

    private static final Uri CORE = Uri.parse("content://" + Contract.CORE_AUTHORITY);

    /** What Friday shares with an enabled add-on. */
    public static final class State {
        public final boolean kids, grownUp;
        /** "auto", "on" or "off". */
        public final String motion;
        public final String placeName;
        public final double lat, lon;
        public final boolean fahrenheit;

        State(Bundle b) {
            kids = b.getBoolean(Contract.K_KIDS, true);   // fail closed
            grownUp = b.getBoolean(Contract.K_GROWN_UP, false);
            motion = b.getString(Contract.K_MOTION, "auto");
            placeName = b.getString(Contract.K_PLACE_NAME, "");
            lat = b.containsKey(Contract.K_PLACE_LAT) ? b.getDouble(Contract.K_PLACE_LAT) : Double.NaN;
            lon = b.containsKey(Contract.K_PLACE_LON) ? b.getDouble(Contract.K_PLACE_LON) : Double.NaN;
            fahrenheit = b.getBoolean(Contract.K_PLACE_F, true);
        }

        public boolean hasPlace() { return !Double.isNaN(lat) && !Double.isNaN(lon); }
    }

    private static volatile State cached;

    /**
     * Call from the add-on's Application.onCreate: makes the shared Look follow Friday's
     * reduce-motion setting (from the last state read; Android's own setting until then).
     */
    public static void install(Context c) {
        Motion.setting = ctx -> {
            State s = cached;
            if (s == null || "auto".equals(s.motion)) return null;
            return "on".equals(s.motion);
        };
    }

    private static Bundle call(Context c, String method, String arg, Bundle extras) {
        try {
            return c.getContentResolver().call(CORE, method, arg, extras);
        } catch (Exception e) {   // Friday missing, too old, or this add-on not enabled
            return null;
        }
    }

    /** Friday's contract version, or 0 when Friday is not installed. */
    public static int coreVersion(Context c) {
        Bundle b = call(c, Contract.M_CONTRACT, null, null);
        return b == null ? 0 : b.getInt(Contract.K_VERSION, 0);
    }

    /** The household's state, or null when this add-on may not ask (not enabled, or no Friday). */
    public static State state(Context c) {
        Bundle b = call(c, Contract.M_STATE, null, null);
        if (b == null) return null;
        State s = new State(b);
        cached = s;
        return s;
    }

    /**
     * What each job will use on this TV: job ("text", "voice", "sound", "music", "images", "video")
     * to a provider name, or the fallback ("tv" voice, "stills" pictures, "none"). Without Friday,
     * or before this add-on is enabled, everything is the fallback: plan for that first.
     */
    public static java.util.Map<String, String> capabilities(Context c) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        out.put("text", "none"); out.put("voice", "tv"); out.put("sound", "none"); out.put("music", "none");
        out.put("images", "stills"); out.put("video", "stills");
        Bundle b = call(c, Contract.M_CAPABILITIES, null, null);
        Bundle plan = b == null ? null : b.getBundle(Contract.K_PLAN);
        if (plan != null) for (String k : out.keySet().toArray(new String[0])) { String v = plan.getString(k); if (v != null) out.put(k, v); }
        return out;
    }

    /** The last state read, or null. */
    public static State cached() { return cached; }

    /**
     * Friday's voice saying {@code text}: raw 22.05 kHz 16-bit mono PCM to play, or null (use the
     * TV's own text-to-speech instead). The add-on never sees a key.
     */
    public static InputStream voice(Context c, String text) {
        if (text == null || text.isEmpty() || text.length() > Contract.MAX_VOICE_CHARS) return null;
        Bundle b = call(c, Contract.M_VOICE, text, null);
        ParcelFileDescriptor pfd = b == null ? null : b.getParcelable(Contract.K_PCM);
        return pfd == null ? null : new ParcelFileDescriptor.AutoCloseInputStream(pfd);
    }

    /** Opens Friday with this question asked; {@code label} is the short line the screen shows. */
    public static boolean ask(Context c, String question, String label) {
        Bundle x = new Bundle();
        x.putString(Contract.K_LABEL, label);
        Bundle b = call(c, Contract.M_ASK, question, x);
        return b != null && b.getBoolean(Contract.K_OK);
    }

    /** Hands a source to Friday Studio. Studio's own card asks the person before anything is made or spent. */
    public static boolean studio(Context c, String title, String source, String link, String text, String relatedJson) {
        Bundle x = new Bundle();
        x.putString(Contract.K_TITLE, title);
        x.putString(Contract.K_SOURCE, source);
        x.putString(Contract.K_LINK, link);
        x.putString(Contract.K_TEXT, text);
        if (relatedJson != null) x.putString(Contract.K_RELATED, relatedJson);
        Bundle b = call(c, Contract.M_STUDIO, null, x);
        return b != null && b.getBoolean(Contract.K_OK);
    }

    /** Adds a plain-words line to Friday's receipts ("Opened Friday News"). Never put secrets or personal data here. */
    public static boolean receipt(Context c, String what) {
        Bundle b = call(c, Contract.M_RECEIPT, what, null);
        return b != null && b.getBoolean(Contract.K_OK);
    }

    /**
     * Asks the person yes or no on Friday's approval card. The answer arrives in the activity's
     * onActivityResult: RESULT_OK only for an explicit yes. Returns false when Friday can't ask.
     */
    public static boolean requestApproval(Activity a, int requestCode, String question, String detail) {
        Intent i = new Intent(Contract.ACTION_APPROVE).setPackage(Contract.CORE_PACKAGE)
                .putExtra(Contract.EXTRA_QUESTION, question).putExtra(Contract.EXTRA_DETAIL, detail);
        try { a.startActivityForResult(i, requestCode); return true; } catch (ActivityNotFoundException | SecurityException e) { return false; }
    }

    /** Returns to Friday's home (when the add-on is done and was opened from Friday). */
    public static void home(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(Contract.CORE_PACKAGE);
        if (i == null) i = c.getPackageManager().getLeanbackLaunchIntentForPackage(Contract.CORE_PACKAGE);
        if (i != null) { try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { } }
    }
}
