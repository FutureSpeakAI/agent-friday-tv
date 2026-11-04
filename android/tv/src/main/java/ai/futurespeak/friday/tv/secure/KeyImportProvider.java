package ai.futurespeak.friday.tv.secure;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import ai.futurespeak.friday.tv.Prefs;

/**
 * How keys get onto the TV without touching its storage in the clear, the APK, or a
 * command line:
 *
 *   (PC) Friday's key store --pipe--> adb exec-in content write --uri content://ai.futurespeak.friday.tv.keys/import
 *
 * The bytes arrive on a pipe into this process and are sealed by {@link SecureStore}
 * straight away. Only the adb shell user (uid 2000) or root may call this provider.
 * {@code content query --uri .../status} answers which keys are present, never a key.
 */
public final class KeyImportProvider extends ContentProvider {
    private static final String TAG = "FridayTV";
    private static final int SHELL_UID = 2000, ROOT_UID = 0;
    private static final int MAX = 64 * 1024;

    @Override public boolean onCreate() { return true; }

    private static void requireShell() {
        int uid = Binder.getCallingUid();
        if (uid != SHELL_UID && uid != ROOT_UID) throw new SecurityException("not allowed");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        requireShell();
        if (!mode.startsWith("w")) throw new java.io.FileNotFoundException("no");
        if ("/reply".equals(uri.getPath())) return replyPipe();
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.equals("/audio") || path.equals("/audio/ontv") || path.equals("/audio/google")) {   // test hook: raw 16 kHz mono PCM into a recognizer
            String engine = path.equals("/audio") ? null : path.substring(7);
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                if (!ai.futurespeak.friday.tv.MainActivity.audioFromShell(pipe[0], engine)) throw new java.io.FileNotFoundException("friday not listening-capable");
                return pipe[1];
            } catch (IOException e) { throw new java.io.FileNotFoundException("pipe failed"); }
        }
        if (path.startsWith("/stt/")) {   // the PC pushes Friday's own speech model (the setup wizard's speech step); fixed names only
            String name = path.substring(5);
            if (!ai.futurespeak.friday.tv.audio.OnTvModel.allowedName(name)) throw new java.io.FileNotFoundException("not a model file");
            java.io.File dir = ai.futurespeak.friday.tv.audio.OnTvModel.dir(getContext().getFilesDir());
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.FileNotFoundException("no dir");
            java.io.File part = new java.io.File(dir, name + ".part");
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                ParcelFileDescriptor readEnd = pipe[0];
                Thread t = new Thread(() -> {
                    try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readEnd);
                         java.io.OutputStream out = new java.io.FileOutputStream(part)) {
                        byte[] b = new byte[1 << 16];
                        int n;
                        while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    } catch (IOException e) { android.util.Log.w("FridayTV", "stt model copy failed: " + name); return; }
                    if (!part.renameTo(new java.io.File(dir, name))) android.util.Log.w("FridayTV", "stt model rename failed: " + name);
                    else android.util.Log.i("FridayTV", "stt model file in: " + name + " " + new java.io.File(dir, name).length() + " bytes");
                }, "stt-model-in");
                t.start();
                return pipe[1];
            } catch (IOException e) { throw new java.io.FileNotFoundException("pipe failed"); }
        }
        if (!"/import".equals(uri.getPath())) throw new java.io.FileNotFoundException("no");
        try {
            // A plain pipe: "content write" closes its end with a raw close(), which a reliable
            // pipe would report as an error.
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor readEnd = pipe[0];
            Thread t = new Thread(() -> importFrom(readEnd), "key-import");
            t.start();
            return pipe[1];
        } catch (IOException e) {
            throw new java.io.FileNotFoundException("pipe failed");
        }
    }

    /**
     * Test hook, shell only: {@code adb exec-in content write --uri .../reply < reply.txt} plays a
     * whole saved Claude reply (text, cards, a channel) into the open app as if it had just
     * arrived, so channel approval and checking can be tested without calling the API.
     */
    private ParcelFileDescriptor replyPipe() throws java.io.FileNotFoundException {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor readEnd = pipe[0];
            new Thread(() -> {
                try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readEnd)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) { if (bos.size() + n > 600_000) throw new IOException("too large"); bos.write(buf, 0, n); }
                    boolean ok = ai.futurespeak.friday.tv.MainActivity.replyFromShell(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                    Log.i(TAG, "shell reply delivered=" + ok);
                } catch (Exception e) { Log.w(TAG, "shell reply failed (" + e.getClass().getSimpleName() + ")"); }
            }, "shell-reply").start();
            return pipe[1];
        } catch (IOException e) {
            throw new java.io.FileNotFoundException("pipe failed");
        }
    }

    private void importFrom(ParcelFileDescriptor readEnd) {
        byte[] raw = null;
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readEnd)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > MAX) throw new IOException("too large");
                bos.write(buf, 0, n);
            }
            Arrays.fill(buf, (byte) 0);
            raw = bos.toByteArray();
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            SecureStore store = new SecureStore(getContext());
            Prefs prefs = new Prefs(getContext());
            int count = 0;
            for (String name : SecureStore.ALL) {
                if (o.has(name)) { store.put(name, o.optString(name, "").trim()); count++; }
            }
            if (o.has("eleven_voice")) prefs.setElevenVoice(o.optString("eleven_voice", ""));
            if (o.has("eleven_model")) prefs.setElevenModel(o.optString("eleven_model", ""));
            if (o.has("model") && !o.optString("model", "").isEmpty()) prefs.setModelIfUnset(o.optString("model"));
            JSONObject prov = o.optJSONObject("providers");   // not secret: which service and model does each job
            if (prov != null) {
                ai.futurespeak.friday.tv.addon.Providers pv = ai.futurespeak.friday.tv.addon.Providers.parse(prov.toString());
                prefs.setProviders(pv);
                if (!pv.voiceId.isEmpty()) prefs.setElevenVoice(pv.voiceId);
            }
            JSONObject place = o.optJSONObject("place");   // not secret: where the TV is, for the weather
            if (place != null && place.has("lat") && place.has("lon"))
                prefs.setPlace(place.optString("name", ""), place.optDouble("lat"), place.optDouble("lon"), !"C".equalsIgnoreCase(place.optString("units", "F")));
            else if (o.has("place") && o.isNull("place")) prefs.clearPlace();
            Log.i(TAG, "keys imported: " + count + " value(s)");   // never the values
        } catch (Exception e) {
            Log.w(TAG, "key import failed (" + e.getClass().getSimpleName() + (e instanceof IOException ? ": " + e.getMessage() : "") + ")");
        } finally {
            if (raw != null) Arrays.fill(raw, (byte) 0);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        requireShell();
        if (!"/status".equals(uri.getPath())) return null;
        SecureStore s = new SecureStore(getContext());
        MatrixCursor c = new MatrixCursor(new String[]{"name", "present"});
        for (String name : SecureStore.ALL) c.addRow(new Object[]{name, s.has(name) ? "yes" : "no"});
        c.addRow(new Object[]{"model", new Prefs(getContext()).model()});
        return c;
    }

    /**
     * Test hook for the PC, shell only: {@code adb shell content call --uri content://ai.futurespeak.friday.tv.keys
     * --method ask --arg "what is a comet?"} asks the open app a question as if it had been spoken.
     */
    @Override
    public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        requireShell();
        android.os.Bundle out = new android.os.Bundle();
        if ("providers".equals(method)) {   // shell only: what each job will use with the keys present (names, never keys)
            SecureStore st = new SecureStore(getContext());
            ai.futurespeak.friday.tv.addon.Providers.Plan plan = ai.futurespeak.friday.tv.Prefs.providersNow().plan(n -> st.has(n));
            out.putString("plan", "text=" + plan.text + " voice=" + plan.voice + " sound=" + plan.sound + " music=" + plan.music
                    + " images=" + plan.images + " video=" + plan.video);
        }
        if ("addon".equals(method) && arg != null) {   // the PC setup wizard: "enable:<package>", "disable:<package>", "list"
            ai.futurespeak.friday.tv.actions.TvActions ac = ((ai.futurespeak.friday.tv.FridayApp) getContext().getApplicationContext()).actions();
            ai.futurespeak.friday.tv.actions.Receipts r = ac == null ? null : ac.receipts();
            String a = arg.trim();
            if (a.startsWith("enable:")) out.putBoolean("enabled", ai.futurespeak.friday.tv.addon.AddonRegistry.enable(getContext(), a.substring(7).trim(), r));
            else if (a.startsWith("disable:")) { ai.futurespeak.friday.tv.addon.AddonRegistry.disable(getContext(), a.substring(8).trim(), r); out.putBoolean("enabled", false); }
            else {
                StringBuilder b = new StringBuilder();
                for (ai.futurespeak.friday.tv.addon.AddonRegistry.Addon x : ai.futurespeak.friday.tv.addon.AddonRegistry.installed(getContext()))
                    b.append(x.spec.packageName).append(' ').append(x.spec.id).append(' ').append(x.enabled ? "enabled" : "installed").append('\n');
                out.putString("addons", b.toString());
            }
        }
        if ("grownup".equals(method)) {   // yes/no facts only: never a PIN or a hash
            ai.futurespeak.friday.tv.FridayApp app = (ai.futurespeak.friday.tv.FridayApp) getContext().getApplicationContext();
            ai.futurespeak.friday.tv.safety.GrownUp.Status s = app.grownUp().status();
            out.putBoolean("shared", s.shared);
            out.putBoolean("unavailable", s.unavailable);
            out.putBoolean("hasPin", s.hasPin);
            out.putBoolean("unlocked", s.unlocked);
            out.putBoolean("fridayKeepsOwnHash", app.prefs().pinHash() != null);
        }
        if ("openrouter".equals(method)) {   // shell only (the PC is the grown-up here): "models:<json>", "select:<id>" or "select:" for Claude
            android.content.SharedPreferences bp = getContext().getSharedPreferences("brains", android.content.Context.MODE_PRIVATE);
            if (arg != null && arg.startsWith("models:")) bp.edit().putString("models", arg.substring(7)).apply();
            if (arg != null && arg.startsWith("select:")) bp.edit().putString("selected", arg.substring(7).trim()).apply();
            out.putString("selected", bp.getString("selected", ""));
            out.putInt("models", ai.futurespeak.friday.tv.brain.ModelChoice.parse(bp.getString("models", null)).size());
        }
        if ("sttmodel".equals(method)) {   // shell only: is Friday's own speech model in place and intact (sizes and SHA-256)?
            ai.futurespeak.friday.tv.audio.OnTvModel.Status s = ai.futurespeak.friday.tv.audio.OnTvModel.check(getContext().getFilesDir(), true);
            out.putString("model", s.line());
            out.putBoolean("ready", s.ready());
            if (s.ready()) ai.futurespeak.friday.tv.MainActivity.sttModelArrived();
        }
        if ("liveprobe".equals(method)) {   // shell only: T5 key/model/protocol check by text. No mic, no speaker, nothing stored.
            String k = new SecureStore(getContext()).get(SecureStore.GEMINI);
            out.putString("live", k == null ? "no key" : ai.futurespeak.friday.tv.live.LiveSession.probe(k));
        }
        if ("stt".equals(method)) {
            out.putString("stt", ai.futurespeak.friday.tv.MainActivity.sttForShell());
            out.putString("service", ai.futurespeak.friday.tv.audio.RecognizerSpeechIn.serviceVersion(getContext()));
            out.putString("measuredFor", new Prefs(getContext()).sttOfflineVerifiedFor());
        }
        if ("sttmeasured".equals(method) && arg != null) {
            // Recorded by the PC after it measured this service recognizing with zero network bytes.
            // It must name the service version installed right now, or nothing is recorded.
            String now = ai.futurespeak.friday.tv.audio.RecognizerSpeechIn.serviceVersion(getContext());
            boolean match = now != null && now.equals(arg.trim());
            if (match) new Prefs(getContext()).setSttOfflineVerifiedFor(now);
            out.putBoolean("recorded", match);
            out.putString("service", now);
        }
        if ("talk".equals(method)) out.putBoolean("delivered", ai.futurespeak.friday.tv.MainActivity.talkFromShell());
        if ("familytest".equals(method)) {   // shell only: Home's family layout on an emulator with no helper (memory only; the gate still asks the helper)
            ai.futurespeak.friday.tv.actions.TvActions a = ai.futurespeak.friday.tv.MainActivity.actionsForShell(getContext());
            if (a != null) a.familyForTest(!"off".equals(arg));
            out.putBoolean("ok", a != null);
        }
        if ("receipts".equals(method)) {   // read-only: does the chain verify, and the last few actions' states
            ai.futurespeak.friday.tv.actions.TvActions a = ai.futurespeak.friday.tv.MainActivity.actionsForShell(getContext());
            if (a == null) { out.putString("result", "no_receipts"); return out; }
            ai.futurespeak.friday.tv.actions.Receipts r = a.receipts();
            ai.futurespeak.friday.tv.actions.Receipts.Check c = r.verify();
            out.putBoolean("ok", c.ok);
            out.putInt("rows", c.rows);
            out.putLong("brokenAtSeq", c.brokenAtSeq);
            out.putString("reason", c.reason);
            java.util.ArrayList<String> last = new java.util.ArrayList<>();
            for (ai.futurespeak.friday.tv.actions.Receipts.Entry e : r.recent(5))
                last.add(e.decision.optString("action") + " " + e.decision.optJSONObject("args") + " " + e.decision.optString("origin") + " -> " + e.state);
            out.putStringArrayList("recent", last);
        }
        if ("act".equals(method) && arg != null) {   // "app.open:Plex" or "app.back": through the gate, receipted, origin desktop
            ai.futurespeak.friday.tv.actions.TvActions a = ai.futurespeak.friday.tv.MainActivity.actionsForShell(getContext());
            if (a == null) { out.putString("result", "no_receipts"); return out; }
            String name = arg.contains(":") ? arg.substring(0, arg.indexOf(':')) : arg;
            org.json.JSONObject in = new org.json.JSONObject();
            boolean approve = false;   // a picture needs the explicit "|approve" from the PC (it stands in for the on-screen yes)
            try {
                if (arg.contains(":")) {
                    String v = arg.substring(arg.indexOf(':') + 1);
                    String n = name.trim();
                    boolean pic = n.startsWith("image.create") || n.startsWith("image_create");
                    if (pic && v.endsWith("|approve")) { approve = true; v = v.substring(0, v.length() - "|approve".length()); }
                    in.put(pic ? "prompt" : n.startsWith("web.search") || n.startsWith("web_search") ? "query" : n.startsWith("web.read") || n.startsWith("web_read") ? "url" : "app", v);
                }
            } catch (org.json.JSONException ignored) { }
            ai.futurespeak.friday.tv.actions.ActionGate.Verdict v = a.fromShell(name.trim(), in, approve);
            if (v.card != null) out.putString("card", v.card.toString());
            out.putString("result", v.result);
            out.putString("say", v.say);
            // What the model would have been given, shortened (for checking the envelope; no keys in it).
            out.putString("toModel", v.toModel == null ? "" : v.toModel.substring(0, Math.min(400, v.toModel.length())));
            out.putString("decision", v.decisionId);
        }
        if ("studio".equals(method) && arg != null) {   // Studio: "card" | "steer" | "piece"; no network, no spend
            out.putString("result", ai.futurespeak.friday.tv.studio.StudioDemo.run(getContext(), arg.trim()));
        }
        if ("open".equals(method) && arg != null) {   // e.g. "settings:voice", "screensaver"
            out.putBoolean("delivered", ai.futurespeak.friday.tv.MainActivity.openFromShell(arg.trim()));
        }
        if ("ask".equals(method) && arg != null && !arg.trim().isEmpty()) {
            out.putBoolean("delivered", ai.futurespeak.friday.tv.MainActivity.askFromShell(arg.trim()));
        }
        return out;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { requireShell(); return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        requireShell();
        if (!"/keys".equals(uri.getPath())) return 0;
        SecureStore s = new SecureStore(getContext());
        for (String name : SecureStore.ALL) s.remove(name);
        return SecureStore.ALL.length;
    }

    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { requireShell(); return 0; }
}
