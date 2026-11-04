package ai.futurespeak.friday.tv;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/** Repository rules that must keep holding as the app grows. Each fails if the rule is broken. */
public class GuardsTest {
    private static final File MAIN = new File("src/main");

    private static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    private static List<File> javaSources() throws IOException {
        try (Stream<Path> s = Files.walk(new File(MAIN, "java").toPath())) {
            return s.filter(p -> p.toString().endsWith(".java")).map(Path::toFile).collect(Collectors.toList());
        }
    }

    @Test public void onlyInternetAndMicrophonePermissions() throws Exception {
        String m = read(new File(MAIN, "AndroidManifest.xml"));
        Matcher x = Pattern.compile("uses-permission\\s+android:name=\"([^\"]+)\"").matcher(m);
        TreeSet<String> perms = new TreeSet<>();
        while (x.find()) perms.add(x.group(1));
        // GROWN_UP is Ad Break Guard's signature permission for the shared PIN: same signing key
        // only, no device or network capability.
                assertEquals("[android.permission.INTERNET, android.permission.RECORD_AUDIO, org.tvprivacy.adbreak.permission.GROWN_UP]", perms.toString());
        assertTrue("WebView metrics must be opted out", m.contains("android.webkit.WebView.MetricsOptOut\" android:value=\"true\""));
        assertTrue(m.contains("android:allowBackup=\"false\""));
        assertTrue(m.contains("android:usesCleartextTraffic=\"false\""));
    }

    /** TV-E01: Friday's hands are the helper's few methods, never a way to read or drive the whole TV. */
    @Test public void noPowerfulTvCapabilitiesAnywhere() throws Exception {
        String m = read(new File(MAIN, "AndroidManifest.xml"));
        for (String banned : new String[]{"BIND_ACCESSIBILITY_SERVICE", "BIND_NOTIFICATION_LISTENER_SERVICE",
                "REQUEST_INSTALL_PACKAGES", "WRITE_SECURE_SETTINGS", "QUERY_ALL_PACKAGES", "INJECT_EVENTS",
                "SYSTEM_ALERT_WINDOW", "PACKAGE_USAGE_STATS"})
            assertFalse("manifest mentions " + banned, m.contains(banned));
        for (File f : javaSources()) {
            String s = read(f);
            assertFalse(f.getName() + " uses accessibility", s.contains("AccessibilityService"));
            assertFalse(f.getName() + " injects input", s.contains("injectInputEvent") || s.contains("Instrumentation()"));
            assertFalse(f.getName() + " shells out", s.contains("Runtime.getRuntime().exec") || s.contains("ProcessBuilder"));
        }
    }

    @Test public void noJavascriptBridgesAnywhere() throws Exception {
        for (File f : javaSources())
            assertFalse(f.getName() + " adds a JavaScript bridge", read(f).contains("addJavascriptInterface("));
    }

    @Test public void noAnalyticsOrTelemetryCode() throws Exception {
        String[] banned = {"firebase", "crashlytics", "analytics", "telemetry", "sentry", "appcenter", "mixpanel", "amplitude"};
        for (File f : javaSources()) {
            String s = read(f).toLowerCase();
            for (String b : banned) {
                java.util.regex.Pattern word = java.util.regex.Pattern.compile("\\b" + b + "\\b");   // whole words: "KidsEntry" is not "sentry"
                if (word.matcher(s).find()) {
                    // allowed only inside a comment that says there is none
                    for (String line : s.split("\n")) if (word.matcher(line).find()) assertTrue(f.getName() + ": " + line.trim(), line.trim().startsWith("*") || line.trim().startsWith("//") || line.contains("no analytics"));
                }
            }
        }
        String gradle = read(new File("build.gradle"));
        // The only runtime dependency is our own add-on SDK module, which has none of its own.
        assertFalse("no third-party runtime dependencies", Pattern.compile("(?m)^\\s*implementation\\s(?!project\\(':addon-sdk'\\))").matcher(gradle).find());
        String sdk = read(new File("../addon-sdk/build.gradle"));
        assertFalse("the SDK has no runtime dependencies", Pattern.compile("(?m)^\\s*(implementation|api)\\s").matcher(sdk).find());
    }

    @Test public void keysAreNeverLogged() throws Exception {
        Pattern logOfSecret = Pattern.compile("Log\\.[a-z]\\([^;]*(anthropicKey\\(\\)|get\\(SecureStore\\.|xi-api-key|Bearer)");
        for (File f : javaSources()) assertFalse(f.getName(), logOfSecret.matcher(read(f)).find());
    }

    @Test public void desktopAvatarCopyIsUntouched() throws Exception {
        File dir = new File(MAIN, "assets/avatar/desktop");
        JSONObject src = new JSONObject(read(new File(dir, "SOURCE.json")));
        JSONObject files = src.getJSONObject("files");
        assertTrue(files.length() >= 10);
        for (Iterator<String> it = files.keys(); it.hasNext(); ) {
            String name = it.next();
            byte[] b = Files.readAllBytes(new File(dir, name).toPath());
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder hex = new StringBuilder();
            for (byte x : d) hex.append(String.format("%02x", x));
            assertEquals(name + " differs from the desktop copy; re-run tools/sync-avatar.mjs instead of editing it",
                    files.getJSONObject(name).getString("sha256"), hex.toString());
        }
    }

    @Test public void approvalScreensAreFixedUi() throws Exception {
        // Approval text must come from the app: ApprovalLayer is only built in MainActivity, from string literals plus the
        // channel's title/host names.
        List<String> builders = new ArrayList<>();
        for (File f : javaSources()) if (read(f).contains("new ApprovalLayer(")) builders.add(f.getName());
        java.util.Collections.sort(builders);
        assertEquals("[MainActivity.java, SettingsPages.java]", builders.toString());
    }
}
