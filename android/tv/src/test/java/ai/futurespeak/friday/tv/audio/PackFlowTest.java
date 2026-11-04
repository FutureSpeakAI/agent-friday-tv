package ai.futurespeak.friday.tv.audio;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ai.futurespeak.friday.tv.audio.OfflineSpeech.Route;
import ai.futurespeak.friday.tv.audio.PackFlow.State;

/**
 * The download-prompt loop of 2026-11-02: the build then installed asked Google's speech app for the
 * pack on every start and resume, its download screen covered Friday, closing it resumed Friday,
 * and Friday asked again. These tests fail on that behaviour.
 */
public class PackFlowTest {
    static final class Mem implements PackFlow.Store {
        State s = State.NEW; long at = 0;
        public State packState() { return s; }
        public long packRequestedAt() { return at; }
        public void setPack(State st, long a) { s = st; at = a; }
    }

    long now = 1_000_000;
    int opened = 0;
    Mem store = new Mem();
    PackFlow flow = new PackFlow(store, () -> now, () -> opened++);

    /** The old loop, replayed: resume after resume (the download screen closing each time) never opens it. */
    @Test public void resumingNeverOpensTheDownloadScreen() {
        for (int i = 0; i < 50; i++) { flow.onResume(); now += 1000; }
        assertEquals(0, opened);
        assertEquals(State.NEW, store.s);
    }

    @Test public void offeredOnceOnTalkAndBackMeansOnlineForNow() {
        assertTrue(flow.offerOnTalk(Route.CLOUD));
        flow.decline();                                      // Back on the card
        assertEquals(0, opened);
        for (int i = 0; i < 10; i++) { flow.onResume(); assertFalse("offered again", flow.offerOnTalk(Route.CLOUD)); }
        assertEquals(0, opened);
    }

    @Test public void neverOfferedWhenOfflineIsConfirmed() {
        assertFalse(flow.offerOnTalk(Route.SERVICE_OFFLINE));
        assertFalse(flow.offerOnTalk(Route.ON_DEVICE));
    }

    @Test public void oneRequestWhileInFlightThenRetryFromSettings() {
        assertTrue(flow.requestDownload());
        assertEquals(1, opened);
        for (int i = 0; i < 20; i++) { now += 60_000; flow.onResume(); flow.requestDownload(); }   // impatient presses, resumes
        assertEquals("never shown again while a download is in flight", 1, opened);
        assertTrue(flow.progress(), flow.progress().contains("min ago"));
        now += PackFlow.IN_FLIGHT_MS;
        assertTrue("Settings can retry once the request is stale", flow.requestDownload());
        assertEquals(2, opened);
    }

    @Test public void choosingDownloadEndsTheOfferToo() {
        flow.decline();
        flow.requestDownload();
        assertFalse(flow.offerOnTalk(Route.CLOUD));
    }

    /** Static half: nothing on the start/resume path can open the download screen. */
    @Test public void onlyAnExplicitChoiceOpensTheDownloadScreen() throws Exception {
        String main = new String(Files.readAllBytes(new File("src/main/java/ai/futurespeak/friday/tv/MainActivity.java").toPath()), StandardCharsets.UTF_8);
        for (String m : new String[]{"void makeSpeech()", "protected void onResume()", "protected void onCreate(", "private void useRoute("}) {
            String body = body(main, m);
            assertNotNull("method not found: " + m, body);
            assertFalse(m + " can open the download screen", body.contains("requestPack(") || body.contains("downloadPack(") || body.contains("requestDownload("));
        }
        // requestPack is reached only through PackFlow (the one guarded opener).
        Matcher x = Pattern.compile("RecognizerSpeechIn\\.requestPack\\(").matcher(main);
        int n = 0; while (x.find()) n++;
        assertEquals("MainActivity calls requestPack only as PackFlow's opener", 1, n);
        assertTrue(main.contains("() -> RecognizerSpeechIn.requestPack(this)"));
        // Back on the card means online recognition: the CLOUD route must get a real recognizer.
        assertTrue("useRoute must listen on every route but NONE", body(main, "private void useRoute(").contains("r != OfflineSpeech.Route.NONE"));
        String settings = new String(Files.readAllBytes(new File("src/main/java/ai/futurespeak/friday/tv/SettingsPages.java").toPath()), StandardCharsets.UTF_8);
        assertFalse("Settings must go through PackFlow", settings.contains("requestPack("));
    }

    /** The text of a method, by brace matching from its signature. */
    static String body(String src, String signature) {
        int i = src.indexOf(signature);
        if (i < 0) return null;
        int open = src.indexOf('{', i), depth = 0;
        for (int k = open; k < src.length(); k++) {
            char c = src.charAt(k);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, k + 1);
        }
        return null;
    }
}
