package ai.futurespeak.friday.tv.ambient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The local weather for the screensaver, from open-meteo.com (free, no key, no account).
 * Fetched at most every 20 minutes and shared; only the chosen place's coordinates are sent,
 * and nothing is fetched until a place is set ({@link ai.futurespeak.friday.tv.Place}).
 */
public final class Weather {
    private static final long FRESH_MS = 20 * 60_000L;

    public static final class Now {
        public final int temp, hi, lo, code;
        Now(int t, int h, int l, int c) { temp = t; hi = h; lo = l; code = c; }
        public String line() { return (place().isEmpty() ? "" : place() + "  ·  ") + temp + "°  " + describe(code) + "  ·  H " + hi + "°  L " + lo + "°"; }
        public String icon() { return Weather.icon(code); }
        /** Two short lines for the screensaver: "82°  Cloudy" / "H 85°  ·  L 66°  ·  Portland". */
        public String twoLines() { return temp + "°  " + describe(code) + "\nH " + hi + "°  ·  L " + lo + "°" + (place().isEmpty() ? "" : "  ·  " + place()); }
    }

    public static String place() { return ai.futurespeak.friday.tv.Place.label(); }

    private static volatile Now last;
    private static volatile long fetchedAt;

    private Weather() {}

    public static Now cached() { return last; }

    /** Blocking; call off the main thread. Returns the cached value if it's fresh. */
    public static Now get() {
        if (!ai.futurespeak.friday.tv.Place.isSet()) return null;
        if (last != null && System.currentTimeMillis() - fetchedAt < FRESH_MS) return last;
        HttpURLConnection c = null;
        try {
            String url = ai.futurespeak.friday.tv.Place.weatherUrl(false);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "AgentFridayTV/0.1");
            if (c.getResponseCode() != 200) return last;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) { byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0 && bos.size() < 65536) bos.write(b, 0, n); }
            last = parse(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            fetchedAt = System.currentTimeMillis();
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return last;
    }

    static Now parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONObject cur = o.getJSONObject("current");
        JSONObject d = o.getJSONObject("daily");
        return new Now((int) Math.round(cur.getDouble("temperature_2m")),
                (int) Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)),
                (int) Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)),
                cur.getInt("weather_code"));
    }

    /** WMO weather codes in plain words. */
    public static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 85 && code <= 86) return "Snow showers";
        if (code >= 95) return "Thunderstorms";
        return "—";
    }

    public static String icon(int code) {
        if (code == 0) return "☀";
        if (code <= 2) return "⛅";
        if (code == 3 || code == 45 || code == 48) return "☁";
        if (code >= 95) return "⛈";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄";
        return "☂";
    }
}
