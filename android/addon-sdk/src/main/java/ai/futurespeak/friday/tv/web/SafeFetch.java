package ai.futurespeak.friday.tv.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.function.Predicate;

import ai.futurespeak.friday.tv.channels.NetPolicy;

/**
 * GET on behalf of a page: https only, no cookies, no credentials, a size cap, redirects
 * followed by hand (each hop re-checked), and never to an address on the home network.
 */
public final class SafeFetch {
    public static final class Result {
        public final int status; public final String contentType; public final byte[] body;
        Result(int s, String ct, byte[] b) { status = s; contentType = ct; body = b; }
    }

    private SafeFetch() {}

    /**
     * @param hostOk which hosts may be reached (each redirect hop too)
     * @param typeOk which Content-Types are accepted (lower-case, without parameters)
     */
    public static Result get(String url, Predicate<String> hostOk, Predicate<String> typeOk, int maxBytes) throws IOException {
        String current = url;
        for (int hop = 0; hop < 4; hop++) {
            URI u;
            try { u = new URI(current); } catch (Exception e) { throw new IOException("bad url"); }
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getRawUserInfo() != null) throw new IOException("blocked");
            if (u.getPort() != -1 && u.getPort() != 443) throw new IOException("blocked port");
            String host = u.getHost().toLowerCase(java.util.Locale.ROOT);
            if (!hostOk.test(host)) throw new IOException("host not allowed");
            for (InetAddress a : InetAddress.getAllByName(host)) if (NetPolicy.isPrivate(a)) throw new IOException("private address");
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                c.setRequestProperty("User-Agent", "AgentFridayTV/0.1");
                c.setRequestProperty("Accept", "*/*");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("redirect without location");
                    current = u.resolve(loc).toString();
                    continue;
                }
                String ct = c.getContentType() == null ? "" : c.getContentType().split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
                if (code == 200 && !typeOk.test(ct)) throw new IOException("type not allowed");
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (in != null) try (InputStream is = in) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = is.read(buf)) > 0) {
                        if (bos.size() + n > maxBytes) throw new IOException("too large");
                        bos.write(buf, 0, n);
                    }
                }
                return new Result(code, ct, bos.toByteArray());
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }
}
