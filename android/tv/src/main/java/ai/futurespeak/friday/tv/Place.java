package ai.futurespeak.friday.tv;

import java.util.Locale;

/**
 * Where the TV is, for the weather only: a place name, its coordinates and the temperature unit.
 * Nothing is set until the owner chooses a place (the PC setup wizard sends it with the keys).
 * Unset means no weather is fetched at all. The coordinates go to Open-Meteo and nowhere else.
 * Times are always shown in the TV's own time zone.
 */
public final class Place {
    private Place() {}

    public static volatile String name = "";
    public static volatile double lat = Double.NaN, lon = Double.NaN;
    public static volatile boolean fahrenheit = true;

    public static boolean isSet() { return !Double.isNaN(lat) && !Double.isNaN(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180; }

    public static void set(String n, double la, double lo, boolean f) {
        name = n == null ? "" : n.trim(); lat = la; lon = lo; fahrenheit = f;
    }

    public static void load(Prefs p) { set(p.placeName(), p.placeLat(), p.placeLon(), p.placeFahrenheit()); }

    /** "Portland", or "" when no name was given. */
    public static String label() { return name; }

    /** Open-Meteo's forecast URL for this place (no key, no account), or null when no place is set. */
    public static String weatherUrl(boolean isDay) {
        if (!isSet()) return null;
        return String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,weather_code%s&daily=temperature_2m_max,temperature_2m_min"
                + "&temperature_unit=%s&timezone=auto&forecast_days=1",
                lat, lon, isDay ? ",is_day" : "", fahrenheit ? "fahrenheit" : "celsius");
    }
}
