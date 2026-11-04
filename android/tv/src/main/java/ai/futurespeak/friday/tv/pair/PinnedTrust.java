package ai.futurespeak.friday.tv.pair;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The only trust decision the TV makes about Friday desktop: the certificate's public key.
 *
 * Pinned (after pairing): a certificate whose SPKI SHA-256 is not the pinned one is refused
 * during the handshake, before a byte of the request is sent. There is no CA and no
 * hostname: the desktop's address may change, its key may not.
 *
 * Pairing (before a pin exists): any certificate is accepted and its pin recorded, and
 * nothing is trusted because of it. The pairing protocol makes the six digits depend on the
 * recorded pin, so a substituted certificate shows up as digits that do not match.
 *
 * Only TLS 1.3 is offered.
 */
public final class PinnedTrust implements X509TrustManager {
    private final String pinned;               // hex, or null while pairing
    private volatile String seen;              // what the last handshake presented

    private PinnedTrust(String pinned) { this.pinned = pinned; }

    public static PinnedTrust pinned(String pinHex) {
        if (pinHex == null || pinHex.length() != 64) throw new IllegalArgumentException("pin must be 64 hex");
        return new PinnedTrust(pinHex.toLowerCase(java.util.Locale.ROOT));
    }

    public static PinnedTrust pairing() { return new PinnedTrust(null); }

    public boolean isPairing() { return pinned == null; }

    /** The pinned key's hex SHA-256, or null while pairing. */
    String pinnedPin() { return pinned; }

    /** The pin of the certificate the last handshake presented (pairing records this). */
    public String seenPin() { return seen; }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) throw new CertificateException("no certificate");
        String pin = PairProtocol.spkiPin(chain[0].getPublicKey().getEncoded());
        seen = pin;
        if (pinned == null) return;
        if (!MessageDigest.isEqual(PairProtocol.unhex(pinned), PairProtocol.unhex(pin))) {
            throw new CertificateException("this is not the paired Friday computer");
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("the TV is never a TLS server");
    }

    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }

    /** A socket factory that offers TLS 1.3 only and trusts nothing but this manager. */
    public SSLSocketFactory socketFactory() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{this}, null);
            return new Tls13Only(ctx.getSocketFactory());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static final class Tls13Only extends SSLSocketFactory {
        private final SSLSocketFactory d;
        Tls13Only(SSLSocketFactory d) { this.d = d; }
        private Socket only13(Socket s) {
            if (s instanceof SSLSocket) ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.3"});
            return s;
        }
        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String h, int p, boolean a) throws IOException { return only13(d.createSocket(s, h, p, a)); }
        @Override public Socket createSocket(String h, int p) throws IOException { return only13(d.createSocket(h, p)); }
        @Override public Socket createSocket(String h, int p, InetAddress la, int lp) throws IOException { return only13(d.createSocket(h, p, la, lp)); }
        @Override public Socket createSocket(InetAddress h, int p) throws IOException { return only13(d.createSocket(h, p)); }
        @Override public Socket createSocket(InetAddress a, int p, InetAddress la, int lp) throws IOException { return only13(d.createSocket(a, p, la, lp)); }
    }
}
