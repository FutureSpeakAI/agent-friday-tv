package ai.futurespeak.friday.tv.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.futurespeak.friday.tv.actions.Catalogue;
import ai.futurespeak.friday.tv.channels.ChannelSpec;

/**
 * What Friday Home shows, decided without Android (unit-tested). The app list is the gate's
 * own catalogue (so Home can never show what voice may not open), narrowed to the kids list in
 * kids mode; the TV's settings tile and News are not offered in kids mode.
 */
final class HomeModel {
    final List<Catalogue.App> apps;
    final List<ChannelSpec> channels;
    final boolean tvSettings;
    final String status;

    private HomeModel(List<Catalogue.App> apps, List<ChannelSpec> channels, boolean tvSettings, String status) {
        this.apps = apps; this.channels = channels; this.tvSettings = tvSettings; this.status = status;
    }

    /** Apps people reach for first on this TV, then the rest alphabetically (the catalogue's order). */
    static final List<String> FIRST = java.util.Arrays.asList(
            "com.plexapp.android", "org.pbskids.video", "com.spotify.tv.android", "org.smarttube.stable",
            "com.amazon.amazonvideo.livingroom", "org.xbmc.kodi", "com.netflix.ninja", "com.google.android.youtube.tv");

    /**
     * @param catalogueApps the gate's catalogue (already without Settings, stores, installers, the helper, Friday)
     * @param kids          kids mode, as the helper reports it (true when it can't say: fail closed)
     */
    static HomeModel of(List<Catalogue.App> catalogueApps, List<ChannelSpec> channels, boolean kids) {
        return of(catalogueApps, channels, kids, null);
    }

    /** As above, with the household's pins, order and hidden apps (ignored in kids mode: one fixed layout). */
    static HomeModel of(List<Catalogue.App> catalogueApps, List<ChannelSpec> channels, boolean kids, HomeLayout layout) {
        List<Catalogue.App> apps = new ArrayList<>();
        for (String p : FIRST) for (Catalogue.App a : catalogueApps) if (a.pkg.equals(p)) apps.add(a);
        for (Catalogue.App a : catalogueApps) if (!apps.contains(a)) apps.add(a);
        if (kids) apps.removeIf(a -> !Catalogue.KIDS_APPS.contains(a.pkg));
        else if (layout != null) apps = layout.order(apps);
        String status = kids
                ? "Kids mode is on: only the kids apps. A grown-up can change this in Ad Break Guard."
                : "Apps open through Friday, so each one has a receipt under What Friday did.";
        return new HomeModel(Collections.unmodifiableList(apps), channels == null ? Collections.emptyList() : channels, !kids, status);
    }
}
