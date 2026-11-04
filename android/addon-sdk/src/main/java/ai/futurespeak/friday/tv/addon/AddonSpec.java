package ai.futurespeak.friday.tv.addon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An add-on's declaration, read from its {@code <friday-addon>} XML element:
 *
 * <pre>
 * &lt;friday-addon contract="1" id="news" title="News" icon="📰" kids="true"
 *               triggers="news headlines briefing" /&gt;
 * </pre>
 *
 * Pure Java and strict: anything unexpected makes the declaration invalid, and an invalid
 * add-on is never shown. {@code kids="true"} only means the add-on is built for children; a
 * grown-up still has to mark it OK before it appears in kids mode.
 */
public final class AddonSpec {
    public final String packageName, id, title, icon;
    public final int contract;
    public final boolean kids;
    /** Lower-case single words; a spoken request that contains one is offered to the add-on. */
    public final List<String> triggers;

    private AddonSpec(String pkg, String id, String title, String icon, int contract, boolean kids, List<String> triggers) {
        this.packageName = pkg; this.id = id; this.title = title; this.icon = icon;
        this.contract = contract; this.kids = kids; this.triggers = Collections.unmodifiableList(triggers);
    }

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern PKG = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    private static final Pattern WORD = Pattern.compile("[a-z]{3,24}");

    /** The declaration, or null when it is invalid or needs a newer contract than this one. */
    public static AddonSpec parse(String packageName, Map<String, String> a) {
        if (packageName == null || !PKG.matcher(packageName).matches() || a == null) return null;
        if (packageName.equals(Contract.CORE_PACKAGE)) return null;
        int contract;
        try { contract = Integer.parseInt(a.getOrDefault("contract", "")); } catch (NumberFormatException e) { return null; }
        if (contract < 1 || contract > Contract.VERSION) return null;
        String id = a.getOrDefault("id", "");
        if (!ID.matcher(id).matches()) return null;
        String title = clean(a.get("title"));
        if (title.isEmpty() || title.codePointCount(0, title.length()) > 24) return null;
        String icon = clean(a.get("icon"));
        if (icon.isEmpty() || icon.codePointCount(0, icon.length()) > 4) icon = "✦";
        String k = a.getOrDefault("kids", "false");
        if (!k.equals("true") && !k.equals("false")) return null;
        List<String> t = new ArrayList<>();
        for (String w : a.getOrDefault("triggers", "").toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (!WORD.matcher(w).matches() || t.size() >= 8) return null;
            if (!t.contains(w)) t.add(w);
        }
        return new AddonSpec(packageName, id, title, icon, contract, k.equals("true"), t);
    }

    /** True when a spoken request contains one of this add-on's trigger words as a whole word. */
    public boolean mightWant(String utterance) {
        if (utterance == null || triggers.isEmpty()) return false;
        String u = " " + utterance.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ") + " ";
        for (String w : triggers) if (u.contains(" " + w + " ")) return true;
        return false;
    }

    /** The id the kids marks and receipts use, so an add-on can never pose as a channel. */
    public String key() { return "addon:" + id; }

    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        s.trim().codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT).forEach(b::appendCodePoint);
        return b.toString().trim();
    }
}
