package ai.futurespeak.friday.tv.cards;

import java.util.Locale;
import java.util.regex.Pattern;

/** Which host names a card image or a channel may reach: public DNS names only. */
public final class HostRules {
    private HostRules() {}

    private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    public static boolean isPublicHostname(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.length() < 4 || h.length() > 253) return false;
        if (h.indexOf(':') >= 0) return false;                         // IPv6 literal
        if (h.matches("^[0-9.]+$")) return false;                       // IPv4 literal
        String[] parts = h.split("\\.", -1);
        if (parts.length < 2) return false;                             // "localhost", "printer"
        for (String p : parts) if (!LABEL.matcher(p).matches()) return false;
        String tld = parts[parts.length - 1];
        if (tld.matches("^[0-9]+$")) return false;
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal") || h.endsWith(".home")
                || h.endsWith(".home.arpa") || h.endsWith(".localhost") || h.endsWith(".arpa") || h.endsWith(".test")
                || h.endsWith(".invalid") || h.endsWith(".corp") || h.endsWith(".intranet")) return false;
        return true;
    }
}
