package ai.futurespeak.friday.tv.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/** Where Friday Home's customization lives on the TV (its own preferences file, nothing shared). */
final class HomePrefs {
    private final SharedPreferences p;
    HomePrefs(Context c) { p = c.getApplicationContext().getSharedPreferences("home", Context.MODE_PRIVATE); }

    HomeLayout load() {
        HomeLayout l = new HomeLayout();
        l.accent = p.getInt("accent", 0);
        l.wallpaper = e(HomeLayout.Wallpaper.class, p.getString("wallpaper", null), HomeLayout.Wallpaper.AMBIENT);
        l.avatar = p.getBoolean("avatar", true);
        l.avatarSize = p.getInt("avatarSize", 1);
        l.side = e(HomeLayout.Side.class, p.getString("side", null), HomeLayout.Side.RIGHT);
        l.rows = list(p.getString("rows", null), new ArrayList<>(HomeLayout.ROWS));
        l.hiddenRows = new LinkedHashSet<>(list(p.getString("hiddenRows", null), new ArrayList<>()));
        l.clock = p.getBoolean("clock", true);
        l.weather = p.getBoolean("weather", true);
        l.motion = e(HomeLayout.Motion.class, p.getString("motion", null), HomeLayout.Motion.FULL);
        l.pins = list(p.getString("pins", null), new ArrayList<>());
        l.hiddenApps = new LinkedHashSet<>(list(p.getString("hiddenApps", null), new ArrayList<>()));
        return l;
    }

    void save(HomeLayout l) {
        p.edit().putInt("accent", l.accent).putString("wallpaper", l.wallpaper.name()).putBoolean("avatar", l.avatar)
                .putInt("avatarSize", l.avatarSize).putString("side", l.side.name()).putString("rows", String.join(",", l.rows))
                .putString("hiddenRows", String.join(",", l.hiddenRows)).putBoolean("clock", l.clock).putBoolean("weather", l.weather)
                .putString("motion", l.motion.name()).putString("pins", String.join(",", l.pins))
                .putString("hiddenApps", String.join(",", l.hiddenApps)).apply();
    }

    private static List<String> list(String s, List<String> dflt) {
        if (s == null) return dflt;
        List<String> out = new ArrayList<>();
        for (String x : Arrays.asList(s.split(","))) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }

    private static <T extends Enum<T>> T e(Class<T> c, String s, T dflt) {
        try { return s == null ? dflt : Enum.valueOf(c, s); } catch (Exception x) { return dflt; }
    }
}
