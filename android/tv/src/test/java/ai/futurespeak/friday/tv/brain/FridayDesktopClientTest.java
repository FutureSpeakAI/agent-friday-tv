package ai.futurespeak.friday.tv.brain;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ai.futurespeak.friday.tv.pair.DesktopLink;
import ai.futurespeak.friday.tv.pair.FakeDesktop;

import static org.junit.Assert.*;

/**
 * Friday desktop as the TV's brain (T2): the desktop's /tv/v1/messages speaks the Messages
 * stream format, and the TV reads it with ClaudeDirect's reader over the pinned link.
 */
public class FridayDesktopClientTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    FakeDesktop pc;
    DesktopLink link;
    final List<JSONObject> turns = Collections.synchronizedList(new ArrayList<>());

    static byte[] sse(String... events) {
        StringBuilder sb = new StringBuilder();
        for (String e : events) sb.append("data: ").append(e).append("\n\n");
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n";
        byte[] h = head.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[h.length + body.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(body, 0, out, h.length, body.length);
        return out;
    }

    @Before public void up() throws Exception {
        pc = new FakeDesktop();
        link = pc.pairedLink(tmp.newFile("link.json"), new FakeDesktop.SoftKey());
        pc.extra = (path, auth, body, s) -> {
            if (!path.equals("/tv/v1/messages")) return null;
            if (!pc.sessionOk(auth)) return "HTTP/1.1 401 X\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".getBytes(StandardCharsets.UTF_8);
            turns.add(body);
            boolean second = body.getJSONArray("messages").toString().contains("tool_result");
            if (!second) return sse(
                    "{\"type\":\"message_start\"}",
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Opening it. \"}}",
                    "{\"type\":\"content_block_stop\",\"index\":0}",
                    "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"app.open\",\"input\":{}}}",
                    "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"app\\\":\\\"plex\\\"}\"}}",
                    "{\"type\":\"content_block_stop\",\"index\":1}",
                    "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}",
                    "{\"type\":\"message_stop\"}");
            return sse(
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Plex is open.\"}}",
                    "{\"type\":\"content_block_stop\",\"index\":0}",
                    "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}",
                    "{\"type\":\"message_stop\"}");
        };
    }

    @After public void down() throws Exception { pc.close(); }

    static final class Out implements Brain.Listener {
        final CountDownLatch done = new CountDownLatch(1);
        final StringBuilder text = new StringBuilder();
        final List<String> tools = Collections.synchronizedList(new ArrayList<>());
        volatile String full;
        volatile BrainException error;
        @Override public void onText(String d) { text.append(d); }
        @Override public void onDone(String f, String stop) { full = f; done.countDown(); }
        @Override public void onError(BrainException e) { error = e; done.countDown(); }
        @Override public Brain.ToolReply onTool(String name, JSONObject input) {
            tools.add(name + " " + input);
            return new Brain.ToolReply("DONE by the TV: Plex is in front.", false);
        }
    }

    Out ask(String principal) throws Exception {
        FridayDesktopClient brain = new FridayDesktopClient(link, () -> principal, 4000);
        Out o = new Out();
        JSONArray tools = new JSONArray().put(new JSONObject().put("name", "app.open"));
        brain.send("tv system", Collections.singletonList(Message.user("open plex")), tools, o);
        assertTrue("the turn finished", o.done.await(20, TimeUnit.SECONDS));
        return o;
    }

    @Test public void aTurnStreamsFromTheDesktopAndProposalsComeBackToTheTvsGate() throws Exception {
        Out o = ask("family");
        assertNull(o.error);
        assertEquals(Collections.singletonList("app.open {\"app\":\"plex\"}"), o.tools);
        assertEquals("Opening it. Plex is open.", o.full);
        assertEquals(2, turns.size());
        assertTrue("the TV's verdict went back in its own words",
                turns.get(1).toString().contains("DONE by the TV: Plex is in front."));
    }

    @Test public void noTvKeyTravelsOnlyTheDeviceSession() throws Exception {
        ask("family");
        for (Map<String, String> h : pc.headers) {
            String a = h.getOrDefault("authorization", "");
            assertFalse("a Bearer key went to the PC", a.startsWith("Bearer"));
            assertFalse(h.containsKey("x-api-key"));
        }
        long withSession = pc.headers.stream()
                .filter(h -> pc.sessionOk(h.get("authorization"))).count();
        assertTrue(withSession >= 2);
    }

    @Test public void theDesktopIsToldWhoIsWatchingButNeverTheirPin() throws Exception {
        ask("kids");
        boolean sawKids = false;
        for (Map<String, String> h : pc.headers) {
            if ("kids".equals(h.get("x-friday-principal"))) sawKids = true;
            for (String v : h.values()) assertFalse(v.toLowerCase().contains("pin"));
        }
        assertTrue(sawKids);
    }

    @Test public void anImpostorKeyMeansNoTurnAndANetworkErrorSoTheTvFallsBack() throws Exception {
        pc.certName = "impostor";
        Out o = ask("family");
        assertNotNull(o.error);
        assertTrue(FailoverBrain.fallsBack(o.error));
        assertTrue(turns.isEmpty());
    }
}
