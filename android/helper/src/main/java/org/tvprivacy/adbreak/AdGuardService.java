package org.tvprivacy.adbreak;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Watches the foreground streaming app's on-screen text for ad markers (rules.json).
 * On a match: optionally mutes, and covers the screen with a calm placard.
 * When the markers are gone: unmutes and removes the placard.
 * Any error, interruption or shutdown: unmute and remove the placard (fail safe).
 *
 * Privacy: no INTERNET permission; logs only the rule id and the matched marker text.
 */
public class AdGuardService extends AccessibilityService {
    static final String TAG = "AdGuard";
    static volatile AdGuardService instance;
    private CastKeepAlive castKeepAlive;   // keeps Google Cast from being frozen (2026-11-02)

    private Handler handler;
    private Rules rules;
    private Prefs prefs;
    private Muter muter;
    private Placard placard;

    private boolean inAd;
    private String adPkg;
    private long adStart;
    private long lastSeen;
    private int absentScans;
    private long suppressUntil;
    private boolean scanQueued;
    private boolean testing;
    private long lastRejectLog, lastProbeLog, probeCheckedAt;
    private boolean probeCached;
    private android.media.AudioManager audio;

    /** Probe mode is on while the file .../files/probe exists (tuning only). */
    private boolean probeEnabled() {
        long now = SystemClock.elapsedRealtime();
        if (now - probeCheckedAt > 10_000) {
            probeCheckedAt = now;
            try { probeCached = new java.io.File(getExternalFilesDir(null), "probe").exists(); } catch (Throwable t) { probeCached = false; }
        }
        return probeCached;
    }

    private final Runnable scanTask = () -> { scanQueued = false; safely(this::scan); };
    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            safely(AdGuardService.this::scan);
            if (inAd && handler != null) handler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            failSafe("screen off");
            try { if (kids != null && kids.lockGrownUp()) Events.log(AdGuardService.this, "grown-up: locked (screen off)"); } catch (Throwable ignored) { }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler = new Handler(Looper.getMainLooper());
        prefs = new Prefs(this);
        muter = new Muter(this, prefs);
        placard = new Placard(this);
        audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        // If the process died mid-ad last time, the stream may still be muted by us.
        muter.unmuteIfWeMuted();
        rules = Rules.load(this);
        keyMap = KeyMap.load(this);
        kids = new Kids(this);
        learn = new Learn(this);
        checkKidsRescue();
        handler.postDelayed(kidsTick, KIDS_TICK_MS);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try { failSafe("crash"); } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
        try { registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF)); } catch (Throwable ignored) { }
        try {   // keyboards never count as "the app in front"
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            for (android.view.inputmethod.InputMethodInfo i : imm.getInputMethodList()) ForegroundTracker.INSTANCE.ignore(i.getPackageName());
        } catch (Throwable ignored) { }
        instance = this;
        handler.postDelayed(() -> safely(this::checkHomeAtStart), 1_500);
        try { castKeepAlive = new CastKeepAlive(this, handler); castKeepAlive.start(); } catch (Throwable ignored) { }
        Log.i(TAG, "service connected; rules source=" + rules.source + " watchAllApps=" + rules.watchAllApps + " tuned=" + rules.apps.keySet());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        safely(() -> {
            if (event == null || event.getPackageName() == null) return;
            String pkg = event.getPackageName().toString();
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                String cls = event.getClassName() == null ? "" : event.getClassName().toString();
                ForegroundTracker.INSTANCE.onWindowState(pkg, cls, SystemClock.elapsedRealtime());
                if (HelperPolicy.FRIDAY.equals(pkg)) homeRedirect.onFridayFront();
                if (HomeRedirect.isGoogleHome(pkg, cls)) maybeRedirectHome("Google home in front");
            }
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && kids.active()) {
                enforceKids(pkg, event.getClassName() == null ? "" : event.getClassName().toString());
            }
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && STOCK_YOUTUBE.equals(pkg) && prefs.redirectYouTube()) {
                redirectToSmartTube();
                return;
            }
            // Backstop (option A): TCL opens YouTube's Play Store page when the YouTube button is
            // pressed and the app is off. If that page appears, close it and open SmartTube.
            if (PLAY_STORE.equals(pkg) && prefs.redirectYouTube()) {
                if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !PLAY_STORE.equals(lastForegroundPkg)) {
                    playStoreOpenedAt = SystemClock.elapsedRealtime();
                }
                lastForegroundPkg = pkg;
                if (SystemClock.elapsedRealtime() - playStoreOpenedAt < 5000) {
                    handler.postDelayed(() -> safely(this::checkPlayStoreYouTubePage), 400);
                }
                return;
            }
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) lastForegroundPkg = pkg;
            if (!inAd && rules.ruleFor(pkg) == null) return;
            if (!scanQueued) {
                scanQueued = true;
                handler.postDelayed(scanTask, 250);
            }
        });
    }

    /** Finds the top application window; if it belongs to a watched app, look for markers. */
    private void scan() {
        if (testing) return;
        long now = SystemClock.elapsedRealtime();
        if (now < suppressUntil) return;

        AccessibilityNodeInfo root = null;
        Rules.AppRule app = null;
        String topPkg = null;
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null || r.getPackageName() == null) continue;
            topPkg = r.getPackageName().toString();
            app = rules.ruleFor(topPkg);
            root = r;
            break; // windows are listed top-most first
        }

        if (app == null || prefs.mode(topPkg) == Prefs.OFF) {
            if (inAd) endAd(app == null ? "left app" : "turned off for app");
            stopAudioGuess();
            return;
        }
        updateAudioGuess(topPkg);

        boolean probe = probeEnabled();
        Rules.ScanResult sr = rules.scan(root, app, probe);
        boolean playing = audio.isMusicActive();
        Rules.Match m = sr.match;
        if (m != null && !inAd && blocked(topPkg, m.ruleId)) m = null;   // paused, snoozed, or switched off by feedback
        if (m != null && !inAd) {
            boolean strongEnough = m.strong || app.weakIsStrong || sr.fullscreenVideo;
            boolean playbackOk = playing || !app.requirePlayback;
            if (!strongEnough || !playbackOk) {
                if (now - lastRejectLog > 5000) {
                    lastRejectLog = now;
                    Log.i(TAG, "ignored app=" + topPkg + " rule=" + m.ruleId + " marker=\"" + m.marker + "\""
                            + (strongEnough ? "" : " (weak marker, no full-screen video)")
                            + (playbackOk ? "" : " (no audio playing)"));
                }
                m = null;
            }
        }
        if (probe && now - lastProbeLog > 5000 && (playing || !sr.probeTexts.isEmpty())) {
            lastProbeLog = now;
            Log.i(TAG, "probe app=" + topPkg + " audio=" + playing + " fullscreenVideo=" + sr.fullscreenVideo
                    + (sr.surfaceClass.isEmpty() ? "" : "(" + sr.surfaceClass + ")")
                    + " labels=" + sr.probeTexts + " ids=" + sr.probeIds);
        }
        if (m != null) {
            lastSeen = now;
            absentScans = 0;
            if (!inAd) startAd(topPkg, m);
            else if (m.countdownSec >= 0) placard.update(m.countdownSec);
        } else if (inAd) {
            absentScans++;
            if (absentScans >= rules.exitScans && now - lastSeen >= rules.exitMs) endAd("markers gone");
        }

        if (inAd && now - adStart > rules.maxAdMs) {
            endAd("max duration reached");
            suppressUntil = now + 30_000;
        }
    }

    private void startAd(String pkg, Rules.Match m) {
        inAd = true;
        adPkg = pkg;
        adStart = SystemClock.elapsedRealtime();
        int mode = prefs.mode(pkg);
        boolean mute = mode == Prefs.FULL && learn.state(pkg, m.ruleId) == Learn.NORMAL;
        curApp = pkg; curRule = m.ruleId;
        Log.i(TAG, "ad start app=" + pkg + " rule=" + m.ruleId + (m.strong ? "" : "(weak+video)") + " marker=\"" + m.marker + "\" mode=" + Prefs.label(mode)
                + (m.countdownSec >= 0 ? " countdown=" + m.countdownSec + "s" : ""));
        if (guessBadge) { guessBadge = false; placard.hideBadge(); }
        guessActing = false;
        placard.show(mute, m.countdownSec);
        if (mute) muter.mute();
        handler.removeCallbacks(pollTask);
        handler.postDelayed(pollTask, 1000);
    }

    private void endAd(String reason) {
        if (!inAd) return;
        inAd = false;
        long secs = (SystemClock.elapsedRealtime() - adStart) / 1000;
        handler.removeCallbacks(pollTask);
        try { muter.unmuteIfWeMuted(); } finally { placard.hide(); }
        Log.i(TAG, "ad end app=" + adPkg + " after " + secs + "s (" + reason + ")");
        adPkg = null;
    }

    // ---------- Google home -> Friday Home (launcher step L2, 0.8.3) ----------
    private final HomeRedirect homeRedirect = new HomeRedirect();

    private void maybeRedirectHome(String why) {
        KeyMap.Entry home = keyMap == null ? null : keyMap.find(HomeKey.KEYCODE_HOME, -1);
        String enabled = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        long now = SystemClock.elapsedRealtime();
        HomeRedirect.Action a = homeRedirect.onGoogleHome(now, HomeKey.projectivyOn(enabled), home != null, kids != null && kids.active());
        if (a == HomeRedirect.Action.GIVE_UP) {
            Events.log(this, "home: gave up redirecting for 10 min (" + homeRedirect.lastGiveUpReason() + "); Google's screen left showing");
            return;
        }
        if (a != HomeRedirect.Action.REDIRECT) return;
        handler.postDelayed(() -> safely(() -> {
            if (!HomeRedirect.GOOGLE_HOME_PKG.equals(ForegroundTracker.INSTANCE.pkg())) { homeRedirect.cancelPending(); return; }
            try {
                launchTarget(home);
                Events.log(this, "home: " + why + " -> Friday Home");
            } catch (Throwable t) {
                homeRedirect.onStartFailed(SystemClock.elapsedRealtime());
                Events.log(this, "home: gave up redirecting for 10 min (could not start Friday Home: " + t.getClass().getSimpleName() + "); Google's screen left showing");
                return;
            }
            handler.postDelayed(() -> safely(() -> {
                if (homeRedirect.checkPending(SystemClock.elapsedRealtime()) == HomeRedirect.Action.GIVE_UP) {
                    Events.log(this, "home: gave up redirecting for 10 min (" + homeRedirect.lastGiveUpReason() + "); Google's screen left showing");
                }
            }), HomeRedirect.CONFIRM_MS + 200);
        }), 250);
    }

    /** At service start (e.g. after a restart) Google's home may already be in front. */
    private void checkHomeAtStart() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            CharSequence p = root == null ? null : root.getPackageName();
            if (root != null) root.recycle();
            if (p != null && HomeRedirect.GOOGLE_HOME_PKG.equals(p.toString())) {
                ForegroundTracker.INSTANCE.onWindowState(p.toString(), HomeRedirect.GOOGLE_HOME_CLS, SystemClock.elapsedRealtime());
                maybeRedirectHome("Google home in front at start");
            }
        } catch (Throwable ignored) { }
    }

    // ---------- kids mode ----------
    private Kids kids;
    private long lastBounce, lastKidsTick;
    private String kidsTopPkg = "";
    private static final java.util.Set<String> KIDS_OK_PKGS = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.android.systemui", "android", "com.google.android.inputmethod.latin", "com.google.android.leanbackime"));
    static { KIDS_OK_PKGS.addAll(HelperPolicy.KIDS_APPS); }   // one kids list, shared with launch()
    private static final java.util.Set<String> KIDS_OK_OWN = new java.util.HashSet<>(java.util.Arrays.asList(
            "org.tvprivacy.adbreak.KidsHomeActivity", "org.tvprivacy.adbreak.TimeUpActivity", "org.tvprivacy.adbreak.PinActivity",
            "org.tvprivacy.adbreak.BedtimeActivity"));
    static final String FRIDAY_KIDS_HOME_ACTION = "ai.futurespeak.friday.tv.action.KIDS_HOME";
    static final long FRIDAY_KIDS_HOME_WAIT_MS = 2_000;
    static final long KIDS_TICK_MS = 5_000;

    private final Runnable kidsTick = new Runnable() {
        @Override public void run() {
            safely(() -> {
                long now = SystemClock.elapsedRealtime();
                if (kids.active()) {
                    android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                    boolean interactive = pm.isInteractive();
                    String top = ForegroundTracker.INSTANCE.pkg();
                    // K-D4: watching counts; Friday counts only with "Count everything".
                    if (lastKidsTick > 0 && KidsClock.counts(true, interactive, top, kids.countEverything())) kids.addUsed(now - lastKidsTick);
                    lastKidsTick = now;
                    // Playback proxy for "the last one can finish": no media-session access until S2.
                    long wall = System.currentTimeMillis();
                    boolean playing = audio != null && audio.isMusicActive();
                    if (playing) kids.setIdleSince(-1); else if (kids.idleSince() < 0) kids.setIdleSince(wall);
                    windDownWarning(top);
                    if (kids.lockedOut()) {
                        if (!isOwnLockScreen(top)) { Events.log(AdGuardService.this, "kids: bedtime lockout"); bounceKids(); }
                    } else if (kids.timeUp() && countsNow(top)) {
                        Log.i(TAG, "kids: daily limit reached"); bounceKids();
                    }
                } else lastKidsTick = 0;
            });
            handler.postDelayed(this, KIDS_TICK_MS);
        }
    };

    /** Is this app something the daily limit applies to right now (watching, or Friday with Count everything)? */
    private boolean countsNow(String pkg) {
        return HelperPolicy.KIDS_APPS.contains(pkg) && (!HelperPolicy.FRIDAY.equals(pkg) || kids.countEverything());
    }

    private boolean isOwnLockScreen(String pkg) {
        // ForegroundTracker reports packages; our own package in front while locked out is the bedtime or PIN screen.
        return getPackageName().equals(pkg);
    }

    /** T-15 / T-5 / T-0: Friday speaks when she's in front; otherwise a short toast (no strip until S4). */
    private void windDownWarning(String top) {
        long wall = System.currentTimeMillis();
        KidsSchedule s = kids.schedule();
        int due = s.warningDue(wall);
        if (due < 0) return;
        String key = s.nightKey(wall) + ":" + due;
        String last = kids.warnedKey();
        if (last.startsWith(s.nightKey(wall) + ":") && rank(last) >= rank(key)) return;   // shown this one (or a later one) tonight
        kids.setWarned(key);
        Events.log(this, "kids: wind-down warning T-" + due);
        if (HelperPolicy.FRIDAY.equals(top)) return;   // Friday says it (she polls kidsStatus)
        String msg = due == 15 ? "15 minutes till wind-down" : due == 5 ? "5 minutes till wind-down" : "Last one: this episode can finish";
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
    }
    /** 15 -> 0, 5 -> 1, 0 -> 2 (later warnings rank higher). */
    private static int rank(String key) {
        if (key.endsWith(":15")) return 0;
        if (key.endsWith(":5")) return 1;
        if (key.endsWith(":0")) return 2;
        return -1;
    }

    private void checkKidsRescue() {
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "kids-reset");
            if (f.exists()) { kids.reset(); f.delete(); Log.i(TAG, "kids: reset via rescue file (kids mode off, PIN cleared)"); }
        } catch (Throwable ignored) { }
    }

    private long lastKidsTrigger = 0;
    /** After a PIN unlock, ignore TCL Home -> kids for this long (stray/double presses). */
    static final long KIDS_GRACE_AFTER_UNLOCK_MS = 10_000;
    static final long KIDS_TRIGGER_DEBOUNCE_MS = 1_500;

    /** Only reached from a deliberate TCL Home press (the keymap's kids target). */
    void enterKids() { enterKids("TCL Home press"); }

    /**
     * Kids mode on: from TCL Home, or from Friday's kidsEnter (narrowing only).
     * Order (AC-K3): close the grown-up window, then kids on, then the kids home.
     * @return false only if there is no PIN yet (first use goes through TCL Home and the PIN-set screen)
     */
    synchronized boolean enterKids(String source) {
        checkKidsRescue();
        long now = SystemClock.elapsedRealtime();
        if (!kids.hasPin()) {
            if (source.startsWith("TCL")) {   // first time: grown-up sets the PIN on the TV
                startActivity(new Intent(this, PinActivity.class).putExtra(PinActivity.MODE, PinActivity.MODE_SET).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return false;
        }
        if (now - lastKidsTrigger < KIDS_TRIGGER_DEBOUNCE_MS) return true;
        lastKidsTrigger = now;
        long sinceUnlock = now - kids.lastUnlockElapsed();
        if (!kids.active() && kids.lastUnlockElapsed() > 0 && sinceUnlock >= 0 && sinceUnlock < KIDS_GRACE_AFTER_UNLOCK_MS) {
            Events.log(this, "kids: " + source + " ignored (" + sinceUnlock + " ms after PIN unlock)");
            return true;
        }
        for (String step : KidsClock.enterPlan(true, kids.unlockedUntil() > 0, kids.active())) {
            if (step.equals("lockGrownUp")) { if (kids.lockGrownUp()) Events.log(this, "grown-up: locked (kids on)"); }
            else if (step.equals("kidsOn")) { kids.setActive(true); Events.log(this, "kids: on (" + source + ")"); }
            else if (step.equals("bounce")) { lastBounce = 0; bounceKids(); }
        }
        return true;
    }

    void kidsExited() { lastKidsTick = 0; }

    /**
     * Bedtime -> the helper's lockout; time's up -> the helper's screen; otherwise Friday's kids home,
     * falling back to the helper's own kids home if Friday isn't in front within 2 s (AC-K4).
     */
    private void bounceKids() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBounce < 800) return;
        lastBounce = now;
        int fl = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP;
        if (kids.lockedOut()) { startActivity(new Intent(this, BedtimeActivity.class).addFlags(fl)); return; }
        if (kids.timeUp()) { startActivity(new Intent(this, TimeUpActivity.class).addFlags(fl)); return; }
        try {
            startActivity(new Intent(FRIDAY_KIDS_HOME_ACTION).setPackage(HelperPolicy.FRIDAY).addFlags(fl));
        } catch (Throwable t) {   // Friday missing or its kids home disabled
            startActivity(new Intent(this, KidsHomeActivity.class).addFlags(fl));
            Events.log(this, "kids: Friday's kids home unavailable; helper's kids home shown");
            return;
        }
        handler.postDelayed(() -> safely(() -> {
            if (!kids.active()) return;
            if (!HelperPolicy.FRIDAY.equals(ForegroundTracker.INSTANCE.pkg())) {
                startActivity(new Intent(this, KidsHomeActivity.class).addFlags(fl));
                Events.log(this, "kids: Friday's kids home not in front in 2 s; helper's kids home shown");
            }
        }), FRIDAY_KIDS_HOME_WAIT_MS);
    }

    /** In kids mode only the kids list and the kids screens may be in front; anything else bounces back. */
    private void enforceKids(String pkg, String cls) {
        if (pkg.equals(getPackageName())) {
            kidsTopPkg = pkg;
            if (!KIDS_OK_OWN.contains(cls) && cls.startsWith("org.tvprivacy.adbreak.")) bounceKids();
            return;
        }
        if (KIDS_OK_PKGS.contains(pkg)) {
            if (cls.startsWith("android.widget") || cls.startsWith("android.view")) return; // toasts/popups, not a new app
            kidsTopPkg = pkg;
            if (HelperPolicy.KIDS_APPS.contains(pkg)) {
                if (kids.lockedOut()) bounceKids();                      // bedtime: every kids app, Friday included
                else if (kids.timeUp() && countsNow(pkg)) bounceKids();  // time's up: what the clock counts
            }
            return;
        }
        if (cls.startsWith("android.widget") || cls.startsWith("android.view")) return;
        kidsTopPkg = pkg;
        Log.i(TAG, "kids: blocked " + pkg);
        bounceKids();
    }

    /** For the provider (kidsEnter, narrowing only). False only if there is no PIN yet. */
    static boolean kidsEnterFromFriday(android.content.Context c) {
        Kids k = new Kids(c);
        if (!k.hasPin()) return false;
        AdGuardService s = instance;
        if (s == null) {   // service down: still narrow (close the window, kids on); no bounce possible
            if (k.lockGrownUp()) Events.log(c, "grown-up: locked (kids on)");
            if (!k.active()) { k.setActive(true); Events.log(c, "kids: on (Friday voice)"); }
            return true;
        }
        s.handler.post(() -> s.safely(() -> s.enterKids("Friday voice")));
        return true;
    }
    // ---------- remote button map ----------
    private KeyMap keyMap;
    private long learnUntil;
    private java.util.function.BiConsumer<Integer, Integer> learnCallback;

    void reloadKeyMap() { safely(() -> { keyMap = KeyMap.load(this); Log.i(TAG, "keymap loaded: " + keyMap.entries.size() + " buttons"); }); }

    /** Settings "Learn a button": the next non-protected key press is reported (keyCode, scanCode) and consumed. */
    void startLearning(java.util.function.BiConsumer<Integer, Integer> cb) {
        learnCallback = cb;
        learnUntil = SystemClock.elapsedRealtime() + 15_000;
    }

    // ---------- one-press feedback on the overlay ----------
    private Learn learn;
    private String curRule, curApp;                 // what put the overlay up
    private final java.util.Map<String, Long> snooze = new java.util.HashMap<>();   // app|rule -> until
    private final java.util.Map<String, Long> paused = new java.util.HashMap<>();   // app -> until
    private int consumedKey = -1;
    private long lastOverlayKeyAt;

    private boolean overlayUp() { return placard != null && (placard.placardShowing() || placard.badgeShowing()); }

    private boolean blocked(String app, String rule) {
        long now = SystemClock.elapsedRealtime();
        Long p = paused.get(app); if (p != null && now < p) return true;
        Long s = snooze.get(app + "|" + rule); if (s != null && now < s) return true;
        return learn.state(app, rule) == Learn.DISABLED;
    }

    /** BACK = not an ad (dismiss now), OK = it is an ad, DOWN = pause this app 30 min. Bursts count once. */
    private boolean handleOverlayKey(android.view.KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == android.view.KeyEvent.ACTION_DOWN;
        if (k == consumedKey) { if (!down) consumedKey = -1; return true; }   // swallow repeats and the release
        if (!overlayUp()) return false;
        boolean back = k == android.view.KeyEvent.KEYCODE_BACK;
        boolean ok = k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER;
        boolean pause = k == android.view.KeyEvent.KEYCODE_DPAD_DOWN;
        if (!back && !ok && !pause) return false;
        if (!down) return true;
        long now = SystemClock.elapsedRealtime();
        consumedKey = k;
        if (now - lastOverlayKeyAt < 700) return true;   // remote sends bursts: one press = one action
        lastOverlayKeyAt = now;
        String app = curApp == null ? "?" : curApp, rule = curRule == null ? "?" : curRule;
        if (back) {
            if (curApp != null) learn.verdict(app, rule, false);
            snooze.put(app + "|" + rule, now + ("audio-guess".equals(rule) ? 180_000 : 120_000));
            Log.i(TAG, "feedback: NOT an ad app=" + app + " rule=" + rule + " -> " + stateName(learn.state(app, rule)));
            dismissAll("viewer: not an ad");
        } else if (ok) {
            if (curApp != null) learn.verdict(app, rule, true);
            Log.i(TAG, "feedback: confirmed ad app=" + app + " rule=" + rule);
            if (guessBadge) {           // promote the corner badge to the full placard
                placard.hideBadge(); guessBadge = false;
                boolean mute = prefs.mode(app) == Prefs.FULL && learn.state(app, rule) == Learn.NORMAL;
                placard.show(mute, -1); if (mute) muter.mute(); guessActing = true;
            }
        } else {
            paused.put(app, now + 30 * 60_000L);
            Log.i(TAG, "feedback: paused app=" + app + " for 30 min");
            dismissAll("viewer: pause 30 min");
        }
        return true;
    }

    private static String stateName(int s) { return s == Learn.DISABLED ? "rule now OFF for this app" : s == Learn.DEMOTED ? "rule now placard-only for this app" : "noted"; }

    private void dismissAll(String why) {
        if (inAd) endAd(why);
        if (guessActing || guessBadge) guessEnd(why);
        try { muter.unmuteIfWeMuted(); } catch (Throwable ignored) { }
        placard.hide(); placard.hideBadge();
        testing = false;
    }

    @Override
    protected boolean onKeyEvent(android.view.KeyEvent e) {
        try {
            if (handleOverlayKey(e)) return true;
        } catch (Throwable t) {
            Log.e(TAG, "overlay key error (" + t.getClass().getSimpleName() + "); failing safe");
            failSafe("key error");
        }
        try {
            int k = e.getKeyCode(), sc = e.getScanCode();
            if (k == HomeKey.KEYCODE_HOME) {
                // Home: only with an explicit keymap entry, and never while Projectivy's service is on.
                KeyMap.Entry home = keyMap == null ? null : keyMap.find(k, sc);
                String enabled = android.provider.Settings.Secure.getString(getContentResolver(),
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (!HomeKey.take(k, home != null, enabled)) return false;
                if (e.getAction() == android.view.KeyEvent.ACTION_UP && !e.isCanceled()) {
                    handler.post(() -> safely(() -> launchTarget(home)));
                }
                return true;
            }
            if (KeyMap.isHardProtected(k)) return false;
            if (KeyMap.isSoft(k) && learnCallback == null) {
                // voice/search/menu keys: only an entry pinned to this exact scan code may take them
                KeyMap.Entry soft = keyMap == null ? null : keyMap.find(k, sc);
                if (soft == null || soft.scanCode <= 0) return false;
            }
            boolean down = e.getAction() == android.view.KeyEvent.ACTION_DOWN;
            if (down && e.getRepeatCount() == 0) {
                Log.i(TAG, "key " + android.view.KeyEvent.keyCodeToString(k) + " code=" + k + " scan=" + sc);
            }
            if (learnCallback != null && SystemClock.elapsedRealtime() < learnUntil) {
                if (down && e.getRepeatCount() == 0) {
                    java.util.function.BiConsumer<Integer, Integer> cb = learnCallback;
                    learnCallback = null;
                    handler.post(() -> cb.accept(k, sc));
                }
                return true;
            }
            if (keyMap == null) return false;
            KeyMap.Entry m = keyMap.find(k, sc);
            if (m == null) return false;
            if (!down && e.getRepeatCount() == 0) handler.post(() -> safely(() -> launchTarget(m)));
            return true; // consume both down and up so the original app does not open
        } catch (Throwable t) {
            Log.e(TAG, "key handling error (" + t.getClass().getSimpleName() + "); passing key through");
            return false;
        }
    }

    private void launchTarget(KeyMap.Entry m) {
        // Optional launch extras: "package-or-component|key=value|key2=true" (e.g. Friday's start-listening extra)
        String[] parts = m.target.split("\\|");
        String base = parts[0];
        Intent i = resolveTarget(m, base);
        if (i == null) return;
        for (int p = 1; p < parts.length; p++) {
            int eq = parts[p].indexOf('=');
            if (eq <= 0) continue;
            String key = parts[p].substring(0, eq), val = parts[p].substring(eq + 1);
            if (val.equals("true") || val.equals("false")) i.putExtra(key, Boolean.parseBoolean(val)); else i.putExtra(key, val);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
        Log.i(TAG, "button " + m.name + " -> " + base + (parts.length > 1 ? " (+extras)" : ""));
    }

    private Intent resolveTarget(KeyMap.Entry m, String target) {
        KeyMap.Entry t = new KeyMap.Entry(m.keyCode, m.scanCode, m.name, target);
        return resolveTargetIntent(t);
    }

    private Intent resolveTargetIntent(KeyMap.Entry m) {
        Intent i;
        if (KeyMap.KIDS_TARGET.equals(m.target)) {
            enterKids();
            Log.i(TAG, "button " + m.name + " -> kids mode");
            return null;
        } else if (kids.active() && !HelperPolicy.kidsAllowedTarget(m.target)) {
            bounceKids();                                  // in kids mode, buttons for apps off the kids list lead back to the kids screen
            return null;
        } else if (KeyMap.SETTINGS_TARGET.equals(m.target)) {
            i = new Intent(this, SettingsActivity.class);
        } else if (m.target.contains("/")) {   // a specific screen, e.g. com.spocky.projengmenu/.ui.sidePanels.settings.SettingsActivity
            android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(m.target);
            i = cn == null ? null : new Intent(Intent.ACTION_MAIN).setComponent(cn);
        } else {
            i = getPackageManager().getLeanbackLaunchIntentForPackage(m.target);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(m.target);
        }
        if (i == null) Log.w(TAG, "button " + m.name + ": target not installed (" + m.target + ")");
        return i;
    }

    // ---------- audio fallback (unlabeled ads) ----------
    private AudioWatcher audioWatcher;
    private String audioPkg;
    private boolean guessActing, guessBadge;

    boolean hasAudioPermission() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    // 0.6: avoid create/release churn of the Visualizer (each one holds an audio-effect slot).
    // Stop only after 30 s without playback, not on every momentary pause.
    private final Runnable audioIdleStop = () -> safely(this::stopAudioGuess);

    private void updateAudioGuess(String pkg) {
        boolean want = prefs.audioGuess() > 0 && hasAudioPermission() && audio.isMusicActive();
        if (!want) {
            if (audioWatcher != null && audioWatcher.isRunning()) { handler.removeCallbacks(audioIdleStop); handler.postDelayed(audioIdleStop, 30_000); }
            return;
        }
        handler.removeCallbacks(audioIdleStop);
        if (audioWatcher == null) audioWatcher = new AudioWatcher(new AudioWatcher.Listener() {
            @Override public void onGuessStart(String why) { safely(() -> guessStart(why)); }
            @Override public void onGuessEnd(String why) { safely(() -> guessEnd(why)); }
        });
        if (!pkg.equals(audioPkg)) { audioWatcher.stop(); audioPkg = pkg; }   // fresh baseline per app
        if (!audioWatcher.isRunning()) audioWatcher.start();
    }

    private void stopAudioGuess() {
        if (audioWatcher != null) audioWatcher.stop();
        audioPkg = null;
    }

    private void guessStart(String why) {
        if (inAd || audioPkg == null) return;            // a labelled ad is already handled
        if (blocked(audioPkg, "audio-guess")) { Log.i(TAG, "audio guess suppressed (feedback/pause) app=" + audioPkg); return; }
        curApp = audioPkg; curRule = "audio-guess";
        int g = prefs.audioGuess();
        if (g == 2 && learn.state(audioPkg, "audio-guess") == Learn.DEMOTED) g = 1;   // demoted: suggest only
        Log.i(TAG, "audio guess start app=" + audioPkg + " (" + why + ") mode=" + Prefs.audioLabel(g));
        if (g == 2) {
            int mode = prefs.mode(audioPkg);
            placard.show(mode == Prefs.FULL, -1);
            if (mode == Prefs.FULL) muter.mute();
            guessActing = true;
        } else {
            placard.showBadge("Ad break? (audio guess)     BACK = no     OK = yes");
            guessBadge = true;
        }
    }

    private void guessEnd(String why) {
        Log.i(TAG, "audio guess end (" + why + ")");
        if (guessActing) { guessActing = false; try { muter.unmuteIfWeMuted(); } finally { placard.hide(); } }
        if (guessBadge) { guessBadge = false; placard.hideBadge(); }
    }

    static final String PLAY_STORE = "com.android.vending";
    private String lastForegroundPkg;
    private long playStoreOpenedAt;

    /** True if the freshly opened Play Store page is the stock YouTube app's page (title "YouTube" plus Enable/Install/Open). */
    private void checkPlayStoreYouTubePage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !PLAY_STORE.equals(root.getPackageName().toString())) return;
        boolean title = false, action = false;
        java.util.ArrayDeque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen++ < 1500) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            CharSequence t = n.getText();
            if (t != null && n.isVisibleToUser()) {
                String s = t.toString().trim();
                if (s.equals("YouTube")) title = true;
                if (s.equals("Enable") || s.equals("Install") || s.equals("Open") || s.equals("Update")) action = true;
            }
            for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo c = n.getChild(i); if (c != null) q.add(c); }
        }
        if (title && action) {
            playStoreOpenedAt = 0;
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(() -> safely(() -> { lastRedirect = 0; redirectToSmartTube(); }), 300);
            Log.i(TAG, "redirect Play Store YouTube page -> " + SMARTTUBE);
        }
    }

    static final String STOCK_YOUTUBE = "com.google.android.youtube.tv";
    static final String SMARTTUBE = "org.smarttube.stable";
    private long lastRedirect;

    /** Any launch of the stock YouTube app (remote button, search, voice, deep link, cast) opens SmartTube instead. */
    private void redirectToSmartTube() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRedirect < 2000) return;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(SMARTTUBE);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(SMARTTUBE);
        if (i == null) { Log.w(TAG, "redirect skipped: SmartTube not installed"); return; }
        lastRedirect = now;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
        Log.i(TAG, "redirect " + STOCK_YOUTUBE + " -> " + SMARTTUBE);
    }

    /** Settings screen "Test placard": shows the placard for 5 s, no mute. */
    void testPlacard() {
        safely(() -> {
            if (inAd) return;
            testing = true;
            curApp = null; curRule = null;   // test placard: feedback keys work but are not learned
            placard.show(false, 5);
            handler.postDelayed(() -> { placard.hide(); testing = false; }, 5000);
        });
    }

    void reloadRules() { safely(() -> { rules = Rules.load(this); Log.i(TAG, "rules reloaded source=" + rules.source); }); }

    private void safely(Runnable r) {
        try { r.run(); }
        catch (Throwable t) {
            Log.e(TAG, "error (" + t.getClass().getSimpleName() + "); failing safe");
            failSafe("error");
        }
    }

    private void failSafe(String why) {
        boolean was = inAd || testing;
        inAd = false;
        testing = false;
        if (handler != null) handler.removeCallbacks(pollTask);
        try { if (muter != null) muter.unmuteIfWeMuted(); } catch (Throwable ignored) { }
        try { if (placard != null) placard.hide(); } catch (Throwable ignored) { }
        try { if (placard != null) placard.hideBadge(); } catch (Throwable ignored) { }
        try { if (audioWatcher != null) audioWatcher.stop(); } catch (Throwable ignored) { }
        audioPkg = null;
        was = was || guessActing || guessBadge;
        guessActing = false; guessBadge = false;
        if (was) Log.i(TAG, "fail-safe restore (" + why + ")");
    }

    @Override public void onInterrupt() { failSafe("interrupt"); }

    @Override
    public boolean onUnbind(Intent intent) {
        failSafe("unbind");
        instance = null;
        if (castKeepAlive != null) castKeepAlive.stop();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        failSafe("destroy");
        try { unregisterReceiver(screenOff); } catch (Throwable ignored) { }
        instance = null;
        super.onDestroy();
    }
}
