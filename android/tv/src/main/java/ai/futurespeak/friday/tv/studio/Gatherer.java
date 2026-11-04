package ai.futurespeak.friday.tv.studio;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ai.futurespeak.friday.tv.actions.Web;

/**
 * What a podcast is built from, so a News story never dead-ends on its feed blurb (
 * 2026-11-02). In order: the story's full article (one Firecrawl read); if that is paywalled or
 * still thin, 2 to 4 other outlets covering the same story, from News's own "other takes"
 * first and a Firecrawl search second. Then the lengths are offered to fit the material: only a
 * 2-minute brief when it is thin, 5 or 10 minutes when there is enough. It never refuses a
 * News story: the headline and summary alone still make a brief, and the card says plainly
 * what it was built from.
 *
 * Pure Java. It never touches the web itself: the gate hands it a {@link Reader} over its own
 * Firecrawl tool, so every read and search is the gate's, checked and metered.
 */
public final class Gatherer {
    public interface Reader {
        Web.Result read(String url);
        Web.Result search(String query);
    }

    /** An article at least this long is the real thing, not a blurb. */
    public static final int FULL = 1500;
    /** Another outlet's page worth citing. */
    public static final int USEFUL = 800;
    public static final int MAX_OTHERS = 4;
    /** Firecrawl reads at most, besides the story's own (each one credit, receipted). */
    public static final int MAX_READS = 6;

    public static final class Result {
        public final List<StudioSource> sources;
        public final Spent spent;
        /** Plain words for the card: what it was built from. */
        public final String note;
        public final int chars;
        /** The lengths the material supports (2 only, when thin). */
        public final int[] lengths;
        Result(List<StudioSource> sources, Spent spent, String note, int chars, int[] lengths) {
            this.sources = sources; this.spent = spent; this.note = note; this.chars = chars; this.lengths = lengths;
        }
        public boolean allows(int len) { for (int l : lengths) if (l == len) return true; return false; }
        public int longest() { return lengths[lengths.length - 1]; }
    }

    public static Result gather(StudioSource primary, Reader reader, double usdPerCredit) {
        Spent spent = new Spent();
        String host = host(primary.url);
        String outlet = primary.outlet.isEmpty() ? host : primary.outlet;
        boolean readOwn = false;

        // 1. The story's own full article.
        if (primary.text.length() < FULL && !primary.url.isEmpty()) {
            Web.Result r = reader.read(primary.url);
            count(spent, r, usdPerCredit);
            readOwn = r.ok;
            String text = r.ok ? clean(r.text) : "";
            if (!paywalled(text) && text.length() > primary.text.length()) primary = primary.withText(r.title, text);
        }
        List<StudioSource> sources = new ArrayList<>();
        sources.add(primary);
        if (primary.text.length() >= FULL) {
            return done(sources, spent, readOwn || primary.kind.equals("web")
                    ? "the full article from " + outlet + "."
                    : "the full story from " + outlet + ".");
        }

        // 2. Other outlets covering the same story: News's own grouping first, then a search.
        List<StudioSource> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (!host.isEmpty()) seen.add(host);
        for (StudioSource s : primary.related) addCandidate(candidates, seen, s);
        if (candidates.size() < 2 && !primary.title.isEmpty()) {
            Web.Result r = reader.search(primary.title);
            count(spent, r, usdPerCredit);
            if (r.ok) for (StudioSource s : parseSearch(r.text)) addCandidate(candidates, seen, s);
        }
        List<StudioSource> others = new ArrayList<>();
        int reads = 0;
        for (StudioSource c : candidates) {
            if (others.size() >= MAX_OTHERS || reads >= MAX_READS) break;
            if (others.size() >= 2 && total(sources, others) >= 8000) break;
            Web.Result r = reader.read(c.url);
            reads++;
            count(spent, r, usdPerCredit);
            String text = r.ok ? clean(r.text) : "";
            if (text.length() >= USEFUL && !paywalled(text)) others.add(c.withText(r.title, text));
        }
        sources.addAll(others);
        String note;
        if (others.isEmpty()) {
            note = "the headline and summary from " + outlet + " only: the full story wasn't available"
                    + (candidates.isEmpty() ? " and no other coverage was found" : " and the other coverage couldn't be read")
                    + ", so it's a short brief.";
        } else {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < others.size(); i++) {
                if (i > 0) names.append(i == others.size() - 1 ? " and " : ", ");
                names.append(others.get(i).outlet.isEmpty() ? host(others.get(i).url) : others.get(i).outlet);
            }
            note = outlet + " only shares a summary" + (readOwn ? " (the full story is behind a paywall or too short)" : "")
                    + ", so it's built from that and " + others.size() + " other outlet" + (others.size() == 1 ? "" : "s")
                    + " covering the same story: " + names + ".";
        }
        return done(sources, spent, note);
    }

    private static Result done(List<StudioSource> sources, Spent spent, String note) {
        int chars = total(sources, new ArrayList<>());
        return new Result(sources, spent, cap(note), chars, lengthsFor(chars));
    }

    /** Thin material makes a 2-minute brief; 5 minutes needs a real article; 10 needs plenty. */
    public static int[] lengthsFor(int chars) {
        if (chars < 3000) return new int[]{2};
        if (chars < 8000) return new int[]{2, 5};
        return new int[]{2, 5, 10};
    }

    static int total(List<StudioSource> a, List<StudioSource> b) {
        int t = 0;
        for (StudioSource s : a) t += Math.min(StudioSource.MAX_CHARS, s.text.length());
        for (StudioSource s : b) t += Math.min(StudioSource.MAX_CHARS, s.text.length());
        return t;
    }

    private static void addCandidate(List<StudioSource> out, Set<String> seen, StudioSource s) {
        String h = host(s.url);
        if (h.isEmpty() || !seen.add(h)) return;   // one page per outlet, never the story's own
        out.add(s);
    }

    private static void count(Spent spent, Web.Result r, double usdPerCredit) {
        if (r != null && r.credits > 0) spent.add("firecrawl", "credits", r.credits, r.credits * usdPerCredit);
    }

    private static final Pattern RESULT = Pattern.compile("(?m)^\\s*\\d+\\.\\s*(.*)\\n\\s+(https?://\\S+)");

    /** Firecrawl's search text ("1. Title\n   url\n   description") as candidate sources. */
    static List<StudioSource> parseSearch(String text) {
        List<StudioSource> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = RESULT.matcher(text);
        while (m.find()) out.add(new StudioSource("web", m.group(1).trim(), host(m.group(2)), m.group(2).trim(), ""));
        return out;
    }

    static String host(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String h = new java.net.URI(url.trim()).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception e) { return ""; }
    }

    /** Markdown link clutter out; a scrape is mostly text already. */
    static String clean(String t) {
        if (t == null) return "";
        return t.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "").replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1").replaceAll("[ \\t]+", " ").trim();
    }

    private static final Pattern PAYWALL = Pattern.compile("(?i)\\b(subscribe (now )?to (continue|keep) reading|to continue reading|already a subscriber|"
            + "subscribers only|this article is for subscribers|create a free account to continue|you have reached your (free )?article limit)\\b");

    /** A short page that is mostly a subscribe prompt is a paywall, however long its navigation is. */
    static boolean paywalled(String text) {
        return text != null && text.length() < 6000 && PAYWALL.matcher(text).find();
    }

    static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
