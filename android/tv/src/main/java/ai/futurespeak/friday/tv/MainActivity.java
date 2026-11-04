package ai.futurespeak.friday.tv;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ai.futurespeak.friday.tv.audio.OfflineSpeech;
import ai.futurespeak.friday.tv.audio.FallbackVoice;
import ai.futurespeak.friday.tv.audio.RecognizerSpeechIn;
import ai.futurespeak.friday.tv.audio.SpeechIn;
import ai.futurespeak.friday.tv.audio.Voice;
import ai.futurespeak.friday.tv.brain.Brain;
import ai.futurespeak.friday.tv.brain.ClaudeDirect;
import ai.futurespeak.friday.tv.channels.ChannelSpec;
import ai.futurespeak.friday.tv.channels.ChannelStore;
import ai.futurespeak.friday.tv.history.Conversation;
import ai.futurespeak.friday.tv.history.HistoryStore;
import ai.futurespeak.friday.tv.prompt.SystemPrompt;
import ai.futurespeak.friday.tv.safety.GrownUp;
import ai.futurespeak.friday.tv.safety.Pin;
import ai.futurespeak.friday.tv.secure.SecureStore;
import ai.futurespeak.friday.tv.ui.ApprovalLayer;
import ai.futurespeak.friday.tv.ui.Brand;
import ai.futurespeak.friday.tv.ui.CardLayer;
import ai.futurespeak.friday.tv.ui.ChannelLayer;
import ai.futurespeak.friday.tv.ui.Layer;
import ai.futurespeak.friday.tv.ui.Motion;
import ai.futurespeak.friday.tv.ui.PinLayer;
import ai.futurespeak.friday.tv.ui.SettingsLayer;
import ai.futurespeak.friday.tv.ui.SettingsScreen;
import ai.futurespeak.friday.tv.web.AvatarView;

/**
 * The Agent Friday™ TV home: Friday's avatar, captions, the talk control, channel tiles,
 * and the layers above them. Talk: hold OK on the Talk tile (or a voice key if the remote
 * sends one to apps); a short press listens until you stop speaking; pressing again while
 * Claude is speaking interrupts.
 */
public final class MainActivity extends Activity implements Layer.Host {
    private static final String TAG = "FridayTV";
    private static final long TAP_MS = 300;
    static final String[] STRUCTURE_NAMES = {
            "Genesis Lattice", "Dyson Sphere", "Shannon Network", "Geodesic Cathedral", "Lovelace Astrolabe",
            "Von Neumann Tesseract", "Dirac Probability", "Mandelbrot Set", "Turing Möbius", "Ocean of Light",
            "Fibonacci Nerve", "Transcendence", "Giga Earth (Rez)", "Einstein-Rosen Bridge", "Hawking Radiation"};

    private final Handler main = new Handler(Looper.getMainLooper());
    FridayApp app;
    Prefs prefs;
    SecureStore secure;

    private FrameLayout root;
    AvatarView avatar;
    TextView stateChip, micPill, youText, replyLabel, replyText, notice, hint, poweredBy;
    private LinearLayout captionBox, tiles;
    private TextView talkTile, moreCue;
    /** The home screen's own header: hidden while a layer (card, channel, settings) is up, so the two never overlap. */
    private View homeTop;
    private android.widget.ScrollView replyScroll;
    private boolean userPaged = false;
    private View dockRow;
    private boolean conversation = false;
    final List<Layer> layers = new ArrayList<>();

    ClaudeDirect claude;
    private Brain brain;
    FallbackVoice voice;
    SpeechIn speech;
    Session session;
    Conversation convo;
    HistoryStore history;
    ChannelStore channels;
    ai.futurespeak.friday.tv.cards.PinnedCards pinnedCards;
    /** Slice S1: the catalogue, the helper, receipts and the gate. Proposals from the brain go only here. */
    ai.futurespeak.friday.tv.actions.TvActions actions;
    private final java.util.concurrent.ExecutorService background = java.util.concurrent.Executors.newSingleThreadExecutor();

    private boolean listening = false, holding = false;
    private long talkDownAt = 0;
    private String uiState = "idle";
    private final StringBuilder replyBuf = new StringBuilder();
    private static volatile MainActivity current;

    /** Shell-only test hook (see KeyImportProvider.call): ask a question as if it were spoken. */
    public static boolean askFromShell(String text) {
        MainActivity a = current;
        if (a == null) return false;
        a.main.post(() -> {
            for (Layer l : new ArrayList<>(a.layers)) if (!l.blocksTalk()) a.pop(l);
            if (!a.layers.isEmpty()) return;
            if (ai.futurespeak.friday.tv.addon.AddonRegistry.routeSpoken(a, text, a.actions == null || a.actions.kids())) return;   // an add-on claims it (docs/ADDON-CONTRACT.md)
            if (a.kidsPhrase(text)) return;                                            // kids slice 1
            if (ai.futurespeak.friday.tv.studio.StudioActivity.openFor(a, text, a.actions != null && a.actions.kids())) return;   // Studio
            a.showYou(text, false);
            a.session.ask(text);
        });
        return true;
    }

    /** Shell-only test hooks (KeyImportProvider "receipts" / "act"): the open app's actions, or null. */
    public static ai.futurespeak.friday.tv.actions.TvActions actionsForShell(android.content.Context c) {
        return ((FridayApp) c.getApplicationContext()).actions();
    }

    /** Shell-only test hook (KeyImportProvider "stt"): the listening route and what the TV's service reported. */
    public static String sttForShell() {
        MainActivity a = current;
        if (a == null) return "friday_not_open";
        RecognizerSpeechIn.Support s = a.sttSupport;
        return "route=" + a.sttRoute + " label=" + (a.speech == null ? "none" : a.speech.label())
                + (s == null ? "" : " installed=" + s.installed + " pending=" + s.pending + " online=" + (s.online == null ? null : s.online.size() + " langs") + (s.error == null ? "" : " error=" + s.error));
    }

    /** Shell-only test hook (KeyImportProvider "talk"): press Talk, as the remote would. */
    public static boolean talkFromShell() {
        MainActivity a = current;
        if (a == null) return false;
        a.main.post(() -> { if (!a.listening) a.talkPress(null); });
        return true;
    }

    /** Shell-only test hook (KeyImportProvider /audio): a recorded question through the same offline recognizer. */
    public static boolean audioFromShell(android.os.ParcelFileDescriptor pcm) { return audioFromShell(pcm, null); }

    /** The PC finished copying Friday's own speech model (and it checked out): choose the route again. */
    public static void sttModelArrived() {
        MainActivity a = current;
        if (a != null) a.main.post(() -> { if (!a.listening) { a.makeSpeech(); a.refreshSettings(); } });
    }

    /** @param engine "ontv" (Friday's own), "google" (the TV's recognizer), or null for whatever Talk uses now */
    public static boolean audioFromShell(android.os.ParcelFileDescriptor pcm, String engine) {
        MainActivity a = current;
        if (a == null) return false;
        a.main.post(() -> {
            for (Layer l : new ArrayList<>(a.layers)) if (!l.blocksTalk()) a.pop(l);
            boolean onTv = "ontv".equals(engine) || (engine == null && a.speech instanceof ai.futurespeak.friday.tv.audio.SherpaSpeechIn);
            if (onTv) {
                if (!(a.speech instanceof ai.futurespeak.friday.tv.audio.SherpaSpeechIn)) { a.useRoute(OfflineSpeech.Route.ON_TV, a.speechEvents); a.measuring = true; }
                a.listening = true;
                a.setUiState("listening");
                ((ai.futurespeak.friday.tv.audio.SherpaSpeechIn) a.speech).startWithAudio(pcm);
                return;
            }
            // Measuring the TV's recognizer: a one-off recognizer held to offline, same listener path.
            if (!(a.speech instanceof RecognizerSpeechIn)) { a.useRoute(OfflineSpeech.Route.SERVICE_OFFLINE, a.speechEvents); a.measuring = true; }
            a.listening = true;
            a.setUiState("listening");
            ((RecognizerSpeechIn) a.speech).startWithAudio(pcm);
        });
        return true;
    }

    /** Shell-only test hook: open "settings[:page]" or "screensaver" (the preview). */
    public static boolean openFromShell(String what) {
        MainActivity a = current;
        if (a == null) return false;
        a.main.post(() -> {
            if (a.listening && a.speech != null) { a.speech.cancel(); a.listening = false; }
            for (Layer l : new ArrayList<>(a.layers)) a.pop(l);
            if (what.startsWith("settings")) a.openSettings(what.contains(":") ? what.substring(what.indexOf(':') + 1) : null);
            else if (what.equals("screensaver")) a.previewScreensaver();
            else if (what.equals("packoffer")) a.offerPack();   // the one-time card, to check its look and Back
        });
        return true;
    }

    /** Shell-only test hook (KeyImportProvider /reply): play a saved reply's blocks into the app. */
    public static boolean replyFromShell(String full) {
        MainActivity a = current;
        if (a == null) return false;
        a.main.post(() -> {
            ai.futurespeak.friday.tv.reply.ReplyParser p = new ai.futurespeak.friday.tv.reply.ReplyParser(new ai.futurespeak.friday.tv.reply.ReplyParser.Sink() {
                @Override public void caption(String t) { }
                @Override public void sentence(String s) { }
                @Override public void block(String tag, String body) {
                    if ("card".equals(tag)) { List<JSONObject> c = ai.futurespeak.friday.tv.cards.CardSpec.parse(body); if (!c.isEmpty()) a.sessionUi.showCards(c); }
                    else if ("channel".equals(tag)) {
                        try { a.askToSaveChannel(ChannelSpec.parseProposal(body)); }
                        catch (ChannelSpec.Rejected r) { a.showNotice(r.getMessage()); }
                    }
                }
                @Override public void blockCutOff(String tag, int chars) {
                    if ("channel".equals(tag)) a.showNotice("The channel was cut off: " + Session.cutOffReason("max_tokens") + ". Nothing was saved.");
                }
            });
            p.feed(full);
            p.finish();
        });
        return true;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        app = (FridayApp) getApplication();
        prefs = app.prefs();
        secure = app.secure();
        history = new HistoryStore(getFilesDir());
        channels = new ChannelStore(getFilesDir());
        pinnedCards = new ai.futurespeak.friday.tv.cards.PinnedCards(getFilesDir());
        convo = history.load();
        ai.futurespeak.friday.tv.audio.ElevenLabsVoice.debugDir = getExternalFilesDir(null);

        claude = new ClaudeDirect(() -> secure.get(SecureStore.ANTHROPIC), prefs.model(), null, ClaudeDirect.MAX_TOKENS);
        // T4: the household's chosen brain, Claude or one OpenRouter model (kids mode: always Claude).
        // Pairing's FailoverBrain wraps this as its standalone brain once P1 lands.
        openRouter = new ai.futurespeak.friday.tv.brain.OpenRouterBrain(() -> secure.get(SecureStore.OPENROUTER), null, "", ClaudeDirect.MAX_TOKENS);
        // Pairing T2: Friday desktop when paired and reachable; otherwise the household's choice on this TV.
        pairLink = new ai.futurespeak.friday.tv.pair.DesktopLink(new java.io.File(getFilesDir(), "desktop_link.json"),
                new ai.futurespeak.friday.tv.pair.KeystoreDeviceKey());
        ai.futurespeak.friday.tv.brain.Brain standalone =
                new ai.futurespeak.friday.tv.brain.ChoiceBrain(claude, openRouter, this::servingModel, brainLog());
        brain = new ai.futurespeak.friday.tv.brain.FailoverBrain(
                new ai.futurespeak.friday.tv.brain.FridayDesktopClient(pairLink, this::pairPrincipal, ClaudeDirect.MAX_TOKENS),
                standalone,
                new ai.futurespeak.friday.tv.brain.FailoverBrain.Desktop() {
                    @Override public boolean paired() { return pairLink.paired(); }
                    @Override public boolean reachable() { return pairLink.reachable(); }
                },
                this::brainSwitched);
        buildUi();
        makeVoice();
        makeSpeech();
        session = new Session(brain, () -> prefs.voiceOn() ? voice : null, convo, () -> history.save(convo),
                this::systemPrompt, sessionUi, main::post);
        actions = app.actions();   // null when receipts can't be kept: then Friday can't act
        if (actions != null) actions.setScreen(this::confirmFromGate, this::progressFromGate);   // T3: the fixed card and progress
        ai.futurespeak.friday.tv.web.ChannelVoice.speaker = new ai.futurespeak.friday.tv.web.ChannelVoice.Speaker() {   // read-aloud for open channels
            public void say(String t) { main.post(() -> { if (prefs.voiceOn() && voice != null) { voice.speak(t, -1); voice.endOfReply(); } }); }
            public boolean busy() { return voice != null && voice.busy(); }
            public void stop() { main.post(() -> { if (voice != null) voice.stop(); }); }
        };
        if (actions != null) session.setActions(actions);

        avatar.setTierListener(level -> { if ("auto".equals(prefs.quality())) prefs.setAvatarTier(level); });
        avatar.start(prefs.quality(), Motion.reduced(this), prefs.structure(), prefs.avatarTier());
        readIntent(getIntent());
        refreshTiles();
        greet();
    }

    private String systemPrompt() {
        String today = new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(new Date());
        ai.futurespeak.friday.tv.actions.TvActions a = actions;
        boolean kids = a != null && a.kids();
        // Kids slice 1: the faster Claude and the gentler voice for this turn, and the room facts for the kids block.
        claude.setModel(ai.futurespeak.friday.tv.kids.KidsVoice.model(prefs.model(), kids));
        if (voice != null) voice.setSpeed(ai.futurespeak.friday.tv.kids.KidsVoice.speed(prefs.voiceSpeed(), kids));
        ai.futurespeak.friday.tv.kids.KidsPrompt.Room room = kids ? ai.futurespeak.friday.tv.kids.KidsHelper.room(this) : null;
        ai.futurespeak.friday.tv.brain.ModelChoice.Model m = servingModel();
        if (poweredBy != null) poweredBy.setText("Powered by " + brain.displayName());   // names who really answers
        ai.futurespeak.friday.tv.ui.CardLayer.who = brain.displayName();               // and so do the cards
        return SystemPrompt.build(effectiveMode(), today, prefs.voiceOn(), a == null ? null : a.promptBlock(), room,
                m == null ? null : ai.futurespeak.friday.tv.brain.ModelChoice.identity(m, false));
    }

    ai.futurespeak.friday.tv.brain.OpenRouterBrain openRouter;

    /** T4: the curated OpenRouter list and the choice, kept on the TV (set from Settings with the PIN, or from the PC). */
    android.content.SharedPreferences brainPrefs() { return getSharedPreferences("brains", MODE_PRIVATE); }
    java.util.List<ai.futurespeak.friday.tv.brain.ModelChoice.Model> openRouterModels() {
        return ai.futurespeak.friday.tv.brain.ModelChoice.parse(brainPrefs().getString("models", null));
    }

    /** Who answers this turn: null for Claude (always in kids mode, or without an OpenRouter key). */
    ai.futurespeak.friday.tv.brain.ModelChoice.Model servingModel() {
        ai.futurespeak.friday.tv.actions.TvActions a = actions;
        return ai.futurespeak.friday.tv.brain.ModelChoice.serving(brainPrefs().getString("selected", null), openRouterModels(),
                a == null || a.kids(), secure.has(SecureStore.OPENROUTER));
    }

    /** Paid replies and switches go to the receipts (What Friday did). */
    private ai.futurespeak.friday.tv.brain.ChoiceBrain.Log brainLog() {
        return new ai.futurespeak.friday.tv.brain.ChoiceBrain.Log() {
            public void switched(String from, String to, String why) { brainReceipt("brain.fallback", new String[]{"from", from, "to", to}, why, 0); }
            public void reply(String id, String name, double usd) { brainReceipt("brain.reply", new String[]{"model", id, "name", name}, name, usd); }
        };
    }

    private void brainReceipt(String action, String[] kv, String detail, double usd) {
        ai.futurespeak.friday.tv.actions.TvActions a = actions;
        if (a == null) return;
        try {
            org.json.JSONObject args = new org.json.JSONObject();
            for (int i = 0; i + 1 < kv.length; i += 2) args.put(kv[i], kv[i + 1]);
            org.json.JSONObject rec = new org.json.JSONObject().put("action", action).put("args", args).put("tier", 0).put("origin", "voice")
                    .put("who_may", "ok").put("taint", false).put("policy", "announce");
            String who = a.kids() ? "kids" : "family";
            String id = a.receipts().decision(who, rec);
            org.json.JSONObject extra = new org.json.JSONObject();
            if ("brain.reply".equals(action)) extra.put("meter", new org.json.JSONObject().put("service", "openrouter").put("units", "usd").put("actual", usd).put("usd", usd));
            a.receipts().outcome(id, who, "executed", detail, extra);
        } catch (Exception e) { Log.w(TAG, "brain receipt failed (" + e.getClass().getSimpleName() + ")"); }
    }

    /**
     * Kids slice 1: requests Friday answers herself (KidsPhrases). In kids mode, anything that would
     * widen it gets "That needs a grown-up." and a receipt, without a PIN pad; "kids mode" asks the
     * helper to narrow. Returns true when handled.
     */
    boolean kidsPhrase(String text) {
        boolean kids = actions != null && actions.kids();
        ai.futurespeak.friday.tv.kids.KidsPhrases.Kind k = ai.futurespeak.friday.tv.kids.KidsPhrases.match(text, kids);
        if (k == null) return false;
        showYou(text, false);
        if (k == ai.futurespeak.friday.tv.kids.KidsPhrases.Kind.WIDEN) {
            ai.futurespeak.friday.tv.kids.KidsHelper.refused(this, "phrase");
            sayFixed(ai.futurespeak.friday.tv.kids.KidsPhrases.widenReply());
        } else {
            new Thread(() -> {
                boolean ok = ai.futurespeak.friday.tv.kids.KidsHelper.enter(this);
                main.post(() -> sayFixed(ok ? "Kids mode is on." : "To start kids mode, press the Home button on the remote."));
            }, "kids-enter").start();
        }
        return true;
    }

    /** A fixed line from the app (not the brain): captioned and spoken. */
    private void sayFixed(String line) {
        sessionUi.captionStart();
        sessionUi.captionAppend(line);
        if (prefs.voiceOn() && voice != null) { voice.stop(); voice.speak(line, 0); voice.endOfReply(); }
        setUiState("idle");
    }

    void makeVoice() {
        if (voice != null) voice.release();
        voice = new FallbackVoice(this, () -> Prefs.elevenVoiceOn() ? secure.get(SecureStore.ELEVENLABS) : null, prefs.elevenVoice(), prefs.elevenModel(),
                new Voice.Events() {
                    @Override public void onSpeaking() { if (session != null) session.onVoiceSpeaking(); }
                    @Override public void onQuiet() { if (session != null) session.onVoiceQuiet(); }
                    @Override public void onSentence(int i) { if (session != null) session.onVoiceSentence(i); }
                    @Override public void onVoiceError(String why) { Log.w(TAG, "voice error: " + why); }
                });
        voice.setVolume(prefs.voiceVolume());
        voice.setSpeed(prefs.voiceSpeed());
    }

    void makeSpeech() {
        if (speech != null) speech.release();
        SpeechIn.Events ev = new SpeechIn.Events() {
            @Override public void onMic(boolean open) { micPill.setVisibility(open ? View.VISIBLE : View.GONE); }
            @Override public void onPartial(String text) { showYou(text, true); }
            @Override public void onFinal(String text) {
                listening = false; holding = false;
                endMeasuring();
                showYou(text, false);
                // "Show me the news": an add-on that claims the request, not the brain (docs/ADDON-CONTRACT.md).
                if (ai.futurespeak.friday.tv.addon.AddonRegistry.routeSpoken(MainActivity.this, text, actions == null || actions.kids())) { setUiState("idle"); setConversation(false); return; }
                if (kidsPhrase(text)) return;   // kids slice 1: "more time", "turn off kids mode" … answered here, never by the brain
                // "Open my library": Studio's Library, not the brain.
                if (ai.futurespeak.friday.tv.studio.StudioActivity.openFor(MainActivity.this, text, actions != null && actions.kids())) { setUiState("idle"); setConversation(false); return; }
                session.ask(text);
            }
            @Override public void onNothing(String why) {
                listening = false; holding = false;
                endMeasuring();
                setUiState("idle");
                if (!session.busy() && replyBuf.length() == 0) setConversation(false);
                showNotice(why);
            }
            @Override public void onLevel(double level) { if (listening) avatar.setLevel(0); }
        };
        speechEvents = ev;
        // Spec D4: listening stays on the TV. Android's on-device recognizer if there is one;
        // otherwise the TV's recognition service held to offline, once it has confirmed its
        // English pack is installed. Never an online recognizer, never the app's own recording.
        // design decision, (2026-11-02): Friday's own recognizer first, whenever its model is on the TV. Google's routes are fallbacks.
        if (prefs.sttOnTv() && ai.futurespeak.friday.tv.audio.OnTvModel.check(getFilesDir(), false).ready()) { useRoute(OfflineSpeech.Route.ON_TV, ev); return; }
        if (RecognizerSpeechIn.onDeviceAvailable(this)) { useRoute(OfflineSpeech.Route.ON_DEVICE, ev); return; }
        if (!RecognizerSpeechIn.serviceAvailable(this)) { useRoute(OfflineSpeech.Route.NONE, ev); return; }
        final SpeechIn.Events evf = ev;
        useRoute(OfflineSpeech.Route.CLOUD, ev);   // until the service answers (it still prefers offline)
        final long t0 = SystemClock.uptimeMillis();
        RecognizerSpeechIn.checkSupport(this, s -> {
            sttSupport = s;
            String version = RecognizerSpeechIn.serviceVersion(this);
            boolean measured = version != null && version.equals(prefs.sttOfflineVerifiedFor());
            OfflineSpeech.Route r = OfflineSpeech.decide(false, true, s.installed, measured);
            Log.i(TAG, "stt support in " + (SystemClock.uptimeMillis() - t0) + " ms: installed=" + s.installed + " pending=" + s.pending
                    + (s.error != null ? " error=" + s.error : "") + " measuredOffline=" + measured + " -> " + r);
            // Never ask for the pack here (start/resume): see audio.PackFlow. Only an explicit choice opens it.
            if (speech != null) speech.release();
            useRoute(r, evf);
        });
    }

    SpeechIn.Events speechEvents;

    /** The offline-English pack: offered once on Talk, requested only on an explicit choice (audio.PackFlow). */
    ai.futurespeak.friday.tv.audio.PackFlow packFlow;

    ai.futurespeak.friday.tv.audio.PackFlow packFlow() {
        if (packFlow == null) packFlow = new ai.futurespeak.friday.tv.audio.PackFlow(new ai.futurespeak.friday.tv.audio.PackFlow.Store() {
            public ai.futurespeak.friday.tv.audio.PackFlow.State packState() { return prefs.packState(); }
            public long packRequestedAt() { return prefs.packRequestedAt(); }
            public void setPack(ai.futurespeak.friday.tv.audio.PackFlow.State s, long at) { prefs.setPack(s, at); }
        }, System::currentTimeMillis, () -> RecognizerSpeechIn.requestPack(this));
        return packFlow;
    }

    /** Download offline English (the offer card or Settings). Never twice while a request is in flight. */
    void downloadPack() {
        if (packFlow().requestDownload()) showNotice("Google's speech app is downloading offline English. Until it's in, Friday uses online speech.");
        else showNotice(packFlow().progress());
        refreshSettings();
    }

    // ---- Pairing T2 ---------------------------------------------------------
    ai.futurespeak.friday.tv.pair.DesktopLink pairLink;

    /** Who is watching, for the desktop (never a name, never the PIN). */
    private String pairPrincipal() {
        ai.futurespeak.friday.tv.actions.TvActions a = actions;
        return a != null && a.kids() ? "kids" : "family";
    }

    /** A brain switch: a receipt (What Friday did), and the pill says who answers now. */
    private void brainSwitched(ai.futurespeak.friday.tv.pair.BrainMode.Mode from,
                               ai.futurespeak.friday.tv.pair.BrainMode.Mode to, String why) {
        try {
            ai.futurespeak.friday.tv.actions.TvActions a = actions;
            if (a != null) {
                org.json.JSONObject rec = new org.json.JSONObject().put("action", "brain.switch")
                        .put("from", from.name()).put("to", to.name()).put("why", why);
                String id = a.receipts().decision("system", rec);
                a.receipts().outcome(id, "system", "executed", to.label);
            }
        } catch (Exception ignored) { }
        main.post(() -> {
            if (poweredBy != null) poweredBy.setText("Powered by " + brain.displayName());
            ai.futurespeak.friday.tv.ui.CardLayer.who = brain.displayName();
            if (pairStatus != null && pairLink != null) pairStatus.setText(pairLink.statusLine());
        });
    }

    private TextView pairStatus;

    /** Who the words of a question go to: Claude, or the OpenRouter model chosen for this household (kids mode: always Claude). */
    String answererName() {
        if (pairLink != null && pairLink.paired() && pairLink.reachable()) return "Friday on your PC";
        ai.futurespeak.friday.tv.brain.ModelChoice.Model m = servingModel();
        return m == null ? "Claude" : m.name + " (through OpenRouter)";
    }

    /** Friday's own one-time card. Back, or "Online for now", means online recognition for now. */
    private void offerPack() {
        Runnable online = () -> { packFlow().decline(); showNotice("Friday will use Google's online speech. You can download offline English any time in Settings, Listening."); };
        push(new ApprovalLayer(this, this, "Keep your voice on this TV?",
                "Friday can recognize speech offline once the TV has Google's offline English pack. Until then, Google's speech recognition may send your voice to Google to be written down. Only the words go to " + answererName() + ".",
                Arrays.asList("Download: Google's speech app fetches it once, in the background.", "Online for now: you can download it later in Settings, Listening.", "Back: online for now."),
                Arrays.asList(
                        new ApprovalLayer.Choice("Download offline English", Brand.CYAN, true, () -> { packFlow().decline(); downloadPack(); }),
                        new ApprovalLayer.Choice("Online for now", Brand.NEUTRAL, true, online)),
                online));
    }
    /** A shell measurement put a one-off recognizer in place: after it, choose the route again. */
    boolean measuring;
    private void endMeasuring() { if (measuring) { measuring = false; main.post(this::makeSpeech); } }

    /** What the TV's recognition service said it can do offline (for the Listening page). */
    RecognizerSpeechIn.Support sttSupport;
    OfflineSpeech.Route sttRoute = OfflineSpeech.Route.NONE;

    private void useRoute(OfflineSpeech.Route r, SpeechIn.Events ev) {
        sttRoute = r;
        if (r == OfflineSpeech.Route.ON_TV) {
            // Friday's own recognizer: the voice goes from the mic to the model in this process, and nowhere else.
            ai.futurespeak.friday.tv.audio.SherpaSpeechIn s = new ai.futurespeak.friday.tv.audio.SherpaSpeechIn(this, ev);
            s.setHoldMode(prefs.talkMode() == Prefs.TalkMode.HOLD);
            speech = s;
            background.execute(() -> ai.futurespeak.friday.tv.audio.SherpaSpeechIn.recognizer(getFilesDir()));   // warm up off the main thread
        } else if (r != OfflineSpeech.Route.NONE) {
            RecognizerSpeechIn s = RecognizerSpeechIn.create(this, ev, r);
            s.setPauseMs(prefs.listenPauseMs());
            s.setHoldMode(prefs.talkMode() == Prefs.TalkMode.HOLD);
            speech = s;
        } else {
            speech = RecognizerSpeechIn.unavailable(r, ev);
        }
        Log.i(TAG, "speech input: " + speech.label());
    }

    // ------------------------------------------------------------------ UI -------
    // The layout follows Friday desktop's Simple mode (static/friday_experience.css): the
    // avatar is the hero with the whole screen to itself, a slim glass rail on the left holds
    // the places (it slides away when idle and comes back with Left or Menu), answers sit in
    // one compact "day" card at the right, and the type is Inter with Orbitron kept for the
    // wordmark. Sizes are Simple mode's, scaled for ten feet.
    private LinearLayout rail;
    private boolean railShown = true;
    private static final long RAIL_IDLE_MS = 8000;
    private final Runnable railAutoHide = () -> showRail(false);

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000103);
        avatar = new AvatarView(this);
        root.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
        // The stage blends into the board (Home v2): a soft vignette and a floor shade over the avatar. Static, so free on the GPU.
        View blend = new View(this);
        android.graphics.drawable.GradientDrawable vignette = new android.graphics.drawable.GradientDrawable();
        vignette.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        vignette.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        vignette.setColors(new int[]{0x00000103, 0x00000103, 0x8C000103, 0xE6000103});
        android.graphics.drawable.GradientDrawable floor = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xD9020409, 0x00020409});
        android.graphics.drawable.LayerDrawable stage = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{vignette, floor});
        stage.setLayerHeight(1, dp(150));
        stage.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        blend.setBackground(stage);
        root.addView(blend, new FrameLayout.LayoutParams(-1, -1));

        // Top: the wordmark, and what is answering. Quiet, like desktop's top bar.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(32), dp(22), dp(32), 0);
        LinearLayout mark = new LinearLayout(this);
        mark.setOrientation(LinearLayout.VERTICAL);
        mark.addView(Brand.wordmark(this, 17));
        TextView by = Brand.text(this, "by FutureSpeak.AI™", 10, 0xB8FFFFFF, Brand.mono(this));
        by.setPadding(dp(1), dp(3), 0, 0);
        mark.addView(by);
        top.addView(mark, new LinearLayout.LayoutParams(0, -2, 1));
        wordmark = mark;
        mark.setTranslationX(railShown ? RAIL_CLEARANCE_DP * getResources().getDisplayMetrics().density : 0);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        poweredBy = Brand.text(this, "Powered by " + brain.displayName(), 12, Brand.TEXT, Brand.bodyBold(this));
        poweredBy.setBackground(Brand.pill(this, 0xC70A0E1A, 0x1FFFFFFF));
        poweredBy.setPadding(dp(12), dp(5), dp(12), dp(5));
        right.addView(poweredBy);
        TextView soon = Brand.text(this, pairLink.statusLine(), 10, Brand.DIM, Brand.body(this));   // pairing status
        pairStatus = soon;
        soon.setPadding(0, dp(5), dp(2), 0);
        right.addView(soon);
        top.addView(right);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        homeTop = top;

        // The microphone indicator: shown whenever the mic is open. Fixed app UI.
        micPill = Brand.text(this, "●  Microphone on · listening", 14, Color.WHITE, Brand.bodyBold(this));
        micPill.setBackground(Brand.pill(this, 0xE6052A18, Brand.OK));
        micPill.setPadding(dp(16), dp(7), dp(16), dp(7));
        micPill.setVisibility(View.GONE);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        mp.topMargin = dp(26);
        root.addView(micPill, mp);

        // The answer: one compact glass card at the right (desktop's "day" panel).
        captionBox = new LinearLayout(this);
        captionBox.setOrientation(LinearLayout.VERTICAL);
        captionBox.setBackground(ai.futurespeak.friday.tv.ui.Look.captionBox(this));
        captionBox.setPadding(dp(22), dp(18), dp(22), dp(18));
        replyLabel = ai.futurespeak.friday.tv.ui.Look.eyebrow(this, brain.displayName(), 9.5f);
        captionBox.addView(replyLabel);
        youText = Brand.text(this, "", 13, Brand.DIM, Brand.body(this));
        youText.setMaxLines(2);
        youText.setEllipsize(TextUtils.TruncateAt.END);
        youText.setPadding(0, dp(6), 0, dp(10));
        captionBox.addView(youText);
        View rule = new View(this);
        rule.setBackgroundColor(0x24FFFFFF);
        captionBox.addView(rule, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.75f))));
        replyText = Brand.text(this, "", 16, Brand.TEXT, Brand.body(this));
        replyText.setLineSpacing(0, 1.32f);
        replyText.setPadding(0, dp(10), 0, 0);
        // Six lines show at a time. While she speaks, the card follows the sentence being
        // said; Up/Down page through it; a cue says when there is more above or below.
        replyScroll = new android.widget.ScrollView(this);
        replyScroll.setVerticalScrollBarEnabled(false);
        replyScroll.setFocusable(false);
        replyScroll.addView(replyText);
        FrameLayout replyFrame = new FrameLayout(this);
        replyFrame.addView(replyScroll, new FrameLayout.LayoutParams(-1, dp(150)));
        moreCue = Brand.text(this, "", 10, Brand.CYAN, Brand.bodyBold(this));
        moreCue.setLetterSpacing(0.1f);
        moreCue.setBackground(Brand.pill(this, Brand.GLASS, 0x5500D4FF));
        moreCue.setPadding(dp(8), dp(3), dp(8), dp(3));
        moreCue.setVisibility(View.GONE);
        replyFrame.addView(moreCue, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));
        captionBox.addView(replyFrame, new LinearLayout.LayoutParams(-1, -2));
        replyScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> updateMoreCue());
        captionBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(360), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        cp.rightMargin = dp(36);
        root.addView(captionBox, cp);

        notice = Brand.text(this, "", 14, Brand.TEXT, Brand.body(this));
        notice.setBackground(Brand.pill(this, Brand.GLASS, 0x33FFFFFF));
        notice.setPadding(dp(16), dp(8), dp(16), dp(8));
        notice.setVisibility(View.GONE);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        np.bottomMargin = dp(66);
        root.addView(notice, np);

        // Status, as desktop's avatar caption: the state in small Orbitron caps over one line of Inter, on glass with an accent hairline.
        stateChip = Brand.text(this, "", 13, 0xD9E2F6FF, Brand.body(this));
        stateChip.setGravity(Gravity.CENTER);
        stateChip.setLineSpacing(dp(4), 1f);
        stateChip.setBackground(ai.futurespeak.friday.tv.ui.Look.captionBox(this));
        stateChip.setPadding(dp(22), dp(9), dp(22), dp(10));
        stateChip.setShadowLayer(dp(3), 0, dp(1), 0xF2000000);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sp.bottomMargin = dp(26);
        root.addView(stateChip, sp);
        // The notice always sits above the status chip, however many lines the chip has.
        stateChip.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int want = sp.bottomMargin + (b - t) + dp(12);
            if (b - t > 0 && np.bottomMargin != want) { np.bottomMargin = want; notice.setLayoutParams(np); }
        });
        hint = stateChip;

        // The rail (desktop's .fx-rail): Talk, channels, Settings.
        rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(8), dp(12), dp(8), dp(12));
        rail.setBackground(Brand.glass(this, 22));   // lit glass; no elevation shadow (a full-height shadow is costly at 4K)
        rail.setClipChildren(false);
        android.widget.ScrollView railScroll = new android.widget.ScrollView(this);
        railScroll.setVerticalScrollBarEnabled(false);
        railScroll.setClipChildren(false);
        railScroll.setClipToPadding(false);
        railScroll.addView(rail);
        tiles = rail;
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(86), -2, Gravity.START | Gravity.CENTER_VERTICAL);
        rp.leftMargin = dp(22);
        root.addView(railScroll, rp);
        dockRow = railScroll;

        setContentView(root);
        // The Home v2 throttle, reused: while focus moves (rail, cards, settings) she holds her last frame.
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (avatar != null && oldF != null && !stagePaused) avatar.hold(ai.futurespeak.friday.tv.home.HomeActivity.HOLD_MS);
        });
        updateStatus();
    }

    /** How far the wordmark steps right while the rail is out, so the two never overlap. */
    static final float RAIL_CLEARANCE_DP = 112;
    private View wordmark;

    /** Friday's own places use the one line-icon set (the same as Settings); channels keep their emoji. */
    static String glyphFor(String icon, String label) {
        if (!java.util.Arrays.asList("Talk", "News", "Live", "Apps", "Settings").contains(label)) return null;   // a channel keeps its own emoji
        switch (icon) {
            case "🎙": return "voice";
            case "📰": return "news";
            case "◉": return "live";
            case "▦": return "apps";
            case "⚙": return "settings";
            default: return null;
        }
    }

    private TextView railItem(String icon, String label) {
        int accent = ai.futurespeak.friday.tv.ui.Look.accent(this);
        String glyph = glyphFor(icon, label);
        TextView t = Brand.text(this, "", 11, 0xC7FFFFFF, Brand.bodyBold(this));
        if (glyph != null) {
            t.setText(label);
            ai.futurespeak.friday.tv.ui.LineIcon.Glyph g = new ai.futurespeak.friday.tv.ui.LineIcon.Glyph(glyph, accent, 0xD9FFFFFF);
            g.setBounds(0, 0, dp(26), dp(26));
            t.setCompoundDrawables(null, g, null, null);
            t.setCompoundDrawablePadding(dp(6));
        } else {
            android.text.SpannableString s = new android.text.SpannableString(icon + "\n" + label);
            s.setSpan(new android.text.style.RelativeSizeSpan(1.8f), 0, icon.length(), 0);
            t.setText(s);
            t.setLineSpacing(dp(3), 1f);
        }
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setClickable(true);
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setBackground(ai.futurespeak.friday.tv.ui.Look.focusable(this, 14, accent, true));
        t.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFFFFFFFF, 0xC7FFFFFF}));
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        t.setOnFocusChangeListener((v, has) -> { if (has) pokeRail(); });
        ai.futurespeak.friday.tv.ui.Look.lift(t, 1.06f);   // after the rail's own listener, which it keeps
        return t;
    }

    /** Kids mode on the main screen: never the full rail. Fail closed until the helper answers. */
    private volatile boolean kidsRail = true;

    void refreshTiles() {
        rail.removeAllViews();
        talkTile = railItem("🎙", "Talk");
        addTile(talkTile);
        talkTile.setOnClickListener(v -> { /* handled in dispatchKeyEvent */ });
        if (kidsRail) { kidsTiles(); return; }   // kids: Talk, the kids home, and only channels a grown-up marked OK
        addonTiles(false);   // enabled add-ons, such as Friday News (docs/ADDON-CONTRACT.md)
        for (ChannelSpec ch : orderedChannels()) {
            TextView t = railItem(ch.emoji, ch.title);
            t.setOnClickListener(v -> openChannel(ch));
            addTile(t);
        }
        TextView appsTile = railItem("▦", "Apps");   // slice S1: the remote's way to the same gate
        appsTile.setOnClickListener(v -> openSettings("apps"));
        addTile(appsTile);
        TextView settings = railItem("⚙", "Settings");
        settings.setOnClickListener(v -> openSettings());
        addTile(settings);
        if (layers.isEmpty() && railShown) talkTile.requestFocus();
    }

    /** The kids rail (design decision, 2026-11-02): no News, Live, Apps, Settings or unmarked channels. */
    private void kidsTiles() {
        TextView home = railItem("⌂", "Kids home");
        home.setOnClickListener(v -> startActivity(new android.content.Intent(this, ai.futurespeak.friday.tv.kids.KidsHomeActivity.class)));
        addTile(home);

        ai.futurespeak.friday.tv.kids.KidsChannels kc = new ai.futurespeak.friday.tv.kids.KidsChannels(this);
        for (ChannelSpec ch : ai.futurespeak.friday.tv.kids.KidsChannels.forRail(true, orderedChannels(), c -> kc.ok(c.id))) {
            TextView t = railItem(ch.emoji, ch.title);
            t.setOnClickListener(v -> openChannel(ch));
            addTile(t);
        }
        addonTiles(true);   // only kids add-ons a grown-up marked OK
        if (layers.isEmpty() && railShown) talkTile.requestFocus();
    }

    private void addonTiles(boolean kids) {
        for (ai.futurespeak.friday.tv.addon.AddonRegistry.Addon ad : ai.futurespeak.friday.tv.addon.AddonRegistry.shown(this, kids)) {
            TextView t = railItem(ad.spec.icon, ad.spec.title);
            t.setOnClickListener(v -> ai.futurespeak.friday.tv.addon.AddonRegistry.open(this, ad, null, null));
            addTile(t);
        }
    }

    /**
     * The remote's path to an action (Apps page): the same gate, checks and receipts as voice.
     * The gate waits for verification, so it runs off the main thread; its fixed words come back here.
     */
    void actFromRemote(String name, String app) {
        ai.futurespeak.friday.tv.actions.TvActions ac = actions;
        if (ac == null) { showNotice("Friday can't open apps right now."); return; }
        JSONObject in = new JSONObject();
        try { if (app != null) in.put("app", app); } catch (org.json.JSONException ignored) { }
        showNotice(app == null ? "One moment…" : "Opening " + app + "…");
        background.execute(() -> {
            ai.futurespeak.friday.tv.actions.ActionGate.Verdict v = ac.fromRemote(name, in);
            main.post(() -> {
                showNotice(v.say);
                // Speak only when Friday is still in front (a refusal); otherwise the app has the room.
                if (!v.leftFriday && prefs.voiceOn() && voice != null) { voice.speak(v.say, -1); voice.endOfReply(); }
                refreshSettings();
            });
        });
    }

    private void addTile(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(4);
        rail.addView(v, lp);
    }

    /** Slides the rail in or out (desktop Simple mode keeps the stage for Friday). */
    private void showRail(boolean show) {
        main.removeCallbacks(railAutoHide);
        if (show == railShown) { if (show) pokeRail(); return; }
        railShown = show;
        long d = Motion.reduced(this) ? 0 : 220;
        if (wordmark != null) wordmark.animate().translationX(show ? dp(RAIL_CLEARANCE_DP) : 0).setDuration(d)
                .setInterpolator(ai.futurespeak.friday.tv.ui.Look.REVEAL).start();   // the rail never covers the wordmark
        if (show) {
            dockRow.setVisibility(View.VISIBLE);
            dockRow.animate().translationX(0).alpha(1f).setDuration(d).start();
            if (talkTile != null) talkTile.requestFocus();
            pokeRail();
        } else if (!layers.isEmpty()) {
            // A layer (settings, a card…) has the remote: just tuck the rail away, never move focus.
            dockRow.animate().translationX(-dp(130)).alpha(0f).setDuration(d)
                    .withEndAction(() -> { if (!railShown) dockRow.setVisibility(View.INVISIBLE); }).start();
        } else {
            View f = getCurrentFocus();
            if (f != null) f.clearFocus();
            root.requestFocus();
            dockRow.animate().translationX(-dp(130)).alpha(0f).setDuration(d)
                    .withEndAction(() -> { if (!railShown) dockRow.setVisibility(View.INVISIBLE); }).start();
        }
        updateStatus();
    }

    /** Any use of the rail keeps it up a little longer. */
    private void pokeRail() {
        main.removeCallbacks(railAutoHide);
        if (railShown && layers.isEmpty()) main.postDelayed(railAutoHide, RAIL_IDLE_MS);
    }

    private void updateStatus() {
        if (stateChip == null) return;
        String s;
        int accent = ai.futurespeak.friday.tv.ui.Look.accent(this);
        if (buildingChars >= 0 && !"listening".equals(uiState)) {
            stateChip.setText(ai.futurespeak.friday.tv.ui.Look.caption(this, "Building",
                    "Your channel · " + Math.max(1, buildingChars / 1024) + " KB · it opens when it's ready", accent));
            return;
        }
        // Desktop's avatar caption: the state word, then one line of what to do.
        String state;
        int color;
        switch (uiState) {
            case "listening": state = "Listening"; s = "Press OK when you're done"; color = Brand.OK; break;
            case "thinking": state = "Thinking"; s = "One moment…"; color = 0xFFA78BFA; break;
            case "speaking": state = "Speaking"; s = "OK to interrupt  ·  Back to stop"; color = Brand.OK; break;
            default: state = "Ready"; color = accent;
                s = railShown ? "Press OK on Talk, then speak  ·  ◀ or Menu for the rail" : "Press OK to talk  ·  ◀ for the rail";
        }
        stateChip.setText(ai.futurespeak.friday.tv.ui.Look.caption(this, state, s, color));
    }

    private int dp(float v) { return Brand.dp(this, v); }

    private void greet() {
        if (!secure.has(SecureStore.ANTHROPIC)) {
            // Stays up (no timeout) until a key arrives and the app restarts.
            notice.setText("Almost ready: add a Claude key from the PC with the Agent Friday TV setup (the Keys step).");
            notice.setVisibility(View.VISIBLE);
        }
    }

    private void setUiState(String s) {
        uiState = s;
        avatar.setState(s);
        updateStatus();
        main.removeCallbacks(levelTick);
        if ("speaking".equals(s)) main.post(levelTick);
    }

    /** Speech level to the avatar, 20 times a second while speaking. */
    private final Runnable levelTick = new Runnable() {
        @Override public void run() {
            if (!"speaking".equals(uiState)) return;
            avatar.setLevel(voice == null ? 0 : voice.level());
            main.postDelayed(this, 50);
        }
    };

    /** Conversation on: the answer card shows at the right and Friday steps a little left. */
    private void setConversation(boolean on) {
        if (conversation == on) return;
        conversation = on;
        long d = Motion.reduced(this) ? 0 : 320;
        if (on) {
            captionBox.setAlpha(0f);
            captionBox.setTranslationX(dp(24));
            captionBox.setVisibility(View.VISIBLE);
            captionBox.animate().alpha(1f).translationX(0).setDuration(d).start();
            avatar.animate().translationX(-dp(150)).setDuration(d).start();
            showRail(false);
        } else {
            captionBox.animate().alpha(0f).translationX(dp(24)).setDuration(d)
                    .withEndAction(() -> { if (!conversation) captionBox.setVisibility(View.GONE); }).start();
            avatar.animate().translationX(0).setDuration(d).start();
        }
        updateStatus();
    }

    /** Keeps caption characters [start, end) of the raw reply in the three-line window. */
    private void followRange(int start, int end) {
        android.text.Layout l = replyText.getLayout();
        if (l == null) return;
        String raw = replyBuf.toString();
        int ds = displayText(raw.substring(0, Math.min(start, raw.length()))).length();
        int de = displayText(raw.substring(0, Math.min(end, raw.length()))).length();
        int visible = Math.max(1, replyScroll.getHeight() / Math.max(1, replyText.getLineHeight()));
        int top = l.getLineForVertical(replyScroll.getScrollY());
        int target = ai.futurespeak.friday.tv.ui.CaptionFollow.top(top, visible,
                l.getLineForOffset(Math.min(ds, Math.max(0, replyText.length() - 1))),
                l.getLineForOffset(Math.min(Math.max(ds, de - 1), Math.max(0, replyText.length() - 1))), l.getLineCount());
        replyScroll.smoothScrollTo(0, l.getLineTop(target));
    }

    /** D-pad Up/Down while captions are up: one page back or on. */
    private void pageCaption(int dir) {
        android.text.Layout l = replyText.getLayout();
        if (l == null) return;
        userPaged = true;
        int visible = Math.max(1, replyScroll.getHeight() / Math.max(1, replyText.getLineHeight()));
        int top = l.getLineForVertical(replyScroll.getScrollY());
        replyScroll.smoothScrollTo(0, l.getLineTop(ai.futurespeak.friday.tv.ui.CaptionFollow.page(top, visible, dir, l.getLineCount())));
    }

    private void updateMoreCue() {
        android.text.Layout l = replyText.getLayout();
        if (l == null) { moreCue.setVisibility(View.GONE); return; }
        int visible = Math.max(1, replyScroll.getHeight() / Math.max(1, replyText.getLineHeight()));
        int top = l.getLineForVertical(replyScroll.getScrollY());
        boolean below = ai.futurespeak.friday.tv.ui.CaptionFollow.moreBelow(top, visible, l.getLineCount());
        boolean above = ai.futurespeak.friday.tv.ui.CaptionFollow.moreAbove(top);
        moreCue.setText(below ? "▼ MORE" : "▲ UP FOR EARLIER");
        moreCue.setVisibility(below || above ? View.VISIBLE : View.GONE);
    }

    private void showYou(String text, boolean partial) {
        setConversation(true);
        youText.setText("You asked: " + text + (partial ? "…" : ""));
    }

    /**
     * Slice T3: the TV's fixed approval card for a paid action, in the TV's own words (cost, service,
     * what leaves the TV, undo). Called on the brain's thread; waits up to 90 s. Only "Make it" is a yes:
     * Back, "Not now", or leaving Friday is a no. Focus starts on "Not now".
     */
    boolean confirmFromGate(ai.futurespeak.friday.tv.actions.ActionGate.Ask a) {
        if (current != this) return false;
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        boolean[] yes = {false};
        main.post(() -> push(new ApprovalLayer(this, this, a.title, a.what, java.util.Arrays.asList(
                "Uses: " + a.service,
                "Cost: " + a.cost + " (it's added to What Friday did)",
                "Leaves the TV: " + a.sends,
                a.undo),
                java.util.Arrays.asList(
                        new ApprovalLayer.Choice("Make it", Brand.CYAN, true, () -> { yes[0] = true; done.countDown(); }),
                        new ApprovalLayer.Choice("Not now", Brand.NEUTRAL, true, done::countDown)),
                done::countDown)));
        try { done.await(90, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return yes[0];
    }

    /** Slice T3: progress of a picture being made ("Drawing… 45%"), as a notice. */
    void progressFromGate(int percent, String words) {
        main.post(() -> showNotice((words == null ? "Working" : words) + "…" + (percent > 0 ? " " + percent + "%" : "")));
    }

    void showNotice(String s) {
        notice.setText(s);
        notice.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideNotice);
        main.postDelayed(hideNotice, 5000);
    }
    private final Runnable hideNotice = () -> notice.setVisibility(View.GONE);
    private final Runnable hideCaptions = () -> { if ("idle".equals(uiState) && !listening) setConversation(false); };

    private final Session.Ui sessionUi = new Session.Ui() {
        /** Kids slice 1: a report marker becomes a receipt on this turn. */
        @Override public void mark(String kind) {
            ai.futurespeak.friday.tv.actions.TvActions a = actions;
            ai.futurespeak.friday.tv.kids.KidsMarks.record(a == null ? null : a.receipts(), a != null && a.kids() ? "kids" : "family", kind);
        }
        @Override public void state(String s) {
            setUiState(s);
            main.removeCallbacks(hideCaptions);
            if ("idle".equals(s)) main.postDelayed(hideCaptions, 25_000);
        }
        @Override public void captionStart() {
            replyBuf.setLength(0);
            userPaged = false;
            replyScroll.scrollTo(0, 0);
            replyLabel.setText(brain.displayName().toUpperCase(Locale.ROOT));
            replyText.setText("");
            setConversation(true);
        }
        @Override public void captionAppend(String t) {
            replyBuf.append(t);
            replyText.setText(prefs.captions() ? displayText(replyBuf.toString()) : "");
            // Captions-only (voice off): follow the text as it arrives.
            if (!prefs.voiceOn() && !userPaged) replyScroll.post(() -> replyScroll.smoothScrollTo(0, replyText.getHeight()));
            replyScroll.post(MainActivity.this::updateMoreCue);
        }
        @Override public void follow(int start, int end) {
            if (userPaged) return;
            replyScroll.post(() -> followRange(start, end));
        }
        @Override public void showCards(List<JSONObject> cards) { push(new CardLayer(MainActivity.this, MainActivity.this, cards, pinnedCards)); }
        @Override public void proposeChannel(ChannelSpec p) { askToSaveChannel(p); }
        @Override public void notice(String text) { showNotice(text); }
        @Override public void building(int chars) {
            int was = buildingChars;
            buildingChars = chars;
            if (chars < 0 || was < 0 || chars / 1024 != was / 1024) updateStatus();
        }
    };

    static String displayText(String s) {
        return s.replaceAll("\\*\\*|__|`", "").replaceAll("(?m)^#+\\s*", "").replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ keys -----
    private static boolean isVoiceKey(int k) {
        return k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST || k == KeyEvent.KEYCODE_ASSIST;
    }

    private long backArmedAt = 0;

    /**
     * Every raw key goes through the app's one input layer (ui/InputLayer): the
     * remote's bursts become one logical press, and route() below sees only logical events,
     * so each key reaches exactly one handler once.
     */
    private final ai.futurespeak.friday.tv.ui.InputLayer input = new ai.futurespeak.friday.tv.ui.InputLayer(this::route);

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) { return input.feed(e); }

    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (!has) input.reset();
    }

    private boolean route(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        Layer top = layers.isEmpty() ? null : layers.get(layers.size() - 1);

        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            // Handled here in full (the system's own Back needs the DOWN, which this consumes).
            // T5: while Gemini Live is on, Back ends it (a card on top is answered first).
            if (down && e.getRepeatCount() == 0) {
                if (live != null && top == null) live.end(ai.futurespeak.friday.tv.live.LiveRules.End.BACK, "");
                else back(top);
            }
            return true;
        }

        if (top == null) {
            // The rail: Left or Menu brings it back, Right puts it away; using it keeps it up.
            if (!railShown && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_MENU)) {
                if (down && e.getRepeatCount() == 0) showRail(true);
                return true;
            }
            if (railShown && k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) showRail(false);
                return true;
            }
            if (railShown && down) pokeRail();
        }
        boolean onTalk = top == null && talkTile != null && (talkTile.hasFocus() || conversation || !railShown)
                && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER);
        boolean voiceKey = isVoiceKey(k) && (top == null || !top.blocksTalk());
        if (onTalk || voiceKey) {
            // Tap to talk: one press starts listening (it stops by itself when you stop
            // talking); a second press stops early. Holding the button counts as one press.
            if (down && e.getRepeatCount() == 0) talkPress(top);
            // Hold to talk: the input layer's single release of the hold is the end of the question.
            if (!down && prefs.talkMode() == Prefs.TalkMode.HOLD && listening && speech != null) speech.finish();
            return true;
        }
        if (top == null && conversation && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (down) pageCaption(k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            return true;
        }
        if (top != null) {
            if (top.key(e)) return true;
            return super.dispatchKeyEvent(e);
        }
        return super.dispatchKeyEvent(e);
    }

    /** Back: stop listening, close the top layer, stop Friday; from the quiet home screen, twice to leave. */
    private void back(Layer top) {
        long now = SystemClock.uptimeMillis();
        if (listening) { if (speech != null) speech.cancel(); listening = false; setUiState("idle"); showNotice("Stopped listening."); return; }
        if (top != null) { top.back(); return; }
        if (session.busy() || conversation) {
            session.interrupt();
            setConversation(false);
            backArmedAt = now;
            showNotice("Press Back again to leave Agent Friday.");
            return;
        }
        if (kidsRail) { startActivity(new android.content.Intent(this, ai.futurespeak.friday.tv.kids.KidsHomeActivity.class)); return; }   // kids: back to the kids home, never out
        if (now - backArmedAt < 3000) { Log.i(TAG, "back: leaving"); finish(); return; }
        backArmedAt = now;
        showNotice("Press Back again to leave Agent Friday.");
    }

    /** Characters of a channel page received so far, or -1 when none is being built. */
    private int buildingChars = -1;
    private long buildWarnedAt = 0;

    private void talkPress(Layer top) {
        // Talking interrupts Friday; while a channel is still being written that would throw
        // it away, so the first press only warns and a second press within 6 s goes ahead.
        if (!listening && session.buildingChannel() && SystemClock.uptimeMillis() - buildWarnedAt > 6000) {
            buildWarnedAt = SystemClock.uptimeMillis();
            showNotice("Friday is still building your channel. Press Talk again to stop it and talk.");
            return;
        }
        if (top != null) pop(top);   // a voice key over a card or channel closes it first
        // Gemini Live is a voice option (2026-11-02): when chosen, Talk and the mic button start or end it. No PIN, no card.
        if (live != null) { live.end(ai.futurespeak.friday.tv.live.LiveRules.End.BACK, ""); return; }
        if (prefs.talkWithGemini() && !listening) {
            if (secure.has(SecureStore.GEMINI)) { startLive(); return; }
            showNotice("Gemini Live needs a Google Gemini key on this TV, so Friday is listening instead.");
        }
        if (listening) {             // second press: stop now and send what was heard
            if (speech != null) speech.finish();
            return;
        }
        if (speech == null) makeSpeech();
        if (speech == null) { showNotice("This TV has no speech recognition."); return; }
        // Google's offline pack offer is retired (2026-11-02): its download never completes on this TV; Friday's own model replaces it.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        session.interrupt();          // barge-in: stop speaking now
        talkDownAt = SystemClock.uptimeMillis();
        listening = true;
        setConversation(true);
        youText.setText("Listening…");
        setUiState("listening");
        Log.i(TAG, "talk: listening (" + speech.label() + ")");
        speech.start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 7) showNotice(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                ? "Thanks. Press OK on Talk and ask me something." : "I can't listen without microphone permission.");
    }

    // ---------------------------------------------------------------- layers -----
    /** Home chrome fades out under any layer (the microphone pill stays: it's a privacy indicator). */
    private void homeChrome(boolean show) {
        float a = show ? 1f : 0f;
        long d = Motion.reduced(this) ? 0 : 160;
        if (homeTop != null) homeTop.animate().alpha(a).setDuration(d).start();
        if (stateChip != null) stateChip.animate().alpha(a).setDuration(d).start();
        if (dockRow != null && !show) dockRow.animate().alpha(0f).setDuration(d).start();
        if (dockRow != null && show && railShown) dockRow.animate().alpha(1f).setDuration(d).start();
    }

    @Override public void push(Layer l) {
        homeChrome(false);
        if (listening) { speech.cancel(); listening = false; }
        for (Layer x : layers) x.setVisibility(View.INVISIBLE);   // nothing generated stays drawn under fixed UI
        layers.add(l);
        root.addView(l, new FrameLayout.LayoutParams(-1, -1));
        if (liveBanner != null) liveBanner.bringToFront();
        ai.futurespeak.friday.tv.ui.Look.enter(l);
        stageFor();
        l.focusFirst();
    }

    @Override public void pop(Layer l) {
        if (!layers.remove(l)) return;
        // It leaves with a short fade; it can't take focus or keys meanwhile, and it is closed at once.
        l.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        l.closed();
        ai.futurespeak.friday.tv.ui.Look.exit(l, () -> root.removeView(l));
        stageFor();
        if (!layers.isEmpty()) {
            Layer t = layers.get(layers.size() - 1);
            t.setVisibility(View.VISIBLE);
            ai.futurespeak.friday.tv.ui.Look.enter(t);
            t.focusFirst();
        } else {
            homeChrome(true);
            if (talkTile != null && railShown) talkTile.requestFocus();
        }
    }

    /** A full-screen page that hides her (Settings, a channel) also stops her renderer: the 4K GPU goes to the page. */
    static boolean hidesStage(Layer l) {
        return l instanceof SettingsScreen || l instanceof ai.futurespeak.friday.tv.ui.SettingsLayer || l instanceof ChannelLayer;
    }

    private boolean stagePaused;

    private void stageFor() {
        boolean hide = false;
        for (Layer x : layers) if (hidesStage(x)) hide = true;
        if (hide == stagePaused || avatar == null) return;
        stagePaused = hide;
        if (hide) avatar.onPause(); else avatar.onResume();   // this WebView only; never pauseTimers (channels run JS)
    }

    // -------------------------------------------------------------- channels -----
    private void askToSaveChannel(ChannelSpec p) {
        List<String> details = new ArrayList<>();
        for (String h : p.requestedHosts) details.add(h);
        List<ApprovalLayer.Choice> choices = new ArrayList<>();
        String body = (p.description.isEmpty() ? "" : p.description + "\n")
                + (p.requestedHosts.isEmpty() ? "It works offline: it can't reach the internet, other apps, settings or keys."
                                               : "It asks to reach these websites (read-only). It can't reach anything else, other apps, settings or keys:");
        choices.add(new ApprovalLayer.Choice("Try it first", Brand.CYAN, false, () -> push(new ChannelLayer(this, this, p, true))));
        if (!p.requestedHosts.isEmpty()) {
            choices.add(new ApprovalLayer.Choice("Save with internet (PIN)", Brand.WARN, false, () -> withGrownUp(
                    "Allow “" + p.title + "” to reach " + String.join(", ", p.requestedHosts) + ".",
                    () -> { closeApprovals(); saveChannel(p, p.requestedHosts); })));
            choices.add(new ApprovalLayer.Choice("Save offline", Brand.CYAN, true, () -> saveChannel(p, Collections.emptyList())));
        } else {
            choices.add(new ApprovalLayer.Choice("Save channel", Brand.OK, true, () -> saveChannel(p, Collections.emptyList())));
        }
        choices.add(new ApprovalLayer.Choice("Don't save", Brand.NEUTRAL, true, () -> showNotice("Not saved.")));
        push(new ApprovalLayer(this, this, "Save “" + p.emoji + " " + p.title + "” as a channel?", body, details, choices, null));
    }

    private void closeApprovals() {
        for (Layer l : new ArrayList<>(layers)) if (l instanceof ApprovalLayer) pop(l);
    }

    /**
     * Opens the channel first and keeps it only if it really draws: "saved" is said after the
     * page is up, and a failure is said in plain words with nothing kept.
     */
    private void saveChannel(ChannelSpec p, List<String> granted) {
        closeApprovals();
        ChannelLayer trial = ChannelLayer.trial(this, this, p, granted);
        push(trial);
        showNotice("Opening “" + p.title + "”…");
        trial.verify(problem -> {
            if (!layers.contains(trial)) return;          // they left before it finished: not saved
            if (problem != null) {
                Log.i(TAG, "channel check failed: " + problem);
                pop(trial);
                sayNotice("“" + p.title + "” didn't work: " + problem + ". Nothing was saved. Ask Friday to fix it and try again.");
                return;
            }
            try {
                ChannelSpec saved = channels.save(p, granted);
                refreshTiles();
                Log.i(TAG, "channel saved after it rendered");
                sayNotice("“" + saved.title + "” is working and saved. It's on the home row.");
            } catch (IOException e) {
                sayNotice("“" + p.title + "” works, but it couldn't be saved (" + e.getMessage() + ").");
            }
        });
    }

    /** A notice that is also spoken, so the outcome is heard even with the channel on screen. */
    private void sayNotice(String text) {
        showNotice(text);
        if (prefs.voiceOn() && voice != null && !listening) { voice.speak(text, -1); voice.endOfReply(); }
    }

    void openChannel(ChannelSpec ch) {
        // Kids mode: a channel opens only if a grown-up marked it OK for kids (every way in comes through here).
        boolean kidsNow = kidsRail || (actions != null && actions.kids());
        if (!ai.futurespeak.friday.tv.kids.KidsChannels.mayOpen(kidsNow, new ai.futurespeak.friday.tv.kids.KidsChannels(this).ok(ch.id))) {
            sayNotice("That needs a grown-up.");
            ai.futurespeak.friday.tv.kids.KidsHelper.refused(this, "channel");
            return;
        }
        session.interrupt();
        push(new ChannelLayer(this, this, ch, false));
    }

    // ------------------------------------------------------------ Gemini Live -----
    // T5, as decided on 2026-11-02: a voice option (Settings, Voice). With it chosen, Talk or the mic button starts and ends
    // a Live conversation: no PIN, no card (allowed in kids mode too). While on,
    // the "Google is listening" banner sits above everything; any ending closes the mic at once.
    volatile ai.futurespeak.friday.tv.live.LiveSession live;
    private ai.futurespeak.friday.tv.live.LiveBanner liveBanner;
    private long liveStartedAt;
    private boolean liveKidsAtStart;
    private String liveReceiptId, liveWho;
    private int liveTicks;
    private android.content.BroadcastReceiver liveScreenOff;

    /**
     * Only from {@link #talkPress} (Talk or the mic button, with Gemini Live chosen in Settings, Voice).
     * Never from the brain, a channel, a tool or the shell.
     */
    private void startLive() {
        if (live != null || actions == null) return;
        String key = secure.get(SecureStore.GEMINI);
        if (key == null) return;
        session.interrupt();
        if (listening) { if (speech != null) speech.release(); speech = null; listening = false; }
        micPill.setVisibility(View.GONE);
        for (Layer l : new ArrayList<>(layers)) pop(l);
        setConversation(false);
        boolean kids = actions.kids();
        liveKidsAtStart = kids;
        liveWho = kids ? "kids" : "family";
        liveReceiptId = liveReceipt(null, null, 0, 0);
        actions.newTurn();
        liveBanner = new ai.futurespeak.friday.tv.live.LiveBanner(this);
        root.addView(liveBanner, ai.futurespeak.friday.tv.live.LiveBanner.params(this));
        liveBanner.setElapsed(0, prefs.liveMinutes());
        liveBanner.setCaption("Connecting to Google…");
        liveStartedAt = System.currentTimeMillis();
        liveTicks = 0;
        final ai.futurespeak.friday.tv.live.LiveBanner banner = liveBanner;
        live = new ai.futurespeak.friday.tv.live.LiveSession(key, liveInstruction(kids), actions.tools(), new ai.futurespeak.friday.tv.live.LiveSession.Listener() {
            @Override public void onReady() { main.post(() -> { banner.setCaption("Say something…"); setUiState("listening"); }); }
            @Override public void onHeard(String text) { main.post(() -> banner.setCaption("You: " + text)); }
            @Override public void onSpeaking(boolean on) { main.post(() -> { if (live != null) setUiState(on ? "speaking" : "listening"); }); }
            @Override public String onAct(String name, JSONObject args) {
                Session.Act a = actions.act(name, args);   // the one gate: same cards, same receipts
                main.post(() -> showNotice(a.say));
                return a.toModel;
            }
            @Override public void onEnded(ai.futurespeak.friday.tv.live.LiveRules.End how, String detail) { main.post(() -> liveEnded(how, detail)); }
        }, () -> banner.isAttachedToWindow() && banner.getVisibility() == View.VISIBLE);
        liveScreenOff = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                ai.futurespeak.friday.tv.live.LiveSession s = live;
                if (s != null) s.end(ai.futurespeak.friday.tv.live.LiveRules.End.SCREEN_OFF, "");
            }
        };
        registerReceiver(liveScreenOff, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF));
        Log.i(TAG, "live: started by a press (" + liveWho + ")");
        live.start();
        main.postDelayed(liveTick, 1000);
    }

    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            ai.futurespeak.friday.tv.live.LiveSession s = live;
            if (s == null) return;
            long now = System.currentTimeMillis();
            if (liveBanner != null) liveBanner.setElapsed(now - liveStartedAt, prefs.liveMinutes());
            if (++liveTicks % 5 == 0 && actions != null) background.execute(() -> { try { actions.refresh(); } catch (Throwable ignored) { } });
            ai.futurespeak.friday.tv.live.LiveRules.End e = ai.futurespeak.friday.tv.live.LiveRules.check(now, liveStartedAt, s.lastVoiceAt(),
                    prefs.liveMinutes(), liveKidsAtStart, actions != null && actions.kids());
            if (e != null) { s.end(e, ""); return; }
            main.postDelayed(this, 1000);
        }
    };

    private void liveEnded(ai.futurespeak.friday.tv.live.LiveRules.End how, String detail) {
        ai.futurespeak.friday.tv.live.LiveSession s = live;
        live = null;
        main.removeCallbacks(liveTick);
        if (liveScreenOff != null) { try { unregisterReceiver(liveScreenOff); } catch (Exception ignored) { } liveScreenOff = null; }
        if (liveBanner != null) { root.removeView(liveBanner); liveBanner = null; }
        long ms = System.currentTimeMillis() - liveStartedAt;
        liveReceipt(how.name().toLowerCase(java.util.Locale.ROOT), detail, ms, s == null ? -1 : s.tokens());
        Log.i(TAG, "live: ended " + how + " after " + ms / 1000 + " s");
        setUiState("idle");
        String words = ai.futurespeak.friday.tv.live.LiveRules.endWords(how)
                + (how == ai.futurespeak.friday.tv.live.LiveRules.End.ERROR && detail != null && !detail.isEmpty() ? " (" + detail + ")" : "");
        if (how == ai.futurespeak.friday.tv.live.LiveRules.End.LEFT_FRIDAY || how == ai.futurespeak.friday.tv.live.LiveRules.End.SCREEN_OFF) showNotice(words);
        else sayNotice(words);
    }

    /** One decision row at the start (live.start, origin remote) and its outcome at the end: duration, how it ended, estimated cost. */
    private String liveReceipt(String ended, String detail, long ms, long tokens) {
        ai.futurespeak.friday.tv.actions.TvActions a = actions;
        if (a == null) return null;
        try {
            if (ended == null) {
                JSONObject rec = new JSONObject().put("action", "live.start").put("args", new JSONObject().put("model", ai.futurespeak.friday.tv.live.LiveProtocol.MODEL)
                        .put("limit_min", prefs.liveMinutes())).put("tier", 2).put("origin", "remote").put("who_may", "ok")
                        .put("taint", false).put("policy", "announce");
                return a.receipts().decision(liveWho, rec);
            }
            if (liveReceiptId == null) return null;
            double usd = ai.futurespeak.friday.tv.live.LiveRules.estimate(ms);
            JSONObject extra = new JSONObject().put("ended", ended).put("seconds", ms / 1000)
                    .put("meter", new JSONObject().put("service", "gemini-live").put("units", "minutes").put("actual", Math.round(ms / 600.0) / 100.0)
                            .put("usd", usd).put("estimate", true).put("tokens", tokens));
            a.receipts().outcome(liveReceiptId, liveWho, "executed", "Live " + ai.futurespeak.friday.tv.live.LiveBanner.clock(ms) + ", ended: " + ended, extra);
        } catch (Exception e) { Log.w(TAG, "live receipt failed (" + e.getClass().getSimpleName() + ")"); }
        return null;
    }

    static String liveInstruction(boolean kids) {
        return "You are Gemini, made by Google, talking live with a family through their TV. Friday is the TV's assistant; "
                + "you are not Friday and not Claude, and you say so if asked. Keep answers short and spoken: no lists, no markdown. "
                + "You can use the TV's tools only when someone asks for that; the TV checks and may refuse, and you say what it said. "
                + (kids ? "Children are listening: be warm and simple, keep everything suitable for young children, and suggest asking a grown-up about anything scary, medical or unsafe. " : "")
                + "If someone says stop, stop.";
    }

    // ----------------------------------------------------------- grown-up PIN -----
    /**
     * Runs {@code action} once a grown-up is confirmed. One PIN for the TV, kept by Ad Break
     * Guard (safety/GrownUp); a correct PIN in either app opens a shared window that lasts until
     * the TV sleeps (at most 4 h), so channels and settings don't ask again. If Ad Break Guard
     * is installed but not answering, grown-up actions stay locked (fail closed).
     */
    void withGrownUp(String why, Runnable action) {
        GrownUp.Status s = app.grownUp().status();
        if (s.unavailable) { showNotice("The grown-up check isn't available right now, so this stays locked."); return; }
        if (s.unlocked) { action.run(); return; }
        if (!s.hasPin) {
            push(PinLayer.create(this, this, "One grown-up PIN protects settings, internet access and keys. " + why,
                    pin -> { String err = app.grownUp().create(pin); if (err != null) showNotice(err); else action.run(); }, null));
            return;
        }
        push(PinLayer.check(this, this, why + " (Stays unlocked until the TV sleeps.)", app.grownUp()::verify,
                pin -> action.run(), null));
    }

    /** Family mode unless a grown-up turned it off; always family if the grown-up check is unavailable. */
    SystemPrompt.Mode effectiveMode() {
        return app.grownUp().status().unavailable ? SystemPrompt.Mode.FAMILY : prefs.mode();
    }

    void changeLocalPin() {
        push(PinLayer.create(this, this, "Choose the new PIN.",
                pin -> { prefs.setPinHash(Pin.hash(pin)); showNotice("PIN changed."); refreshSettings(); }, null));
    }

    // --------------------------------------------------------------- settings -----
    SettingsScreen settingsScreen;

    void openSettings() { openSettings(null); }

    void openSettings(String page) {
        session.interrupt();
        settingsScreen = SettingsPages.build(this);
        push(settingsScreen);
        if (page != null) settingsScreen.open(page);
    }

    void refreshSettings() { if (settingsScreen != null && layers.contains(settingsScreen)) settingsScreen.rebuild(); }

    static final String PREVIEW = "Hi, I'm Friday. This is how I'll sound when I answer you.";

    String voiceName(String id) {
        for (String[] v : Prefs.VOICES) if (v[0].equals(id)) return v[1];
        return id;
    }

    String voiceSummary() {
        boolean noKey = !secure.has(SecureStore.ELEVENLABS);
        if (prefs.talkWithGemini()) return "Talk: Gemini Live (Google)";
        return (noKey ? "TV voice" : voiceName(prefs.elevenVoice()).split(" ·")[0])
                + " · " + Math.round(prefs.voiceVolume() * 100) + "%";
    }

    void preview() {
        if (voice == null) return;
        session.interrupt();
        voice.speak(PREVIEW, -1);
        voice.endOfReply();
    }

    /** Pinned channels first (in pin order), then the rest by age. */
    List<ChannelSpec> orderedChannels() {
        List<ChannelSpec> all = channels.list(), out = new ArrayList<>();
        for (String id : prefs.pinnedChannels()) for (ChannelSpec c : all) if (c.id.equals(id)) out.add(c);
        for (ChannelSpec c : all) if (!out.contains(c)) out.add(c);
        return out;
    }

    void openChannelFromSettings(ChannelSpec ch) {
        for (Layer l : new ArrayList<>(layers)) pop(l);
        openChannel(ch);
    }

    /** Is Friday the TV's screensaver? null when the TV won't say. */
    Boolean screensaverActive() {
        try {
            String v = android.provider.Settings.Secure.getString(getContentResolver(), "screensaver_components");
            if (v == null) return null;
            return v.contains(getPackageName() + "/");
        } catch (Exception e) { return null; }
    }

    void previewScreensaver() {
        push(new ai.futurespeak.friday.tv.ui.AmbientLayer(this, this,
                new ai.futurespeak.friday.tv.ambient.AmbientView(this, prefs, pinnedCards, channels)));
    }

    void confirmForget() {
        push(new ApprovalLayer(this, this, "Forget our conversation?", "Everything we've said is deleted from this TV. Channels are kept.",
                null, Arrays.asList(
                new ApprovalLayer.Choice("Forget it", Brand.DENY, true, () -> {
                    session.interrupt();
                    convo.clear();
                    history.forget();
                    replyText.setText("");
                    youText.setText("");
                    showNotice("Forgotten.");
                    refreshSettings();
                }),
                new ApprovalLayer.Choice("Keep it", Brand.NEUTRAL, true, null)), null));
    }

    void confirmRemoveKeys() {
        push(new ApprovalLayer(this, this, "Remove the keys from this TV?", "I'll stop working until a grown-up adds them again from the PC.",
                null, Arrays.asList(
                new ApprovalLayer.Choice("Remove keys", Brand.DENY, true, () -> {
                    secure.remove(SecureStore.ANTHROPIC);
                    secure.remove(SecureStore.ELEVENLABS);
                    makeVoice();
                    makeSpeech();
                    refreshSettings();
                }),
                new ApprovalLayer.Choice("Keep them", Brand.NEUTRAL, true, null)), null));
    }

    // ------------------------------------------------------------- lifecycle -----
    /** Launch with this action (or the extra) to start listening at once: for a remote button. */
    public static final String ACTION_LISTEN = "ai.futurespeak.friday.tv.action.LISTEN";
    public static final String EXTRA_LISTEN = "listen";
    public static final String EXTRA_SETTINGS = "settings_page";
    public static final String EXTRA_CHANNEL = "channel_id";
    private boolean listenOnResume = false;
    private String settingsOnResume = null;

    private void readIntent(android.content.Intent i) {
        if (i == null) return;
        boolean alias = i.getComponent() != null && i.getComponent().getClassName().endsWith(".Listen");
        if (ACTION_LISTEN.equals(i.getAction()) || i.getBooleanExtra(EXTRA_LISTEN, false) || alias) listenOnResume = true;
        if (i.getComponent() != null && i.getComponent().getClassName().endsWith(".ScreensaverSettings")) settingsOnResume = "screensaver";
        // From Friday Home (home/HomeActivity): a settings page, or one of Friday's channels. Taken once.
        String page = i.getStringExtra(EXTRA_SETTINGS);
        if (page != null) { settingsOnResume = page; i.removeExtra(EXTRA_SETTINGS); }
        String chId = i.getStringExtra(EXTRA_CHANNEL);
        if (chId != null) {
            i.removeExtra(EXTRA_CHANNEL);
            for (ChannelSpec ch : orderedChannels()) if (ch.id.equals(chId)) { main.postDelayed(() -> openChannel(ch), 400); break; }
        }
        // "Ask Friday about this" from an add-on (through the contract's ask) or the kids home. Taken once, so a recreate doesn't ask again.
        if (ACTION_ASK.equals(i.getAction())) {
            newsAsk = i.getStringExtra(EXTRA_ASK);
            newsAskLabel = i.getStringExtra(EXTRA_ASK_LABEL);
            setIntent(new android.content.Intent(i).setAction(null));
        }
    }
    private String newsAsk, newsAskLabel;
    /** Opens Friday with a question already asked. Not exported: only Friday's own code (and the add-on provider) starts it. */
    public static final String ACTION_ASK = "ai.futurespeak.friday.tv.action.ASK";
    public static final String EXTRA_ASK = "ask", EXTRA_ASK_LABEL = "ask_label";

    @Override protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        readIntent(i);
    }

    @Override protected void onResume() {
        super.onResume();
        current = this;
        // Kids slice 1: notice kids mode turning on (closes an open grown-up window first; receipted; AC-K3).
        new Thread(() -> {
            boolean k = ai.futurespeak.friday.tv.kids.KidsHelper.status(this).kids;
            main.post(() -> { if (k != kidsRail) { kidsRail = k; refreshTiles(); } });   // the rail follows kids mode
        }, "kids-status").start();
        if (ai.futurespeak.friday.tv.studio.Studio.get() != null) ai.futurespeak.friday.tv.studio.Studio.get().resumeUnfinished();   // Studio AC-S3
        if (speech == null) makeSpeech();
        if (settingsOnResume != null) {
            String pg = settingsOnResume;
            settingsOnResume = null;
            main.postDelayed(() -> { for (Layer l : new ArrayList<>(layers)) pop(l); openSettings(pg); }, 300);
        }
        if (listenOnResume) {
            listenOnResume = false;
            main.postDelayed(() -> { for (Layer l : new ArrayList<>(layers)) if (!l.blocksTalk()) pop(l); if (layers.isEmpty() && !listening) talkPress(null); }, 600);
        }
        if (newsAsk != null) {
            String q = newsAsk, label = newsAskLabel == null ? newsAsk : newsAskLabel;
            newsAsk = null;
            main.postDelayed(() -> {
                for (Layer l : new ArrayList<>(layers)) if (!l.blocksTalk()) pop(l);
                if (!layers.isEmpty() || listening) return;
                showYou(label, false);
                session.ask(q);
            }, 500);
        }
        if (actions != null) background.execute(() -> { try { actions.refresh(); } catch (Throwable ignored) { } });
        avatar.onResume();
        avatar.resumeTimers();
        if (stagePaused) avatar.onPause();   // Settings or a channel is still up over her
        avatar.setReduced(Motion.reduced(this));
    }

    @Override protected void onPause() {
        current = null;
        if (live != null) live.end(ai.futurespeak.friday.tv.live.LiveRules.End.LEFT_FRIDAY, "");   // T5: the mic closes first
        if (actions != null) actions.noteLeftFriday();   // S2: which app the TV goes to is the target for "pause"
        // Give the microphone and the recognizer back to the system every time we leave.
        if (speech != null) { speech.release(); speech = null; }
        listening = false;
        micPill.setVisibility(View.GONE);
        if (session != null) session.interrupt();
        avatar.onPause();
        avatar.pauseTimers();
        super.onPause();
    }

    @Override protected void onDestroy() {
        for (Layer l : new ArrayList<>(layers)) pop(l);
        if (speech != null) speech.release();
        if (voice != null) voice.release();
        avatar.destroy();
        super.onDestroy();
    }

    /** Version string without generating BuildConfig (kept off in this build). */
    static final class BuildConfigLite { static final String VERSION = "0.1"; }
}
