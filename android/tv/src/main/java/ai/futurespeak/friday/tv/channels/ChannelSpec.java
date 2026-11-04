package ai.futurespeak.friday.tv.channels;

import ai.futurespeak.friday.tv.cards.CardSpec;
import ai.futurespeak.friday.tv.cards.HostRules;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A channel: a small saved mini-app (recipe board, homework helper, trivia, weather board)
 * that Claude proposes as {@code <channel>{json}</channel>}. This class only checks the
 * proposal; saving it and any network it wants are approved by the person on the TV's
 * fixed approval screen.
 */
public final class ChannelSpec {
    public static final int MAX_HTML = 200_000;
    public static final int MAX_HOSTS = 8;   // a news board draws on several feeds

    public final String id;          // set when saved
    public final String title;
    public final String emoji;
    public final String description;
    public final String html;
    /** Hosts the channel asked to reach (https only). Empty = no network. */
    public final List<String> requestedHosts;
    /** Hosts the grown-up approved. Always a subset of requestedHosts. */
    public final List<String> grantedHosts;
    public final long createdAt;

    public ChannelSpec(String id, String title, String emoji, String description, String html,
                       List<String> requestedHosts, List<String> grantedHosts, long createdAt) {
        this.id = id;
        this.title = title;
        this.emoji = emoji;
        this.description = description;
        this.html = html;
        this.requestedHosts = Collections.unmodifiableList(new ArrayList<>(requestedHosts));
        List<String> g = new ArrayList<>();
        for (String h : grantedHosts) if (requestedHosts.contains(h)) g.add(h);
        this.grantedHosts = Collections.unmodifiableList(g);
        this.createdAt = createdAt;
    }

    /** Thrown with a reason the TV can show ("The channel's page is too big."). */
    public static final class Rejected extends Exception {
        public Rejected(String m) { super(m); }
    }

    /** Checks a proposal from the brain. */
    public static ChannelSpec parseProposal(String json) throws Rejected {
        JSONObject o;
        try {
            Object root = new JSONTokener(stripFence(json)).nextValue();
            if (!(root instanceof JSONObject)) throw new Rejected("That channel wasn't readable.");
            o = (JSONObject) root;
        } catch (JSONException e) {
            throw new Rejected("That channel wasn't readable.");
        }
        String title = o.optString("title", "").trim();
        if (title.isEmpty()) throw new Rejected("The channel has no name.");
        if (title.length() > 40) title = title.substring(0, 40).trim();
        String html = o.optString("html", "");
        if (html.trim().isEmpty()) throw new Rejected("The channel has no page.");
        if (html.length() > MAX_HTML) throw new Rejected("The channel's page is too big for the TV.");
        List<String> hosts = hosts(o.optJSONArray("network"));
        String emoji = firstGrapheme(o.optString("emoji", "📺"));
        String desc = o.optString("description", "").trim();
        if (desc.length() > 160) desc = desc.substring(0, 159) + "…";
        return new ChannelSpec(null, title, emoji, desc, html, hosts, Collections.emptyList(), System.currentTimeMillis());
    }

    static List<String> hosts(JSONArray a) throws Rejected {
        Set<String> out = new LinkedHashSet<>();
        if (a == null) return new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String h = a.optString(i, "").trim().toLowerCase(Locale.ROOT);
            if (h.startsWith("https://")) h = h.substring(8);
            int slash = h.indexOf('/');
            if (slash >= 0) h = h.substring(0, slash);
            if (h.isEmpty()) continue;
            if (h.equals(NetPolicy.FEEDS_HOST)) continue;   // the app's own feed proxy: always there, never a grant
            if (h.contains("*")) throw new Rejected("A channel can't ask for wildcard sites.");
            if (!HostRules.isPublicHostname(h)) throw new Rejected("A channel can only reach public websites.");
            out.add(h);
        }
        if (out.size() > MAX_HOSTS) throw new Rejected("A channel can reach at most " + MAX_HOSTS + " sites.");
        return new ArrayList<>(out);
    }

    public ChannelSpec withId(String newId, List<String> granted) {
        return new ChannelSpec(newId, title, emoji, description, html, requestedHosts, granted, createdAt);
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("title", title).put("emoji", emoji).put("description", description)
                .put("html", html).put("requested", new JSONArray(requestedHosts)).put("granted", new JSONArray(grantedHosts))
                .put("created", createdAt);
    }

    public static ChannelSpec fromJson(JSONObject o) throws Rejected {
        List<String> req = hosts(o.optJSONArray("requested"));
        List<String> granted = hosts(o.optJSONArray("granted"));
        String html = o.optString("html", "");
        if (html.length() > MAX_HTML) throw new Rejected("stored channel too big");
        return new ChannelSpec(o.optString("id", null), o.optString("title", "Channel"), o.optString("emoji", "📺"),
                o.optString("description", ""), html, req, granted, o.optLong("created", 0));
    }

    static String stripFence(String s) {
        return s == null ? "" : CardSpec.stripFence(s);
    }

    static String firstGrapheme(String s) {
        if (s == null || s.isEmpty()) return "📺";
        java.text.BreakIterator it = java.text.BreakIterator.getCharacterInstance();
        it.setText(s);
        int end = it.next();
        return end > 0 && end <= 16 ? s.substring(0, end) : "📺";
    }

}
