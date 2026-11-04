package ai.futurespeak.friday.tv.studio;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * The price table the estimate is built from (ST-E08), never guessed by the model. Seeded from
 * the providers' list prices as of 2026-11-02; the owner can correct any figure by putting a
 * {@code files/studio/prices.json} with the same keys on the TV (the receipts then show the
 * corrected estimate). ElevenLabs' list price is unverified (spec §12), which is why it is a
 * setting. Pure Java.
 */
public final class Prices {
    /** US dollars per million tokens. */
    public static final class Claude {
        public final double in, out, cacheRead, cacheWrite;
        public Claude(double in, double out, double cacheRead, double cacheWrite) { this.in = in; this.out = out; this.cacheRead = cacheRead; this.cacheWrite = cacheWrite; }
        public double usd(long inTok, long outTok, long cacheReadTok, long cacheWriteTok) {
            return (inTok * in + outTok * out + cacheReadTok * cacheRead + cacheWriteTok * cacheWrite) / 1_000_000.0;
        }
    }

    final Map<String, Claude> claude = new HashMap<>();
    /** US dollars per 1,000 characters spoken by ElevenLabs. */
    public double elevenPer1k = 0.05;
    /** US dollars per Firecrawl credit (one page read is one credit). */
    public double firecrawlPerCredit = ai.futurespeak.friday.tv.actions.Web.USD_PER_CREDIT;

    public Prices() {
        claude.put("claude-opus-5-5", new Claude(4.00, 20.00, 0.20, 5.00));
        claude.put("claude-sonnet-5-5", new Claude(2.00, 10.00, 0.20, 2.50));
        claude.put("claude-haiku-5-5", new Claude(0.10, 0.50, 0.01, 0.125));
    }

    /** A model's prices; an unknown model is priced as Opus 5.5, so the estimate errs high, never low. */
    public Claude claude(String model) {
        Claude c = claude.get(model);
        return c != null ? c : claude.get("claude-opus-5-5");
    }

    /** The table with the owner's corrections applied; a missing or broken file leaves the defaults. */
    public static Prices load(File f) {
        Prices p = new Prices();
        try {
            if (f == null || !f.isFile()) return p;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (o.has("elevenlabs_per_1k_chars")) p.elevenPer1k = positive(o.getDouble("elevenlabs_per_1k_chars"));
            if (o.has("firecrawl_per_credit")) p.firecrawlPerCredit = positive(o.getDouble("firecrawl_per_credit"));
            JSONObject c = o.optJSONObject("claude");
            if (c != null) for (java.util.Iterator<String> k = c.keys(); k.hasNext(); ) {
                String m = k.next();
                JSONObject v = c.getJSONObject(m);
                p.claude.put(m, new Claude(positive(v.getDouble("in")), positive(v.getDouble("out")),
                        positive(v.optDouble("cache_read", v.getDouble("in") / 10)), positive(v.optDouble("cache_write", v.getDouble("in") * 1.25))));
            }
        } catch (Exception e) {
            return new Prices();
        }
        return p;
    }

    private static double positive(double d) {
        if (!(d >= 0) || d > 1000) throw new IllegalArgumentException("price");
        return d;
    }
}
