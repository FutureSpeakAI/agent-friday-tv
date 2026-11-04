package ai.futurespeak.friday.tv.web;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import ai.futurespeak.friday.tv.cards.CardSpec;
import ai.futurespeak.friday.tv.cards.HostRules;

/**
 * Draws text, list, table, image and chart cards with the app's own renderer
 * (assets/cards/cards.js). Card content is data, never code: the renderer only sets text.
 * The one way out is the image proxy (/img?u=...), which fetches https images from public
 * hosts natively and returns them only if they really are images.
 */
@SuppressLint("ViewConstructor")
public final class CardView extends WebView {
    static final String HOST = "cards.friday.local";
    private static final int MAX_IMAGE = 8 * 1024 * 1024;
    private String pendingJson = null;
    private boolean ready = false;

    public CardView(Context c) {
        super(c);
        Hardened.apply(this, false);
        setBackgroundColor(0);   // transparent: Friday stays visible behind the card
        setFocusable(false);
        setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!"https".equals(u.getScheme()) || !HOST.equals(u.getHost())) return Hardened.blocked();
                String path = u.getPath() == null ? "/" : u.getPath();
                if (path.equals("/img")) return image(u.getQueryParameter("u"));
                if (path.equals("/gen")) return made(u.getQueryParameter("id"));
                if (path.startsWith("/")) path = path.substring(1);
                if (path.startsWith("fonts/")) return Hardened.asset(getContext(), "avatar/desktop/" + path.substring(6));
                return Hardened.asset(getContext(), "cards/" + path);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
            @Override public void onPageFinished(WebView v, String url) {
                ready = true;
                if (pendingJson != null) { show(pendingJson); pendingJson = null; }
            }
        });
        loadUrl("https://" + HOST + "/cards.html");
    }

    /** A picture the TV made (slice T3): only from the app's own images folder, by plain id. */
    private WebResourceResponse made(String id) {
        java.io.File f = new ai.futurespeak.friday.tv.actions.Images.Store(getContext().getFilesDir()).file(id);
        if (f == null || f.length() > MAX_IMAGE) return Hardened.response(404, "text/plain", new byte[0], null);
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            String mime = ai.futurespeak.friday.tv.actions.Images.sniff(b);
            if (mime == null) return Hardened.response(404, "text/plain", new byte[0], null);
            return Hardened.response(200, mime, b, null);
        } catch (Exception e) {
            return Hardened.response(404, "text/plain", new byte[0], null);
        }
    }

    private WebResourceResponse image(String url) {
        String safe = url == null ? null : CardSpec.safeImageUrl(url);
        if (safe == null) return Hardened.blocked();
        try {
            SafeFetch.Result r = SafeFetch.get(safe, HostRules::isPublicHostname,
                    t -> t.startsWith("image/") && !t.equals("image/svg+xml"), MAX_IMAGE);
            if (r.status != 200) return Hardened.response(404, "text/plain", new byte[0], null);
            return Hardened.response(200, r.contentType, r.body, null);
        } catch (Exception e) {
            return Hardened.response(404, "text/plain", new byte[0], null);
        }
    }

    /** Shows one validated card (from {@link CardSpec}). */
    public void show(JSONObject card) { show(card.toString()); }

    private void show(String json) {
        if (!ready) { pendingJson = json; return; }
        evaluateJavascript("FridayCards.show(" + JSONObject.quote(json) + ")", null);
    }

    /** Arrow keys scroll the card. */
    public void scrollBy(int direction) {
        evaluateJavascript("FridayCards.scroll(" + direction + ")", null);
    }
}
