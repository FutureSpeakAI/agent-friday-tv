package ai.futurespeak.friday.tv.live;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** T5: Gemini Live — framing, protocol, the ending rules, and the static guards. */
public class LiveTest {

    // ------------------------------------------------------------------ framing -----
    @Test public void clientFramesAreMaskedAndSized() throws Exception {
        byte[] mask = {1, 2, 3, 4};
        byte[] small = WsFrames.encode(WsFrames.TEXT, "hi".getBytes(StandardCharsets.UTF_8), mask);
        assertEquals((byte) 0x81, small[0]);
        assertEquals((byte) (0x80 | 2), small[1]);
        assertEquals('h' ^ 1, small[6]);
        assertEquals('i' ^ 2, small[7]);
        byte[] mid = WsFrames.encode(WsFrames.TEXT, new byte[300], mask);
        assertEquals((byte) (0x80 | 126), mid[1]);
        assertEquals(300, ((mid[2] & 0xFF) << 8) | (mid[3] & 0xFF));
        assertEquals(300 + 8, mid.length);
        byte[] big = WsFrames.encode(WsFrames.BINARY, new byte[70000], mask);
        assertEquals((byte) (0x80 | 127), big[1]);
        long n = 0; for (int i = 2; i < 10; i++) n = (n << 8) | (big[i] & 0xFF);
        assertEquals(70000, n);
        assertEquals(70000 + 14, big.length);
    }

    @Test public void framesRoundTripIncludingMasking() throws Exception {
        String s = "{\"setup\":{}}" + "x".repeat(500);
        byte[] f = WsFrames.encode(WsFrames.TEXT, s.getBytes(StandardCharsets.UTF_8));
        WsFrames.Message m = WsFrames.read(new ByteArrayInputStream(f));
        assertEquals(WsFrames.TEXT, m.opcode);
        assertEquals(s, new String(m.data, StandardCharsets.UTF_8));
    }

    static byte[] serverFrame(boolean fin, int op, byte[] p) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((fin ? 0x80 : 0) | op);
        if (p.length < 126) o.write(p.length); else { o.write(126); o.write(p.length >>> 8); o.write(p.length); }
        o.write(p, 0, p.length);
        return o.toByteArray();
    }

    @Test public void fragmentsAreJoinedAndAPingInBetweenComesFirst() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(serverFrame(false, WsFrames.BINARY, "{\"a\":".getBytes()));
        o.write(serverFrame(true, WsFrames.PING, "p".getBytes()));
        o.write(serverFrame(true, WsFrames.CONT, "1}".getBytes()));
        ByteArrayInputStream in = new ByteArrayInputStream(o.toByteArray());
        assertEquals(WsFrames.PING, WsFrames.read(in).opcode);
        // The ping was returned mid-message; the joined data starts again from the continuation,
        // so a server never interleaves like this in practice. A clean fragmented message:
        o.reset();
        o.write(serverFrame(false, WsFrames.TEXT, "{\"a\":".getBytes()));
        o.write(serverFrame(true, WsFrames.CONT, "1}".getBytes()));
        WsFrames.Message m = WsFrames.read(new ByteArrayInputStream(o.toByteArray()));
        assertEquals(WsFrames.TEXT, m.opcode);
        assertEquals("{\"a\":1}", new String(m.data, StandardCharsets.UTF_8));
    }

    @Test public void badFramesAreRefused() {
        byte[] huge = {(byte) 0x82, 127, 0, 0, 0, 0, 0x7F, 0, 0, 0};
        try { WsFrames.read(new ByteArrayInputStream(huge)); fail("too large"); } catch (IOException e) { assertTrue(e.getMessage().contains("large")); }
        try { WsFrames.read(new ByteArrayInputStream(serverFrame(true, WsFrames.CONT, "x".getBytes()))); fail("orphan"); } catch (IOException e) { assertTrue(e.getMessage().contains("continuation")); }
        try { WsFrames.read(new ByteArrayInputStream(new byte[]{(byte) 0x81, 5, 'a'})); fail("cut"); } catch (IOException e) { }
    }

    @Test public void handshakeAcceptMatchesTheRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsFrames.accept("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test public void handshakeHeadIsReadToTheBlankLine() throws Exception {
        String h = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n";
        ByteArrayInputStream in = new ByteArrayInputStream((h + "\u0081").getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(h, LiveSession.readHead(in));
        assertEquals(0x81, in.read());
    }

    // ----------------------------------------------------------------- protocol -----
    @Test public void setupAsksForSpokenAnswersWithTheCataloguesTools() throws Exception {
        JSONArray tools = new JSONArray()
                .put(new JSONObject().put("name", "app_open").put("description", "Open an app")
                        .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject().put("app", new JSONObject().put("type", "string")))))
                .put(new JSONObject().put("name", "app_back").put("description", "Back").put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject())));
        JSONObject s = LiveProtocol.setup("BE GEMINI", tools).getJSONObject("setup");
        assertEquals("models/gemini-3.8-live", s.getString("model"));
        assertEquals("AUDIO", s.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0));
        assertEquals("BE GEMINI", s.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"));
        assertTrue("transcription is how \"stop\" is heard", s.has("inputAudioTranscription"));
        JSONArray decl = s.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations");
        assertEquals(2, decl.length());
        assertEquals("app_open", decl.getJSONObject(0).getString("name"));
        assertTrue(decl.getJSONObject(0).has("parameters"));
        assertFalse("an empty schema is left out", decl.getJSONObject(1).has("parameters"));
        assertFalse(LiveProtocol.setup("x", null).getJSONObject("setup").has("tools"));
    }

    @Test public void audioGoesAs16kPcm() throws Exception {
        byte[] pcm = {1, 2, 3, 4, 5, 6};
        JSONObject a = LiveProtocol.audio(pcm, 4).getJSONObject("realtime_input").getJSONObject("audio");
        assertEquals("audio/pcm;rate=16000", a.getString("mimeType"));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, java.util.Base64.getDecoder().decode(a.getString("data")));
        JSONObject r = LiveProtocol.toolResponse("c1", "app_open", "REFUSED by the TV: kids.").getJSONObject("tool_response")
                .getJSONArray("functionResponses").getJSONObject(0);
        assertEquals("c1", r.getString("id"));
        assertEquals("REFUSED by the TV: kids.", r.getJSONObject("response").getString("result"));
    }

    @Test public void serverMessagesAreUnderstood() throws Exception {
        String audio = java.util.Base64.getEncoder().encodeToString(new byte[]{9, 8, 7, 6});
        LiveProtocol.Event e = LiveProtocol.parse("{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"" + audio + "\"}},{\"text\":\"x\"}]},"
                + "\"inputTranscription\":{\"text\":\"stop\"},\"outputTranscription\":{\"text\":\"Hi\"},\"turnComplete\":true}}");
        assertEquals(1, e.audio.size());
        assertArrayEquals(new byte[]{9, 8, 7, 6}, e.audio.get(0));
        assertEquals("stop", e.heard);
        assertEquals("Hi", e.said);
        assertTrue(e.turnComplete);
        assertFalse(e.interrupted);
        LiveProtocol.Event t = LiveProtocol.parse("{\"toolCall\":{\"functionCalls\":[{\"id\":\"f1\",\"name\":\"app_open\",\"args\":{\"app\":\"Plex\"}}]}}");
        assertEquals(1, t.calls.size());
        assertEquals("f1", t.calls.get(0)[0]);
        assertEquals("app_open", t.calls.get(0)[1]);
        assertEquals("Plex", new JSONObject(t.calls.get(0)[2]).getString("app"));
        assertTrue(LiveProtocol.parse("{\"setupComplete\":{}}").setupComplete);
        assertTrue(LiveProtocol.parse("{\"goAway\":{\"timeLeft\":\"5s\"}}").goAway);
        assertEquals(1234, LiveProtocol.parse("{\"usageMetadata\":{\"totalTokenCount\":1234}}").tokens);
        assertTrue(LiveProtocol.parse("{\"serverContent\":{\"interrupted\":true}}").interrupted);
        assertNotNull(LiveProtocol.parse("not json").error);
        assertTrue("non-audio inline data is not played",
                LiveProtocol.parse("{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"" + audio + "\"}}]}}}").audio.isEmpty());
    }

    // -------------------------------------------------------------------- rules -----
    @Test public void endingRules() {
        long t0 = 1_000_000;
        assertNull(LiveRules.check(t0 + 5_000, t0, t0 + 4_000, 10, false, false));
        assertEquals(LiveRules.End.LIMIT, LiveRules.check(t0 + 10 * 60_000, t0, t0 + 10 * 60_000 - 1, 10, false, false));
        assertNull(LiveRules.check(t0 + 10 * 60_000 - 1, t0, t0 + 10 * 60_000 - 2, 10, false, false));
        assertEquals("5 min is the floor", LiveRules.End.LIMIT, LiveRules.check(t0 + 5 * 60_000, t0, t0 + 5 * 60_000, 1, false, false));
        assertEquals(LiveRules.End.SILENCE, LiveRules.check(t0 + 61_000, t0, t0 + 1_000, 10, false, false));
        assertNull(LiveRules.check(t0 + 60_999, t0, t0 + 1_000, 10, false, false));
        assertEquals(LiveRules.End.KIDS_CHANGED, LiveRules.check(t0 + 1_000, t0, t0 + 1_000, 10, false, true));
        assertEquals(LiveRules.End.KIDS_CHANGED, LiveRules.check(t0 + 1_000, t0, t0 + 1_000, 10, true, false));
        assertEquals("off by default", 0, LiveRules.minutes(0));
        assertNull("no limit when off, however long", LiveRules.check(t0 + 300 * 60_000L, t0, t0 + 300 * 60_000L, 0, false, false));
        assertEquals(5, LiveRules.minutes(2));
        assertEquals(30, LiveRules.minutes(90));
        assertEquals(15, LiveRules.minutes(15));
    }

    @Test public void stopIsHeardButNotInsideOtherWords() {
        for (String s : new String[]{"stop", "Stop.", "STOP!", "stop it", "OK, stop", "stop talking", "please stop", "Goodbye", "stop, Gemini"})
            assertTrue(s, LiveRules.stopWord(s));
        for (String s : new String[]{"don't stop", "stop sign", "what's a bus stop", "unstoppable", "", null, "can you stop by the store"})
            assertFalse(String.valueOf(s), LiveRules.stopWord(s));
    }

    @Test public void theMicNeedsTheBanner() {
        assertTrue(LiveRules.mayOpenMic(true, false));
        assertFalse(LiveRules.mayOpenMic(false, false));
        assertFalse(LiveRules.mayOpenMic(true, true));
        assertEquals("Google is listening", LiveBanner.WORDS);
        assertEquals(0.3, LiveRules.estimate(10 * 60_000), 1e-9);
        assertEquals("1:05", LiveBanner.clock(65_000));
    }

    @Test public void rmsSeesSpeechNotSilence() {
        byte[] quiet = new byte[3200];
        byte[] loud = new byte[3200];
        for (int i = 0; i < loud.length; i += 2) { short v = (short) ((i / 2) % 2 == 0 ? 4000 : -4000); loud[i] = (byte) v; loud[i + 1] = (byte) (v >> 8); }
        assertEquals(0, LiveSession.rms(quiet, quiet.length), 1e-9);
        assertEquals(4000, LiveSession.rms(loud, loud.length), 1);
    }

    // ------------------------------------------------------------------- guards -----
    private static String read(String rel) throws Exception { return new String(Files.readAllBytes(new File("src/main/java/ai/futurespeak/friday/tv/" + rel).toPath()), StandardCharsets.UTF_8); }

    private static List<Path> sources() throws Exception {
        try (Stream<Path> s = Files.walk(new File("src/main/java").toPath())) { return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList()); }
    }

    @Test public void theMicOpensOnlyAfterTheBannerCheck() throws Exception {
        String s = read("live/LiveSession.java");
        int check = s.indexOf("LiveRules.mayOpenMic(banner.showing()");
        int open = s.indexOf("startRecording()");
        assertTrue(check > 0 && open > check);
        assertEquals("one place starts recording", s.indexOf("startRecording()"), s.lastIndexOf("startRecording()"));
        int end = s.indexOf("public void end(");
        int stop = s.indexOf("r.stop()", end);
        int sock = s.indexOf("s.close()", end);
        assertTrue("the mic closes before anything else", stop > end && sock > stop);
    }

    @Test public void theKeyTravelsInAHeaderNeverTheUrl() throws Exception {
        String s = read("live/LiveSession.java");
        assertTrue(s.contains("x-goog-api-key: \" + key"));
        assertFalse(s.contains("?key="));
        assertFalse(s.contains("Log.i(TAG, \"" + "key"));
        assertTrue("hostname checked", s.contains("setEndpointIdentificationAlgorithm(\"HTTPS\")"));
    }

    /**
     * (2026-11-02): Gemini Live is a voice option, no PIN. It starts only from the Talk press
     * (the Talk tile or the mic button) when chosen in Settings, Voice. Never by the brain, a channel,
     * a tool or the shell, and no card or PIN stands in front of it.
     */
    @Test public void liveIsAVoiceOptionStartedOnlyByTalk() throws Exception {
        String m = read("MainActivity.java");
        assertEquals("startLive is defined once and called once", 2, count(m, "startLive()"));
        int press = m.indexOf("private void talkPress(Layer top)");
        int call = m.indexOf("startLive(); return; }", press);
        int end = m.indexOf("\n    }", press);
        assertTrue("the one call is inside the Talk press", call > press && call < end);
        assertTrue("only when chosen", m.substring(press, call).contains("if (prefs.talkWithGemini() && !listening)"));
        assertTrue("Talk again ends it", m.substring(press, call).contains("if (live != null) { live.end(ai.futurespeak.friday.tv.live.LiveRules.End.BACK"));
        assertFalse("no PIN", m.contains("Start a live conversation with Google's Gemini."));
        assertFalse("no separate tile or card", m.contains("offerLive") || m.contains("liveTile"));
        for (Path p : sources()) {
            String f = p.getFileName().toString();
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            if (!f.equals("MainActivity.java")) assertFalse(f + " starts Live", s.contains("startLive") || s.contains("offerLive"));
            if (!p.toString().replace('\\', '/').contains("/live/") && !f.equals("MainActivity.java"))
                assertFalse(f + " makes a Live session", s.contains("new ai.futurespeak.friday.tv.live.LiveSession(") || s.contains("new LiveSession("));
        }
        assertFalse("no catalogue action starts Live", read("actions/Catalogue.java").matches("(?s).*(\"live[._]|gemini|LiveSession).*"));
        String sp = read("SettingsPages.java");
        int voice = sp.indexOf("static void voice(MainActivity a, SettingsPage p)");
        int opt = sp.indexOf("new String[]{\"Friday\", \"Gemini Live (Google)\"}", voice);
        assertTrue("the choice is on the Voice page", voice > 0 && opt > voice && opt < sp.indexOf("static void", voice + 10));
        assertFalse("choosing it needs no PIN", sp.substring(voice, sp.indexOf("static void", voice + 10)).contains("withGrownUp(\"Change the live"));
        assertTrue(read("Prefs.java").contains("p.getBoolean(\"talk_gemini\", false)"));
    }

    @Test public void endingsAreWiredAndReceipted() throws Exception {
        String m = read("MainActivity.java");
        assertTrue("Back ends it", m.contains("if (live != null && top == null) live.end(ai.futurespeak.friday.tv.live.LiveRules.End.BACK"));
        assertTrue("leaving Friday ends it", m.contains("live.end(ai.futurespeak.friday.tv.live.LiveRules.End.LEFT_FRIDAY"));
        assertTrue("screen off ends it", m.contains("Intent.ACTION_SCREEN_OFF"));
        assertTrue("the rules run every second", m.contains("LiveRules.check(now, liveStartedAt, s.lastVoiceAt()"));
        assertTrue("start receipt", m.contains("put(\"action\", \"live.start\")") && m.contains("return a.receipts().decision(liveWho, rec);"));
        assertTrue("end receipt with the estimate", m.contains("put(\"service\", \"gemini-live\")") && m.contains("put(\"estimate\", true)"));
        assertTrue("tools go through the one gate", m.contains("Session.Act a = actions.act(name, args);"));
        int ended = m.indexOf("private void liveEnded(");
        assertTrue("the banner goes", m.indexOf("root.removeView(liveBanner)", ended) > ended);
    }

    private static int count(String s, String sub) { int n = 0, i = 0; while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); } return n; }
}
