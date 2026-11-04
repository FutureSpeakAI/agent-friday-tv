package ai.futurespeak.friday.tv.actions;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/**
 * Slice S2 through the helper's media-session reader: {@code media_now} and
 * {@code media_send}. The helper holds the notification-listener grant and has no internet; this
 * side passes a command word, a package and a ref, nothing else. When the helper doesn't have
 * these methods yet (an older Ad Break Guard), every call returns null and Friday falls back to
 * {@link KeyPlayer}, with "unverified" outcomes.
 */
public final class HelperPlayer implements Player {
    private static final Uri URI = Uri.parse("content://org.tvprivacy.adbreak.grownup");
    private final Context ctx;
    private final Player fallback;

    public HelperPlayer(Context c, Player fallback) { ctx = c.getApplicationContext(); this.fallback = fallback; }

    private Bundle call(String method, Bundle extras) {
        try { return ctx.getContentResolver().call(URI, method, null, extras); }
        catch (Throwable t) { return null; }   // unknown method on an older helper, or not installed
    }

    /** Whether the helper answers media_now right now (it has the listener grant and the method). */
    @Override public boolean canRead() {
        Bundle b = call("media_now", null);
        return b != null && b.containsKey("state");
    }

    @Override public Now now() {
        Bundle b = call("media_now", null);
        if (b == null || !b.containsKey("state")) return fallback.now();
        return new Now(b.getString("pkg", ""), b.getString("state", "none"), b.getString("title", ""), b.getString("artist", ""), b.getLong("positionMs", -1));
    }

    @Override public boolean send(String command, String pkg, String ref) {
        if (!Player.COMMANDS.contains(command)) return false;
        Bundle x = new Bundle();
        x.putString("command", command);
        x.putString("pkg", pkg == null ? "" : pkg);
        x.putString("ref", ref == null ? "" : ref);
        Bundle b = call("media_send", x);
        if (b != null && b.containsKey("ok")) return b.getBoolean("ok");
        return fallback.send(command, pkg, ref);   // the helper can't yet: a plain media key
    }
}
