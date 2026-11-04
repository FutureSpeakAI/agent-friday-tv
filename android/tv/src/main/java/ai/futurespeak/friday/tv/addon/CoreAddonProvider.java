package ai.futurespeak.friday.tv.addon;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ai.futurespeak.friday.tv.FridayApp;
import ai.futurespeak.friday.tv.MainActivity;
import ai.futurespeak.friday.tv.Place;
import ai.futurespeak.friday.tv.Prefs;
import ai.futurespeak.friday.tv.actions.Receipts;
import ai.futurespeak.friday.tv.actions.TvActions;
import ai.futurespeak.friday.tv.secure.SecureStore;
import ai.futurespeak.friday.tv.studio.StudioActivity;
import ai.futurespeak.friday.tv.studio.StudioSource;

/**
 * Friday's side of the add-on contract (docs/ADDON-CONTRACT.md), authority
 * {@code ai.futurespeak.friday.tv.addon}. Anyone may ask the contract version; everything else
 * answers only an enabled add-on whose signing certificate still matches. Keys never leave this
 * process: the voice call returns audio, not a key. Nothing here logs what an add-on sent.
 */
public final class CoreAddonProvider extends ContentProvider {
    private static final String TAG = "FridayTV";

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (Contract.M_CONTRACT.equals(method)) { out.putInt(Contract.K_VERSION, Contract.VERSION); return out; }
        AddonSpec spec = AddonRegistry.enabledSpec(getContext(), getCallingPackage());
        if (spec == null) throw new SecurityException("not an enabled Friday add-on");
        FridayApp app = (FridayApp) getContext().getApplicationContext();
        TvActions actions = app.actions();
        boolean kids = actions == null || actions.kids();   // fail closed
        switch (method == null ? "" : method) {
            case Contract.M_STATE: {
                out.putBoolean(Contract.K_KIDS, kids);
                boolean grownUp = false;
                try { grownUp = app.grownUp().status().unlocked; } catch (Throwable ignored) { }
                out.putBoolean(Contract.K_GROWN_UP, grownUp);
                Prefs.Motion m = app.prefs().motion();
                out.putString(Contract.K_MOTION, m == Prefs.Motion.ON ? "on" : m == Prefs.Motion.OFF ? "off" : "auto");
                out.putString(Contract.K_PLACE_NAME, Place.label());
                if (Place.isSet()) { out.putDouble(Contract.K_PLACE_LAT, Place.lat); out.putDouble(Contract.K_PLACE_LON, Place.lon); }
                out.putBoolean(Contract.K_PLACE_F, Place.fahrenheit);
                return out;
            }
            case Contract.M_CAPABILITIES: {
                SecureStore st = app.secure();
                Providers.Plan p = Prefs.providersNow().plan(st::has);
                Bundle plan = new Bundle();
                plan.putString("text", p.text); plan.putString("voice", p.voice); plan.putString("sound", p.sound);
                plan.putString("music", p.music); plan.putString("images", p.images); plan.putString("video", p.video);
                out.putBundle(Contract.K_PLAN, plan);
                return out;
            }
            case Contract.M_VOICE: {
                ParcelFileDescriptor pcm = voice(app, arg);
                if (pcm != null) out.putParcelable(Contract.K_PCM, pcm);
                return out;
            }
            case Contract.M_ASK: {
                if (arg == null || arg.trim().isEmpty() || arg.length() > Contract.MAX_ASK_CHARS) return out;
                String label = extras == null ? null : extras.getString(Contract.K_LABEL);
                Intent i = new Intent(getContext(), MainActivity.class).setAction(MainActivity.ACTION_ASK)
                        .putExtra(MainActivity.EXTRA_ASK, arg.trim())
                        .putExtra(MainActivity.EXTRA_ASK_LABEL, label == null ? null : label.substring(0, Math.min(label.length(), 120)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                getContext().startActivity(i);
                note(actions, spec, "ask", "asked Friday a question");
                out.putBoolean(Contract.K_OK, true);
                return out;
            }
            case Contract.M_STUDIO: {
                if (extras == null) return out;
                String title = extras.getString(Contract.K_TITLE, ""), text = extras.getString(Contract.K_TEXT, "");
                if (title.isEmpty() || title.length() > 300 || text.length() > 200_000) return out;
                List<StudioSource> related = new ArrayList<>();
                try {
                    JSONArray a = new JSONArray(extras.getString(Contract.K_RELATED, "[]"));
                    for (int k = 0; k < a.length() && k < 8; k++) {
                        JSONObject o = a.getJSONObject(k);
                        related.add(new StudioSource(spec.id, o.optString("title"), o.optString("source"), o.optString("link"), o.optString("summary")));
                    }
                } catch (Exception ignored) { }
                String ref = spec.id + "-" + System.nanoTime();
                StudioSource.hold(ref, new StudioSource(spec.id, title, extras.getString(Contract.K_SOURCE, ""), extras.getString(Contract.K_LINK, ""), text, related));
                getContext().startActivity(new Intent(getContext(), StudioActivity.class).setAction(StudioActivity.ACTION_FROM_NEWS)
                        .putExtra(StudioActivity.EXTRA_REF, ref).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                note(actions, spec, "studio", "handed a story to Studio");
                out.putBoolean(Contract.K_OK, true);
                return out;
            }
            case Contract.M_RECEIPT: {
                if (arg == null || arg.trim().isEmpty()) return out;
                out.putBoolean(Contract.K_OK, note(actions, spec, "note", arg.trim().substring(0, Math.min(arg.trim().length(), 200))));
                return out;
            }
            default:
                return out;
        }
    }

    static boolean note(TvActions actions, AddonSpec spec, String what, String detail) {
        Receipts r = actions == null ? null : actions.receipts();
        if (r == null) return false;
        try {
            String id = r.decision(spec.key(), new JSONObject().put("action", "addon." + what).put("origin", spec.key()));
            r.outcome(id, spec.key(), "executed", detail);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Friday's ElevenLabs voice for a short text, streamed as PCM; null when there's no key (the add-on uses the TV's voice). */
    private static ParcelFileDescriptor voice(FridayApp app, String text) {
        if (text == null || text.trim().isEmpty() || text.length() > Contract.MAX_VOICE_CHARS) return null;
        String key = app.secure().get(SecureStore.ELEVENLABS);
        if (key == null || key.isEmpty() || !app.prefs().voiceOn() || !Prefs.elevenVoiceOn()) return null;   // the add-on uses the TV's voice
        Prefs p = app.prefs();
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor write = pipe[1];
            new Thread(() -> {
                HttpURLConnection c = null;
                try (OutputStream sink = new ParcelFileDescriptor.AutoCloseOutputStream(write)) {
                    String url = "https://api.elevenlabs.io/v1/text-to-speech/" + URLEncoder.encode(p.elevenVoice(), "UTF-8") + "/stream?output_format=pcm_22050";
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(20000);
                    c.setDoOutput(true);
                    c.setRequestProperty("xi-api-key", key);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("Accept", "audio/pcm");
                    JSONObject req = new JSONObject().put("text", text).put("model_id", p.elevenModel());
                    if (Math.abs(p.voiceSpeed() - 1f) > 0.01f) req.put("voice_settings", new JSONObject().put("speed", p.voiceSpeed()));
                    try (OutputStream os = c.getOutputStream()) { os.write(req.toString().getBytes(StandardCharsets.UTF_8)); }
                    if (c.getResponseCode() != 200) return;   // closing the pipe empty: the add-on falls back to the TV's voice
                    try (InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[16 * 1024];
                        long total = 0;
                        for (int n; (n = in.read(buf)) > 0 && total < 12L * 1024 * 1024; total += n) sink.write(buf, 0, n);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "add-on voice failed (" + e.getClass().getSimpleName() + ")");
                } finally {
                    if (c != null) c.disconnect();
                }
            }, "addon-voice").start();
            return pipe[0];
        } catch (Exception e) {
            return null;
        }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
