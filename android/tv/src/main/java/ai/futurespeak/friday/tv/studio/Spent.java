package ai.futurespeak.friday.tv.studio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Money in a job, per service: the estimate's lines before, the actual lines as they happen
 * (spec §10, AC-S2). Its JSON is the receipt's {@code meter}. The meter deliberately has no
 * top-level {@code actual} key: that key means Firecrawl credits on the Spend page.
 * Pure Java.
 */
public final class Spent {
    public static final class Line {
        public final String service, units; public double qty, usd;
        Line(String service, String units) { this.service = service; this.units = units; }
    }

    private final Map<String, Line> lines = new LinkedHashMap<>();

    public synchronized void add(String service, String units, double qty, double usd) {
        Line l = lines.get(service);
        if (l == null) { l = new Line(service, units); lines.put(service, l); }
        l.qty += qty; l.usd += usd;
    }

    public synchronized double usd() {
        double t = 0;
        for (Line l : lines.values()) t += l.usd;
        return t;
    }

    public synchronized Line line(String service) { return lines.get(service); }

    public synchronized void addAll(Spent o) {
        for (Line l : o.copy().lines.values()) add(l.service, l.units, l.qty, l.usd);
    }

    synchronized Spent copy() {
        Spent s = new Spent();
        for (Line l : lines.values()) { Line c = new Line(l.service, l.units); c.qty = l.qty; c.usd = l.usd; s.lines.put(l.service, c); }
        return s;
    }

    /** The receipt's meter: {where, usd (or usd_est), lines: [{service, units, qty, usd}]}. */
    public synchronized JSONObject meter(String key) {
        try {
            JSONArray a = new JSONArray();
            for (Line l : lines.values())
                a.put(new JSONObject().put("service", l.service).put("units", l.units).put("qty", round(l.qty, 1)).put(key, round(l.usd, 4)));
            return new JSONObject().put("service", "studio").put("where", "tv").put(key, round(usd(), 4)).put("lines", a);
        } catch (JSONException e) { return new JSONObject(); }
    }

    public static Spent fromMeter(JSONObject m, String key) {
        Spent s = new Spent();
        if (m == null) return s;
        JSONArray a = m.optJSONArray("lines");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject l = a.optJSONObject(i);
            if (l != null) s.add(l.optString("service"), l.optString("units"), l.optDouble("qty", 0), l.optDouble(key, 0));
        }
        return s;
    }

    static double round(double v, int places) { double f = Math.pow(10, places); return Math.round(v * f) / f; }

    /** "about 70¢", "about $1.40", "less than 1¢". */
    public static String words(double usd) {
        if (usd < 0.005) return "less than 1¢";
        if (usd < 1) return String.format(Locale.US, "about %.0f¢", usd * 100);
        return String.format(Locale.US, "about $%.2f", usd);
    }
}
