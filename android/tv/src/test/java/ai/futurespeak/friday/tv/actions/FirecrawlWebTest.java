package ai.futurespeak.friday.tv.actions;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Firecrawl v2 requests and answers, against a local stand-in server. */
public class FirecrawlWebTest {
    private java.net.ServerSocket server;
    private final AtomicReference<String> body = new AtomicReference<>(), auth = new AtomicReference<>(), path = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{}";

    @Before public void up() throws Exception {
        server = new java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (java.net.Socket s = server.accept()) {
                    java.io.InputStream in = s.getInputStream();
                    java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
                    int c, m = 0;
                    while ((c = in.read()) >= 0) { head.write(c); m = (c == '\r' || c == '\n') ? m + 1 : 0; if (m == 4) break; }
                    String h = head.toString("UTF-8");
                    path.set(h.split(" ")[1]);
                    int len = 0;
                    for (String line : h.split("\r\n")) {
                        int i = line.indexOf(':'); if (i < 0) continue;
                        String k = line.substring(0, i).trim().toLowerCase(), v = line.substring(i + 1).trim();
                        if (k.equals("content-length")) len = Integer.parseInt(v);
                        if (k.equals("authorization")) auth.set(v);
                    }
                    byte[] b = new byte[len]; int off = 0;
                    while (off < len) { int n = in.read(b, off, len - off); if (n < 0) break; off += n; }
                    body.set(new String(b, StandardCharsets.UTF_8));
                    byte[] r = reply.getBytes(StandardCharsets.UTF_8);
                    OutputStream os = s.getOutputStream();
                    os.write(("HTTP/1.1 " + status + " X\r\nContent-Length: " + r.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    os.write(r); os.flush();
                } catch (Exception ignored) { }
            }
        });
        t.setDaemon(true); t.start();
    }

    @After public void down() throws Exception { server.close(); }

    FirecrawlWeb fw(String key) { return new FirecrawlWeb(() -> key, "http://127.0.0.1:" + server.getLocalPort() + "/v2"); }

    @Test public void searchSendsTheQueryAndReadsWebResults() throws Exception {
        reply = "{\"success\":true,\"data\":{\"web\":[{\"title\":\"Riverside Tree\",\"url\":\"https://a.gov/t\",\"description\":\"Lights at 6\"}]},\"creditsUsed\":2}";
        Web.Result r = fw("fc-test").search("riverside tree");
        assertTrue(r.ok);
        assertEquals("/v2/search", path.get());
        assertEquals("Bearer fc-test", auth.get());
        JSONObject b = new JSONObject(body.get());
        assertEquals("riverside tree", b.getString("query"));
        assertEquals(FirecrawlWeb.SEARCH_LIMIT, b.getInt("limit"));
        assertFalse("no page scraping inside search (cheaper)", b.has("scrapeOptions"));
        assertTrue(r.text.contains("Riverside Tree") && r.text.contains("https://a.gov/t"));
        assertEquals(2.0, r.credits, 0);
    }

    @Test public void scrapeAsksForMainContentMarkdown() throws Exception {
        reply = "{\"success\":true,\"data\":{\"markdown\":\"# Tree\\nLights at 6\",\"metadata\":{\"title\":\"Tree\",\"sourceURL\":\"https://a.gov/t\",\"statusCode\":200,\"creditsUsed\":1}}}";
        Web.Result r = fw("k").read("https://a.gov/t");
        assertEquals("/v2/scrape", path.get());
        JSONObject b = new JSONObject(body.get());
        assertTrue(b.getBoolean("onlyMainContent"));
        assertEquals("markdown", b.getJSONArray("formats").getString(0));
        assertTrue(r.ok);
        assertEquals("Tree", r.title);
        assertTrue(r.text.contains("Lights at 6"));
    }

    @Test public void errorsBecomePlainReasons() {
        status = 402; reply = "{\"success\":false}";
        assertEquals("out of credits", fw("k").search("x").error);
        status = 401;
        assertEquals("key refused", fw("k").read("https://a.gov").error);
        assertEquals("no key", fw("").search("x").error);
        assertFalse(fw(null).ready());
    }
}
