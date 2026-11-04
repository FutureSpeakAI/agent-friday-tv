package ai.futurespeak.friday.tv;

import android.content.Intent;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ai.futurespeak.friday.tv.brain.ClaudeDirect;
import ai.futurespeak.friday.tv.channels.ChannelSpec;
import ai.futurespeak.friday.tv.prompt.SystemPrompt;
import ai.futurespeak.friday.tv.safety.GrownUp;
import ai.futurespeak.friday.tv.secure.SecureStore;
import ai.futurespeak.friday.tv.ui.ApprovalLayer;
import ai.futurespeak.friday.tv.ui.Brand;
import ai.futurespeak.friday.tv.ui.Motion;
import ai.futurespeak.friday.tv.ui.RenameLayer;
import ai.futurespeak.friday.tv.ui.SettingsPage;
import ai.futurespeak.friday.tv.ui.SettingsScreen;

/**
 * Friday's settings pages (Voice, Picture, Screensaver, Listening, Channels, Privacy,
 * Family & PIN, About). Everything a family member may change is open; anything that opens
 * the internet, changes the safety mode, the model or the keys needs the grown-up PIN.
 */
final class SettingsPages {
    private SettingsPages() {}

    static final String[] TIER_NAMES = {"Full detail", "Full detail, softer", "Balanced", "Light", "Lightest"};

    static SettingsScreen build(MainActivity a) {
        List<SettingsScreen.Section> s = new ArrayList<>();
        s.add(new SettingsScreen.Section("voice", "🎙", "Voice", a::voiceSummary, p -> voice(a, p)));
        s.add(new SettingsScreen.Section("picture", "🖼", "Picture", () -> qualityName(a.prefs.quality()) + " · " + structureName(a.prefs.structure()), p -> picture(a, p)));
        s.add(new SettingsScreen.Section("screensaver", "🌌", "Screensaver", () -> screensaverSummary(a), p -> screensaver(a, p)));
        s.add(new SettingsScreen.Section("listening", "👂", "Listening", () -> (a.prefs.talkMode() == Prefs.TalkMode.TAP ? "Tap to talk" : "Hold to talk") + " · " + sttName(a), p -> listening(a, p)));
        s.add(new SettingsScreen.Section("channels", "📺", "Channels", () -> channelsSummary(a), p -> channels(a, p)));
        s.add(new SettingsScreen.Section("apps", "▦", "Apps", () -> appsSummary(a), p -> apps(a, p)));
        s.add(new SettingsScreen.Section("did", "🧾", "What Friday did", () -> didSummary(a), p -> did(a, p)));
        s.add(new SettingsScreen.Section("privacy", "🛡", "Privacy", () -> a.convo.size() + " messages kept on this TV", p -> privacy(a, p)));
        s.add(new SettingsScreen.Section("family", "👪", "Family & PIN", () -> familySummary(a), p -> family(a, p)));
        s.add(new SettingsScreen.Section("about", "✦", "About & keys", () -> modelName(a.prefs.model()), p -> about(a, p)));
        return new SettingsScreen(a, a, s, "Agent Friday™ by FutureSpeak.AI™\nPowered by Claude · v" + MainActivity.BuildConfigLite.VERSION);
    }

    private static boolean locked(MainActivity a) { return !a.app.grownUp().status().unlocked; }

    // ---- Voice ----------------------------------------------------------------------------
    static void voice(MainActivity a, SettingsPage p) {
        Prefs pr = a.prefs;
        boolean noKey = !a.secure.has(SecureStore.ELEVENLABS);
        p.header("Voice", "How Friday sounds", "Pick a voice and she says hello in it. Changes are kept on this TV.");
        // T5 as a voice option (2026-11-02): no PIN. With Gemini Live chosen, Talk and the mic button start and end a live conversation.
        p.card("Who you talk to");
        boolean gem = pr.talkWithGemini();
        boolean gemKey = a.secure.has(SecureStore.GEMINI);
        p.choice("talkwith", "Talk button", gem
                        ? "Gemini Live (Google): Talk starts a live conversation; Talk or Back ends it. Your voice goes to Google while the “Google is listening” banner is up. About $" + ai.futurespeak.friday.tv.live.LiveRules.USD_PER_MINUTE + " a minute."
                        : "Friday: she listens, answers with " + a.answererName() + " and speaks in the voice below." + (gemKey ? "" : " Gemini Live needs a Google Gemini key on this TV."),
                new String[]{"Friday", "Gemini Live (Google)"}, gem ? 1 : 0,
                i -> { pr.setTalkWithGemini(i == 1); a.refreshSettings(); });
        int[] mins = {0, 5, 10, 15, 20, 30};
        int at = 0;
        for (int i = 0; i < mins.length; i++) if (mins[i] == pr.liveMinutes()) at = i;
        p.choice("livemins", "Live time limit", "Off: it ends when you press Talk or Back, after a minute of quiet, or when the TV sleeps.",
                new String[]{"Off", "5 min", "10 min", "15 min", "20 min", "30 min"}, at,
                i -> { pr.setLiveMinutes(mins[i]); a.refreshSettings(); });
        p.card("Voice");
        String[][] items = new String[Prefs.VOICES.length][];
        int sel = 0;
        for (int i = 0; i < Prefs.VOICES.length; i++) {
            String[] parts = Prefs.VOICES[i][1].split(" · ", 2);
            items[i] = new String[]{parts[0], parts.length > 1 ? parts[1] : ""};
            if (Prefs.VOICES[i][0].equals(pr.elevenVoice())) sel = i;
        }
        p.tiles("voice", items, sel, 3, i -> {
            pr.setElevenVoice(Prefs.VOICES[i][0]);
            a.makeVoice();
            a.preview();
        });
        if (noKey) p.note("There's no ElevenLabs key on this TV, so Friday uses the TV voice until a grown-up adds one.");
        p.card("Delivery");
        float sp = pr.voiceSpeed();
        p.choice("speed", "Speaking speed", null, new String[]{"Slower", "Normal", "Faster"}, sp < 0.95f ? 0 : sp > 1.05f ? 2 : 1, i -> {
            float v = i == 0 ? 0.85f : i == 2 ? 1.15f : 1.0f;
            pr.setVoiceSpeed(v);
            a.voice.setSpeed(v);
        });
        int vol = Math.round(pr.voiceVolume() * 100);
        p.stepper("volume", "Voice volume", "Separate from the TV's volume.", vol + "%", pr.voiceVolume(),
                () -> { float v = Math.max(0.1f, Math.round(pr.voiceVolume() * 10 - 1) / 10f); pr.setVoiceVolume(v); a.voice.setVolume(v); },
                () -> { float v = Math.min(1f, Math.round(pr.voiceVolume() * 10 + 1) / 10f); pr.setVoiceVolume(v); a.voice.setVolume(v); });
        p.toggle("captions", "Captions", "Show her words on screen while she speaks.", pr.captions(), () -> pr.setCaptions(!pr.captions()));
        p.locked(locked(a)).toggle("spoken", "Spoken replies", "Off means captions only.", pr.voiceOn(),
                () -> a.withGrownUp("Turn spoken replies on or off.", () -> { pr.setVoiceOn(!pr.voiceOn()); a.refreshSettings(); }));
        p.action("test", "Test voice", "“Hi, I'm Friday…”", "▶  Play", Brand.CYAN, a::preview);
    }

    // ---- Picture ----------------------------------------------------------------------------
    static String qualityName(String q) { return q.equals("ultra") ? "Ultra" : q.equals("high") ? "High" : "Auto"; }
    static String structureName(int i) { return MainActivity.STRUCTURE_NAMES[Math.max(0, Math.min(i, MainActivity.STRUCTURE_NAMES.length - 1))]; }

    static void picture(MainActivity a, SettingsPage p) {
        Prefs pr = a.prefs;
        p.header("Picture", "Friday's look", "Auto keeps her smooth on this TV. High and Ultra are sharper but can stutter.");
        p.card("Quality");
        String q = pr.quality();
        p.choice("quality", "Picture quality", null, new String[]{"Auto", "High", "Ultra"}, q.equals("ultra") ? 2 : q.equals("high") ? 1 : 0, i -> {
            String next = i == 2 ? "ultra" : i == 1 ? "high" : "auto";
            pr.setQuality(next);
            a.avatar.setQuality(next);
        });
        int t = pr.avatarTier();
        p.value("This TV", "What Auto settled on after measuring; she starts there next time.",
                t < 0 ? "Measuring…" : TIER_NAMES[t], Brand.CYAN);
        Prefs.Motion m = pr.motion();
        p.choice("motion", "Reduce motion", "Auto follows the TV's accessibility setting (now " + (Motion.systemReduced(a) ? "on" : "off") + ").",
                new String[]{"Auto", "On", "Off"}, m == Prefs.Motion.ON ? 1 : m == Prefs.Motion.OFF ? 2 : 0, i -> {
                    pr.setMotion(i == 1 ? Prefs.Motion.ON : i == 2 ? Prefs.Motion.OFF : Prefs.Motion.AUTO);
                    a.avatar.setReduced(Motion.reduced(a));
                });
        p.card("Friday's form");
        String[][] items = new String[MainActivity.STRUCTURE_NAMES.length][];
        for (int i = 0; i < items.length; i++) items[i] = new String[]{MainActivity.STRUCTURE_NAMES[i], "Form " + (i + 1)};
        p.tiles("look", items, pr.structure(), 3, i -> { pr.setStructure(i); a.avatar.setStructure(i); });
    }

    // ---- Screensaver ---------------------------------------------------------------------------
    static String screensaverSummary(MainActivity a) {
        Boolean active = a.screensaverActive();
        if (Boolean.FALSE.equals(active)) return "Not chosen as the TV's screensaver";
        List<String> parts = new ArrayList<>();
        if (a.prefs.ssClock()) parts.add("clock");
        if (a.prefs.ssWeather()) parts.add("weather");
        int n = a.pinnedCards.list().size() + a.prefs.ssChannels().size();
        if (n > 0) parts.add(n + " shown");
        return parts.isEmpty() ? "Constellation only" : String.join(" · ", parts);
    }

    static void screensaver(MainActivity a, SettingsPage p) {
        Prefs pr = a.prefs;
        p.header("Screensaver", "Friday's ambient screen", "When the TV is idle it shows Friday's constellation with the time, the weather, Friday News and the cards and channels you pick. Everything drifts slowly so nothing burns in.");
        p.card("The TV's screensaver");
        Boolean active = a.screensaverActive();
        p.value("Friday as the screensaver", null, active == null ? "Choose it in the TV's settings" : active ? "On" : "Not chosen", active != null && active ? Brand.OK : Brand.DIM);
        p.action("preview", "Preview", "See it now. Any key returns here.", "▶  Preview", Brand.CYAN, a::previewScreensaver);
        p.action("tvsettings", "TV screensaver settings", "Where the TV picks its screensaver and when it starts.", "Open", Brand.CYAN, () -> {
            try { a.startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS)); }
            catch (Exception e) { a.showNotice("This TV doesn't open its screensaver settings from apps. Find them under Settings, System, Ambient mode."); }
        });
        p.card("Friday News");
        boolean newsReady = ai.futurespeak.friday.tv.ambient.AmbientSources.news != null;
        p.toggle("news", "Friday News", newsReady ? "Headlines from your news sections, with their pictures." : "Arrives with the next Friday News update.",
                pr.ssNews(), () -> pr.setSsNews(!pr.ssNews()));
        p.choice("mix", "Mix", "How often news comes up compared with your pinned cards and channels.",
                new String[]{"Mostly news", "Even", "Mostly cards"}, pr.ssMix(), pr::setSsMix);
        p.card("What it shows");
        p.toggle("clock", "Clock and date", null, pr.ssClock(), () -> pr.setSsClock(!pr.ssClock()));
        p.toggle("weather", "Local weather", Place.isSet() ? "From open-meteo.com, refreshed every 20 minutes." : "Choose a place in the PC setup to see it.", pr.ssWeather(), () -> pr.setSsWeather(!pr.ssWeather()));
        int pinned = a.pinnedCards.list().size();
        p.toggle("cards", "Pinned cards", pinned == 0 ? "None yet: press OK on any card Friday shows to pin it." : pinned + " pinned. Press OK on a card to pin or unpin it.",
                pr.ssCards(), () -> pr.setSsCards(!pr.ssCards()));
        p.value("Timers and reminders", null, "Shown here once timers arrive", Brand.FAINT);
        List<ChannelSpec> chs = a.channels.list();
        if (!chs.isEmpty()) {
            p.card("Channels in the screensaver");
            List<String> on = pr.ssChannels();
            for (ChannelSpec ch : chs) {
                boolean in = on.contains(ch.id);
                p.toggle("ssch-" + ch.id, ch.emoji + "  " + ch.title, ch.grantedHosts.isEmpty() ? "Works offline" : "Live data from its approved sites", in, () -> {
                    List<String> l = new ArrayList<>(pr.ssChannels());
                    if (l.contains(ch.id)) l.remove(ch.id); else l.add(ch.id);
                    pr.setSsChannels(l);
                });
            }
        }
        p.card("Look");
        p.choice("layout", "Layout", null, new String[]{"Drifting", "Clock corner", "Centred"}, pr.ssLayout(), pr::setSsLayout);
        int r = pr.ssRotateSec();
        p.choice("rotate", "Change every", null, new String[]{"20 s", "40 s", "90 s"}, r == 20 ? 0 : r == 90 ? 2 : 1, i -> pr.setSsRotateSec(i == 0 ? 20 : i == 2 ? 90 : 40));
        p.choice("dim", "Brightness", "Night is the gentlest on the screen and on your eyes.", new String[]{"Full", "Dimmed", "Night"}, pr.ssDim(), pr::setSsDim);
    }

    // ---- Listening -----------------------------------------------------------------------------
    static String sttName(MainActivity a) {
        switch (a.sttRoute) {
            case ON_DEVICE: case SERVICE_OFFLINE: return "on this TV";
            case CLOUD: return "Google online";
            default: return "unavailable";
        }
    }

    static void listening(MainActivity a, SettingsPage p) {
        Prefs pr = a.prefs;
        p.header("Listening", "How Friday hears you", "The microphone opens only after you press Talk, and closes when you stop.");
        p.card("Talking");
        p.choice("talk", "Talk button", pr.talkMode() == Prefs.TalkMode.TAP ? "Press once, speak; she stops listening when you pause." : "Hold while you speak; let go to send.",
                new String[]{"Tap to talk", "Hold to talk"}, pr.talkMode() == Prefs.TalkMode.TAP ? 0 : 1, i -> {
                    pr.setTalkMode(i == 0 ? Prefs.TalkMode.TAP : Prefs.TalkMode.HOLD);
                    a.makeSpeech();
                });
        int ms = pr.listenPauseMs();
        p.choice("pause", "Pause that ends a question", "Patient suits slower talkers and children.", new String[]{"Quick", "Normal", "Patient"},
                ms <= 800 ? 0 : ms >= 1800 ? 2 : 1, i -> { pr.setListenPauseMs(i == 0 ? 800 : i == 2 ? 1800 : 1100); a.makeSpeech(); });
        p.card("Speech recognition");
        ai.futurespeak.friday.tv.audio.OfflineSpeech.Route r = a.sttRoute;
        boolean ok = ai.futurespeak.friday.tv.audio.OfflineSpeech.offline(r);
        p.value("Where your voice goes", ai.futurespeak.friday.tv.audio.OfflineSpeech.explain(r),
                ok ? "✓ Stays on this TV" : r == ai.futurespeak.friday.tv.audio.OfflineSpeech.Route.CLOUD ? "Google online" : "Can't listen",
                ok ? Brand.OK : Brand.WARN);
        // design decision, (2026-11-02): Friday's own on-TV recognizer replaces Google's offline pack (its download never completes here).
        ai.futurespeak.friday.tv.audio.OnTvModel.Status ms2 = ai.futurespeak.friday.tv.audio.OnTvModel.check(a.getFilesDir(), false);
        p.value("On-TV speech", ms2.ready() ? "Friday's own recognizer: offline, no Google, and your voice never leaves the TV."
                        : "Not on this TV yet. A grown-up copies it from the PC once (the setup's speech step); until then Friday uses Google's recognizer.",
                ms2.line().replace("On-TV speech: ", ""), ms2.ready() ? Brand.OK : Brand.DIM);
        if (ms2.ready()) p.choice("sttengine", "Recognizer", pr.sttOnTv() ? "Friday's own, on this TV." : "Google's recognizer (may send your voice to Google).",
                new String[]{"On this TV (Friday)", "Google"}, pr.sttOnTv() ? 0 : 1, i -> { pr.setSttOnTv(i == 0); a.makeSpeech(); a.refreshSettings(); });
        boolean ontv = r == ai.futurespeak.friday.tv.audio.OfflineSpeech.Route.ON_TV;
        p.fact("voice", ontv ? "Your voice stays here" : "No recordings", ontv
                ? "Friday listens with her own model, inside this app on this TV. Nothing is recorded or kept, and only the words of your question go to " + a.answererName() + "."
                : "Friday never keeps a recording. The TV's recognizer turns speech into words, and only the words go to " + a.answererName() + ".");
        p.value("“Hey Friday”", "An on-device wake word, off by default.", "Coming later", Brand.FAINT);
    }

    // ---- Channels --------------------------------------------------------------------------------
    static String channelsSummary(MainActivity a) {
        int n = a.channels.list().size(), pins = a.prefs.pinnedChannels().size();
        return n == 0 ? "None yet" : n + " saved" + (pins > 0 ? " · " + pins + " pinned" : "");
    }

    static void channels(MainActivity a, SettingsPage p) {
        Prefs pr = a.prefs;
        List<ChannelSpec> all = a.orderedChannels();
        p.header("Channels", "Your channels", "Mini-apps Friday made for you. Pinned ones come first on the rail. Deleting needs the grown-up PIN.");
        if (all.isEmpty()) {
            p.card(null).fact("channels", "No channels yet", "Ask Friday for one: “make me a news channel” or “a chore chart for the kids”.");
            return;
        }
        p.card(null);
        for (ChannelSpec ch : all) {
            boolean pinned = pr.pinnedChannels().contains(ch.id);
            String hint = (pinned ? "📌 Pinned · " : "") + (ch.grantedHosts.isEmpty() ? "Works offline" : "Reaches " + ch.grantedHosts.size() + " approved site" + (ch.grantedHosts.size() == 1 ? "" : "s"));
            p.buttons("ch-" + ch.id, ch.emoji + "  " + ch.title, hint,
                    new String[]{"Open", "Rename", pinned ? "Unpin" : "Pin", "Delete"},
                    new int[]{Brand.CYAN, Brand.CYAN, Brand.CYAN, Brand.DENY},
                    new Runnable[]{
                            () -> a.openChannelFromSettings(ch),
                            () -> a.push(new RenameLayer(a, a, "Rename “" + ch.title + "”", ch.title, name -> {
                                try { a.channels.rename(ch.id, name); a.refreshTiles(); a.refreshSettings(); a.showNotice("Renamed to “" + name + "”."); }
                                catch (IOException e) { a.showNotice("Couldn't rename it."); }
                            })),
                            () -> {
                                List<String> l = new ArrayList<>(pr.pinnedChannels());
                                if (l.contains(ch.id)) l.remove(ch.id); else l.add(ch.id);
                                pr.setPinnedChannels(l);
                                a.refreshTiles();
                            },
                            () -> a.withGrownUp("Delete “" + ch.title + "”.", () -> a.push(new ApprovalLayer(a, a, "Delete “" + ch.title + "”?",
                                    "Its saved data goes too. This can't be undone.", null, Arrays.asList(
                                    new ApprovalLayer.Choice("Delete", Brand.DENY, true, () -> {
                                        a.channels.delete(ch.id);
                                        List<String> l = new ArrayList<>(pr.pinnedChannels()); l.remove(ch.id); pr.setPinnedChannels(l);
                                        List<String> s = new ArrayList<>(pr.ssChannels()); s.remove(ch.id); pr.setSsChannels(s);
                                        a.refreshTiles();
                                        a.refreshSettings();
                                        a.showNotice("Deleted “" + ch.title + "”.");
                                    }),
                                    new ApprovalLayer.Choice("Keep it", Brand.NEUTRAL, true, null)), null)))});
        }
        p.note("A channel can reach only the sites a grown-up approved, and never the TV, other apps, settings or keys.");
    }

    // ---- Apps (slice S1, remote parity: the same gate and receipts as asking by voice) ----------------
    static String appsSummary(MainActivity a) {
        ai.futurespeak.friday.tv.actions.TvActions ac = a.actions;
        if (ac == null) return "Unavailable";
        return ac.kids() ? "Kids mode · kids apps only" : ac.catalogue().apps().size() + " apps";
    }

    static void apps(MainActivity a, SettingsPage p) {
        ai.futurespeak.friday.tv.actions.TvActions ac = a.actions;
        p.header("Apps", "Open an app", "The same as asking Friday: she opens it, checks it really came to the front, and keeps a receipt. Back on the remote returns.");
        if (ac == null) {
            p.card(null).fact("key", "Friday can't open apps right now", "She keeps a sealed receipt of everything she does, and the TV's key store isn't available for that.");
            return;
        }
        boolean kids = ac.kids();
        List<ai.futurespeak.friday.tv.actions.Catalogue.App> list = new ArrayList<>();
        for (ai.futurespeak.friday.tv.actions.Catalogue.App app : ac.catalogue().apps())
            if (!kids || ai.futurespeak.friday.tv.actions.Catalogue.KIDS_APPS.contains(app.pkg)) list.add(app);
        p.card(kids ? "Kids mode: the kids apps" : null);
        if (list.isEmpty()) p.fact("channels", "No apps to open", kids ? "Only the kids list can open in kids mode." : "Friday didn't find any apps she may open.");
        for (ai.futurespeak.friday.tv.actions.Catalogue.App app : list)
            p.action("app-" + app.pkg, app.label, null, "Open", Brand.CYAN, () -> a.actFromRemote("app.open", app.label));
        p.note("Settings, app stores, Ad Break Guard and installers are never on this list, by voice or by remote.");
    }

    // ---- What Friday did (the receipts, spec §8.6) ---------------------------------------------------
    static String didSummary(MainActivity a) {
        ai.futurespeak.friday.tv.actions.TvActions ac = a.actions;
        if (ac == null) return "Unavailable";
        List<ai.futurespeak.friday.tv.actions.Receipts.Entry> r = ac.receipts().recent(1);
        return r.isEmpty() ? "Nothing yet" : "Last: " + didTitle(r.get(0)) + " · " + ago(r.get(0).ts);
    }

    static String didTitle(ai.futurespeak.friday.tv.actions.Receipts.Entry e) {
        String action = e.decision.optString("action"), app = "";
        JSONObject args = e.decision.optJSONObject("args");
        if (args != null) app = args.optString("app", "");
        switch (action) {
            case "app.open": return "Open " + (app.isEmpty() ? "an app" : app);
            case "app.back": return "Go back";
            case "web.search": return "Search the web: “" + (args == null ? "" : args.optString("query", "")) + "”";
            case "web.read": return "Read " + (args == null ? "a page" : ai.futurespeak.friday.tv.actions.Web.host(args.optString("url", "")));
            case "brain.reply": return "Answered by " + (args == null ? "OpenRouter" : args.optString("name", "OpenRouter"));
            case "brain.fallback": return "Switched to " + (args == null ? "Claude" : args.optString("to", "Claude")) + " (" + (args == null ? "" : args.optString("from", "")) + " didn't answer)";
            case "image.create": return "Make a picture: “" + (args == null ? "" : args.optString("title", args.optString("prompt", ""))) + "”";
            case "studio.make": return "Make a podcast" + (args == null || args.optString("topic", "").isEmpty() ? "" : ": “" + args.optString("topic", "") + "”");
            case "live.start": return "Live conversation with Google's Gemini";
            case "play.control": return Character.toUpperCase(args == null ? 'P' : args.optString("command", "play").isEmpty() ? 'P' : args.optString("command").charAt(0)) + (args == null ? "lay" : args.optString("command", "play").substring(1)) + (args == null || args.optString("app", "").isEmpty() ? "" : " in " + args.optString("app"));
            case "play.now": return "Checked what's playing";
            case "app.search": return "Search " + (args == null ? "an app" : args.optString("app", "an app")) + " for “" + (args == null ? "" : args.optString("query", "")) + "”";
            default: return "“" + action + "” (not something Friday can do)";
        }
    }

    static String didState(String state) {
        switch (state) {
            case "verified": return "✓ Done, checked";
            case "executed": return "✓ Done";
            case "unverified": return "Asked, couldn't check";
            case "refused": return "Refused";
            case "failed": return "Didn't work";
            case "pending": return "In progress";
            default: return "Not confirmed";
        }
    }

    static String originName(String o) {
        return "voice".equals(o) ? "asked by voice" : "remote".equals(o) ? "from the remote" : "desktop".equals(o) ? "from the PC" : o;
    }

    static String ago(long ts) {
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return s / 60 + " min ago";
        if (s < 86400) return s / 3600 + " h ago";
        return new java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(new java.util.Date(ts));
    }

    static void did(MainActivity a, SettingsPage p) {
        ai.futurespeak.friday.tv.actions.TvActions ac = a.actions;
        p.header("What Friday did", "Every action, with a receipt", "Each time Friday is asked to do something on the TV, she writes down the decision before acting and the result after. The list can't be quietly changed.");
        if (ac == null) { p.card(null).fact("key", "No receipts available", "The TV's key store isn't available, so Friday isn't acting at all."); return; }
        ai.futurespeak.friday.tv.actions.Receipts.Check c = ac.receipts().verify();
        p.card("The record");
        p.value("Checked just now", c.ok ? "Every entry matches its seal, in order, with nothing missing." : "Something changed: " + c.reason + ".",
                c.ok ? "✓ Intact · " + c.rows + " entries" : "⚠ Broken at " + c.brokenAtSeq, c.ok ? Brand.OK : Brand.DENY);
        // Spend, from the receipts alone.
        double credits = 0; int uses = 0;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0);
        for (ai.futurespeak.friday.tv.actions.Receipts.Entry e : ac.receipts().recent(500)) {
            if (e.ts < cal.getTimeInMillis() || e.outcome == null) continue;
            JSONObject m = e.outcome.optJSONObject("meter");
            if (m != null && m.has("actual")) { credits += m.optDouble("actual", 0); uses++; }
        }
        p.value("Web today", uses == 0 ? "No web searches or pages today." : uses + " use" + (uses == 1 ? "" : "s") + " of Firecrawl, from the receipts.",
                uses == 0 ? "None" : (Math.abs(credits - Math.round(credits)) < 0.05 ? String.valueOf(Math.round(credits)) : String.format(java.util.Locale.US, "%.1f", credits)) + " credits · about $" + String.format(java.util.Locale.US, "%.2f", credits * ai.futurespeak.friday.tv.actions.Web.USD_PER_CREDIT),
                Brand.DIM);
        double studioUsd = ai.futurespeak.friday.tv.studio.Studio.todayUsd(ac.receipts().recent(500), cal.getTimeInMillis());   // Studio, AC-S2
        p.value("Studio today", studioUsd == 0 ? "No podcasts made today." : "What the pieces made today cost, from the receipts.",
                studioUsd == 0 ? "None" : "$" + String.format(java.util.Locale.US, "%.2f", studioUsd), Brand.DIM);
        List<ai.futurespeak.friday.tv.actions.Receipts.Entry> recent = ac.receipts().recent(20);
        p.card("Recent");
        if (recent.isEmpty()) p.fact("history", "Nothing yet", "Ask “open Plex”, or use Apps. Each try shows up here, including the ones Friday refused.");
        for (ai.futurespeak.friday.tv.actions.Receipts.Entry e : recent) {
            String state = e.state;
            int color = "verified".equals(state) || "executed".equals(state) ? Brand.OK : "refused".equals(state) || "failed".equals(state) ? Brand.DENY : Brand.WARN;
            String why = e.outcome == null ? "" : e.outcome.optString("detail", "");
            p.value(didTitle(e), originName(e.decision.optString("origin")) + " · " + ago(e.ts) + (why.isEmpty() || "verified".equals(state) ? "" : " · " + why),
                    didState(state), color);
        }
        p.note("To undo opening an app, press Back on the remote while it's open, or ask Friday to go back. Receipts stay on this TV.");
    }

    // ---- Privacy ----------------------------------------------------------------------------------
    static void privacy(MainActivity a, SettingsPage p) {
        p.header("Privacy", "What stays on this TV", "Friday is built to keep the family's things here, not in the cloud.");
        p.card("Conversation history");
        p.action("forget", "Forget our conversation", a.convo.size() + " messages kept. Channels and settings stay.", "Forget…", Brand.DENY, a::confirmForget);
        p.card("How Friday protects you");
        p.fact("voice", "Microphone only while you talk", "It opens after a Talk press, the green pill shows it is on, and it closes when you stop. Recordings are never kept.");
        p.fact("chart", "No analytics, no ads, no tracking", "The app sends nothing about how it's used. Only the words of your questions go to " + a.answererName() + ". Your voice is recognized on the TV when its offline English pack is installed, otherwise by Google (Settings, Listening says which).");
        p.fact("history", "History on this TV", "Our conversation is saved here so Friday can follow along, and nowhere else.");
        p.fact("key", "Keys sealed by the TV", "Every key is encrypted with the TV's own key store, never in the app.");
        p.fact("history", "A receipt for every action", "Opening an app, by voice or remote, is written down before and after, sealed, and listed under What Friday did.");
        p.fact("globe", "Channels in a sandbox", "Each channel reaches only the sites a grown-up approved.");
    }

    // ---- Family & PIN -------------------------------------------------------------------------------
    static String familySummary(MainActivity a) {
        GrownUp.Status s = a.app.grownUp().status();
        return (a.prefs.mode() == SystemPrompt.Mode.FAMILY ? "Family mode" : "Grown-up mode") + " · " + (s.unavailable ? "check unavailable" : s.unlocked ? "unlocked" : "locked");
    }

    static void family(MainActivity a, SettingsPage p) {
        Prefs pr = a.prefs;
        GrownUp.Status s = a.app.grownUp().status();
        p.header("Family & PIN", "Safe for everyone", "Family mode keeps Friday's answers suitable for children. One grown-up PIN protects settings, internet access and keys.");
        p.card("Family mode");
        boolean fam = pr.mode() == SystemPrompt.Mode.FAMILY;
        p.locked(!s.unlocked).toggle("family", "Family mode", fam ? "On: kind, age-appropriate answers; she won't ask for personal details." : "Off: answers as for an adult, still brief.", fam,
                () -> a.withGrownUp("Change family mode.", () -> {
                    pr.setMode(pr.mode() == SystemPrompt.Mode.FAMILY ? SystemPrompt.Mode.GROWN_UP : SystemPrompt.Mode.FAMILY);
                    if (pr.mode() == SystemPrompt.Mode.FAMILY) a.app.grownUp().lock();
                    a.refreshSettings();
                }));
        // Kids slice 1: the reading band, a grown-up setting; nothing is pre-selected.
        p.card("Kids mode");
        ai.futurespeak.friday.tv.kids.KidsSettings ks = new ai.futurespeak.friday.tv.kids.KidsSettings(a);
        ai.futurespeak.friday.tv.kids.KidsRules.Band band = ks.band();
        p.locked(!s.unlocked).choice("kidsband", "Reading level",
                band == null ? "Not set: Friday asks the child's age once a day and doesn't keep it." : "Friday talks with a child aged " + band.ages + ".",
                new String[]{"Not set", "5 to 7", "8 to 10", "11 to 13"}, band == null ? 0 : band.ordinal() + 1,
                i -> a.withGrownUp("Change the kids reading level.", () -> {
                    ks.setBand(i == 0 ? null : ai.futurespeak.friday.tv.kids.KidsRules.Band.values()[i - 1]);
                    a.refreshSettings();
                }));
        // Which channels open in kids mode (design decision, 2026-11-02): none until a grown-up marks one here.
        ai.futurespeak.friday.tv.kids.KidsChannels kc = new ai.futurespeak.friday.tv.kids.KidsChannels(a);
        for (ChannelSpec ch : a.channels.list())
            p.locked(!s.unlocked).toggle("kidsch-" + ch.id, ch.emoji + "  " + ch.title + ": OK for kids",
                    "In kids mode only channels switched on here open. What they look up is still checked.", kc.ok(ch.id),
                    () -> a.withGrownUp("Change which channels kids can open.", () -> {
                        kc.set(ch.id, !kc.ok(ch.id), a.actions == null ? null : a.actions.receipts());
                        a.refreshTiles();
                        a.refreshSettings();
                    }));
        // Add-ons (docs/ADDON-CONTRACT.md): on or off, and for kids-built ones, OK for kids. Both behind the PIN.
        for (ai.futurespeak.friday.tv.addon.AddonRegistry.Addon ad : ai.futurespeak.friday.tv.addon.AddonRegistry.installed(a)) {
            p.locked(!s.unlocked).toggle("addon-" + ad.spec.id, ad.spec.icon + "  " + ad.spec.title + " (add-on): on",
                    "Installed add-ons do nothing until switched on here or by the PC setup.", ad.enabled,
                    () -> a.withGrownUp("Change which add-ons Friday uses.", () -> {
                        ai.futurespeak.friday.tv.actions.Receipts r = a.actions == null ? null : a.actions.receipts();
                        if (ad.enabled) ai.futurespeak.friday.tv.addon.AddonRegistry.disable(a, ad.spec.packageName, r);
                        else ai.futurespeak.friday.tv.addon.AddonRegistry.enable(a, ad.spec.packageName, r);
                        a.refreshTiles();
                        a.refreshSettings();
                    }));
            if (ad.enabled && ad.spec.kids)
                p.locked(!s.unlocked).toggle("kidsch-" + ad.spec.key(), ad.spec.icon + "  " + ad.spec.title + ": OK for kids",
                        "Built for kids, and shown in kids mode only when switched on here.", kc.ok(ad.spec.key()),
                        () -> a.withGrownUp("Change which add-ons kids can open.", () -> {
                            kc.set(ad.spec.key(), !kc.ok(ad.spec.key()), a.actions == null ? null : a.actions.receipts());
                            a.refreshTiles();
                            a.refreshSettings();
                        }));
        }
        p.value("Bedtime and screen time", "Kept by the helper, so bedtime still happens without Friday.", "In Ad Break Guard", Brand.DIM);
        p.card("Grown-up PIN");
        String state = s.unavailable ? "Check unavailable" : !s.hasPin ? "Not set" : s.unlocked ? "Unlocked" : "Locked";
        String hint = s.shared ? "One PIN for this TV, shared with Ad Break Guard. Unlocked until the TV sleeps (at most 4 hours)."
                : s.unavailable ? "Ad Break Guard isn't answering, so grown-up settings stay locked. Reinstalling Friday usually fixes this."
                : "Unlocked until the TV sleeps.";
        p.value("PIN", hint, state, s.unlocked ? Brand.OK : s.unavailable ? Brand.WARN : Brand.TEXT);
        if (s.unlocked) p.action("lock", "Lock now", "Ask for the PIN again before grown-up changes.", "Lock", Brand.CYAN, () -> { a.app.grownUp().lock(); a.showNotice("Locked."); });
        else if (!s.unavailable) p.action("unlock", "Unlock grown-up settings", null, "Enter PIN", Brand.WARN, () -> a.withGrownUp("Unlock grown-up settings.", a::refreshSettings));
        if (s.shared) p.value("Change the PIN", "It's the one PIN for this TV.", "In Ad Break Guard", Brand.DIM);
        else if (!s.unavailable) p.locked(!s.unlocked).action("changepin", "Change the PIN", null, "Change…", Brand.WARN,
                () -> a.withGrownUp("Change the PIN.", a::changeLocalPin));
    }

    // ---- About & keys ----------------------------------------------------------------------------------
    static String modelName(String m) { return Prefs.FAST_MODEL.equals(m) ? "Claude Sonnet 5.5" : Prefs.DEFAULT_MODEL.equals(m) ? "Claude Opus 5.5" : m; }

    static void about(MainActivity a, SettingsPage p) {
        Prefs pr = a.prefs;
        p.header("About & keys", "Agent Friday™ on your TV", "Friday's answers come from Claude, made by Anthropic, or from Friday on your PC when they're paired. Every key stays sealed on this TV.");
        // Pairing: status, Pair and Forget, both behind the grown-up PIN. Keys never leave the TV .
        p.card("Friday desktop");
        ai.futurespeak.friday.tv.pair.DesktopLink link = a.pairLink;
        boolean paired = link != null && link.paired();
        p.value("Friday on your PC", paired ? "While the PC is reachable, it answers and the TV uses none of its own keys. If it isn't, the TV answers by itself."
                        : "Pair once and Friday on your PC answers here; the TV keeps working on its own whenever the PC is off.",
                link == null ? "Not available" : link.statusLine(), paired ? Brand.OK : Brand.DIM);
        if (link != null) {
            p.locked(locked(a)).action("pair", paired ? "Pair again" : "Pair with Friday on your PC",
                    "Shows six digits here; you confirm on the PC only if it shows the same six.", "Pair…", Brand.WARN,
                    () -> a.withGrownUp("Pair this TV with Friday on your PC.", () -> a.push(new ai.futurespeak.friday.tv.pair.PairLayer(a, a, link,
                            "This TV", a::refreshSettings))));
            if (paired) p.locked(locked(a)).action("unpair", "Forget this PC", "The TV stops using the PC and answers by itself. Pair again any time.", "Forget…", Brand.DENY,
                    () -> a.withGrownUp("Forget the paired PC.", () -> { link.forget(); a.showNotice("This TV no longer uses Friday on your PC."); a.refreshSettings(); }));
        }
        p.card("Claude");
        boolean fast = Prefs.fastModel().equals(pr.model());
        p.locked(locked(a)).choice("model", "Model", fast ? "Sonnet: quicker answers." : "Opus: the most capable; a little slower.",
                new String[]{"Opus 5.5", "Sonnet 5.5"}, fast ? 1 : 0, i -> a.withGrownUp("Change the Claude model.", () -> {
                    String next = i == 1 ? Prefs.fastModel() : Prefs.providersNow().textModel;
                    pr.setModel(next);
                    a.claude.setModel(next);
                    a.refreshSettings();
                }));
        // T4: who answers. Claude, or one model through OpenRouter; kids mode is always Claude.
        p.card("Who answers");
        java.util.List<ai.futurespeak.friday.tv.brain.ModelChoice.Model> models = a.openRouterModels();
        boolean orKey = a.secure.has(SecureStore.OPENROUTER);
        String sel = a.brainPrefs().getString("selected", "");
        String[] names = new String[models.size() + 1];
        names[0] = "Claude";
        int at = 0;
        for (int i = 0; i < models.size(); i++) { names[i + 1] = models.get(i).name; if (models.get(i).id.equals(sel)) at = i + 1; }
        ai.futurespeak.friday.tv.brain.ModelChoice.Model cur = at == 0 ? null : models.get(at - 1);
        p.locked(locked(a) || !orKey).choice("brain", "Model that answers",
                !orKey ? "OpenRouter isn't set up on this TV, so Claude answers." : cur == null ? "Claude, directly from Anthropic."
                        : cur.name + " through OpenRouter · " + cur.price() + ". The pill on screen always names who is answering; kids mode always uses Claude.",
                names, at, i -> a.withGrownUp("Change who answers.", () -> {
                    a.brainPrefs().edit().putString("selected", i == 0 ? "" : models.get(i - 1).id).apply();
                    a.refreshSettings();
                }));
        double usd = 0; int replies = 0;
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.set(java.util.Calendar.HOUR_OF_DAY, 0); day.set(java.util.Calendar.MINUTE, 0); day.set(java.util.Calendar.SECOND, 0);
        if (a.actions != null) for (ai.futurespeak.friday.tv.actions.Receipts.Entry e : a.actions.receipts().recent(500)) {
            if (e.ts < day.getTimeInMillis() || !"brain.reply".equals(e.decision.optString("action")) || e.outcome == null) continue;
            org.json.JSONObject mtr = e.outcome.optJSONObject("meter");
            if (mtr != null) { usd += mtr.optDouble("usd", 0); replies++; }
        }
        p.value("OpenRouter today", "From the receipts (What Friday did). No cap; every reply is counted.",
                replies == 0 ? "None" : replies + " repl" + (replies == 1 ? "y" : "ies") + " · $" + String.format(java.util.Locale.US, "%.3f", usd), Brand.DIM);
        p.card("Keys on this TV");
        p.value("Claude", null, a.secure.has(SecureStore.ANTHROPIC) ? "✓ Added" : "Missing", a.secure.has(SecureStore.ANTHROPIC) ? Brand.OK : Brand.WARN);
        p.value("ElevenLabs", "Friday's voice.", a.secure.has(SecureStore.ELEVENLABS) ? "✓ Added" : "Missing", a.secure.has(SecureStore.ELEVENLABS) ? Brand.OK : Brand.WARN);
        int tools = 0;
        for (String k : new String[]{SecureStore.FIRECRAWL, SecureStore.KIE, SecureStore.OPENROUTER, SecureStore.GEMINI}) if (a.secure.has(k)) tools++;
        p.value("Tool keys", "Firecrawl, kie.ai, OpenRouter, Gemini. Stored for tools that aren't switched on yet.", tools + " of 4 added", tools == 4 ? Brand.OK : Brand.DIM);
        p.locked(locked(a)).action("removekeys", "Remove the keys", "Friday stops until a grown-up adds them again from the PC.", "Remove…", Brand.DENY,
                () -> a.withGrownUp("Remove the keys from this TV.", a::confirmRemoveKeys));
        p.card("Friday");
        p.value("Pair with Friday on your PC", "Your memories, tools and the rest of Friday, on the TV.", "Coming soon", Brand.DIM);
        p.value("Version", null, MainActivity.BuildConfigLite.VERSION + " · max reply " + ClaudeDirect.MAX_TOKENS / 1000 + "k", Brand.DIM);
        p.note("Agent Friday™ and FutureSpeak.AI™ are trademarks of FutureSpeak.AI. Claude is made by Anthropic.");
    }

    /** Pinned-card helper for the screensaver page and CardLayer (keeps imports here small). */
    static boolean togglePin(MainActivity a, JSONObject card) { return a.pinnedCards.toggle(card); }
}
