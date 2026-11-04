package ai.futurespeak.friday.tv.actions;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * The real helper: Ad Break Guard 0.8's methods on the shared, signature-protected provider
 *. No internet on that side; this side only passes a package name and a ref.
 */
public final class HelperHands implements Hands {
    private static final Uri URI = Uri.parse("content://org.tvprivacy.adbreak.grownup");
    private final Context ctx;

    public HelperHands(Context c) { ctx = c.getApplicationContext(); }

    private Bundle call(String method, Bundle extras) {
        try { return ctx.getContentResolver().call(URI, method, null, extras); }
        catch (Throwable t) { Log.w("FridayTV", "helper " + method + " failed (" + t.getClass().getSimpleName() + ")"); return null; }
    }

    @Override public Foreground foreground() {
        Bundle b = call("foreground", null);
        if (b == null || !b.containsKey("seq")) return null;
        return new Foreground(b.getString("pkg", ""), b.getLong("seq"));
    }

    @Override public Launch launch(String pkg, String ref) {
        Bundle x = new Bundle();
        x.putString("pkg", pkg);
        x.putString("ref", ref);
        Bundle b = call("launch", x);
        if (b == null || !b.containsKey("ok")) return null;
        return new Launch(b.getBoolean("ok"), b.getString("reason", ""), b.getLong("seqBefore", -1));
    }

    @Override public boolean back(String ref) {
        Bundle x = new Bundle();
        x.putString("ref", ref);
        Bundle b = call("back", x);
        return b != null && b.getBoolean("ok");
    }

    /** Kids mode, from the helper (fail closed: true when it doesn't answer). */
    public boolean kids() {
        Bundle b = call("status", null);
        return b == null || b.getBoolean("kids", true);
    }
}
