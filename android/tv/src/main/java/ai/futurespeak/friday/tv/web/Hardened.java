package ai.futurespeak.friday.tv.web;

import android.content.Context;
import android.content.res.AssetManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Settings every WebView in the app shares: no file or content access, no bridges, no popups. */
public final class Hardened {
    private Hardened() {}

    public static void apply(WebView w, boolean domStorage) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setDomStorageEnabled(domStorage);
        s.setDatabaseEnabled(false);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSaveFormData(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (android.os.Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(false);
        w.removeJavascriptInterface("searchBoxJavaBridge_");
        w.removeJavascriptInterface("accessibility");
        w.removeJavascriptInterface("accessibilityTraversal");
        w.setWebChromeClient(new QuietChrome());
        w.setLongClickable(false);
        w.setDefaultFocusHighlightEnabled(false);
        w.setHapticFeedbackEnabled(false);
        w.setBackgroundColor(0xFF000103);
    }

    /** Denies camera/mic/etc., swallows alert/confirm/prompt (a page must not raise system-looking dialogs). */
    public static class QuietChrome extends WebChromeClient {
        @Override public void onPermissionRequest(PermissionRequest r) { r.deny(); }
        @Override public boolean onJsAlert(WebView v, String u, String m, JsResult r) { r.cancel(); return true; }
        @Override public boolean onJsConfirm(WebView v, String u, String m, JsResult r) { r.cancel(); return true; }
        @Override public boolean onJsPrompt(WebView v, String u, String m, String d, JsPromptResult r) { r.cancel(); return true; }
        @Override public boolean onJsBeforeUnload(WebView v, String u, String m, JsResult r) { r.confirm(); return true; }
        @Override public boolean onCreateWindow(WebView v, boolean d, boolean g, android.os.Message m) { return false; }
    }

    public static WebResourceResponse blocked() {
        return response(403, "text/plain", new byte[0], null);
    }

    public static WebResourceResponse response(int status, String mime, byte[] body, Map<String, String> headers) {
        Map<String, String> h = headers == null ? new HashMap<>() : new HashMap<>(headers);
        h.put("Cache-Control", "no-store");
        String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : status == 403 ? "Forbidden" : "Status";
        return new WebResourceResponse(mime, "utf-8", status, reason, h, new ByteArrayInputStream(body));
    }

    /** Serves a file from the APK's assets, or 404. */
    public static WebResourceResponse asset(Context c, String path) {
        if (path.contains("..")) return response(404, "text/plain", new byte[0], null);
        AssetManager am = c.getAssets();
        try {
            InputStream in = am.open(path);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-store");
            return new WebResourceResponse(mime(path), "utf-8", 200, "OK", h, in);
        } catch (IOException e) {
            return response(404, "text/plain", new byte[0], null);
        }
    }

    static String mime(String p) {
        String l = p.toLowerCase(Locale.ROOT);
        if (l.endsWith(".html")) return "text/html";
        if (l.endsWith(".js")) return "text/javascript";
        if (l.endsWith(".json")) return "application/json";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".woff2")) return "font/woff2";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
