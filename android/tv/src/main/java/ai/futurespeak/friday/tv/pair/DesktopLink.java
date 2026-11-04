package ai.futurespeak.friday.tv.pair;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

/**
 * The TV's line to Friday desktop on the home network.
 *
 * - Finding it: a UDP broadcast; the answer is only a hint (address, port, pin).
 * - Pairing: {@link #startPairing} runs commit/reveal over TLS and returns the six digits
 *   to show; {@link #pollPairing} waits for the owner's press on the PC and then pins the
 *   desktop's key. Every connection of one pairing must present the same key, or it stops.
 * - After pairing: every connection is TLS 1.3 to the pinned key only ({@link PinnedTrust});
 *   the address may change (DHCP), the key may not. A session is opened by signing a
 *   one-time challenge with the TV's {@link DeviceKey}; the session token lives in memory.
 *
 * It never goes through SafeFetch or NetPolicy: those stay strict about home-network
 * addresses, and this class trusts one key rather than any address.
 *
 * State on disk (app-private, excluded from backup like the rest of files/): the desktop's
 * address, port and pin. No secret is stored; the private key is in the Keystore.
 */
public final class DesktopLink {
    public static final int DISCOVERY_PORT = 47811;
    static final byte[] PROBE = "FRIDAY-TV-DISCOVER v1".getBytes(StandardCharsets.US_ASCII);
    static final int CONNECT_MS = 1500, READ_MS = 15000;

    public static final class Hint {
        public final String host; public final int port; public final String pin;
        Hint(String host, int port, String pin) { this.host = host; this.port = port; this.pin = pin; }
    }

    /** One pairing in progress: what the TV shows, and where it is waiting. */
    public static final class Attempt {
        public final String host; public final int port; public final String pairingId;
        public final String code; final String pin;
        Attempt(String host, int port, String pairingId, String code, String pin) {
            this.host = host; this.port = port; this.pairingId = pairingId; this.code = code; this.pin = pin;
        }
    }

    public static class LinkException extends Exception {
        public LinkException(String m) { super(m); }
        public LinkException(String m, Throwable c) { super(m, c); }
    }

    private final File stateFile;
    private final DeviceKey key;
    private final SecureRandom rng = new SecureRandom();

    private volatile String host, pin;
    private volatile int port;
    private volatile String session;
    private volatile long sessionUntil;

    public DesktopLink(File stateFile, DeviceKey key) {
        this.stateFile = stateFile;
        this.key = key;
        load();
    }

    public boolean paired() { return pin != null; }
    public String pin() { return pin; }
    public String host() { return host; }
    public int port() { return port; }

    public String statusLine() {
        return paired() ? "Paired with your Friday computer" : "Not paired with a Friday computer";
    }

    // ── finding the desktop ────────────────────────────────────────────────

    /** Broadcast a probe and collect answers for up to waitMs. */
    public static List<Hint> discover(InetAddress broadcast, int discoveryPort, int waitMs) {
        List<Hint> out = new ArrayList<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(Math.max(100, waitMs));
            s.send(new DatagramPacket(PROBE, PROBE.length, broadcast, discoveryPort));
            long until = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < until) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException e) { break; }
                try {
                    JSONObject j = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                    if (!"agent-friday-tv-gateway".equals(j.optString("service"))) continue;
                    out.add(new Hint(p.getAddress().getHostAddress(), j.getInt("port"), j.optString("spki_sha256")));
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** After pairing: find the pinned desktop again if its address moved. Only a hint naming
     *  our pin is followed, and the TLS pin check still decides. */
    public boolean rediscover(InetAddress broadcast) {
        if (!paired()) return false;
        for (Hint h : discover(broadcast, DISCOVERY_PORT, 1500)) {
            if (pin.equalsIgnoreCase(h.pin)) {
                host = h.host; port = h.port; save();
                return true;
            }
        }
        return false;
    }

    // ── pairing ─────────────────────────────────────────────────────────────

    public Attempt startPairing(String atHost, int atPort, String name) throws LinkException {
        try {
            PinnedTrust trust = PinnedTrust.pairing();
            byte[] spki = key.spki();
            JSONObject st = call(trust, null, atHost, atPort, "POST", "/tv/v1/pair/start",
                    new JSONObject().put("device_spki", b64(spki)).put("name", name));
            String pinSeen = trust.seenPin();
            String pid = st.getString("pairing_id"), commit = st.getString("commit");
            byte[] nt = new byte[32];
            rng.nextBytes(nt);
            JSONObject rv = call(trust, null, atHost, atPort, "POST", "/tv/v1/pair/reveal",
                    new JSONObject().put("pairing_id", pid).put("nt", b64(nt)));
            if (pinSeen == null || !pinSeen.equals(trust.seenPin())) {
                throw new LinkException("the computer's key changed during pairing; stopped");
            }
            byte[] nd = Base64.getDecoder().decode(rv.getString("nd"));
            if (!PairProtocol.commitmentHolds(pinSeen, spki, nd, commit)) {
                throw new LinkException("the computer's answer did not match what it promised; stopped");
            }
            return new Attempt(atHost, atPort, pid, PairProtocol.sas(pinSeen, spki, nt, nd), pinSeen);
        } catch (org.json.JSONException e) {
            throw new LinkException("your Friday computer gave an answer the TV doesn't understand", e);
        }
    }

    /** "awaiting_press", "paired", "refused", "expired" or "unknown". On "paired" the
     *  desktop's key is pinned and saved. */
    public String pollPairing(Attempt a) throws LinkException {
        PinnedTrust trust = PinnedTrust.pinned(a.pin);       // the same key as the pairing, or nothing
        JSONObject st = call(trust, null, a.host, a.port, "GET",
                "/tv/v1/pair/status?pairing_id=" + urlSafe(a.pairingId), null);
        String state = st.optString("state", "unknown");
        if ("paired".equals(state)) {
            host = a.host; port = a.port; pin = a.pin; session = null;
            save();
        }
        return state;
    }

    /** Forget the desktop: the pin, the address and the device key. */
    public void forget() {
        host = null; pin = null; port = 0; session = null;
        //noinspection ResultOfMethodCallIgnored
        stateFile.delete();
        key.forget();
    }

    // ── reachability ─────────────────────────────────────────────────────

    static final long REACH_CACHE_MS = 10_000;
    private volatile long reachCheckedAt;
    private volatile boolean reachOk;

    /** Whether the paired PC answers on its pinned key right now (cached for ten seconds,
     *  so a burst of turns costs one check). A wrong key counts as unreachable. */
    public boolean reachable() {
        if (!paired()) return false;
        long now = System.currentTimeMillis();
        if (now - reachCheckedAt < REACH_CACHE_MS) return reachOk;
        boolean ok;
        try {
            JSONObject h = call(PinnedTrust.pinned(pin), null, host, port, "GET", "/tv/v1/hello", null);
            ok = pin.equalsIgnoreCase(h.optString("spki_sha256"));
        } catch (LinkException e) {
            ok = false;
        }
        reachOk = ok;
        reachCheckedAt = now;
        return ok;
    }

    /** Forget the cached answer (after a failed turn, so the next turn checks again). */
    public void recheck() { reachCheckedAt = 0; }

    // ── sessions and requests ─────────────────────────────────────────────

    public String deviceId() { return PairProtocol.deviceId(key.spki()); }

    synchronized String session() throws LinkException {
        try {
            if (session != null && System.currentTimeMillis() < sessionUntil) return session;
            if (!paired()) throw new LinkException("not paired");
            PinnedTrust trust = PinnedTrust.pinned(pin);
            String nonce = call(trust, null, host, port, "POST", "/tv/v1/session/challenge", new JSONObject())
                    .getString("nonce");
            String id = deviceId();
            byte[] sig;
            try { sig = key.sign(PairProtocol.sessionMessage(nonce, id)); }
            catch (Exception e) { throw new LinkException("the TV's key could not sign", e); }
            JSONObject r = call(trust, null, host, port, "POST", "/tv/v1/session/open",
                    new JSONObject().put("device_id", id).put("nonce", nonce).put("sig", b64(sig)));
            session = r.getString("session");
            sessionUntil = System.currentTimeMillis() + Math.max(60, r.optLong("ttl_s", 3600) - 60) * 1000L;
            return session;
        } catch (org.json.JSONException e) {
            throw new LinkException("your Friday computer gave an answer the TV doesn't understand", e);
        }
    }

    /** A JSON request to the paired desktop under a device session; one retry on a dead session. */
    public JSONObject request(String method, String path, JSONObject body) throws LinkException {
        PinnedTrust trust = PinnedTrust.pinned(requirePin());
        try {
            return call(trust, session(), host, port, method, path, body);
        } catch (Unauthorized e) {
            synchronized (this) { session = null; }
            return call(trust, session(), host, port, method, path, body);
        }
    }

    /** A connection to the paired PC under a device session, not yet sent: the caller writes
     *  the body, reads the (streamed) reply and disconnects. */
    public HttpsURLConnection connection(String method, String path) throws LinkException {
        PinnedTrust trust = PinnedTrust.pinned(requirePin());
        String sess = session();
        try {
            HttpsURLConnection c = connect(trust, sess, host, port, method, path);
            c.setReadTimeout(90_000);                  // a streamed turn may think for a while
            return c;
        } catch (Exception e) {
            throw new LinkException("could not reach your Friday computer", e);
        }
    }

    /** Drop the session so the next request signs a fresh challenge (after a 401). */
    public synchronized void dropSession() { session = null; }

    private String requirePin() throws LinkException {
        if (pin == null) throw new LinkException("not paired");
        return pin;
    }

    static final class Unauthorized extends LinkException { Unauthorized() { super("refused"); } }

    static HttpsURLConnection connect(PinnedTrust trust, String sess, String h, int p, String method, String path)
            throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL("https", h, p, path).openConnection();
        c.setSSLSocketFactory(trust.socketFactory());
        // No CA and no hostname: the key is the identity. The verifier re-checks the pin on
        // the session the handshake produced, so it can only say yes to the pinned key.
        c.setHostnameVerifier((hostname, s) -> {
            try {
                Certificate[] chain = s.getPeerCertificates();
                String got = PairProtocol.spkiPin(chain[0].getPublicKey().getEncoded());
                return trust.isPairing() ? got.equals(trust.seenPin()) : got.equals(trust.pinnedPin());
            } catch (Exception e) { return false; }
        });
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        if (sess != null) c.setRequestProperty("Authorization", "Friday-Device " + sess);
        return c;
    }

    static void write(HttpsURLConnection c, JSONObject body) throws Exception {
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setFixedLengthStreamingMode(b.length);
        try (OutputStream os = c.getOutputStream()) { os.write(b); }
    }

    static JSONObject call(PinnedTrust trust, String sess, String h, int p, String method, String path,
                           JSONObject body) throws LinkException {
        HttpsURLConnection c = null;
        try {
            c = connect(trust, sess, h, p, method, path);
            if (body != null) write(c, body);
            int code = c.getResponseCode();
            if (code == 401) throw new Unauthorized();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            JSONObject j = new JSONObject(in == null ? "{}" : readAll(in));
            if (code >= 400) throw new LinkException(j.optString("error", "refused (" + code + ")"));
            return j;
        } catch (LinkException e) {
            throw e;
        } catch (javax.net.ssl.SSLException e) {
            throw new LinkException("that is not your paired Friday computer", e);
        } catch (Exception e) {
            throw new LinkException("could not reach your Friday computer", e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    // ── state ───────────────────────────────────────────────────────────────

    private void load() {
        try {
            JSONObject j = new JSONObject(new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8));
            String p = j.getString("pin");
            if (p.length() != 64) return;
            host = j.getString("host"); port = j.getInt("port"); pin = p;
        } catch (Exception ignored) { }
    }

    private void save() {
        try {
            File tmp = new File(stateFile.getPath() + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(new JSONObject().put("host", host).put("port", port).put("pin", pin)
                        .toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(stateFile)) {
                Files.move(tmp.toPath(), stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) { }
    }

    /** Pairing ids are URL-safe base64 from the desktop; anything else is refused. */
    static String urlSafe(String s) throws LinkException {
        if (!s.matches("[A-Za-z0-9_-]{1,64}")) throw new LinkException("bad pairing id");
        return s;
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
}
