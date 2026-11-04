package ai.futurespeak.friday.tv.live;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * One Gemini Live conversation. The only place in the app that records audio: the
 * mic opens only after the "Google is listening" banner is up ({@link LiveRules#mayOpenMic}),
 * and closes at once on any ending. Audio and transcripts are never stored; the receipts (kept
 * by the caller) say who started it, for how long, how it ended and the estimated cost.
 *
 * Tool calls go back through the caller's {@link Listener#onAct}, which is the one ActionGate.
 */
public final class LiveSession {
    private static final String TAG = "FridayLive";

    public interface Listener {
        /** Connected and set up; the mic is about to open. */
        void onReady();
        /** What Google heard (shown as a caption, never stored); also checked for "stop". */
        void onHeard(String text);
        /** Gemini is speaking (true) or done (false). */
        void onSpeaking(boolean speaking);
        /** A catalogue action proposed by Gemini: runs through the gate; returns what Gemini is told. */
        String onAct(String name, JSONObject args);
        /** Ended (called once, on a background thread). */
        void onEnded(LiveRules.End how, String detail);
    }

    /** The banner the user sees; the mic never opens while it isn't showing. */
    public interface Banner { boolean showing(); }

    private final String key;
    private final String instruction;
    private final JSONArray tools;
    private final Listener listener;
    private final Banner banner;
    private final String host;
    private final int port;

    private volatile boolean ended;
    private volatile SSLSocket socket;
    private volatile AudioRecord rec;
    private volatile AudioTrack track;
    private final Object sendLock = new Object();
    private final LinkedBlockingQueue<byte[]> playQ = new LinkedBlockingQueue<>();
    private volatile long lastVoiceAt;
    private volatile long tokens = -1;

    public LiveSession(String key, String instruction, JSONArray tools, Listener listener, Banner banner) {
        this(key, instruction, tools, listener, banner, LiveProtocol.HOST, 443);
    }

    LiveSession(String key, String instruction, JSONArray tools, Listener listener, Banner banner, String host, int port) {
        this.key = key; this.instruction = instruction; this.tools = tools;
        this.listener = listener; this.banner = banner; this.host = host; this.port = port;
    }

    public long lastVoiceAt() { return lastVoiceAt; }
    public long tokens() { return tokens; }
    public boolean ended() { return ended; }

    /** Connects, sets up and opens the mic, all off the main thread. */
    public void start() {
        lastVoiceAt = System.currentTimeMillis();
        Thread t = new Thread(this::run, "live-socket");
        t.setDaemon(true);
        t.start();
    }

    private void run() {
        try {
            InputStream in = connect();
            send(LiveProtocol.setup(instruction, tools).toString());
            // Wait for setupComplete before any audio leaves the TV.
            while (!ended) {
                LiveProtocol.Event e = next(in);
                if (e == null) continue;
                if (e.setupComplete) break;
            }
            if (ended) return;
            listener.onReady();
            startSpeaker();
            startMic();
            while (!ended) {
                LiveProtocol.Event e = next(in);
                if (e != null) handle(e);
            }
        } catch (IOException e) {
            if (!ended) end(LiveRules.End.ERROR, plain(e));
        } catch (Exception e) {
            if (!ended) end(LiveRules.End.ERROR, e.getClass().getSimpleName());
        }
    }

    private static String plain(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains(" 401") || m.contains(" 403")) return "Google refused the key";
        if (m.contains(" 429")) return "Google says the key is over its limit";
        if (e instanceof java.net.UnknownHostException) return "no internet";
        if (e instanceof java.net.SocketTimeoutException) return "Google didn't answer";
        return "the connection dropped";
    }

    // ------------------------------------------------------------------ socket -----
    private InputStream connect() throws IOException {
        SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        s.connect(new InetSocketAddress(host, port), 10_000);
        SSLParameters p = s.getSSLParameters();
        p.setServerNames(java.util.Collections.singletonList(new SNIHostName(host)));
        p.setEndpointIdentificationAlgorithm("HTTPS");   // hostname verified against the certificate
        s.setSSLParameters(p);
        s.startHandshake();
        s.setSoTimeout(70_000);   // longer than the 60 s quiet rule; end() closing the socket unblocks a read
        socket = s;
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String wsKey = Base64.getEncoder().encodeToString(nonce);
        // The key goes in a header, never in the URL (so no log line or proxy ever sees it).
        String req = "GET " + LiveProtocol.PATH + " HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + wsKey + "\r\nSec-WebSocket-Version: 13\r\nx-goog-api-key: " + key + "\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = new BufferedInputStream(s.getInputStream());
        String head = readHead(in);
        String status = head.split("\r\n", 2)[0];
        if (!status.contains(" 101")) throw new IOException("handshake " + status.replaceAll("[^ 0-9A-Za-z/.]", ""));
        String expect = WsFrames.accept(wsKey);
        boolean ok = false;
        for (String line : head.split("\r\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase("sec-websocket-accept") && line.substring(i + 1).trim().equals(expect)) ok = true;
        }
        if (!ok) throw new IOException("handshake not a websocket");
        return in;
    }

    static String readHead(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c, m = 0;
        while ((c = in.read()) >= 0) {
            b.append((char) c);
            m = (c == '\r' || c == '\n') ? m + 1 : 0;
            if (m == 4) return b.toString();
            if (b.length() > 16384) throw new IOException("handshake too long");
        }
        throw new IOException("handshake cut off");
    }

    private void send(String json) throws IOException {
        sendFrame(WsFrames.TEXT, json.getBytes(StandardCharsets.UTF_8));
    }

    private void sendFrame(int op, byte[] data) throws IOException {
        SSLSocket s = socket;
        if (s == null) throw new IOException("not connected");
        byte[] f = WsFrames.encode(op, data);
        synchronized (sendLock) { s.getOutputStream().write(f); s.getOutputStream().flush(); }
    }

    /** The next server message as an event; null for control frames handled here. */
    private LiveProtocol.Event next(InputStream in) throws IOException {
        WsFrames.Message m;
        m = WsFrames.read(in);
        if (m.opcode == WsFrames.PING) { sendFrame(WsFrames.PONG, m.data); return null; }
        if (m.opcode == WsFrames.PONG) return null;
        if (m.opcode == WsFrames.CLOSE) {
            String why = m.data.length > 2 ? new String(m.data, 2, m.data.length - 2, StandardCharsets.UTF_8) : "";
            Log.i(TAG, "google closed: code=" + (m.data.length >= 2 ? (((m.data[0] & 0xFF) << 8) | (m.data[1] & 0xFF)) : 0));
            end(LiveRules.End.GOOGLE, why.length() > 120 ? why.substring(0, 120) : why);
            return null;
        }
        // Google sends its JSON in binary frames as well as text frames.
        return LiveProtocol.parse(new String(m.data, StandardCharsets.UTF_8));
    }

    private void handle(LiveProtocol.Event e) {
        if (e.tokens >= 0) tokens = e.tokens;
        if (e.goAway) { end(LiveRules.End.GOOGLE, "Google is closing the session"); return; }
        if (!e.heard.isEmpty()) {
            lastVoiceAt = System.currentTimeMillis();
            listener.onHeard(e.heard);
            if (LiveRules.stopWord(e.heard)) { end(LiveRules.End.STOP_WORD, ""); return; }
        }
        if (e.interrupted) { playQ.clear(); AudioTrack t = track; if (t != null) { try { t.pause(); t.flush(); t.play(); } catch (Exception ignored) { } } }
        for (byte[] a : e.audio) { playQ.offer(a); lastVoiceAt = System.currentTimeMillis(); }
        if (!e.audio.isEmpty()) listener.onSpeaking(true);
        if (e.turnComplete) listener.onSpeaking(false);
        for (String[] c : e.calls) {
            String id = c[0], name = c[1];
            JSONObject args;
            try { args = new JSONObject(c[2]); } catch (Exception x) { args = new JSONObject(); }
            final JSONObject a = args;
            Thread t = new Thread(() -> {
                String toModel;
                try { toModel = listener.onAct(name, a); } catch (Exception x) { toModel = "REFUSED by the TV: it couldn't run that."; }
                if (ended) return;
                try { send(LiveProtocol.toolResponse(id, name, toModel).toString()); } catch (Exception ignored) { }
            }, "live-act");
            t.setDaemon(true);
            t.start();
        }
    }

    // -------------------------------------------------------------------- audio -----
    private void startMic() throws Exception {
        if (!LiveRules.mayOpenMic(banner.showing(), ended)) { end(LiveRules.End.ERROR, "the banner wasn't showing"); return; }
        int min = AudioRecord.getMinBufferSize(LiveProtocol.IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, LiveProtocol.IN_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 6400) * 2);
        if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); end(LiveRules.End.ERROR, "the microphone isn't available"); return; }
        rec = r;
        r.startRecording();
        Log.i(TAG, "mic open");
        Thread t = new Thread(() -> {
            byte[] buf = new byte[3200];   // 100 ms
            while (!ended) {
                int n = r.read(buf, 0, buf.length);
                if (n <= 0 || ended) break;
                if (rms(buf, n) > 900) lastVoiceAt = System.currentTimeMillis();
                try { send(LiveProtocol.audio(buf, n).toString()); } catch (Exception e) { if (!ended) end(LiveRules.End.ERROR, "the connection dropped"); break; }
            }
        }, "live-mic");
        t.setDaemon(true);
        t.start();
    }

    static double rms(byte[] pcm, int n) {
        long sum = 0; int count = 0;
        for (int i = 0; i + 1 < n; i += 2) { int s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8)); sum += (long) s * s; count++; }
        return count == 0 ? 0 : Math.sqrt((double) sum / count);
    }

    private void startSpeaker() {
        int min = AudioTrack.getMinBufferSize(LiveProtocol.OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(LiveProtocol.OUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min, 48000)).setTransferMode(AudioTrack.MODE_STREAM).build();
        track = t;
        t.play();
        Thread p = new Thread(() -> {
            while (!ended) {
                try {
                    byte[] a = playQ.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (a != null && !ended) t.write(a, 0, a.length);
                } catch (InterruptedException e) { break; } catch (Exception e) { break; }
            }
        }, "live-speaker");
        p.setDaemon(true);
        p.start();
    }

    // --------------------------------------------------------------------- end -----
    /** Ends the session: the mic closes first, at once; then the speaker and the socket. Idempotent. */
    public void end(LiveRules.End how, String detail) {
        synchronized (this) { if (ended) return; ended = true; }
        long t0 = System.nanoTime();
        AudioRecord r = rec; rec = null;
        if (r != null) { try { r.stop(); } catch (Exception ignored) { } r.release(); }
        Log.i(TAG, "mic closed in " + (System.nanoTime() - t0) / 1_000_000 + " ms (" + how + ")");
        playQ.clear();
        AudioTrack t = track; track = null;
        if (t != null) { try { t.pause(); t.flush(); t.stop(); } catch (Exception ignored) { } t.release(); }
        Thread closer = new Thread(() -> {
            SSLSocket s = socket;
            if (s != null) {
                try { sendFrame(WsFrames.CLOSE, new byte[]{0x03, (byte) 0xE8}); } catch (Exception ignored) { }
                try { s.close(); } catch (Exception ignored) { }
            }
        }, "live-close");
        closer.setDaemon(true);
        closer.start();
        listener.onEnded(how, detail == null ? "" : detail);
    }

    /**
     * Shell-only check (no mic, no speaker): connect with the key, ask by text for three words,
     * count the audio that comes back. Proves the key, the model and the protocol on the TV.
     */
    public static String probe(String key) {
        long t0 = System.currentTimeMillis();
        final StringBuilder said = new StringBuilder();
        final long[] bytes = {0};
        final String[] err = {null};
        LiveSession s = new LiveSession(key, "You are Gemini. Answer in at most five words.", null, new Listener() {
            public void onReady() { }
            public void onHeard(String text) { }
            public void onSpeaking(boolean speaking) { }
            public String onAct(String name, JSONObject args) { return "REFUSED"; }
            public void onEnded(LiveRules.End how, String detail) { }
        }, () -> false);
        try {
            InputStream in = s.connect();
            s.send(LiveProtocol.setup(s.instruction, null).toString());
            long setupMs = -1;
            while (System.currentTimeMillis() - t0 < 20_000) {
                LiveProtocol.Event e = s.next(in);
                if (e == null) continue;
                if (e.setupComplete) { setupMs = System.currentTimeMillis() - t0; break; }
            }
            if (setupMs < 0) return "no setupComplete";
            s.send(new JSONObject().put("realtime_input", new JSONObject().put("text", "Say hello to the family in three words.")).toString());
            long firstAudio = -1;
            while (System.currentTimeMillis() - t0 < 40_000) {
                LiveProtocol.Event e = s.next(in);
                if (e == null) continue;
                for (byte[] a : e.audio) { bytes[0] += a.length; if (firstAudio < 0) firstAudio = System.currentTimeMillis() - t0; }
                said.append(e.said);
                if (e.turnComplete) break;
            }
            s.ended = true;
            try { s.sendFrame(WsFrames.CLOSE, new byte[]{0x03, (byte) 0xE8}); } catch (Exception ignored) { }
            try { s.socket.close(); } catch (Exception ignored) { }
            return "ok model=" + LiveProtocol.MODEL + " setup=" + setupMs + "ms firstAudio=" + firstAudio + "ms audio="
                    + String.format(java.util.Locale.ROOT, "%.1f", bytes[0] / 48000.0) + "s said=\"" + said.toString().trim() + "\"";
        } catch (IOException e) {
            err[0] = plain(e);
        } catch (Exception e) {
            err[0] = e.getClass().getSimpleName();
        }
        try { if (s.socket != null) s.socket.close(); } catch (Exception ignored) { }
        return "failed: " + err[0];
    }
}
