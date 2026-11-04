package ai.futurespeak.friday.tv.web;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Start-up smoothness: auto quality must start where the TV settled, or low, never at the top. */
public class AvatarTierTest {
    @Test public void readsTheSettledLine() {
        assertEquals(3, AvatarView.settledLevel("fridaytv: settled level=3 tier=lite@1.25+trim fps=27.0"));
        assertEquals(-1, AvatarView.settledLevel("fridaytv: fps=59.3 tier=lite@1.5+trim"));
        assertEquals(-1, AvatarView.settledLevel(null));
    }

    @Test public void firstRunStartsConservativeNotAtFullQuality() throws Exception {
        String shim = new String(Files.readAllBytes(Paths.get("src/main/assets/avatar/tv_shim.js")), "UTF-8");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("FIRST_RUN_TIER = (\\d)").matcher(shim);
        assertTrue(m.find());
        assertTrue("first run must not start at the full pipeline", Integer.parseInt(m.group(1)) >= 2);
        assertTrue(shim.contains("q.get('tier')"));
    }

    @Test public void canvasFadesInAfterWarmUp() throws Exception {
        String html = new String(Files.readAllBytes(Paths.get("src/main/assets/avatar/avatar.html")), "UTF-8");
        String bridge = new String(Files.readAllBytes(Paths.get("src/main/assets/avatar/tv_bridge.js")), "UTF-8");
        assertTrue(html.contains("opacity: 0"));
        assertTrue(bridge.contains("renderer.compile(scene, camera)"));
        assertTrue(bridge.contains("c.style.opacity = '1'"));
    }
}
