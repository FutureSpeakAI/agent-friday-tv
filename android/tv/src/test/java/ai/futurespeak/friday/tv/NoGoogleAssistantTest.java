package ai.futurespeak.friday.tv;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * (2026-11-02): Friday never opens Google's assistant or Gemini UI. Gemini Live stays in
 * the app (a WebSocket from live/), speech recognition never starts Google's voice activity, and
 * the only search Friday starts is an app's own search, addressed to that app by package.
 */
public class NoGoogleAssistantTest {
    private static List<Path> sources() throws Exception {
        try (Stream<Path> s = Files.walk(new File("src/main/java/ai/futurespeak").toPath())) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    @Test public void noIntentOpensGooglesAssistantOrGemini() throws Exception {
        String[] banned = {
                "ACTION_ASSIST", "android.intent.action.ASSIST", "ACTION_VOICE_COMMAND", "android.intent.action.VOICE_COMMAND",
                "ACTION_WEB_SEARCH", "ACTION_VOICE_SEARCH", "VOICE_SEARCH_HANDS_FREE", "SEARCH_LONG_PRESS",
                "com.google.android.katniss", "googlequicksearchbox", "com.google.android.apps.bard", "com.google.android.apps.tv.launcherx",
                "triggerSearch(", "startSearch(null", "ACTION_VOICE_ASSIST", "showAssist(", "startAssist", "VoiceInteractionSession"};
        for (Path p : sources()) {
            String f = p.getFileName().toString();
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            for (String b : banned) {
                if (b.equals("com.google.android.apps.tv.launcherx") && (f.equals("TvActions.java") || f.equals("Catalogue.java"))) continue;   // named only as "not something you were watching"
                assertFalse(f + " uses " + b, s.contains(b));
            }
            // RecognizerIntent is only ever handed to SpeechRecognizer (in-process listening), never to startActivity (Google's voice UI).
            if (s.contains("ACTION_RECOGNIZE_SPEECH")) assertFalse(f + " starts Google's voice UI", s.matches("(?s).*startActivity(ForResult)?\\([^;]*RecognizerIntent.*"));
        }
    }

    @Test public void theOnlySearchIntentIsAnAppsOwnSearchByPackage() throws Exception {
        for (Path p : sources()) {
            String f = p.getFileName().toString();
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            if (!f.equals("TvActions.java")) assertFalse(f + " starts a search intent", s.contains("Intent.ACTION_SEARCH"));
        }
        String tv = new String(Files.readAllBytes(new File("src/main/java/ai/futurespeak/friday/tv/actions/TvActions.java").toPath()), StandardCharsets.UTF_8);
        int start = tv.indexOf("private boolean startSearch(");
        String body = tv.substring(start, tv.indexOf("\n    }", start));
        assertTrue("addressed to the app itself", body.indexOf("i.setPackage(r.pkg);") > 0 && body.indexOf("i.setPackage(r.pkg);") < body.indexOf("ctx.startActivity(i);"));
        for (String pkg : ai.futurespeak.friday.tv.actions.SearchRecipesAccess.packages())
            assertFalse("no Google app has a search recipe: " + pkg, pkg.startsWith("com.google."));
    }

    @Test public void geminiLiveIsASocketInsideFridayNeverAnActivity() throws Exception {
        for (Path p : sources()) {
            if (!p.toString().replace('\\', '/').contains("/live/")) continue;
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            assertFalse(p.getFileName() + " starts an activity", s.contains("startActivity"));
            assertFalse(p.getFileName() + " uses an Intent", s.contains("new Intent("));
        }
    }
}
