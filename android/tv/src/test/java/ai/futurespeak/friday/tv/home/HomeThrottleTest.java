package ai.futurespeak.friday.tv.home;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Home v2: focus moves missed 60 fps on the TCL with the avatar rendering, so she holds her last frame while focus moves. */
public class HomeThrottleTest {
    private static String read(String p) throws Exception { return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8); }

    @Test public void focusMovesHoldTheAvatar() throws Exception {
        String h = read("src/main/java/ai/futurespeak/friday/tv/home/HomeActivity.java");
        int l = h.indexOf("addOnGlobalFocusChangeListener(");
        int hold = h.indexOf("avatar.hold(HOLD_MS)", l);
        assertTrue(l > 0 && hold > l && hold - l < 400);
        assertTrue(HomeActivity.HOLD_MS >= 300 && HomeActivity.HOLD_MS <= 1000);
        int drift = h.indexOf("if (oldF != null) holdDrift();", l);
        assertTrue("the drift waits too", drift > l && drift - l < 500);
        assertTrue(h.contains("drift.pause();") && h.contains("root.postDelayed(resumeDrift, HOLD_MS);"));
    }

    @Test public void aHoldDefersFramesAndNeverCountsAsSlow() throws Exception {
        String b = read("src/main/assets/avatar/tv_bridge.js");
        assertTrue(b.contains("hold: hold,"));
        assertTrue("frames wait while held", b.contains("if (performance.now() < holdUntil) { held.push(cb); return 0; }"));
        assertTrue("the fps window restarts after a hold", b.contains("var cbs = held; held = []; windowStart = 0; frames = 0;"));
        assertTrue("a hold is capped", b.contains("Math.min(ms | 0, 2000)"));
        String a = read("src/main/java/ai/futurespeak/friday/tv/web/AvatarView.java");
        assertTrue(a.contains("js(\"fridayTV.hold(\" + ms + \")\")"));
    }
}
