package ai.futurespeak.friday.tv.channels;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * The network rule for untrusted pages (channels and HTML cards). Every request a sandboxed
 * page makes goes through {@link #decide}; the WebView never reaches the network by itself.
 * Allowed requests are fetched by the app (GET only, https only, granted hosts only, and
 * never to an address on the home network), so a page cannot reach the router, the TV's
 * other apps, or anything it was not granted.
 */
public final class NetPolicy {
    private NetPolicy() {}

    /** The private origin pages are served from; never resolvable on a real network. */
    public static final String LOCAL_SUFFIX = ".friday.local";
    /** The app's news-feed proxy (see web.FeedProxy): RSS/Atom as JSON, approved feed hosts only. */
    public static final String FEEDS_HOST = "feeds" + LOCAL_SUFFIX;

    /** Friday's voice for channels (web.ChannelVoice): read-aloud without a JavaScript bridge. */
    public static final String VOICE_HOST = "voice" + LOCAL_SUFFIX;

    /**
     * FETCH_IMAGE: a sibling of a granted host (same organisation domain, e.g. thumb.wikimedia.org
     * when upload.wikimedia.org was granted), allowed for pictures only. The caller must accept
     * nothing but image/* from it. Sites move their picture servers (Wikipedia did); a grant for
     * a picture host should keep working without asking again, and pictures carry nothing back.
     */
    public enum Decision { LOCAL, FETCH, FETCH_IMAGE, BLOCK }

    /** The organisation part of a host name: wikimedia.org, bbc.co.uk. Null for an address or a bare name. */
    public static String registrable(String host) {
        if (host == null) return null;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.matches("^[0-9.]+$") || h.contains(":")) return null;
        String[] p = h.split("\\.");
        if (p.length < 2) return null;
        boolean twoPart = p.length >= 3 && p[p.length - 1].length() == 2
                && java.util.Arrays.asList("co", "com", "org", "net", "gov", "ac", "edu", "or", "ne", "go").contains(p[p.length - 2]);
        int keep = twoPart ? 3 : 2;
        if (p.length < keep) return null;
        StringBuilder s = new StringBuilder();
        for (int i = p.length - keep; i < p.length; i++) { if (s.length() > 0) s.append('.'); s.append(p[i]); }
        return s.toString();
    }

    public static boolean siblingOf(String host, List<String> granted) { return sibling(host, granted); }

    static boolean sibling(String host, List<String> granted) {
        String r = registrable(host);
        if (r == null || granted == null) return false;
        for (String g : granted) if (r.equals(registrable(g))) return true;
        return false;
    }

    public static Decision decide(String url, String method, List<String> granted) {
        if (url == null) return Decision.BLOCK;
        URI u;
        try { u = new URI(url); } catch (Exception e) { return Decision.BLOCK; }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (scheme.equals("https") && host.endsWith(LOCAL_SUFFIX)) return Decision.LOCAL;
        if (!scheme.equals("https")) return Decision.BLOCK;
        if (method != null && !method.equalsIgnoreCase("GET")) return Decision.BLOCK;
        if (u.getRawUserInfo() != null) return Decision.BLOCK;
        if (u.getPort() != -1 && u.getPort() != 443) return Decision.BLOCK;
        if (granted != null && granted.contains(host)) return Decision.FETCH;
        if (sibling(host, granted)) return Decision.FETCH_IMAGE;
        return Decision.BLOCK;
    }

    /** Content-Security-Policy for a sandboxed page with these granted hosts. */
    public static String csp(List<String> granted) {
        StringBuilder hosts = new StringBuilder(), siblings = new StringBuilder();
        java.util.Set<String> orgs = new java.util.LinkedHashSet<>();
        if (granted != null) for (String h : granted) { hosts.append(" https://").append(h); String r = registrable(h); if (r != null) orgs.add(r); }
        for (String r : orgs) siblings.append(" https://*.").append(r);   // pictures only (see FETCH_IMAGE)
        return "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                + "img-src data: blob: https://" + FEEDS_HOST + hosts + siblings + "; media-src data: blob:; font-src data:; "
                + "connect-src https://" + VOICE_HOST + (hosts.length() == 0 ? "" : " https://" + FEEDS_HOST + hosts) + "; form-action 'none'; frame-src 'none'; child-src 'none'; "
                + "worker-src 'none'; object-src 'none'; base-uri 'none'; manifest-src 'none'";
    }

    /** True for any address on this device or the home network (blocked even for granted hosts). */
    public static boolean isPrivate(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;    // carrier-grade NAT
            if (b0 == 0 || b0 >= 240) return true;
            if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return true;
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true;
        } else if (b.length == 16) {
            if ((b[0] & 0xfe) == 0xfc) return true;                  // unique local fc00::/7
            boolean mapped = true;                                    // ::ffff:a.b.c.d
            for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPrivate(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (Exception e) { return true; }
            }
        }
        return false;
    }
}
