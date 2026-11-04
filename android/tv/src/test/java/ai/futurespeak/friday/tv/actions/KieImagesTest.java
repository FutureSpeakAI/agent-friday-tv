package ai.futurespeak.friday.tv.actions;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** kie.ai's createTask / recordInfo contract, against a local stand-in server. */
public class KieImagesTest {
    private java.net.ServerSocket server;
    private final ConcurrentLinkedQueue<String> replies = new ConcurrentLinkedQueue<>();
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

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
                    paths.add(h.split(" ")[0] + " " + h.split(" ")[1]);
                    int len = 0;
                    for (String line : h.split("\r\n")) { int i = line.indexOf(':'); if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase("content-length")) len = Integer.parseInt(line.substring(i + 1).trim()); }
                    byte[] b = new byte[len]; int off = 0;
                    while (off < len) { int n = in.read(b, off, len - off); if (n < 0) break; off += n; }
                    bodies.add(new String(b, StandardCharsets.UTF_8));
                    String reply = replies.poll();
                    byte[] r = (reply == null ? "{}" : reply).getBytes(StandardCharsets.UTF_8);
                    OutputStream os = s.getOutputStream();
                    os.write(("HTTP/1.1 200 X\r\nContent-Length: " + r.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    os.write(r); os.flush();
                } catch (Exception ignored) { }
            }
        });
        t.setDaemon(true); t.start();
    }

    @After public void down() throws Exception { server.close(); }

    final List<String> fetched = new ArrayList<>();
    KieImages kie() {
        return new KieImages(() -> "kie-test", "http://127.0.0.1:" + server.getLocalPort(), ms -> { }, url -> { fetched.add(url); return PNG; });
    }

    static String record(String state, int progress, String result) throws Exception {
        JSONObject d = new JSONObject().put("taskId", "t1").put("state", state).put("progress", progress).put("creditsConsumed", 6);
        if (result != null) d.put("resultJson", new JSONObject().put("resultUrls", new org.json.JSONArray().put(result)).toString());
        return new JSONObject().put("code", 200).put("msg", "success").put("data", d).toString();
    }

    @Test public void createsPollsAndFetchesThePicture() throws Exception {
        replies.add("{\"code\":200,\"msg\":\"success\",\"data\":{\"taskId\":\"t1\"}}");
        replies.add(record("generating", 40, null));
        replies.add(record("success", 100, "https://tempfile.example.com/p.png"));
        List<Integer> pct = new ArrayList<>();
        Images.Result r = kie().create("a dragon reading", (p, w) -> pct.add(p));
        assertTrue(r.error, r.ok);
        assertEquals("image/png", r.mime);
        assertEquals(6.0, r.credits, 0);
        assertEquals("POST /api/v1/jobs/createTask", paths.get(0));
        assertTrue(paths.get(1).startsWith("GET /api/v1/jobs/recordInfo?taskId=t1"));
        JSONObject body = new JSONObject(bodies.get(0));
        assertEquals("nano-banana-2", body.getString("model"));
        assertEquals("a dragon reading", body.getJSONObject("input").getString("prompt"));
        assertFalse("no callback: the TV has nothing listening", body.has("callBackUrl"));
        assertEquals("https://tempfile.example.com/p.png", fetched.get(0));
        assertTrue(pct.contains(40));
    }

    @Test public void aFailedTaskIsAFailure() throws Exception {
        replies.add("{\"code\":200,\"data\":{\"taskId\":\"t1\"}}");
        replies.add(record("fail", 0, null));
        assertFalse(kie().create("x", null).ok);
    }

    @Test public void outOfCreditsIsNamed() {
        replies.add("{\"code\":402,\"msg\":\"Insufficient credits\"}");
        assertEquals("out of credits", kie().create("x", null).error);
    }

    @Test public void somethingThatIsNotAPictureIsRefused() throws Exception {
        replies.add("{\"code\":200,\"data\":{\"taskId\":\"t1\"}}");
        replies.add(record("success", 100, "https://x.example.com/p.png"));
        KieImages k = new KieImages(() -> "k", "http://127.0.0.1:" + server.getLocalPort(), ms -> { }, url -> "<html>".getBytes(StandardCharsets.UTF_8));
        assertFalse(k.create("x", null).ok);
    }
}
