package ai.futurespeak.friday.tv.pair;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * A fake Friday desktop for the TV's tests: the TV gateway's pairing and session routes over
 * real TLS 1.3 with a real certificate (test/resources/pair), one request per connection.
 * Other routes go to {@link #extra}.
 */
public final class FakeDesktop {
    /** Answers a route the fake does not know: the whole raw HTTP response, or null for 404. */
    public interface Extra { byte[] handle(String path, String auth, JSONObject body, Socket s) throws Exception; }

    public volatile Extra extra;

    /** A software stand-in for the Keystore key: P-256, SHA256withECDSA, DER. */
    public static final class SoftKey implements DeviceKey {
        KeyPair kp;
        boolean forgotten;
        public SoftKey() throws Exception {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            kp = g.generateKeyPair();
        }
        @Override public byte[] spki() { return kp.getPublic().getEncoded(); }
        @Override public byte[] sign(byte[] msg) throws Exception {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(kp.getPrivate());
            s.update(msg);
            return s.sign();
        }
        @Override public void forget() { forgotten = true; }
    }


    final java.net.ServerSocket server;
    public volatile String certName = "desktop";
    public volatile boolean lieAboutNd, approve = true, refuse, refuseSessions, tls12Only;
    public final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    public final List<String> auths = Collections.synchronizedList(new ArrayList<>());
    /** Every request's headers, lower-cased names, in order. */
    public final List<java.util.Map<String, String>> headers = Collections.synchronizedList(new ArrayList<>());
    public final AtomicInteger sessionsOpened = new AtomicInteger();
    public byte[] deviceSpki, nd = new byte[32];
    public String commit, code, nonce, liveSession;
    final SSLContext desktop, impostor;

    public FakeDesktop() throws Exception {
        desktop = ctx("desktop");
        impostor = ctx("impostor");
        server = new java.net.ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        new SecureRandom().nextBytes(nd);
        Thread t = new Thread(this::loop);
        t.setDaemon(true);
        t.start();
    }

    SSLContext ctx(String name) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = getClass().getResourceAsStream("/pair/" + name + ".p12")) {
            ks.load(in, "testpass".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "testpass".toCharArray());
        SSLContext c = SSLContext.getInstance("TLSv1.3");
        c.init(kmf.getKeyManagers(), null, null);
        return c;
    }

    public String pinOf(String name) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = getClass().getResourceAsStream("/pair/" + name + ".p12")) {
            ks.load(in, "testpass".toCharArray());
        }
        return PairProtocol.spkiPin(ks.getCertificate(name).getPublicKey().getEncoded());
    }

    public int port() { return server.getLocalPort(); }

    /** From this many accepted connections on, present the impostor's key (-1: never).
     *  Decided by the server as each connection arrives, so a test needs no timing. */
    public volatile int impostorFrom = -1;
    private int accepted;

    void loop() {
        while (!server.isClosed()) {
            try (Socket plain = server.accept()) {
                plain.setSoTimeout(5000);                          // a stuck client never stalls the fake
                int n = accepted++;
                boolean fake = "impostor".equals(certName) || (impostorFrom >= 0 && n >= impostorFrom);
                // Wrap the accepted TCP stream with whichever certificate is current.
                javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) (fake ? impostor : desktop)
                        .getSocketFactory().createSocket(plain, "127.0.0.1", plain.getPort(), true);
                s.setUseClientMode(false);                         // the server side of TLS
                if (tls12Only) s.setEnabledProtocols(new String[]{"TLSv1.2"});
                serve(s);
            } catch (Exception ignored) { }
        }
    }

    void serve(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int c, m = 0;
        while ((c = in.read()) >= 0) { head.write(c); m = (c == '\r' || c == '\n') ? m + 1 : 0; if (m == 4) break; }
        String[] lines = head.toString("UTF-8").split("\r\n");
        String path = lines[0].split(" ")[1];
        int len = 0;
        String auth = null;
        java.util.Map<String, String> hs = new java.util.HashMap<>();
        for (String line : lines) {
            int i = line.indexOf(':');
            if (i < 0) continue;
            String k = line.substring(0, i).trim().toLowerCase(), v = line.substring(i + 1).trim();
            hs.put(k, v);
            if (k.equals("content-length")) len = Integer.parseInt(v);
            if (k.equals("authorization")) auth = v;
        }
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) { int n = in.read(b, off, len - off); if (n < 0) break; off += n; }
        JSONObject body = len > 0 ? new JSONObject(new String(b, StandardCharsets.UTF_8)) : new JSONObject();
        paths.add(path);
        headers.add(hs);
        auths.add(auth);
        int status = 200;
        JSONObject out = new JSONObject().put("ok", true);
        String pin = pinOf("desktop");
        if (path.equals("/tv/v1/pair/start")) {
            deviceSpki = Base64.getDecoder().decode(body.getString("device_spki"));
            commit = PairProtocol.commitment(pin, deviceSpki, nd);
            out.put("pairing_id", "pid_123").put("commit", commit);
        } else if (path.equals("/tv/v1/pair/reveal")) {
            byte[] nt = Base64.getDecoder().decode(body.getString("nt"));
            code = PairProtocol.sas(pin, deviceSpki, nt, nd);
            byte[] sent = nd.clone();
            if (lieAboutNd) sent[0] ^= 1;
            out.put("nd", Base64.getEncoder().encodeToString(sent));
        } else if (path.startsWith("/tv/v1/pair/status")) {
            out.put("state", refuse ? "refused" : approve ? "paired" : "awaiting_press");
        } else if (path.equals("/tv/v1/session/challenge")) {
            nonce = "ab" + Long.toHexString(System.nanoTime());
            out.put("nonce", nonce);
        } else if (path.equals("/tv/v1/session/open")) {
            PublicKey pub = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(deviceSpki));
            Signature v = Signature.getInstance("SHA256withECDSA");
            v.initVerify(pub);
            v.update(PairProtocol.sessionMessage(body.getString("nonce"), body.getString("device_id")));
            boolean good = body.getString("nonce").equals(nonce)
                    && body.getString("device_id").equals(PairProtocol.deviceId(deviceSpki))
                    && v.verify(Base64.getDecoder().decode(body.getString("sig")));
            if (good) {
                liveSession = "fdev_" + sessionsOpened.incrementAndGet();
                out.put("session", liveSession).put("ttl_s", 43200);
            } else { status = 401; out.put("ok", false); }
        } else if (path.equals("/tv/v1/whoami")) {
            if (refuseSessions || auth == null || !auth.equals("Friday-Device " + liveSession)) {
                status = 401; out.put("ok", false).put("error", "device session required");
                refuseSessions = false;
            } else out.put("principal", "device:" + PairProtocol.deviceId(deviceSpki));
        } else if (extra != null) {
            byte[] raw = extra.handle(path, auth, body, s);
            if (raw == null) { status = 404; }
            else { s.getOutputStream().write(raw); s.getOutputStream().flush(); s.close(); return; }
        } else { status = 404; }
        byte[] r = out.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + r.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(r);
        os.flush();
        s.close();
    }

    public void close() throws Exception { server.close(); }

    /** A TV that is paired with this fake and holds a live session. */
    public DesktopLink pairedLink(java.io.File state, DeviceKey key) throws Exception {
        DesktopLink link = new DesktopLink(state, key);
        DesktopLink.Attempt a = link.startPairing("127.0.0.1", port(), "TV");
        if (!"paired".equals(link.pollPairing(a))) throw new IllegalStateException("not paired");
        return link;
    }

    /** Whether this Authorization header carries the live device session. */
    public boolean sessionOk(String auth) { return auth != null && auth.equals("Friday-Device " + liveSession); }
}
