package ai.futurespeak.friday.tv.ambient;

import static org.junit.Assert.*;

import org.junit.Test;

public class MixTest {
    private static String run(int mix, int turns, boolean news, boolean other) {
        AmbientMix m = new AmbientMix(mix);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < turns; i++) s.append(m.next(news, other).name().charAt(0));   // N(ews) / O(ther) / N(othing)->'N'
        return s.toString();
    }

    @Test public void ratios() {
        assertEquals("NNNONNNO", run(0, 8, true, true));   // mostly news 3:1
        assertEquals("NONONONO", run(1, 8, true, true));   // even
        assertEquals("NOOONOOO", run(2, 8, true, true));   // mostly cards and channels 1:3
    }

    @Test public void onlyWhatExists() {
        assertEquals("NNNN", run(2, 4, true, false));     // nothing pinned: news only
        assertEquals("OOOO", run(0, 4, false, true));     // News not built in or switched off
        assertEquals(AmbientMix.Kind.NOTHING, new AmbientMix(1).next(false, false));
    }
}
