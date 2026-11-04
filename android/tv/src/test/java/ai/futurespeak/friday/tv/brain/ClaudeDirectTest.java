package ai.futurespeak.friday.tv.brain;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ClaudeDirectTest {
    private java.net.ServerSocket server;
    private final AtomicReference<String> gotBody = new AtomicReference<>();
    private final AtomicReference<String> gotAuth = new AtomicReference<>();
    private final AtomicReference<String> gotXApiKey = new AtomicReference<>();
    private final AtomicReference<String> gotVersion = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "";
    /** When not empty, each request takes the next reply from here (tool-use rounds). */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> replies = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final List<String> bodies = java.util.Collections.synchronizedList(new ArrayList<>());

    /** A tiny HTTP/1.1 server: one request per connection, enough for these tests. */
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
                    int len = 0;
                    for (String line : h.split("\r\n")) {
                        int i = line.indexOf(':');
                        if (i < 0) continue;
                        String k = line.substring(0, i).trim().toLowerCase(), v = line.substring(i + 1).trim();
                        if (k.equals("content-length")) len = Integer.parseInt(v);
                        if (k.equals("authorization")) gotAuth.set(v);
                        if (k.equals("x-api-key")) gotXApiKey.set(v);
                        if (k.equals("anthropic-version")) gotVersion.set(v);
                    }
                    byte[] body = new byte[len];
                    int off = 0;
                    while (off < len) { int n = in.read(body, off, len - off); if (n < 0) break; off += n; }
                    gotBody.set(new String(body, StandardCharsets.UTF_8));
                    bodies.add(gotBody.get());
                    String next = replies.poll();
                    byte[] b = (next != null ? next : reply).getBytes(StandardCharsets.UTF_8);
                    OutputStream os = s.getOutputStream();
                    os.write(("HTTP/1.1 " + status + " X\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    os.write(b);
                    os.flush();
                } catch (Exception ignored) { }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @After public void down() throws Exception { server.close(); }

    private String base() { return "http://127.0.0.1:" + server.getLocalPort(); }

    static String sse(String... events) {
        StringBuilder s = new StringBuilder();
        for (String e : events) s.append("event: x\ndata: ").append(e).append("\n\n");
        return s.toString();
    }

    static final class Rec implements Brain.Listener {
        final StringBuilder text = new StringBuilder();
        String done, stop; BrainException err;
        final CountDownLatch latch = new CountDownLatch(1);
        @Override public void onText(String d) { text.append(d); }
        @Override public void onDone(String f, String s) { done = f; stop = s; latch.countDown(); }
        @Override public void onError(BrainException e) { err = e; latch.countDown(); }
    }

    /** Emoji and "·" arriving a byte at a time (split across reads) must decode exactly. */
    @Test public void multibyteTextSurvivesSplitReads() throws Exception {
        String text = "📰 Riverbend News · Riverton — 12 min ago…";
        String body = sse("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":"
                + org.json.JSONObject.quote(text) + "}}", "{\"type\":\"message_stop\"}");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        java.io.InputStream oneByteAtATime = new java.io.InputStream() {
            int i = 0;
            @Override public int read() { return i < bytes.length ? bytes[i++] & 0xff : -1; }
            @Override public int read(byte[] b, int off, int len) { if (i >= bytes.length) return -1; b[off] = bytes[i++]; return 1; }
        };
        Rec r = new Rec();
        ClaudeDirect.stream(oneByteAtATime, r, new java.util.concurrent.atomic.AtomicBoolean());
        assertEquals(text, r.done);
        assertFalse(r.done.contains("Â") || r.done.contains("ð"));
    }

    @Test public void streamsTextAndSendsTheRightRequest() throws Exception {
        reply = sse("{\"type\":\"message_start\",\"message\":{}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}",
                "{\"type\":\"ping\"}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" there.\"}}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}",
                "{\"type\":\"message_stop\"}");
        ClaudeDirect c = new ClaudeDirect(() -> "sk-ant-test-key", "claude-opus-5-5", base(), 1000);
        Rec r = new Rec();
        c.send("be kind", Arrays.asList(Message.user("hi")), r);
        assertTrue(r.latch.await(10, TimeUnit.SECONDS));
        assertNull(r.err);
        assertEquals("Hello there.", r.text.toString());
        assertEquals("Hello there.", r.done);
        assertEquals("end_turn", r.stop);
        assertEquals("Bearer sk-ant-test-key", gotAuth.get());
        assertNull("key must not also go in x-api-key", gotXApiKey.get());
        assertEquals("2026-10-21", gotVersion.get());
        JSONObject body = new JSONObject(gotBody.get());
        assertEquals("claude-opus-5-5", body.getString("model"));
        assertTrue(body.getBoolean("stream"));
        assertEquals("be kind", body.getString("system"));
        assertFalse("sampling params are an error in 2026-10-21", body.has("temperature"));
        assertFalse(body.has("top_p"));
        assertFalse(body.has("top_k"));
        // Thinking counts against max_tokens: effort must be set so a channel page isn't cut off.
        assertEquals("adaptive", body.getJSONObject("thinking").getString("type"));
        assertEquals("low", body.getJSONObject("output_config").getString("effort"));
        assertFalse("budget_tokens is a 400 on Opus 5.5", body.getJSONObject("thinking").has("budget_tokens"));
    }

    @Test public void tvCeilingLeavesRoomForAChannel() {
        assertTrue(ClaudeDirect.MAX_TOKENS >= 24000);
    }

    @Test public void mapsHttpErrors() throws Exception {
        status = 401;
        reply = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"x\"}}";
        Rec r = new Rec();
        new ClaudeDirect(() -> "k", "m", base(), 100).send("", Arrays.asList(Message.user("hi")), r);
        assertTrue(r.latch.await(10, TimeUnit.SECONDS));
        assertEquals(BrainException.Kind.AUTH, r.err.kind);

        status = 529;
        reply = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}";
        Rec r2 = new Rec();
        new ClaudeDirect(() -> "k", "m", base(), 100).send("", Arrays.asList(Message.user("hi")), r2);
        assertTrue(r2.latch.await(10, TimeUnit.SECONDS));
        assertEquals(BrainException.Kind.OVERLOADED, r2.err.kind);
    }

    @Test public void noKeyFailsWithoutCallingTheNetwork() throws Exception {
        Rec r = new Rec();
        new ClaudeDirect(() -> null, "m", base(), 100).send("", Arrays.asList(Message.user("hi")), r);
        assertTrue(r.latch.await(5, TimeUnit.SECONDS));
        assertEquals(BrainException.Kind.NO_KEY, r.err.kind);
        assertNull(gotBody.get());
    }

    @Test public void midStreamErrorEventIsAnError() throws Exception {
        Rec r = new Rec();
        String s = sse("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}",
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}");
        ClaudeDirect.stream(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)), r, new AtomicBoolean(false));
        assertEquals(BrainException.Kind.OVERLOADED, r.err.kind);
        assertNull(r.done);
    }

    @Test public void cancelledStreamReportsNothing() throws Exception {
        Rec r = new Rec();
        String s = sse("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}", "{\"type\":\"message_stop\"}");
        ClaudeDirect.stream(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)), r, new AtomicBoolean(true));
        assertNull(r.done);
        assertNull(r.err);
    }

    // ---- Slice S1: tool use. The brain proposes; only the listener (the gate) decides. ----

    /** A tool_use round: thinking with a signature, a little text, and a tool call whose input arrives in pieces. */
    static String toolRound(String toolId, String partA, String partB, boolean complete) {
        List<String> ev = new ArrayList<>(Arrays.asList(
                "{\"type\":\"message_start\",\"message\":{}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"They want Plex.\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"SIG123\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"One moment.\"}}",
                "{\"type\":\"content_block_stop\",\"index\":1}",
                "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"" + toolId + "\",\"name\":\"app_open\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":" + JSONObject.quote(partA) + "}}",
                "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":" + JSONObject.quote(partB) + "}}"));
        if (complete) {
            ev.add("{\"type\":\"content_block_stop\",\"index\":2}");
            ev.add("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}");
            ev.add("{\"type\":\"message_stop\"}");
        }
        return sse(ev.toArray(new String[0]));
    }

    static final class ToolRec implements Brain.Listener {
        final Rec inner = new Rec();
        final List<String> calls = new ArrayList<>();
        Brain.ToolReply answer = new Brain.ToolReply("REFUSED by the TV: test.", false);
        @Override public void onText(String d) { inner.onText(d); }
        @Override public void onDone(String f, String s) { inner.onDone(f, s); }
        @Override public void onError(BrainException e) { inner.onError(e); }
        @Override public Brain.ToolReply onTool(String name, JSONObject input) { calls.add(name + " " + input); return answer; }
    }

    static org.json.JSONArray tools() throws Exception {
        return new org.json.JSONArray().put(new JSONObject().put("name", "app_open").put("description", "d")
                .put("input_schema", new JSONObject().put("type", "object")));
    }

    @Test public void aProposalGoesToTheListenerAndItsAnswerGoesBackUnchanged() throws Exception {
        replies.add(toolRound("toolu_1", "{\"app\":", "\"Hulu\"}", true));
        replies.add(sse("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hulu isn't here.\"}}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}", "{\"type\":\"message_stop\"}"));
        ToolRec r = new ToolRec();
        new ClaudeDirect(() -> "k", "m", base(), 1000).send("s", Arrays.asList(Message.user("open hulu")), tools(), r);
        assertTrue(r.inner.latch.await(10, TimeUnit.SECONDS));
        assertNull(r.inner.err);
        assertEquals(Arrays.asList("app_open {\"app\":\"Hulu\"}"), r.calls);
        assertEquals("One moment. Hulu isn't here.", r.inner.done);
        assertEquals(2, bodies.size());
        JSONObject first = new JSONObject(bodies.get(0));
        assertEquals("app_open", first.getJSONArray("tools").getJSONObject(0).getString("name"));
        org.json.JSONArray msgs = new JSONObject(bodies.get(1)).getJSONArray("messages");
        assertEquals(3, msgs.length());
        org.json.JSONArray asst = msgs.getJSONObject(1).getJSONArray("content");
        assertEquals("thinking", asst.getJSONObject(0).getString("type"));
        assertEquals("SIG123", asst.getJSONObject(0).getString("signature"));   // thinking must go back signed
        assertEquals("Hulu", asst.getJSONObject(2).getJSONObject("input").getString("app"));
        JSONObject res = msgs.getJSONObject(2).getJSONArray("content").getJSONObject(0);
        assertEquals("tool_result", res.getString("type"));
        assertEquals("toolu_1", res.getString("tool_use_id"));
        assertEquals("REFUSED by the TV: test.", res.getString("content"));     // the gate's words, not the model's
    }

    @Test public void whenTheTvAlreadyActedTheTurnEndsWithoutAnotherRequest() throws Exception {
        replies.add(toolRound("toolu_2", "{\"app\":\"Plex\"", "}", true));
        ToolRec r = new ToolRec();
        r.answer = new Brain.ToolReply("DONE and verified.", true);
        new ClaudeDirect(() -> "k", "m", base(), 1000).send("s", Arrays.asList(Message.user("open plex")), tools(), r);
        assertTrue(r.inner.latch.await(10, TimeUnit.SECONDS));
        assertEquals(1, r.calls.size());
        assertEquals("tool_use", r.inner.stop);
        Thread.sleep(200);
        assertEquals(1, bodies.size());
    }

    @Test public void aCutOffToolCallIsNeverRun() throws Exception {
        replies.add(toolRound("toolu_3", "{\"app\":\"Ne", "tflix\"}", false));
        ToolRec r = new ToolRec();
        new ClaudeDirect(() -> "k", "m", base(), 1000).send("s", Arrays.asList(Message.user("open netflix")), tools(), r);
        assertTrue(r.inner.latch.await(10, TimeUnit.SECONDS));
        assertTrue(r.calls.isEmpty());
        assertEquals("incomplete", r.inner.stop);
    }

    @Test public void roundsAreBounded() throws Exception {
        for (int i = 0; i < 10; i++) replies.add(toolRound("toolu_x" + i, "{\"app\":", "\"Plex\"}", true));
        ToolRec r = new ToolRec();
        new ClaudeDirect(() -> "k", "m", base(), 1000).send("s", Arrays.asList(Message.user("loop")), tools(), r);
        assertTrue(r.inner.latch.await(10, TimeUnit.SECONDS));
        assertEquals(ClaudeDirect.MAX_TOOL_ROUNDS, r.calls.size());
        assertEquals(ClaudeDirect.MAX_TOOL_ROUNDS + 1, bodies.size());
    }

    @Test public void aListenerThatDoesNotRouteProposalsRefusesThem() {
        Brain.ToolReply d = new Rec().onTool("app_open", new JSONObject());
        assertTrue(d.toModel.startsWith("REFUSED"));
        assertFalse(d.endTurn);
    }

    @Test public void normalizeMakesTurnsAlternateAndStartWithUser() throws Exception {
        List<Message> in = new ArrayList<>(Arrays.asList(
                Message.assistant("stale greeting"), Message.user("a"), Message.user("b"),
                Message.assistant(""), Message.assistant("c"), Message.user("d")));
        List<Message> out = ClaudeDirect.normalize(in);
        assertEquals(3, out.size());
        assertEquals("user", out.get(0).role);
        assertEquals("a\n\nb", out.get(0).text);
        assertEquals("assistant", out.get(1).role);
        assertEquals("c", out.get(1).text);
        assertEquals("d", out.get(2).text);
    }
}
