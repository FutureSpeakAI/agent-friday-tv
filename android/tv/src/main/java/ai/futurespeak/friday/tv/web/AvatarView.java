package ai.futurespeak.friday.tv.web;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

/**
 * Friday's avatar: Friday desktop's own 3D scene (assets/avatar, synced byte for byte from
 * the desktop repo) in a WebView. Local assets only; every other request is refused.
 * The app drives it one way, through window.fridayTV; the page has no way back in.
 */
@SuppressLint("ViewConstructor")
public final class AvatarView extends WebView {
    static final String ORIGIN_HOST = "avatar.friday.local";
    private String lastState = "";
    private boolean loaded = false;

    /** Auto quality settled on a ladder rung for this TV: the app keeps it for next launch. */
    public interface TierListener { void settled(int level); }
    private TierListener tierListener;
    public void setTierListener(TierListener l) { tierListener = l; }

    /** "fridaytv: settled level=3 tier=..." from tv_bridge.js; -1 for anything else. */
    static int settledLevel(String line) {
        if (line == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^fridaytv:\\s*settled level=(\\d)\\b").matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    public AvatarView(Context c) {
        super(c);
        Hardened.apply(this, false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        // Never focused, never an accessibility target: a focused WebView gets a focus frame.
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        // The TV's WebView does not copy page logs to logcat; the quality governor's lines are relayed.
        setWebChromeClient(new Hardened.QuietChrome() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                String s = m.message();
                if (s != null && s.startsWith("fridaytv:")) android.util.Log.i("FridayTV", "avatar " + s.substring(9).trim());
                int lv = settledLevel(s);
                if (lv >= 0 && tierListener != null) tierListener.settled(lv);
                return true;
            }
        });
        setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("https".equals(u.getScheme()) && ORIGIN_HOST.equals(u.getHost())) {
                    String path = u.getPath() == null ? "" : u.getPath();
                    if (path.startsWith("/")) path = path.substring(1);
                    return Hardened.asset(getContext(), "avatar/" + path);
                }
                return Hardened.blocked();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
            @Override public void onPageFinished(WebView v, String url) { loaded = true; }
        });
    }

    /**
     * @param quality auto | high | ultra (Settings, Picture quality)
     * @param tier    for auto: the rung this TV last settled on, or -1 the first time
     */
    public void start(String quality, boolean reduced, int structure, int tier) {
        loaded = false;
        loadUrl(String.format(Locale.ROOT, "https://%s/avatar.html?quality=%s&reduced=%d&structure=%d&state=idle%s",
                ORIGIN_HOST, quality, reduced ? 1 : 0, Math.max(0, structure), tier >= 0 ? "&tier=" + tier : ""));
    }

    /** Never focused: WebView can turn its own focusability back on as it loads. */
    @Override public boolean requestFocus(int direction, android.graphics.Rect previous) { return false; }
    @Override public void addFocusables(java.util.ArrayList<android.view.View> views, int direction, int mode) { }

    public void setQuality(String quality) { js("fridayTV.quality('" + quality + "')"); }

    /** idle | listening | thinking | speaking */
    public void setState(String s) {
        if (s.equals(lastState)) return;
        lastState = s;
        js("fridayTV.state('" + s + "')");
    }

    public void setLevel(double a) {
        js("fridayTV.amp(" + String.format(Locale.ROOT, "%.3f", Math.max(0, Math.min(1, a))) + ")");
    }

    public void setStructure(int i) { js("fridayTV.structure(" + i + ")"); }

    public void setReduced(boolean on) { js("fridayTV.reduced(" + on + ")"); }

    /** Holds the last frame for {@code ms} (Home: while focus moves, the tiles get the GPU). */
    public void hold(int ms) { js("fridayTV.hold(" + ms + ")"); }

    public void status(android.webkit.ValueCallback<String> cb) {
        evaluateJavascript("window.fridayTV ? fridayTV.status() : '{}'", cb);
    }

    private void js(String expr) {
        evaluateJavascript("window.fridayTV && " + expr, null);
    }

    public boolean isLoaded() { return loaded; }
}
