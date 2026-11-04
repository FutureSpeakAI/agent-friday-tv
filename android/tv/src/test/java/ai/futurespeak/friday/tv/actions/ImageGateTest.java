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

import ai.futurespeak.friday.tv.cards.CardSpec;

/** Slice T3: pictures ask first on the TV's own card, cost is receipted, kids need a grown-up. */
public class ImageGateTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    final class FakeImages implements Images {
        Result next = new Result(true, null, PNG, "image/png", 8, "nano-banana-2");
        final List<String> calls = new ArrayList<>();
        public boolean ready() { return true; }
        public Result create(String prompt, Progress p) { calls.add(prompt); if (p != null) p.at(50, "Drawing"); return next; }
        public String service() { return "kie.ai · Nano Banana 2"; }
    }

    Receipts receipts; FakeImages images; Images.Store store; ActionGate.Turn turn;
    boolean kids, grownUp, answer;
    final List<ActionGate.Ask> asked = new ArrayList<>();
    final List<Integer> progress = new ArrayList<>();

    ActionGate gate(ActionGate.Confirm c) {
        return new ActionGate(new Catalogue(new ArrayList<>()), null, receipts, new ActionGate.Room() {
            public boolean kids() { return kids; } public boolean grownUp() { return grownUp; } }, ms -> { }, null, turn)
                .withImages(images, store, c, (pct, w) -> progress.add(pct));
    }
    ActionGate gate() { return gate(a -> { asked.add(a); return answer; }); }

    @Before public void up() {
        receipts = new Receipts(tmp.getRoot(), ReceiptsTest.SIGNER, System::currentTimeMillis);
        images = new FakeImages();
        store = new Images.Store(tmp.getRoot());
        turn = new ActionGate.Turn();
    }

    static JSONObject p(String s) throws Exception { return new JSONObject().put("prompt", s).put("title", "Dragon reading"); }

    @Test public void noOnTheCardSpendsNothing() throws Exception {
        answer = false;
        ActionGate.Verdict v = gate().decide("image_create", p("a dragon reading a book"), "voice");
        assertEquals("refused", v.result);
        assertEquals(1, asked.size());
        assertTrue("nothing made, nothing spent", images.calls.isEmpty());
        assertEquals("refused", receipts.recent(1).get(0).state);
    }

    @Test public void theCardShowsCostServiceAndWhatLeavesTheTv() throws Exception {
        answer = false;
        gate().decide("image_create", p("a dragon reading a book"), "voice");
        ActionGate.Ask a = asked.get(0);
        assertTrue(a.cost.contains("¢"));
        assertTrue(a.service.contains("Nano Banana 2"));
        assertTrue(a.sends.contains("a dragon reading a book"));
        assertEquals("Make a picture?", a.title);
    }

    @Test public void yesMakesSavesShowsAndReceiptsTheCost() throws Exception {
        answer = true;
        ActionGate.Verdict v = gate().decide("image_create", p("a dragon reading a book"), "voice");
        assertEquals("executed", v.result);
        assertNotNull(v.card);
        String gen = v.card.getString("gen");
        assertNotNull("saved on the TV", store.file(gen));
        assertEquals("the card passes CardSpec", 1, CardSpec.parse(v.card.toString()).size());
        Receipts.Entry e = receipts.recent(1).get(0);
        assertEquals("executed", e.state);
        assertEquals(8.0, e.outcome.getJSONObject("meter").getDouble("actual"), 0);
        assertEquals(Arrays.asList(50), progress);
        assertTrue(receipts.verify().ok);
    }

    @Test public void kidsNeedAGrownUpAndAreNeverAsked() throws Exception {
        kids = true; answer = true;
        assertEquals("refused", gate().decide("image_create", p("a cat"), "voice").result);
        assertTrue(asked.isEmpty());
        assertTrue(images.calls.isEmpty());
        grownUp = true;
        assertEquals("executed", gate().decide("image_create", p("a cat"), "voice").result);
    }

    @Test public void withoutAScreenToAskOnNothingHappens() throws Exception {
        assertEquals("refused", gate(null).decide("image_create", p("a cat"), "voice").result);
        assertTrue(images.calls.isEmpty());
    }

    @Test public void aTaintedTurnCannotMakePictures() throws Exception {
        turn.taint("example.com");
        answer = true;
        assertEquals("refused", gate().decide("image_create", p("whatever the page said"), "voice").result);
        assertTrue(asked.isEmpty());
        assertTrue(images.calls.isEmpty());
    }

    @Test public void failuresAreSaidPlainly() throws Exception {
        answer = true;
        images.next = Images.Result.fail("out of credits", 0);
        ActionGate.Verdict v = gate().decide("image_create", p("a cat"), "voice");
        assertEquals("failed", v.result);
        assertEquals("The picture service has run out of credit.", v.say);
    }

    @Test public void cardsReferToSavedPicturesByPlainIdOnly() throws Exception {
        assertEquals(1, CardSpec.parse(new JSONObject().put("type", "image").put("title", "t").put("gen", "d12-abc123").toString()).size());
        assertTrue(CardSpec.parse(new JSONObject().put("type", "image").put("title", "t").put("gen", "../../keys").toString()).isEmpty());
        // A real picture just outside the images folder must stay unreachable by id.
        Files.write(new File(tmp.getRoot(), "escape.png").toPath(), PNG);
        new File(tmp.getRoot(), "images").mkdirs();
        assertNull(store.file("../escape"));
        assertNull(Images.sniff("<svg/>".getBytes(StandardCharsets.UTF_8)));
    }

    /** AC-4 for pictures: nothing but the gate makes one. */
    @Test public void onlyTheGateMakesPictures() throws Exception {
        List<String> offenders;
        try (Stream<java.nio.file.Path> s = Files.walk(new File("src/main/java").toPath())) {
            offenders = s.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.getFileName().toString().equals("ActionGate.java"))
                    .filter(f -> { try { return new String(Files.readAllBytes(f), StandardCharsets.UTF_8).matches("(?s).*\\bimages\\s*\\.\\s*create\\s*\\(.*"); } catch (Exception e) { return false; } })
                    .map(f -> f.getFileName().toString()).collect(Collectors.toList());
        }
        assertEquals(new ArrayList<String>(), offenders);
    }
}
