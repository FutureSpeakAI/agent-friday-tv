package org.tvprivacy.adbreak;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** The Cast keep-alive only holds a plain binding to Google's cast receiver: no calls, no data. */
public class CastKeepAliveTest {
    static String code() throws Exception {
        Path p = Paths.get("src/main/java/org/tvprivacy/adbreak/CastKeepAlive.java");
        if (!Files.exists(p)) p = Paths.get("app").resolve(p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    @Test public void bindsOnlyTheCastReceiver_explicitPackage() throws Exception {
        String c = code();
        assertTrue(c.contains("new Intent(CAST_ACTION).setPackage(CAST_PKG)"));
        assertEquals("com.google.android.apps.mediashell", CastKeepAlive.CAST_PKG);
        assertEquals(1, c.split("(?<!un)bindService\\(", -1).length - 1);
    }

    @Test public void neverTalksToTheReceiver() throws Exception {
        String c = code();
        for (String bad : new String[] {"transact(", "queryLocalInterface", "Parcel", "linkToDeath", "putExtra", "startService", "sendBroadcast"}) {
            assertFalse("CastKeepAlive uses " + bad, c.contains(bad));
        }
    }

    @Test public void backsOff_doesNotSpin() throws Exception {
        String c = code();
        assertTrue(c.contains("backoffMs = Math.min(backoffMs * 2, 10 * 60_000L)"));
    }
}
