package ai.futurespeak.friday.tv.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

import ai.futurespeak.friday.tv.web.CardView;
import ai.futurespeak.friday.tv.web.SandboxView;

/** Claude's cards, full screen. Left/Right: next card. Up/Down: scroll. Back: close. */
public final class CardLayer extends Layer {
    /** Who is answering (set by the app when the brain changes), so a card never says Claude when another model wrote it. */
    public static volatile String who = "Claude";
    private final List<JSONObject> cards;
    private final FrameLayout stage;
    private final TextView pager, hint;
    private CardView cardView;
    private SandboxView sandbox;
    private int index = 0;
    private final ai.futurespeak.friday.tv.cards.PinnedCards pins;

    public CardLayer(Context c, Host host, List<JSONObject> cards) { this(c, host, cards, null); }

    /** @param pins where OK pins a card for the screensaver (null: no pinning) */
    public CardLayer(Context c, Host host, List<JSONObject> cards, ai.futurespeak.friday.tv.cards.PinnedCards pins) {
        super(c, host);
        this.cards = cards;
        this.pins = pins;
        setBackgroundColor(0xC2000103);   // Friday dimmed behind the card, not hidden
        stage = new FrameLayout(c);
        addView(stage, new LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int p = Brand.dp(c, 18);
        top.setPadding(Brand.dp(c, 28), p, Brand.dp(c, 28), p);
        top.addView(Brand.wordmark(c, 15));
        pager = Brand.text(c, "", 13, Brand.DIM, Brand.display(c));
        pager.setLetterSpacing(0.12f);
        pager.setPadding(Brand.dp(c, 18), 0, 0, 0);
        top.addView(pager, new LinearLayout.LayoutParams(0, -2, 1));
        TextView powered = Brand.text(c, "POWERED BY " + who.toUpperCase(Locale.ROOT), 12, Brand.DIM, Brand.display(c));
        powered.setLetterSpacing(0.12f);
        top.addView(powered);
        addView(top, new LayoutParams(-1, -2, Gravity.TOP));

        hint = Brand.text(c, "", 15, Brand.FAINT, Brand.body(c));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, Brand.dp(c, 12));
        addView(hint, new LayoutParams(-1, -2, Gravity.BOTTOM));
        show(0);
    }

    @Override public boolean blocksTalk() { return false; }

    /** The card plus how to draw it: the accent, the Motion level and who answered (cards.js reads "_look"). */
    private JSONObject withLook(JSONObject card) {
        try {
            JSONObject c = new JSONObject(card.toString());
            c.put("_look", new JSONObject().put("accent", String.format(Locale.ROOT, "#%06x", Look.accent(getContext()) & 0xFFFFFF))
                    .put("motion", Look.level(getContext()).name()).put("who", who));
            return c;
        } catch (org.json.JSONException e) { return card; }
    }

    private void show(int i) {
        index = Math.max(0, Math.min(cards.size() - 1, i));
        JSONObject card = cards.get(index);
        boolean html = "html".equals(card.optString("type"));
        if (sandbox != null) { stage.removeView(sandbox); sandbox.shutdown(); sandbox = null; }
        if (html) {
            if (cardView != null) cardView.setVisibility(View.GONE);
            sandbox = new SandboxView(getContext(), freshId(), card.optString("html"), null);
            stage.addView(sandbox, new LayoutParams(-1, -1));
            sandbox.start();
            sandbox.requestFocus();
        } else {
            if (cardView == null) { cardView = new CardView(getContext()); stage.addView(cardView, 0, new LayoutParams(-1, -1)); }
            cardView.setVisibility(View.VISIBLE);
            cardView.show(withLook(card));
            requestFocus();
        }
        pager.setText(cards.size() > 1 ? String.format(Locale.ROOT, "CARD %d OF %d", index + 1, cards.size()) : "");
        String more = cards.size() > 1 ? "Left and right for more cards · " : "";
        String pin = pins != null && ai.futurespeak.friday.tv.cards.PinnedCards.pinnable(card)
                ? (pins.isPinned(card) ? "📌 Pinned · OK to unpin · " : "OK to pin to the screensaver · ") : "";
        hint.setText(html ? "Interactive card · Back to close" : pin + more + "Up and down to scroll · Back to close");
    }

    @Override public void focusFirst() {
        if (sandbox != null) sandbox.requestFocus(); else { setFocusable(true); requestFocus(); }
    }

    @Override public boolean key(KeyEvent e) {
        if (sandbox != null) return false;   // an interactive card gets its keys
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (index + 1 < cards.size()) show(index + 1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (index > 0) show(index - 1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (cardView != null) cardView.scrollBy(1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: if (cardView != null) cardView.scrollBy(-1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (pins != null && e.getRepeatCount() == 0) { pins.toggle(cards.get(index)); show(index); }
                return true;
            default: return false;
        }
    }

    @Override public void closed() {
        if (sandbox != null) { sandbox.shutdown(); sandbox = null; }
        if (cardView != null) { cardView.destroy(); cardView = null; }
    }

    private static String freshId() {
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder("card");
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }
}
