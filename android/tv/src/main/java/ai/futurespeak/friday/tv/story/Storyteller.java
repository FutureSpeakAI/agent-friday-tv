package ai.futurespeak.friday.tv.story;

import android.content.Context;

import ai.futurespeak.friday.tv.FridayApp;
import ai.futurespeak.friday.tv.Prefs;
import ai.futurespeak.friday.tv.actions.KeystoreSigner;
import ai.futurespeak.friday.tv.actions.KieImages;
import ai.futurespeak.friday.tv.actions.Receipts;
import ai.futurespeak.friday.tv.actions.TvActions;
import ai.futurespeak.friday.tv.kids.KidsVoice;
import ai.futurespeak.friday.tv.secure.SecureStore;
import ai.futurespeak.friday.tv.studio.Prices;

import java.io.File;

/**
 * The Storyteller on this TV: one engine, wired to the app's sealed keys, Friday's
 * voice settings, the gate's receipts and the Keystore signer. Null when the TV can't keep
 * receipts (then Friday can't act, so she can't tell a priced story either).
 */
public final class Storyteller {
    private static volatile Storyteller instance;

    public static synchronized Storyteller get(Context c) {
        if (instance == null) {
            FridayApp app = (FridayApp) c.getApplicationContext();
            TvActions a = app.actions();
            if (a == null) return null;
            instance = new Storyteller(app, a.receipts());
        }
        return instance;
    }

    private final FridayApp app;
    private final File root;
    private final StoryEngine engine;
    private final Shelf shelf;
    private final NightGrant night;
    private final SecureStore store;
    private final Eleven eleven;
    private final TvVoice tvVoice;

    private Storyteller(FridayApp app, Receipts receipts) {
        this.app = app;
        root = new File(app.getFilesDir(), "story");
        store = app.secure();
        Prefs prefs = app.prefs();
        KeystoreSigner signer = new KeystoreSigner();
        shelf = new Shelf(root, signer);
        night = new NightGrant(root, signer);
        File tmp = new File(app.getCacheDir(), "story");
        float speed = KidsVoice.speed(prefs.voiceSpeed(), true);
        eleven = new Eleven(() -> store.get(SecureStore.ELEVENLABS), prefs.elevenVoice(), prefs.elevenModel(), speed, tmp);
        tvVoice = new TvVoice(app, tmp, speed);
        Seams.Speaker fridayVoice = Prefs.TV_VOICE.equals(prefs.elevenVoice()) || !Prefs.elevenVoiceOn() ? tvVoice : eleven.speaker();
        Prices prices = Prices.load(new File(app.getFilesDir(), "studio" + File.separator + "prices.json"));
        engine = new StoryEngine(root, new StoryClaude(() -> store.get(SecureStore.ANTHROPIC), Prefs.fastModel()),
                fridayVoice, tvVoice, new KieImages(() -> store.get(SecureStore.KIE)), a(app).imageStore(),
                new SoundPack(root, eleven.sounds()), new MusicBeds(root, eleven.music()), eleven.designer(),
                receipts, prices, System::currentTimeMillis);
        engine.setPoster(r -> new android.os.Handler(android.os.Looper.getMainLooper()).post(r));
        StoryEngine.setLogger((w, m) -> { if (w) android.util.Log.w("FridayStory", m); else android.util.Log.i("FridayStory", m); });
    }

    private static TvActions a(FridayApp app) { return app.actions(); }

    public StoryEngine engine() { return engine; }
    public Shelf shelf() { return shelf; }
    public NightGrant night() { return night; }
    public File root() { return root; }

    public boolean haveClaude() { String k = store.get(SecureStore.ANTHROPIC); return k != null && !k.isEmpty(); }
    public boolean haveEleven() { return eleven.ready(); }
    public boolean haveKie() { String k = store.get(SecureStore.KIE); return k != null && !k.isEmpty(); }
    public boolean fridayVoiceIsEleven() { return haveEleven() && !Prefs.TV_VOICE.equals(app.prefs().elevenVoice()); }

    /** The room right now (spec §5): kids from the helper (fail closed), the grown-up window, the story night. */
    public StoryGate.Room room(boolean kids) {
        boolean grownUp = false;
        try { grownUp = app.grownUp().status().unlocked; } catch (Throwable ignored) { }
        return new StoryGate.Room(kids, grownUp, night.open(System.currentTimeMillis()), haveClaude(), haveEleven(), haveKie());
    }
}
