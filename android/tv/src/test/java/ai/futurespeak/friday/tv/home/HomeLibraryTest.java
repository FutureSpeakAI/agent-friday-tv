package ai.futurespeak.friday.tv.home;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Studio hook 7: "Made by Friday" on Home, under the hero tiles, refreshed on return, never in kids mode. */
public class HomeLibraryTest {
    @Test public void theLibraryRowSitsUnderTheHeroAndRefreshes() throws Exception {
        String h = new String(Files.readAllBytes(new File("src/main/java/ai/futurespeak/friday/tv/home/HomeActivity.java").toPath()), StandardCharsets.UTF_8);
        int add = h.indexOf("if (\"hero\".equals(r) && !kids) {");
        assertTrue("added right after the hero row, family mode only", add > 0);
        int make = h.indexOf("new ai.futurespeak.friday.tv.studio.LibraryRow(this)", add);
        assertTrue(make > add && make - add < 200);
        assertTrue(h.indexOf("column.addView(library", make) > make);
        int resume = h.indexOf("protected void onResume()");
        int refresh = h.indexOf("if (library != null && !kids) library.refresh();", resume);
        assertTrue("re-read on every return", refresh > resume && refresh - resume < 600);
    }
}
