package ai.futurespeak.friday.tv.actions;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Slice S1: AC-3 (unknown refused, receipted, model told), AC-4 (nothing reaches the hands around the gate), AC-5 (only verified "open"). */
public class ActionGateTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final Catalogue CAT = new Catalogue(Arrays.asList(
            new Catalogue.App("com.plexapp.android", "Plex"),
            new Catalogue.App("org.pbskids.video", "PBS KIDS"),
            new Catalogue.App("com.android.tv.settings", "Settings"),        // filtered out: never offered
            new Catalogue.App("com.netflix.ninja", "Netflix")));

    /** A helper stand-in that records every call and checks a decision receipt exists for its ref (the AC-4 canary). */
    final class FakeHands implements Hands {
        String front = "com.spocky.projengmenu";
        long seq = 10;
        boolean silent = false, frontAfterLaunch = true, refuse = false;
        final List<String> calls = new ArrayList<>();
        @Override public Foreground foreground() { return silent ? null : new Foreground(front, seq); }
        @Override public Launch launch(String pkg, String ref) {
            calls.add("launch " + pkg + " ref=" + ref);
            assertTrue("hands called without a decision receipt for " + ref, hasDecision(ref));
            if (refuse) return new Launch(false, "forbidden", seq);
            long before = seq;
            if (frontAfterLaunch) { front = pkg; seq++; }
            return new Launch(true, "", before);
        }
        @Override public boolean back(String ref) {
            calls.add("back ref=" + ref);
            assertTrue(hasDecision(ref));
            seq++;
            front = "com.spocky.projengmenu";
            return true;
        }
    }

    Receipts receipts;
    FakeHands hands;
    boolean kids = false;
    ActionGate gate;

    @Before public void up() {
        receipts = new Receipts(tmp.getRoot(), ReceiptsTest.SIGNER, System::currentTimeMillis);
        hands = new FakeHands();
        gate = new ActionGate(CAT, hands, receipts, new ActionGate.Room() {
            public boolean kids() { return kids; }
            public boolean grownUp() { return false; }
        }, ms -> { });
    }

    boolean hasDecision(String id) {
        try {
            for (String l : Files.readAllLines(new File(tmp.getRoot(), "receipts/actions.jsonl").toPath(), StandardCharsets.UTF_8)) {
                JSONObject r = new JSONObject(l);
                if ("decision".equals(r.optString("kind")) && id.equals(r.getJSONObject("rec").optString("id"))) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    static JSONObject app(String a) throws Exception { return new JSONObject().put("app", a); }

    @Test public void opensAndVerifiesBeforeClaimingIt() throws Exception {
        ActionGate.Verdict v = gate.decide("app_open", app("Plex"), "voice");
        assertEquals("verified", v.result);
        assertEquals("Plex is open.", v.say);
        assertEquals("verified", receipts.recent(1).get(0).state);
        assertTrue(receipts.verify().ok);
    }

    @Test public void helperSilentNeverClaimsOpen() throws Exception {
        hands.frontAfterLaunch = true;
        // the launch is accepted but the helper stops answering before it can confirm
        Hands silentAfter = new Hands() {
            public Foreground foreground() { return null; }
            public Launch launch(String pkg, String ref) { return new Launch(true, "", 10); }
            public boolean back(String ref) { return false; }
        };
        ActionGate g = new ActionGate(CAT, silentAfter, receipts, new ActionGate.Room() {
            public boolean kids() { return false; } public boolean grownUp() { return false; } }, ms -> { });
        ActionGate.Verdict v = g.decide("app.open", app("Plex"), "voice");
        assertEquals("unverified", v.result);
        assertEquals("I asked Plex to open; I can't see whether it did.", v.say);
        assertFalse(v.say.contains("is open"));
        assertTrue(v.toModel.startsWith("UNVERIFIED"));
    }

    @Test public void appThatNeverCameForwardIsUnverified() throws Exception {
        hands.frontAfterLaunch = false;
        ActionGate.Verdict v = gate.decide("app.open", app("Plex"), "voice");
        assertEquals("unverified", v.result);
        assertEquals("unverified", receipts.recent(1).get(0).state);
    }

    @Test public void staleForegroundDoesNotCount() throws Exception {
        // Plex was already in front before the request: the same seq must not verify a new launch.
        hands.front = "com.plexapp.android";
        hands.frontAfterLaunch = false;
        assertEquals("unverified", gate.decide("app.open", app("Plex"), "voice").result);
    }

    @Test public void unknownActionIsRefusedReceiptedAndExplained() throws Exception {
        ActionGate.Verdict v = gate.decide("purchase_movie", new JSONObject(), "voice");
        assertEquals("refused", v.result);
        assertTrue(v.toModel.startsWith("REFUSED"));
        assertTrue(hands.calls.isEmpty());
        Receipts.Entry e = receipts.recent(1).get(0);
        assertEquals("refused", e.state);
        assertEquals("purchase_movie", e.decision.optString("action"));
    }

    @Test public void appNotOnThisTvIsRefused() throws Exception {
        ActionGate.Verdict v = gate.decide("app_open", app("Hulu"), "voice");
        assertEquals("refused", v.result);
        assertEquals("Hulu isn't on this TV.", v.say);
        assertTrue(v.toModel.contains("Plex"));          // the model is told what exists
        assertTrue(hands.calls.isEmpty());
    }

    @Test public void settingsIsNeverOffered() throws Exception {
        assertEquals("refused", gate.decide("app_open", app("Settings"), "voice").result);
        assertFalse(CAT.tools().toString().contains("Settings"));
        assertTrue(hands.calls.isEmpty());
    }

    @Test public void kidsModeRefusesWithoutAPinAndAllowsTheKidsList() throws Exception {
        kids = true;
        ActionGate.Verdict v = gate.decide("app_open", app("Netflix"), "voice");
        assertEquals("refused", v.result);
        assertEquals("That needs a grown-up.", v.say);
        assertTrue(hands.calls.isEmpty());
        assertEquals("kids", receipts.recent(1).get(0).decision.has("who_may") ? "kids" : "");
        assertEquals("verified", gate.decide("app_open", app("PBS KIDS"), "voice").result);
    }

    @Test public void helperRefusalIsReported() throws Exception {
        hands.refuse = true;
        ActionGate.Verdict v = gate.decide("app_open", app("Plex"), "voice");
        assertEquals("refused", v.result);
        assertEquals("I'm not allowed to open Plex.", v.say);
    }

    @Test public void backIsReceiptedAndVerified() throws Exception {
        gate.decide("app_open", app("Plex"), "voice");
        ActionGate.Verdict v = gate.decide("app.back", new JSONObject(), "remote");
        assertEquals("verified", v.result);
        assertEquals(2, hands.calls.size());
        assertTrue(receipts.verify().ok);
    }

    /** AC-4, static half: nothing in the app calls the hands except the gate. */
    @Test public void onlyTheGateTouchesTheHands() throws Exception {
        List<String> offenders;
        try (Stream<java.nio.file.Path> s = Files.walk(new File("src/main/java").toPath())) {
            offenders = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("ActionGate.java"))
                    .filter(p -> {
                        try {
                            String t = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                            return t.matches("(?s).*\\b(hands|helper|Hands)\\w*\\s*\\.\\s*(launch|back)\\s*\\(.*")
                                    || t.matches("(?s).*new\\s+HelperHands\\s*\\([^)]*\\)\\s*\\.\\s*(launch|back)\\s*\\(.*");
                        } catch (Exception e) { return false; }
                    })
                    .map(p -> p.getFileName().toString()).collect(Collectors.toList());
        }
        assertEquals("only ActionGate may call Hands.launch/back", new ArrayList<String>(), offenders);
    }
}
