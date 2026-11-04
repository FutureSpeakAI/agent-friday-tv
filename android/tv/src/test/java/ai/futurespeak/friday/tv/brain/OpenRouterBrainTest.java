package ai.futurespeak.friday.tv.brain;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** T4: OpenRouter as a second brain, with the same tools, the same gate, honest names, and a Claude fallback. */
public class OpenRouterBrainTest {
    private java.net.ServerSocket server;
    private final ConcurrentLinkedQueue<String> replies = new ConcurrentLinkedQueue<>();
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private volatile int status = 200;

    @Before public void up() throws Exception {
        server = new java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (java.net.Socket s = server.accept()) {
                    java.io.InputStream in = s.getInputStream();
                    java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
                    int c, m = 0;
                    while ((c = in.read()) >= 0) { head.write(c); m = (c == '\r' || c == '\n') ? m + 1 : 0; if (m == 4) break; }
                    int len = 0;
                    for (String line : head.toString("UTF-8").split("\r\n")) { int i = line.indexOf(':'); if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase("content-length")) len = Integer.parseInt(line.substring(i + 1).trim()); }
                    byte[] b = new byte[len]; int off = 0;
                    while (off < len) { int n = in.read(b, off, len - off); if (n < 0) break; off += n; }
                    bodies.add(new String(b, StandardCharsets.UTF_8));
                    String r = replies.poll(); byte[] out = (r == null ? "" : r).getBytes(StandardCharsets.UTF_8);
                    OutputStream os = s.getOutputStream();
                    os.write(("HTTP/1.1 " + status + " X\r\nContent-Length: " + out.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    os.write(out); os.flush();
                } catch (Exception ignored) { }
            }
        });
        t.setDaemon(true); t.start();
    }
    @After public void down() throws Exception { server.close(); }

    OpenRouterBrain or() { return new OpenRouterBrain(() -> "or-test", "http://127.0.0.1:" + server.getLocalPort(), "openai/gpt-6-sol", "GPT-6 Sol", 1000); }

    static String sse(String... chunks) {
        StringBuilder s = new StringBuilder(": OPENROUTER PROCESSING\n\n");
        for (String c : chunks) s.append("data: ").append(c).append("\n\n");
        return s.append("data: [DONE]\n\n").toString();
    }
    static String text(String t) { return "{\"choices\":[{\"index\":0,\"delta\":{\"content\":" + JSONObject.quote(t) + "}}]}"; }

    static final class Rec implements Brain.Listener {
        final StringBuilder text = new StringBuilder(); String done, stop; BrainException err;
        final List<String> tools = new ArrayList<>(); Brain.ToolReply answer = new Brain.ToolReply("REFUSED by the TV: test.", false);
        final CountDownLatch latch = new CountDownLatch(1);
        public void onText(String d) { text.append(d); }
        public void onDone(String f, String s) { done = f; stop = s; latch.countDown(); }
        public void onError(BrainException e) { err = e; latch.countDown(); }
        public Brain.ToolReply onTool(String n, JSONObject in) { tools.add(n + " " + in); return answer; }
    }

    static JSONArray claudeTools() throws Exception {
        return new JSONArray().put(new JSONObject().put("name", "app_open").put("description", "Open an app")
                .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject().put("app", new JSONObject().put("type", "string")))));
    }

    @Test public void streamsTextAndRecordsTheCost() throws Exception {
        replies.add(sse(text("Hello"), text(" there."), "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"cost\":0.0012}}"));
        OpenRouterBrain b = or(); Rec r = new Rec();
        b.send("SYS", Arrays.asList(Message.user("hi")), r);
        assertTrue(r.latch.await(10, TimeUnit.SECONDS));
        assertEquals("Hello there.", r.done);
        assertEquals("end_turn", r.stop);
        assertEquals(0.0012, b.lastCostUsd(), 1e-9);
        JSONObject body = new JSONObject(bodies.get(0));
        assertEquals("openai/gpt-6-sol", body.getString("model"));
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"));
    }

    @Test public void toolCallsGoToTheSameGateAndTheAnswerGoesBack() throws Exception {
        replies.add(sse("{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"app_open\",\"arguments\":\"{\\\"app\\\":\"}}]}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Netflix\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}"));
        replies.add(sse(text("I can't open that one."), "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"));
        Rec r = new Rec();
        or().send("SYS", Arrays.asList(Message.user("open netflix")), claudeTools(), r);
        assertTrue(r.latch.await(10, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("app_open {\"app\":\"Netflix\"}"), r.tools);
        JSONObject first = new JSONObject(bodies.get(0));
        assertEquals("function", first.getJSONArray("tools").getJSONObject(0).getString("type"));
        assertEquals("app_open", first.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"));
        JSONArray msgs = new JSONObject(bodies.get(1)).getJSONArray("messages");
        JSONObject toolMsg = msgs.getJSONObject(msgs.length() - 1);
        assertEquals("tool", toolMsg.getString("role"));
        assertEquals("call_1", toolMsg.getString("tool_call_id"));
        assertEquals("REFUSED by the TV: test.", toolMsg.getString("content"));
    }

    @Test public void aCutOffToolCallIsNeverRun() throws Exception {
        // The stream even says "tool_calls", but it is cut off before [DONE]: nothing may run.
        replies.add(": x\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Opening\",\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":{\"name\":\"app_open\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n");
        Rec r = new Rec();
        or().send("SYS", Arrays.asList(Message.user("x")), claudeTools(), r);
        assertTrue(r.latch.await(10, TimeUnit.SECONDS));
        assertTrue(r.tools.isEmpty());
        assertEquals("incomplete", r.stop);
    }

    @Test public void kidsModeAndNoKeyAreAlwaysClaude() {
        List<ModelChoice.Model> l = ModelChoice.DEFAULTS;
        assertNull(ModelChoice.serving("openai/gpt-6-sol", l, true, true));
        assertNull(ModelChoice.serving("openai/gpt-6-sol", l, false, false));
        assertNull("not on the list", ModelChoice.serving("evil/model", l, false, true));
        assertEquals("GPT-6 Sol", ModelChoice.serving("openai/gpt-6-sol", l, false, true).name);
    }

    @Test public void theIdentityIsHonest() {
        ModelChoice.Model m = ModelChoice.DEFAULTS.get(0);
        String id = ModelChoice.identity(m, false);
        assertTrue(id.contains("You are GPT-6 Sol"));
        assertTrue(id.contains("You are not Claude"));
        assertFalse(id.contains("You are Claude"));
        String sys = ai.futurespeak.friday.tv.prompt.SystemPrompt.build(ai.futurespeak.friday.tv.prompt.SystemPrompt.Mode.FAMILY, "Monday", true, null, null, id);
        assertTrue(sys.startsWith("You are GPT-6 Sol"));
        assertFalse(sys.contains("say you are Claude"));
        assertTrue("a fallback turn says Claude again", ChoiceBrain.fallbackPrompt(sys, m).startsWith("You are Claude"));
    }

    @Test public void aCuratedListIsValidated() {
        List<ModelChoice.Model> l = ModelChoice.parse("[{\"id\":\"openai/gpt-6-luna\",\"name\":\"GPT-6 Luna\",\"in\":0.1,\"out\":0.5},{\"id\":\"../../etc\",\"name\":\"x\"}]");
        assertEquals(1, l.size());
        assertEquals(ModelChoice.DEFAULTS, ModelChoice.parse("not json"));
    }

    /** If OpenRouter fails before saying anything, Claude answers, Friday says so, and the switch is receipted. */
    @Test public void fallbackToClaudeIsSaidAndReceipted() throws Exception {
        status = 503;
        List<String> log = new ArrayList<>();
        Brain claude = new Brain() {
            public String displayName() { return "Claude"; }
            public boolean ready() { return true; }
            public Turn send(String s, List<Message> h, Listener l) { l.onText("Hi from Claude."); l.onDone("Hi from Claude.", "end_turn"); return () -> { }; }
        };
        ChoiceBrain cb = new ChoiceBrain(claude, or(), () -> ModelChoice.DEFAULTS.get(0), new ChoiceBrain.Log() {
            public void switched(String from, String to, String why) { log.add("switch " + from + " -> " + to); }
            public void reply(String id, String name, double usd) { log.add("reply " + id); }
        });
        assertEquals("GPT-6 Sol via OpenRouter", cb.displayName());
        Rec r = new Rec();
        cb.send("SYS", Arrays.asList(Message.user("hi")), r);
        assertTrue(r.latch.await(10, TimeUnit.SECONDS));
        assertTrue(r.done, r.done.startsWith("GPT-6 Sol didn't answer, so Claude is answering."));
        assertTrue("spoken, not only kept", r.text.toString().startsWith("GPT-6 Sol didn't answer, so Claude is answering."));
        assertEquals(Arrays.asList("switch GPT-6 Sol via OpenRouter -> Claude"), log);
        assertEquals("Claude", cb.lastAnswered());
    }
}
