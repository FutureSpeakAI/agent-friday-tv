package ai.futurespeak.friday.tv.home;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ai.futurespeak.friday.tv.actions.Catalogue;

/**
 * Friday Home's customization, as plain values (unit-tested; stored by {@link HomePrefs}).
 * Everything here only rearranges what the gate's catalogue already allows: pins and hiding
 * can reorder or remove an app tile, never add one.
 */
public final class HomeLayout {
    public enum Wallpaper { AMBIENT, CONSTELLATION, HORIZON, MARK, SOLID }
    public enum Motion { FULL, CALM, OFF }
    public enum Side { RIGHT, LEFT }

    /** Rows of the board, in the order a person can rearrange. */
    public static final List<String> ROWS = Collections.unmodifiableList(Arrays.asList("hero", "apps", "channels", "friday"));
    public static final String[] ROW_NAMES = {"Talk and Friday", "Apps", "Friday's channels", "Settings"};

    /** Accents from Friday's brand (cyan, violet, magenta, signal green, amber). */
    public static final int[] ACCENTS = {0xFF00D4FF, 0xFF7B61FF, 0xFFFF00FF, 0xFF00FF80, 0xFFF59E0B};
    public static final String[] ACCENT_NAMES = {"Cyan", "Violet", "Magenta", "Signal green", "Amber"};

    /** Avatar stage width as a share of the screen, like desktop Simple mode's Focus / Companion / Present. */
    public static final float[] STAGE = {0.21f, 0.30f, 0.42f};
    public static final String[] STAGE_NAMES = {"Small", "Medium", "Large"};

    public int accent = 0;
    public Wallpaper wallpaper = Wallpaper.AMBIENT;
    public boolean avatar = true;
    public int avatarSize = 1;
    public Side side = Side.RIGHT;
    public List<String> rows = new ArrayList<>(ROWS);
    public Set<String> hiddenRows = new LinkedHashSet<>();
    public boolean clock = true, weather = true;
    public Motion motion = Motion.FULL;
    /** Apps pinned to the front, in order. */
    public List<String> pins = new ArrayList<>();
    public Set<String> hiddenApps = new LinkedHashSet<>();

    public int accentColor() { return ACCENTS[Math.floorMod(accent, ACCENTS.length)]; }
    public float stage() { return avatar ? STAGE[Math.max(0, Math.min(STAGE.length - 1, avatarSize))] : 0f; }

    /** Every known row once, in the saved order, then any new ones (so an old save never loses a row). */
    public List<String> rowOrder() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String r : rows) if (ROWS.contains(r)) out.add(r);
        out.addAll(ROWS);
        return new ArrayList<>(out);
    }

    public List<String> visibleRows() {
        List<String> out = new ArrayList<>();
        for (String r : rowOrder()) if (!hiddenRows.contains(r)) out.add(r);
        if (out.isEmpty()) out.add("hero");   // Home can never be emptied: Talk always stays
        return out;
    }

    public void moveRow(String row, int delta) {
        List<String> o = rowOrder();
        int i = o.indexOf(row), j = i + delta;
        if (i < 0 || j < 0 || j >= o.size()) return;
        Collections.swap(o, i, j);
        rows = o;
    }

    /**
     * The app tiles, in order: pins first (as pinned), then the default order; hidden ones removed.
     * Only apps in {@code allowed} (the gate's catalogue, already filtered) can appear.
     */
    public List<Catalogue.App> order(List<Catalogue.App> allowed) {
        List<Catalogue.App> out = new ArrayList<>();
        for (String p : pins) for (Catalogue.App a : allowed) if (a.pkg.equals(p) && !out.contains(a)) out.add(a);
        for (Catalogue.App a : allowed) if (!out.contains(a)) out.add(a);
        out.removeIf(a -> hiddenApps.contains(a.pkg));
        return out;
    }

    /** Pin to the front (or move a pinned app to the front). */
    public void pinFirst(String pkg) { pins.remove(pkg); pins.add(0, pkg); hiddenApps.remove(pkg); }

    /** Move an app one place left/right in what's shown; it becomes pinned in that place. */
    public void move(List<Catalogue.App> allowed, String pkg, int delta) {
        List<String> shown = new ArrayList<>();
        for (Catalogue.App a : order(allowed)) shown.add(a.pkg);
        int i = shown.indexOf(pkg), j = i + delta;
        if (i < 0 || j < 0 || j >= shown.size()) return;
        Collections.swap(shown, i, j);
        int keep = Math.max(j, i) + 1;   // pin everything up to the moved pair, so the order sticks
        pins = new ArrayList<>(shown.subList(0, keep));
    }

    public void hide(String pkg) { hiddenApps.add(pkg); pins.remove(pkg); }
    public void unhideAll() { hiddenApps.clear(); }

    /** Kids mode keeps one fixed layout, whatever the grown-ups chose. */
    public static HomeLayout kidsFixed() {
        HomeLayout k = new HomeLayout();
        k.rows = new ArrayList<>(Arrays.asList("hero", "apps"));
        k.hiddenRows = new LinkedHashSet<>(Arrays.asList("channels", "friday"));
        k.motion = Motion.CALM;
        return k;
    }
}
