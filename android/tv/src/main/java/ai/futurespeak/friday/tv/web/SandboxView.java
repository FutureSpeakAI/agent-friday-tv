package ai.futurespeak.friday.tv.web;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.futurespeak.friday.tv.channels.NetPolicy;

/**
 * Runs generated HTML (a channel or an HTML card). It cannot see the app, the TV, other
 * apps, settings or keys: no JavaScript bridge, no file or content access, no navigation
 * away, no popups or dialogs, no camera/mic. Its only network is a GET to a host a grown-up
 * granted, fetched by the app through {@link SafeFetch} ({@link NetPolicy}). Each channel
 * has its own origin, so its saved data (localStorage) is its own.
 */
@SuppressLint("ViewConstructor")
public final class SandboxView extends WebView {
    private static final String TAG = "FridayTV";
    private static final int MAX_FETCH = 2 * 1024 * 1024;

    private final String host;
    /** The Wonder Owl channel: its native half answers, and the kid checks are always on. */
    private final List<String> granted;
    private final byte[] page;
    private final FeedProxy feeds;
    /** What went wrong, for the plain-words "didn't work" message. */
    private volatile String jsError = null, blockedHost = null;
    private volatile boolean finished = false;
    private RenderCheck pendingCheck;

    /** Result of {@link #verify}: null when the page drew something, else what went wrong. */
    public interface RenderCheck { void result(String problem); }

    /**
     * @param originId a short id (channel id, or a fresh one for a card): the page's origin is
     *                 https://ch-&lt;id&gt;.friday.local/
     */
    public SandboxView(Context c, String originId, String html, List<String> granted) {
        super(c);
        this.host = "ch-" + originId.replaceAll("[^a-z0-9]", "") + NetPolicy.LOCAL_SUFFIX;
        this.granted = granted == null ? Collections.emptyList() : granted;
        this.page = wrap(html, NetPolicy.csp(this.granted)).getBytes(StandardCharsets.UTF_8);
        this.feeds = new FeedProxy(this.granted);
        Hardened.apply(this, true);
        setWebChromeClient(new Hardened.QuietChrome() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR && jsError == null) {
                    String t = m.message() == null ? "" : m.message();
                    jsError = t.length() > 120 ? t.substring(0, 120) + "…" : t;
                }
                return true;
            }
        });
        getSettings().setUseWideViewPort(true);
        getSettings().setLoadWithOverviewMode(true);
        setInitialScale(0);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String url = u.toString();
                if (FeedProxy.handles(u)) {
                    if (!"GET".equalsIgnoreCase(r.getMethod())) return Hardened.blocked();
                    return feeds.serve(u);
                }
                NetPolicy.Decision d = NetPolicy.decide(url, r.getMethod(), SandboxView.this.granted);
                if (d == NetPolicy.Decision.LOCAL && NetPolicy.VOICE_HOST.equals(u.getHost())) {
                    // Read-aloud through Friday's voice (ChannelVoice): open channels only, never the screensaver.
                    Map<String, String> h = new HashMap<>();
                    h.put("Access-Control-Allow-Origin", "*");
                    h.put("Cache-Control", "no-store");
                    if (!voiceAllowed || !"GET".equalsIgnoreCase(r.getMethod())) return Hardened.response(403, "application/json", "{}".getBytes(StandardCharsets.UTF_8), h);
                    ChannelVoice.Answer a = ChannelVoice.handle(u.getPath(), u.getQueryParameter("t"));
                    return Hardened.response(a.status, "application/json", a.json.getBytes(StandardCharsets.UTF_8), h);
                }
                if (d == NetPolicy.Decision.LOCAL) {
                    if (host.equals(u.getHost()) && ("/".equals(u.getPath()) || "/index.html".equals(u.getPath()))) {
                        Map<String, String> h = new HashMap<>();
                        h.put("Content-Security-Policy", NetPolicy.csp(SandboxView.this.granted));
                        return Hardened.response(200, "text/html", page, h);
                    }
                    return Hardened.response(404, "text/plain", new byte[0], null);
                }
                if (d == NetPolicy.Decision.FETCH || d == NetPolicy.Decision.FETCH_IMAGE) {
                    // Kids mode: the word list and Claude's kid-safety check, here where the page can't get around them.
                    ai.futurespeak.friday.tv.kids.KidsContent.Gate kg = ai.futurespeak.friday.tv.kids.KidsContent.gate(url);
                    if (kg.blocked) {
                        Log.i(TAG, "channel fetch blocked in kids mode");
                        Map<String, String> h = new HashMap<>();
                        h.put("Access-Control-Allow-Origin", "*");
                        return Hardened.response(403, "application/json", kg.json(), h);
                    }
                }
                if (d == NetPolicy.Decision.FETCH_IMAGE) {
                    // A picture from a sibling of a granted host (thumb.wikimedia.org for upload.wikimedia.org): images only.
                    try {
                        SafeFetch.Result res = SafeFetch.get(url, h -> SandboxView.this.granted.contains(h) || NetPolicy.siblingOf(h, SandboxView.this.granted),
                                t -> t.startsWith("image/"), MAX_FETCH);
                        Map<String, String> h = new HashMap<>();
                        h.put("Access-Control-Allow-Origin", "*");
                        return Hardened.response(res.status == 200 ? 200 : res.status, res.contentType, res.body, h);
                    } catch (Exception e) {
                        Log.i(TAG, "channel picture refused (" + e.getMessage() + ")");
                        if (blockedHost == null) blockedHost = u.getHost();
                        return Hardened.blocked();
                    }
                }
                if (d == NetPolicy.Decision.FETCH) {
                    try {
                        SafeFetch.Result res = SafeFetch.get(url, h -> SandboxView.this.granted.contains(h),
                                t -> t.startsWith("application/json") || t.startsWith("text/") || t.startsWith("image/")
                                        || t.endsWith("+json") || t.isEmpty(), MAX_FETCH);
                        Map<String, String> h = new HashMap<>();
                        h.put("Access-Control-Allow-Origin", "*");
                        String type = res.contentType.isEmpty() ? "application/octet-stream" : res.contentType;
                        byte[] body = ai.futurespeak.friday.tv.kids.KidsContent.filter(res.body, type);   // kids mode: listed titles out of results
                        return Hardened.response(res.status == 200 ? 200 : res.status, type, body, h);
                    } catch (Exception e) {
                        Log.i(TAG, "channel fetch refused (" + e.getMessage() + ")");
                        if (blockedHost == null) blockedHost = u.getHost();
                        return Hardened.blocked();
                    }
                }
                if (blockedHost == null && u.getHost() != null && !"data".equals(u.getScheme())) blockedHost = u.getHost();
                return Hardened.blocked();
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (url == null || url.startsWith("about:")) return;
                finished = true;
                if (pendingCheck != null) { RenderCheck c = pendingCheck; pendingCheck = null; checkSoon(c); }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
        });
    }

    public void start() { loadUrl("https://" + host + "/"); }

    private volatile boolean voiceAllowed = false;
    /** Lets this page read aloud through Friday's voice (an open channel; never the screensaver). */
    public void allowVoice(boolean on) { voiceAllowed = on; }

    private long lastBack = 0;

    /**
     * The remote's Back, offered to the page first as an Escape keydown. If the page handles it
     * (calls preventDefault, e.g. going back inside its own screens), the channel stays open;
     * otherwise {@code leave} runs. Two Backs within 0.7 s, or a page that doesn't answer in
     * 0.5 s, always leave: a broken page can never trap anyone.
     */
    public void offerBack(Runnable leave) {
        long now = android.os.SystemClock.uptimeMillis();
        boolean twice = now - lastBack < 700;
        lastBack = now;
        if (twice) { leave.run(); return; }
        boolean[] answered = {false};
        evaluateJavascript(BACK_JS, v -> {
            if (answered[0]) return;
            answered[0] = true;
            if (!"true".equals(v)) leave.run();
        });
        postDelayed(() -> { if (!answered[0]) { answered[0] = true; leave.run(); } }, 500);
    }

    static final String BACK_JS = "(function(){try{var e=new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true});"
            + "(document.activeElement||document.body||document).dispatchEvent(e);return e.defaultPrevented;}catch(x){return false;}})()";

    /**
     * Calls back once the page has had a few seconds to draw: null if it shows something,
     * otherwise what went wrong in plain words (a script error, a blank page, a blocked site).
     */
    public void verify(RenderCheck cb) {
        if (finished) checkSoon(cb); else pendingCheck = cb;
        postDelayed(() -> { if (pendingCheck == cb) { pendingCheck = null; cb.result("its page never finished loading"); } }, 15000);
    }

    private void checkSoon(RenderCheck cb) {
        postDelayed(() -> evaluateJavascript(
                "(function(){var b=document.body;if(!b)return '0|0';var t=(b.innerText||'').replace(/\\s+/g,' ').trim();"
                        + "var n=0;b.querySelectorAll('canvas,svg,img,video').forEach(function(e){var r=e.getBoundingClientRect();if(r.width>40&&r.height>40)n++;});"
                        + "return t.length+'|'+n;})()",
                v -> {
                    String s = v == null ? "" : v.replace("\"", "");
                    int text = 0, media = 0;
                    try { String[] p = s.split("\\|"); text = Integer.parseInt(p[0]); media = Integer.parseInt(p[1]); } catch (Exception ignored) { }
                    cb.result(RenderVerdict.problem(text, media, jsError, blockedHost, feeds.lastProblem));
                }), 3500);
    }

    /** The page with the policy as its first element, so it applies before any of its own code. */
    static String wrap(String html, String csp) {
        // Pages are written for a 1920x1080 TV (the system prompt says so); lay them out at that width.
        String meta = "<meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"" + csp.replace("\"", "&quot;") + "\">"
                + "<meta name=\"viewport\" content=\"width=1920\">" + ChannelVoice.POLYFILL;
        // The page's own viewport tag (usually width=device-width) would win; drop it.
        html = html.replaceAll("(?i)<meta[^>]+name\\s*=\\s*[\"']?viewport[\"']?[^>]*>", "");
        String lower = html.toLowerCase(java.util.Locale.ROOT);
        int head = lower.indexOf("<head>");
        if (head >= 0) return html.substring(0, head + 6) + meta + html.substring(head + 6);
        return "<!doctype html><html><head>" + meta + "</head><body>" + html + "</body></html>";
    }

    /** Clears the page and its timers before the view is thrown away. */
    public void shutdown() {
        try {
            stopLoading();
            loadUrl("about:blank");
            onPause();
            removeAllViews();
            destroy();
        } catch (Throwable ignored) { }
    }
}
