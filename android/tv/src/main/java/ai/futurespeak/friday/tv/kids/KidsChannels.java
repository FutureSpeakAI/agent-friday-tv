package ai.futurespeak.friday.tv.kids;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import ai.futurespeak.friday.tv.actions.Receipts;

/**
 * Which channels a grown-up has marked OK for kids (design decision, 2026-11-02, within the
 * approved kids spec): in kids mode a channel opens only if it is marked, otherwise "That needs
 * a grown-up", and the rail lists only marked ones. Marks change only on the PIN-locked Family
 * page, and each change is receipted. Nothing is marked to start with.
 */
public final class KidsChannels {
    private static final String KEY = "ok_channels";
    private final SharedPreferences p;

    public KidsChannels(Context c) { p = c.getApplicationContext().getSharedPreferences("kids", Context.MODE_PRIVATE); }

    public boolean ok(String channelId) { return channelId != null && p.getStringSet(KEY, new HashSet<>()).contains(channelId); }

    /** Call only inside the grown-up window (the Family page's PIN). */
    public void set(String channelId, boolean ok, Receipts receipts) {
        Set<String> s = new HashSet<>(p.getStringSet(KEY, new HashSet<>()));
        if (ok) s.add(channelId); else s.remove(channelId);
        p.edit().putStringSet(KEY, s).apply();
        if (receipts == null) return;
        try {
            String id = receipts.decision("grownup", new JSONObject().put("action", "kids.channel").put("args", new JSONObject().put("channel", channelId).put("ok", ok))
                    .put("tier", 0).put("origin", "remote").put("who_may", "grown-up window").put("policy", "record"));
            receipts.outcome(id, "grownup", "executed", ok ? "marked OK for kids" : "no longer OK for kids");
        } catch (Exception ignored) { }
    }

    /** The one rule: outside kids mode every channel opens; in kids mode only marked ones. */
    public static boolean mayOpen(boolean kids, boolean markedOk) { return !kids || markedOk; }

    /** The rail's channels: all of them, or in kids mode only the marked ones. */
    public static <T> List<T> forRail(boolean kids, List<T> all, Predicate<T> marked) {
        if (!kids) return all;
        List<T> out = new ArrayList<>();
        for (T t : all) if (marked.test(t)) out.add(t);
        return out;
    }
}
