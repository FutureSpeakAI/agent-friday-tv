package ai.futurespeak.friday.tv.audio;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ai.futurespeak.friday.tv.audio.OfflineSpeech.Route;

/** Spec D4 / AC-13: the app never records or uploads audio itself; recognition always prefers offline, and says when it can't promise it. */
public class OfflineSpeechTest {

    @Test public void routes() {
        assertEquals(Route.ON_DEVICE, OfflineSpeech.decide(true, true, null));
        assertEquals(Route.SERVICE_OFFLINE, OfflineSpeech.decide(false, true, Arrays.asList("fr-FR", "en-US")));
        assertEquals(Route.SERVICE_OFFLINE, OfflineSpeech.decide(false, true, Collections.singletonList("en_us")));
        assertEquals(Route.NONE, OfflineSpeech.decide(false, false, Arrays.asList("en-US")));
    }

    @Test public void unconfirmedMeansGoogleOnlineAndSaysSo() {
        // The fallback: the service couldn't say (null), or English is only online.
        assertEquals(Route.CLOUD, OfflineSpeech.decide(false, true, null));
        assertEquals(Route.CLOUD, OfflineSpeech.decide(false, true, Collections.emptyList()));
        assertEquals(Route.CLOUD, OfflineSpeech.decide(false, true, Arrays.asList("en-GB-x-other", "de-DE")));
        assertEquals("measured on this TV", Route.SERVICE_OFFLINE, OfflineSpeech.decide(false, true, null, true));
        assertFalse(OfflineSpeech.offline(Route.CLOUD));
        assertTrue("the person is told", OfflineSpeech.explain(Route.CLOUD).contains("Google"));
        assertFalse("never claims offline", OfflineSpeech.explain(Route.CLOUD).contains("never leaves"));
    }

    @Test public void holdToTalkNeverEndsOnAPause() {
        assertTrue(OfflineSpeech.silenceMs(true, 800) >= 10000);
        assertEquals(1100, OfflineSpeech.silenceMs(false, 1100));
        assertEquals(600, OfflineSpeech.silenceMs(false, 10));
    }

    private static List<Path> sources() throws Exception {
        try (Stream<Path> s = Files.walk(new File("src/main/java").toPath())) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    private static String read(Path p) throws Exception { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8); }

    /** AC-13, static half: nothing in the app records audio or sends it anywhere. */
    @Test public void theAppNeverRecordsOrUploadsAudio() throws Exception {
        for (Path p : sources()) {
            String s = read(p);
            String f = p.getFileName().toString();
            // T5: the one exception is Gemini Live, which records only while its banner is up (live/LiveTest).
            boolean live = p.toString().replace('\\', '/').endsWith("/live/LiveSession.java");
            // On-TV speech (design decision, 2026-11-02): the second exception, Friday's own recognizer, which may never reach the network.
            boolean onTv = p.toString().replace('\\', '/').endsWith("/audio/SherpaSpeechIn.java");
            if (!live && !onTv) assertFalse(f + " records audio itself", s.contains("AudioRecord") || s.contains("MediaRecorder"));
            else if (live) assertTrue("Live records only behind the banner check", s.contains("LiveRules.mayOpenMic(banner.showing()"));
            else {
                for (String net : new String[]{"java.net", "HttpURLConnection", "Socket", "URL(", "okhttp", "WebSocket", "SpeechRecognizer", "RecognizerIntent", "ContentResolver", "sendBroadcast", "startActivity"})
                    assertFalse("on-TV speech must not reach the network or another app: " + net, s.contains(net));
                int mic = s.indexOf("events.onMic(true)");
                int rec = s.indexOf("startRecording()");
                assertTrue("the mic indicator is up before recording starts", mic > 0 && rec > mic);
            }
            assertFalse(f + " calls a cloud speech-to-text API", s.contains("speech-to-text") || s.toLowerCase().contains("scribe_v"));
            assertFalse(f + " turns off offline recognition", s.contains("EXTRA_PREFER_OFFLINE, false") || s.contains("intent(false)"));
            if (!f.equals("RecognizerSpeechIn.java"))
                assertFalse(f + " makes its own recognizer", s.contains("createSpeechRecognizer") || s.contains("createOnDeviceSpeechRecognizer"));
        }
    }

    /** Every recognizer intent asks for offline, and the language is fixed. */
    @Test public void everyRecognizerIntentIsOffline() throws Exception {
        String s = read(new File("src/main/java/ai/futurespeak/friday/tv/audio/RecognizerSpeechIn.java").toPath());
        assertTrue(s.contains("putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)"));
        int intents = s.split("new Intent\\(RecognizerIntent.ACTION_RECOGNIZE_SPEECH\\)", -1).length - 1;
        assertEquals("one place builds recognizer intents", 1, intents);
        int calls = s.split("intent\\(true\\)", -1).length - 1;
        assertTrue("every use asks for offline", calls >= 3);
    }
}
