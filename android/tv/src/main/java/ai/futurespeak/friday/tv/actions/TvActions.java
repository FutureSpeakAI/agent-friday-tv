package ai.futurespeak.friday.tv.actions;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.futurespeak.friday.tv.Session;

/**
 * Slice S1 on the device: the catalogue of apps found on this TV, the real helper, the
 * receipts sealed with the Keystore, and the one {@link ActionGate}, joined up for
 * {@link Session}. Every proposal from the brain lands in {@link #act}, which only calls
 * the gate.
 */
public final class TvActions implements Session.Actions {
    private static final String TAG = "FridayActions";

    /** Whether a grown-up window is open (the shared PIN). */
    public interface GrownUpNow { boolean unlocked(); }

    private final Context ctx;
    private final HelperHands hands;
    private final Receipts receipts;
    private final GrownUpNow grownUp;
    private volatile Catalogue catalogue;
    private volatile boolean kids = true;   // fail closed until the helper has answered
    private final Web web;
    /** The spoken turn's taint (voice only; the remote and the PC each start clean). */
    private final ActionGate.Turn turn = new ActionGate.Turn();

    public TvActions(Context c, GrownUpNow grownUp) {
        ctx = c.getApplicationContext();
        this.grownUp = grownUp;
        hands = new HelperHands(ctx);
        receipts = new Receipts(ctx.getFilesDir(), new KeystoreSigner(), System::currentTimeMillis);
        catalogue = new Catalogue(probe(ctx));
        ai.futurespeak.friday.tv.secure.SecureStore store = new ai.futurespeak.friday.tv.secure.SecureStore(ctx);
        web = new FirecrawlWeb(() -> store.get(ai.futurespeak.friday.tv.secure.SecureStore.FIRECRAWL));
        images = new KieImages(() -> store.get(ai.futurespeak.friday.tv.secure.SecureStore.KIE));
        imageStore = new Images.Store(ctx.getFilesDir());
        studio = ai.futurespeak.friday.tv.studio.Studio.init(ctx, receipts);   // Studio slice 1
        player = new HelperPlayer(ctx, new KeyPlayer(ctx));                     // slice S2: the helper's reader, else a media key
        // Kids mode's channel filter: knows kids mode from here (fail closed), asks Claude with the kids model.
        ai.futurespeak.friday.tv.kids.KidsContent.kidsNow = this::kids;
        ai.futurespeak.friday.tv.kids.KidsContent.checker = new ai.futurespeak.friday.tv.kids.KidsSafetyCheck(
                () -> store.get(ai.futurespeak.friday.tv.secure.SecureStore.ANTHROPIC), ai.futurespeak.friday.tv.Prefs.fastModel());
    }

    private final ai.futurespeak.friday.tv.studio.Studio studio;

    // ---- Slice S2: playback and in-app search ------------------------------------------------
    private final Player player;
    private volatile String lastWatched;
    /** Launchers and Friday's own screens are never "what you were watching". */
    static final java.util.Set<String> NOT_WATCHING = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.google.android.apps.tv.launcherx", "com.spocky.projengmenu", "com.google.android.tvlauncher", "com.android.systemui",
            "org.tvprivacy.adbreak", "ai.futurespeak.friday.tv"));

    /** Friday's screen just went away: a moment later, note which app the TV is showing (the target for "pause"). */
    public void noteLeftFriday() {
        Thread t = new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { return; }
            Hands.Foreground f = hands.foreground();
            if (f != null && watchable(f.pkg)) lastWatched = f.pkg;
        }, "friday-left");
        t.setDaemon(true);
        t.start();
    }

    static boolean watchable(String pkg) { return pkg != null && !pkg.isEmpty() && !NOT_WATCHING.contains(pkg) && Catalogue.allowed(pkg); }

    /** Starts an app's own search (SearchRecipes) from Friday's process. Only the gate calls this. */
    private boolean startSearch(SearchRecipes.Recipe r, String query) {
        try {
            Intent i = r.isSearchAction()
                    ? new Intent(Intent.ACTION_SEARCH).putExtra(android.app.SearchManager.QUERY, query)
                    : new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(r.uri));
            i.setPackage(r.pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "search didn't start in " + r.pkg + " (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    /** Studio is offered when Claude is set up, and in kids mode only while a grown-up has unlocked. */
    boolean studioOffered() { return studio.ready() && (!kids || grownUp.unlocked()); }

    // ---- Slice T3: pictures ------------------------------------------------------------------
    private final Images images;
    private final Images.Store imageStore;
    private volatile ActionGate.Confirm confirm;
    private volatile Images.Progress progress;

    /** Friday's screen: where the fixed card asks, and where progress shows. Null when no screen is up. */
    public void setScreen(ActionGate.Confirm c, Images.Progress p) { confirm = c; progress = p; }

    public Images.Store imageStore() { return imageStore; }

    boolean imagesOffered() { return images.ready() && confirm != null && (!kids || grownUp.unlocked()); }

    /** Web tools are offered when the key is present, and in kids mode only while a grown-up has unlocked. */
    boolean webOffered() { return web.ready() && (!kids || grownUp.unlocked()); }

    @Override public void newTurn() { turn.reset(); }

    public Receipts receipts() { return receipts; }
    public Catalogue catalogue() { return catalogue; }
    public boolean kids() { return familyForTest ? false : kids; }

    /** Shell-only test switch (KeyImportProvider "familytest"): see the family layout on an emulator with no helper. Memory only. */
    private volatile boolean familyForTest;
    public void familyForTest(boolean on) { familyForTest = on; }

    /** Re-reads the installed apps and kids mode (on resume, and before each question). Off the main thread when possible. */
    public void refresh() {
        catalogue = new Catalogue(probe(ctx));
        kids = hands.kids();
    }

    /** The "what you can / cannot do" block for the prompt, generated from the catalogue (TV-E05). */
    public String promptBlock() {
        String can = catalogue.canDo(kids, webOffered(), imagesOffered(), studioOffered());
        String play = catalogue.canPlay(player.canRead(), kids);   // S2 lines, inside the same list
        int at = can.indexOf("- Use a tool only when");
        if (at > 0) can = can.substring(0, at) + play + can.substring(at);
        return can + "\n" + Catalogue.cannotDo();
    }

    @Override public JSONArray tools() {
        JSONArray t = catalogue.tools(webOffered(), imagesOffered(), studioOffered());
        JSONArray p = catalogue.playTools(player.canRead(), kids);
        for (int i = 0; i < p.length(); i++) t.put(p.opt(i));
        return t;
    }

    @Override public Session.Act act(String name, JSONObject input) {
        return toAct(gate(turn).withImages(images, imageStore, confirm, progress).decide(name, input, "voice"), name, input);
    }

    /** The remote's path (Apps rail): the same gate, the same receipts (remote parity). */
    public ActionGate.Verdict fromRemote(String name, JSONObject input) { return gate(new ActionGate.Turn()).decide(name, input, "remote"); }

    /** A Friday screen of its own (Wonder Owl's "Draw it") asking for a paid tool: the same gate and rules, with that screen's fixed card. */
    public ActionGate.Verdict fromRemote(String name, JSONObject input, ActionGate.Confirm onScreen) {
        return gate(new ActionGate.Turn()).withImages(images, imageStore, onScreen != null ? onScreen : confirm, progress).decide(name, input, "remote");
    }

    /** The PC over adb (shell-only test hook): the same gate and receipts, origin "desktop". */
    public ActionGate.Verdict fromShell(String name, JSONObject input) { return gate(new ActionGate.Turn()).decide(name, input, "desktop"); }

    /** The PC over adb, for a picture: the person's yes is the shell's explicit "approve" (the receipt says origin desktop). */
    public ActionGate.Verdict fromShell(String name, JSONObject input, boolean approve) {
        return gate(new ActionGate.Turn()).withImages(images, imageStore, a -> approve, progress).decide(name, input, "desktop");
    }

    private ActionGate gate(ActionGate.Turn t) {
        return new ActionGate(catalogue, hands, receipts, new ActionGate.Room() {
            @Override public boolean kids() { return hands.kids(); }   // asked fresh at decision time
            @Override public boolean grownUp() { return grownUp.unlocked(); }
        }, Thread::sleep, web, t).withStudio(studio).withPlayer(player, () -> lastWatched, this::startSearch);
    }

    static Session.Act toAct(ActionGate.Verdict v, String name, JSONObject input) {
        String app = input == null ? "" : input.optString("app", "");
        String note;
        switch (v.result) {
            case "verified": note = app.isEmpty() ? "[the TV did: " + name + "]" : "[the TV opened " + app + "]"; break;
            case "executed": note = "[the TV used " + name + "]"; break;
            case "unverified": note = name.contains("play") ? "[the TV sent " + (input == null ? "" : input.optString("command", "")) + "; not confirmed]"
                    : name.contains("search") ? "[the TV asked " + app + " to search; not confirmed]"
                    : "[the TV asked " + (app.isEmpty() ? name : app) + " to open; not confirmed]"; break;
            case "refused": note = "[the TV refused: " + name + (app.isEmpty() ? "" : " " + app) + "]"; break;
            default: note = "[the TV could not do: " + name + "]"; break;
        }
        Session.Act act = new Session.Act(v.say, v.toModel, v.leftFriday, note);
        act.card = v.card;   // a picture the TV made: shown by the TV itself
        return act;
    }

    /** Launchable apps (TV launcher first, then phone launcher), one per package, with their labels. */
    static List<Catalogue.App> probe(Context c) {
        Map<String, Catalogue.App> found = new LinkedHashMap<>();
        PackageManager pm = c.getPackageManager();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                    String pkg = r.activityInfo.packageName;
                    if (found.containsKey(pkg)) continue;
                    CharSequence label = r.loadLabel(pm);
                    if (label == null || label.toString().trim().isEmpty()) continue;
                    found.put(pkg, new Catalogue.App(pkg, label.toString().trim()));
                }
            } catch (Exception e) {
                Log.w(TAG, "app probe failed (" + e.getClass().getSimpleName() + ")");
            }
        }
        return new ArrayList<>(found.values());
    }
}
