package org.tvprivacy.adbreak;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Spec TV-E02 / TV-E04 guard tests. These go red on a build that grows network access,
 * gestures, screenshots, or lets launch() take anything but a package name.
 */
public class GuardTest {
    private static Path module() {
        Path p = Paths.get("").toAbsolutePath();
        if (Files.isDirectory(p.resolve("src/main"))) return p;          // run from app/
        if (Files.isDirectory(p.resolve("app/src/main"))) return p.resolve("app");
        throw new IllegalStateException("can't find app module from " + p);
    }

    private static String read(Path p) throws IOException { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8); }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> s = Files.walk(module().resolve("src/main/java"))) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    /** Strip // and /* comments so a comment mentioning a word doesn't trip the guard. */
    private static String code(Path p) throws IOException {
        return read(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    @Test public void manifest_hasNoNetworkPermissions() throws IOException {
        String m = read(module().resolve("src/main/AndroidManifest.xml")).replaceAll("(?s)<!--.*?-->", "");
        assertFalse("INTERNET permission", m.contains("android.permission.INTERNET"));
        assertFalse("ACCESS_NETWORK_STATE", m.contains("android.permission.ACCESS_NETWORK_STATE"));
        assertFalse("ACCESS_WIFI_STATE", m.contains("android.permission.ACCESS_WIFI_STATE"));
    }

    @Test public void build_hasNoRuntimeDependencies() throws IOException {
        String g = read(module().resolve("build.gradle")).replaceAll("//[^\\n]*", "");
        Matcher m = Pattern.compile("(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|releaseImplementation|debugImplementation)\\b").matcher(g);
        assertFalse("runtime dependency declared in app/build.gradle", m.find());
    }

    @Test public void sources_referenceNoSocketsOrNetworking() throws IOException {
        Pattern net = Pattern.compile("\\bjava\\.net\\.|\\bSocket\\b|\\bServerSocket\\b|HttpURLConnection|URLConnection|\\bokhttp|javax\\.net\\.|android\\.net\\.http|WebView|\\bDatagram");
        List<String> hits = new ArrayList<>();
        for (Path p : mainSources()) if (net.matcher(code(p)).find()) hits.add(p.getFileName().toString());
        assertTrue("network code in " + hits, hits.isEmpty());
    }

    @Test public void sources_noGesturesNoScreenshots() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path p : mainSources()) {
            String c = code(p);
            if (c.contains("dispatchGesture") || c.contains("takeScreenshot") || c.contains("GLOBAL_ACTION_TAKE_SCREENSHOT")) hits.add(p.getFileName().toString());
        }
        assertTrue("gesture/screenshot in " + hits, hits.isEmpty());
    }

    /** launch builds its Intent only from PackageManager; it never takes component/action/URI/extras from the caller. */
    @Test public void launch_onlyStartsTheLauncherIntentForAPackage() throws IOException {
        String hands = code(module().resolve("src/main/java/org/tvprivacy/adbreak/Hands.java"));
        for (String bad : new String[] {"new Intent(", "setComponent", "setClassName", "setData", "setDataAndType", "setAction",
                "putExtra", "putExtras", "parseUri", "getParcelable", "Intent.CATEGORY", "ComponentName", "Uri.parse", "setPackage", "setSelector"}) {
            assertFalse("Hands.java uses " + bad, hands.contains(bad));
        }
        assertTrue(hands.contains("getLeanbackLaunchIntentForPackage(pkg)"));
        assertTrue(hands.contains("getLaunchIntentForPackage(pkg)"));

        // In the provider, the hands methods may read only "pkg" and "ref" from the caller.
        String prov = code(module().resolve("src/main/java/org/tvprivacy/adbreak/GrownUpProvider.java"));
        int a = prov.indexOf("case \"foreground\"");
        int b = prov.indexOf("case \"events\"");
        assertTrue(a > 0 && b > a);
        String section = prov.substring(a, b);
        Matcher m = Pattern.compile("extras\\.(\\w+)\\(\\s*\"([^\"]*)\"").matcher(section);
        while (m.find()) {
            assertEquals("only getString on extras", "getString", m.group(1));
            assertTrue("reads extra '" + m.group(2) + "'", m.group(2).equals("pkg") || m.group(2).equals("ref"));
        }
        assertFalse(section.contains("extras.get(") || section.contains("extras.keySet") || section.contains("getParcelable"));
    }

    @Test public void provider_checksPermissionOnEveryCall() throws IOException {
        String prov = code(module().resolve("src/main/java/org/tvprivacy/adbreak/GrownUpProvider.java"));
        int call = prov.indexOf("public Bundle call(");
        int enforce = prov.indexOf("HelperPolicy.enforce(");
        int sw = prov.indexOf("switch (");
        assertTrue("enforce must come first in call()", call > 0 && enforce > call && enforce < sw);
        String manifest = read(module().resolve("src/main/AndroidManifest.xml"));
        assertTrue(manifest.contains("android:protectionLevel=\"signature\""));
    }
}
