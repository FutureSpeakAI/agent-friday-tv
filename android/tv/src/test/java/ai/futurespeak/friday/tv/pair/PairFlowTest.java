package ai.futurespeak.friday.tv.pair;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** The pairing screen's steps (T1b), against the fake Friday computer. */
public class PairFlowTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    FakeDesktop pc;
    DesktopLink link;
    final List<String> shown = Collections.synchronizedList(new ArrayList<>());
    int sleeps;

    @Before public void up() throws Exception {
        pc = new FakeDesktop();
        link = new DesktopLink(tmp.newFile("link.json"), new FakeDesktop.SoftKey());
    }

    @After public void down() throws Exception { pc.close(); }

    PairFlow flow(boolean anyone) throws Exception {
        List<DesktopLink.Hint> hints = anyone
                ? Collections.singletonList(new DesktopLink.Hint("127.0.0.1", pc.port(), pc.pinOf("desktop")))
                : Collections.emptyList();
        return new PairFlow(link, (step, detail, found) -> shown.add(step + (detail == null ? "" : ":" + detail)),
                () -> hints, ms -> sleeps++, "Den TV");
    }

    @Test public void noComputerSaysSoAndChangesNothing() throws Exception {
        flow(false).search();
        assertEquals(java.util.Arrays.asList("SEARCHING", "NONE"), shown);
        assertFalse(link.paired());
    }

    @Test public void theTvShowsThePcsDigitsAndIsPairedOnlyAfterThePress() throws Exception {
        PairFlow f = flow(true);
        f.search();
        f.choose(0);
        assertTrue(shown.contains("CODE:" + pc.code));
        assertEquals("PAIRED", shown.get(shown.size() - 1));
        assertTrue(link.paired());
    }

    @Test public void aDeclineOnThePcPairsNothing() throws Exception {
        pc.refuse = true;
        PairFlow f = flow(true);
        f.search();
        f.choose(0);
        assertEquals("REFUSED", shown.get(shown.size() - 1));
        assertFalse(link.paired());
    }

    @Test public void noPressInFiveMinutesPairsNothing() throws Exception {
        pc.approve = false;
        PairFlow f = flow(true);
        f.search();
        f.choose(0);
        assertEquals("EXPIRED", shown.get(shown.size() - 1));
        assertEquals(PairFlow.WAIT_MS / PairFlow.POLL_MS, sleeps);
        assertFalse(link.paired());
    }

    @Test public void anImpostorStopsPairingWithAPlainReason() throws Exception {
        pc.certName = "impostor";
        PairFlow f = flow(true);
        f.search();
        f.choose(0);
        String last = shown.get(shown.size() - 1);
        assertTrue(last, last.startsWith("FAILED:") && last.contains("did not match"));
        assertFalse(link.paired());
    }

    @Test public void cancellingStopsWaitingAndPairsNothing() throws Exception {
        pc.approve = false;
        final PairFlow[] self = new PairFlow[1];
        self[0] = new PairFlow(link, (step, d, found) -> shown.add(step.toString()),
                () -> Collections.singletonList(new DesktopLink.Hint("127.0.0.1", pc.port(), "x")),
                ms -> { if (++sleeps == 3) self[0].cancel(); }, "TV");
        self[0].search();
        self[0].choose(0);
        assertEquals(3, sleeps);
        assertEquals("CODE", shown.get(shown.size() - 1));     // stopped waiting; nothing more was shown
        assertFalse(link.paired());
    }
}
