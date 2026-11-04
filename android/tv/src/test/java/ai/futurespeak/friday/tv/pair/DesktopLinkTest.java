package ai.futurespeak.friday.tv.pair;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import static org.junit.Assert.*;

/**
 * The TV's side of pairing (slice T1), against a fake Friday desktop that speaks the
 * gateway's protocol over real TLS 1.3 with a real certificate.
 */
public class DesktopLinkTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    FakeDesktop gw;
    FakeDesktop.SoftKey key;
    File state;

    @Before public void up() throws Exception {
        gw = new FakeDesktop();
        key = new FakeDesktop.SoftKey();
        state = new File(tmp.getRoot(), "desktop_link.json");
    }

    @After public void down() throws Exception { gw.close(); }

    DesktopLink paired() throws Exception {
        DesktopLink link = new DesktopLink(state, key);
        DesktopLink.Attempt a = link.startPairing("127.0.0.1", gw.port(), "Den TV");
        assertEquals("paired", link.pollPairing(a));
        return link;
    }

    // ── the shared arithmetic ───────────────────────────────────────────────

    @Test public void protocolMatchesTheDesktopByteForByte() {
        // The same inputs through tv_gateway/pairing.py and device_pairing.session_message.
        String pin = "64f46a7526a186d2346552453ae478ca51244674f5b21ba150bd483b39f7c812";
        byte[] spki = new byte[91];
        for (int i = 0; i < 91; i++) spki[i] = (byte) i;
        byte[] nt = new byte[32], nd = new byte[32];
        java.util.Arrays.fill(nt, (byte) 7);
        java.util.Arrays.fill(nd, (byte) 9);
        assertEquals(PairProtocol.hex(PairProtocol.sha256("pin".getBytes(StandardCharsets.US_ASCII))), pin);
        assertEquals("1cd3c6b4d7c4804019f1622719840f69582aefac20b9064b30ccd2f6890e69ae",
                PairProtocol.commitment(pin, spki, nd));
        assertEquals("067201", PairProtocol.sas(pin, spki, nt, nd));
        assertEquals("friday-tv-session-v1|" + "ab".repeat(32) + "|0123456789abcdef",
                new String(PairProtocol.sessionMessage("ab".repeat(32), "0123456789abcdef"), StandardCharsets.US_ASCII));
        assertEquals("067 201", PairProtocol.spoken("067201"));
    }

    // ── pairing ─────────────────────────────────────────────────────────────

    @Test public void bothScreensShowTheSameDigitsAndThePinIsTheDesktopsKey() throws Exception {
        DesktopLink link = new DesktopLink(state, key);
        DesktopLink.Attempt a = link.startPairing("127.0.0.1", gw.port(), "Den TV");
        assertEquals(gw.code, a.code);
        assertFalse(link.paired());                       // nothing is pinned before the press
        assertEquals("paired", link.pollPairing(a));
        assertTrue(link.paired());
        assertEquals(gw.pinOf("desktop"), link.pin());
        assertEquals(gw.pinOf("desktop"), new DesktopLink(state, key).pin());   // survives a restart
    }

    @Test public void nothingIsPinnedUntilThePressOnThePc() throws Exception {
        gw.approve = false;
        DesktopLink link = new DesktopLink(state, key);
        DesktopLink.Attempt a = link.startPairing("127.0.0.1", gw.port(), "TV");
        assertEquals("awaiting_press", link.pollPairing(a));
        assertFalse(link.paired());
        assertFalse(state.exists());
    }

    @Test public void aRevealThatBreaksTheCommitmentStopsPairing() throws Exception {
        gw.lieAboutNd = true;
        try {
            new DesktopLink(state, key).startPairing("127.0.0.1", gw.port(), "TV");
            fail("pairing went on after a broken commitment");
        } catch (DesktopLink.LinkException e) {
            assertTrue(e.getMessage().contains("did not match"));
        }
    }

    @Test public void anImpostorDuringPairingShowsDifferentDigits() throws Exception {
        gw.certName = "impostor";      // the TV meets another key; the PC computes with its own
        DesktopLink link = new DesktopLink(state, key);
        try {
            DesktopLink.Attempt a = link.startPairing("127.0.0.1", gw.port(), "TV");
            assertNotEquals("the impostor's digits matched the PC's", gw.code, a.code);
        } catch (DesktopLink.LinkException stopped) {
            // The commitment covers the pin too, so the TV usually stops before any digits.
            assertTrue(stopped.getMessage().contains("did not match"));
        }
        assertFalse(link.paired());
    }

    @Test public void aKeyChangeInTheMiddleOfPairingStopsIt() throws Exception {
        DesktopLink link = new DesktopLink(state, key);
        gw.impostorFrom = 1;          // start meets the PC's key, reveal meets another
        try {
            link.startPairing("127.0.0.1", gw.port(), "TV");
            fail("pairing went on with two different keys");
        } catch (DesktopLink.LinkException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("key changed during pairing"));
            assertFalse(link.paired());
        }
    }

    // ── after pairing ───────────────────────────────────────────────────────

    @Test public void aSessionIsASignedChallengeAndRequestsCarryIt() throws Exception {
        DesktopLink link = paired();
        JSONObject who = link.request("GET", "/tv/v1/whoami", null);
        assertEquals("device:" + PairProtocol.deviceId(key.spki()), who.getString("principal"));
        assertEquals(1, gw.sessionsOpened.get());
    }

    @Test public void aRefusedSessionIsReopenedOnceNotForever() throws Exception {
        DesktopLink link = paired();
        link.request("GET", "/tv/v1/whoami", null);
        gw.refuseSessions = true;
        link.request("GET", "/tv/v1/whoami", null);
        assertEquals(2, gw.sessionsOpened.get());
    }

    @Test public void afterPairingAnotherKeyIsRefusedBeforeAnythingIsSent() throws Exception {
        DesktopLink link = paired();
        gw.certName = "impostor";
        int before = gw.paths.size();
        try {
            link.request("GET", "/tv/v1/whoami", null);
            fail("the TV talked to a computer with another key");
        } catch (DesktopLink.LinkException e) {
            assertTrue(e.getMessage().contains("not your paired"));
        }
        assertEquals("no request reached the impostor", before, gw.paths.size());
    }

    @Test public void aComputerOfferingOnlyTls12IsRefused() throws Exception {
        DesktopLink link = paired();
        gw.tls12Only = true;
        try {
            link.request("GET", "/tv/v1/whoami", null);
            fail("the TV accepted TLS 1.2");
        } catch (DesktopLink.LinkException expected) { }
    }

    @Test public void theDeviceKeyNeverLeavesTheTvOnlyItsPublicHalf() throws Exception {
        paired().request("GET", "/tv/v1/whoami", null);
        String priv = Base64.getEncoder().encodeToString(key.kp.getPrivate().getEncoded());
        for (String a : gw.auths) if (a != null) assertFalse(a.contains(priv));
        assertArrayEquals(key.spki(), gw.deviceSpki);
    }

    @Test public void forgetDropsThePinAndTheKey() throws Exception {
        DesktopLink link = paired();
        link.forget();
        assertFalse(link.paired());
        assertFalse(state.exists());
        assertTrue(key.forgotten);
        assertFalse(new DesktopLink(state, key).paired());
    }

    @Test public void aCorruptStateFileMeansNotPaired() throws Exception {
        Files.write(state.toPath(), "{\"host\":\"1.2.3.4\",\"port\":1,\"pin\":\"short\"}".getBytes(StandardCharsets.UTF_8));
        assertFalse(new DesktopLink(state, key).paired());
    }

    @Test public void onlyAUrlSafePairingIdIsUsed() {
        try { DesktopLink.urlSafe("x&evil=1"); fail(); } catch (DesktopLink.LinkException expected) { }
    }

    // ── guards on the source ────────────────────────────────────────────────

    static String mainSources() throws Exception {
        Path root = Paths.get("src/main/java");
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".java"))::iterator) {
                sb.append("\n//FILE ").append(root.relativize(p).toString().replace('\\', '/')).append('\n')
                  .append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    @Test public void onlyPinnedTrustMakesATrustDecision() throws Exception {
        String all = mainSources();
        for (String f : all.split("\n//FILE ")) {
            if (f.isEmpty()) continue;
            String name = f.substring(0, f.indexOf('\n'));
            boolean trusts = f.contains("implements X509TrustManager") || f.contains("X509ExtendedTrustManager");
            assertTrue(name + " makes its own TLS trust decision", !trusts || name.endsWith("pair/PinnedTrust.java"));
            assertFalse(name + " changes the process-wide TLS defaults",
                    f.contains("setDefaultSSLSocketFactory") || f.contains("setDefaultHostnameVerifier"));
        }
    }

    @Test public void theDesktopLinkNeverWidensTheWebFetchRules() throws Exception {
        String link = new String(Files.readAllBytes(Paths.get(
                "src/main/java/ai/futurespeak/friday/tv/pair/DesktopLink.java")), StandardCharsets.UTF_8);
        // Uses, not mentions: the class comment explains why it stays clear of both.
        assertFalse(link.contains("SafeFetch.") || link.contains("NetPolicy."));
        assertFalse(link.contains("import ai.futurespeak.friday.tv.web"));
        assertFalse(link.contains("import ai.futurespeak.friday.tv.channels"));
    }
}
