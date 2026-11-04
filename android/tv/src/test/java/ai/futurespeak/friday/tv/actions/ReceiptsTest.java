package ai.futurespeak.friday.tv.actions;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Slice S0: AC-1 (any edit, removal, reorder or truncation breaks verify) and AC-2 (unconfirmed, never done). */
public class ReceiptsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** A deterministic stand-in for the Keystore HMAC. */
    static final Receipts.Signer SIGNER = h -> Receipts.sha256("k3y:" + h);
    long now = 1_000_000;
    Receipts.Clock clock = () -> now;

    Receipts fresh() { return new Receipts(tmp.getRoot(), SIGNER, clock); }

    File log() { return new File(tmp.getRoot(), "receipts/actions.jsonl"); }

    List<String> lines() throws Exception { return new ArrayList<>(Files.readAllLines(log().toPath(), StandardCharsets.UTF_8)); }
    void write(List<String> l) throws Exception { Files.write(log().toPath(), (String.join("\n", l) + "\n").getBytes(StandardCharsets.UTF_8)); }

    Receipts withThreeActions() throws Exception {
        Receipts r = fresh();
        for (int i = 0; i < 3; i++) {
            String id = r.decision("family", new JSONObject().put("action", "app.open").put("args", new JSONObject().put("app", "Plex")));
            now += 1000;
            r.outcome(id, "family", "verified", "in front");
        }
        return r;
    }

    @Test public void intactChainVerifies() throws Exception {
        Receipts r = withThreeActions();
        Receipts.Check c = r.verify();
        assertTrue(c.reason, c.ok);
        assertEquals(6, c.rows);
        assertTrue(fresh().verify().ok);   // reopened from disk, still fine
    }

    @Test public void editingOneLineBreaks() throws Exception {
        Receipts r = withThreeActions();
        List<String> l = lines();
        l.set(2, l.get(2).replace("Plex", "Netflix"));
        write(l);
        Receipts.Check c = r.verify();
        assertFalse(c.ok);
        assertEquals(3, c.brokenAtSeq);
    }

    @Test public void removingALineBreaks() throws Exception {
        Receipts r = withThreeActions();
        List<String> l = lines();
        l.remove(1);
        write(l);
        assertFalse(r.verify().ok);
    }

    @Test public void reorderingBreaks() throws Exception {
        Receipts r = withThreeActions();
        List<String> l = lines();
        Collections.swap(l, 2, 3);
        write(l);
        assertFalse(r.verify().ok);
    }

    @Test public void cuttingTheEndBreaks() throws Exception {
        Receipts r = withThreeActions();
        List<String> l = lines();
        write(l.subList(0, 4));   // a clean prefix: the chain itself is valid, only the head catches it
        Receipts.Check c = r.verify();
        assertFalse(c.ok);
        assertTrue(c.reason, c.reason.contains("removed from the end"));
    }

    @Test public void aWrongKeyBreaks() throws Exception {
        withThreeActions();
        Receipts other = new Receipts(tmp.getRoot(), h -> Receipts.sha256("other:" + h), clock);
        assertFalse(other.verify().ok);
    }

    @Test public void decisionWithoutOutcomeIsUnconfirmedNeverDone() throws Exception {
        Receipts r = fresh();
        String id = r.decision("family", new JSONObject().put("action", "app.open"));
        assertEquals("pending", r.recent(5).get(0).state);
        now += Receipts.UNCONFIRMED_MS - 1;
        assertEquals("pending", r.recent(5).get(0).state);
        now += 1;
        assertEquals("unconfirmed", r.recent(5).get(0).state);
        r.outcome(id, "family", "verified", "");
        assertEquals("verified", r.recent(5).get(0).state);
    }
}
