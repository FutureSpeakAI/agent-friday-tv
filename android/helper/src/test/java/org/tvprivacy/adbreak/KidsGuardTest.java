package org.tvprivacy.adbreak;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * KM-E01 / AC-K7: no provider method can extend, mint, schedule, grant or leave kids mode.
 * Those exist only as presses in the helper's own activities, behind the PIN.
 */
public class KidsGuardTest {
    private static Path module() {
        Path p = Paths.get("").toAbsolutePath();
        if (Files.isDirectory(p.resolve("src/main"))) return p;
        return p.resolve("app");
    }
    private static Path src(String name) { return module().resolve("src/main/java/org/tvprivacy/adbreak/" + name); }
    private static String code(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> s = Files.walk(module().resolve("src/main/java"))) { return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList()); }
    }
    /** Files (by name) whose code contains this text. */
    private static Set<String> filesUsing(String needle) throws IOException {
        Set<String> out = new HashSet<>();
        for (Path p : mainSources()) if (code(p).contains(needle)) out.add(p.getFileName().toString());
        return out;
    }

    @Test public void provider_hasNoWideningMethod() throws IOException {
        String prov = code(src("GrownUpProvider.java"));
        Matcher m = Pattern.compile("case\\s+\"([^\"]+)\"").matcher(prov);
        List<String> methods = new ArrayList<>();
        while (m.find()) methods.add(m.group(1));
        assertTrue(methods.contains("kidsStatus"));
        assertTrue(methods.contains("kidsEnter"));
        Pattern bad = Pattern.compile("(?i)mint|extend|schedul|grant|exit|leave|kidsoff|bonus|token|bedtime|limit|morning|setTime|countEverything");
        for (String name : methods) assertFalse("provider method '" + name + "' widens", bad.matcher(name).find());
    }

    @Test public void provider_neverCallsWideningCode() throws IOException {
        String prov = code(src("GrownUpProvider.java"));
        for (String bad : new String[] {"setActive(false)", "extendToday", "extendBedtime", "setTime(", "setCountEverything", "setLimitMin",
                "addUsed", "setIdleSince", "reset()", "setPin(", "remove(\"locked_night\")"}) {
            assertFalse("GrownUpProvider calls " + bad, prov.contains(bad));
        }
        // The kids section reads no extras at all.
        int a = prov.indexOf("case \"kidsStatus\""), b = prov.indexOf("case \"events\"");
        assertTrue(a > 0 && b > a);
        assertFalse(prov.substring(a, b).contains("extras"));
        // openGrownUp only after a PIN check (the existing checkPin/unlock case).
        int og = prov.indexOf("openGrownUp");
        assertTrue(og > prov.indexOf("kids.checkPin(pin)"));
        assertEquals(og, prov.lastIndexOf("openGrownUp"));
    }

    @Test public void extend_onlyOnTheLockoutAndTimesUpScreens() throws IOException {
        Set<String> allowed = new HashSet<>(Arrays.asList("Kids.java", "TimeUpActivity.java", "BedtimeActivity.java"));
        assertTrue(allowed.containsAll(filesUsing("extendToday(")));
        assertTrue(allowed.containsAll(filesUsing("extendBedtime(")));
        assertTrue(filesUsing("extendToday(").contains("TimeUpActivity.java"));
        assertTrue(filesUsing("extendBedtime(").contains("BedtimeActivity.java"));
        // and both screens ask for the PIN first
        assertTrue(code(src("TimeUpActivity.java")).contains("PinActivity.MODE_VERIFY"));
        assertTrue(code(src("BedtimeActivity.java")).contains("PinActivity.MODE_VERIFY"));
    }

    @Test public void schedule_onlyOnThePinPage() throws IOException {
        Set<String> allowed = new HashSet<>(Arrays.asList("Kids.java", "KidsSettingsActivity.java"));
        for (String setter : new String[] {"setTime(", "setCountEverything(", "setLimitMin("}) {
            assertTrue(setter + " used in " + filesUsing(setter), allowed.containsAll(filesUsing(setter)));
        }
        String page = code(src("KidsSettingsActivity.java"));
        assertTrue(page.contains("PinActivity.MODE_VERIFY"));
        assertTrue(page.indexOf("if (res != RESULT_OK) { finish(); return; }") > 0);
    }

    @Test public void leavingKidsMode_onlyWithThePin() throws IOException {
        assertEquals(new HashSet<>(Arrays.asList("PinActivity.java")), filesUsing("setActive(false)"));
        String pin = code(src("PinActivity.java"));
        assertTrue(pin.indexOf("kids.checkPin(pin)") < pin.indexOf("kids.setActive(false)"));
    }

    @Test public void grownUpEntry_isSignatureProtected_andIgnoresExtras() throws IOException {
        String m = new String(Files.readAllBytes(module().resolve("src/main/AndroidManifest.xml")), StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
        int a = m.indexOf("<activity-alias");
        assertTrue(a > 0);
        String alias = m.substring(a, m.indexOf("</activity-alias>", a));
        assertTrue(alias.contains("android:targetActivity=\".PinActivity\""));
        assertTrue(alias.contains("android:permission=\"org.tvprivacy.adbreak.permission.GROWN_UP\""));
        assertTrue(alias.contains("org.tvprivacy.adbreak.action.KIDS_GROWN_UP"));
        assertEquals("only one exported alias", a, m.lastIndexOf("<activity-alias"));
        String pin = code(src("PinActivity.java"));
        int branch = pin.indexOf("ACTION_KIDS_GROWN_UP.equals(getIntent().getAction())");
        int extra = pin.indexOf("getIntExtra(MODE");
        assertTrue("alias branch must decide before any extra is read", branch > 0 && branch < extra);
        String aliasBranch = pin.substring(branch, pin.indexOf("} else {", branch));
        assertTrue(aliasBranch.contains("mode = MODE_EXIT"));
        assertFalse(aliasBranch.contains("Extra"));
    }

    @Test public void newActivities_notExported() throws IOException {
        String m = new String(Files.readAllBytes(module().resolve("src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);
        for (String a : new String[] {".BedtimeActivity", ".KidsSettingsActivity", ".PinActivity", ".TimeUpActivity", ".KidsHomeActivity"}) {
            int i = m.indexOf("android:name=\"" + a + "\"");
            assertTrue(a, i > 0);
            String tag = m.substring(m.lastIndexOf("<", i), m.indexOf(">", i));
            assertTrue(a + " must be exported=false", tag.contains("android:exported=\"false\""));
        }
    }
}
