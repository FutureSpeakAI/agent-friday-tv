package ai.futurespeak.friday.tv.brain;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** P5: every key leaves SecureStore through pair/KeyGate; presence checks do not. */
public class SecureStoreGateTest {
    static String source() throws Exception {
        String p = System.getProperty("securestore.src", "src/main/java/ai/futurespeak/friday/tv/secure/SecureStore.java");
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    static String method(String src, String signature) {
        int i = src.indexOf(signature);
        assertTrue("missing: " + signature, i >= 0);
        int depth = 0, j = src.indexOf('{', i);
        for (int k = j; k < src.length(); k++) {
            char c = src.charAt(k);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return src.substring(i, k + 1);
        }
        throw new AssertionError("unbalanced");
    }

    @Test public void getReleasesThroughTheKeyGate() throws Exception {
        String get = method(source(), "public synchronized String get(String name)");
        assertTrue(get.contains("KeyGate.release(name, open(name))"));
    }

    @Test public void hasReportsPresenceWhoeverTheBrainIs() throws Exception {
        String has = method(source(), "public boolean has(String name)");
        assertTrue(has.contains("open(name)"));
        assertFalse(has.contains("get("));
    }
}
