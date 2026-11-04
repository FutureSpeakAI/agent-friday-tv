package ai.futurespeak.friday.tv.pair;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import ai.futurespeak.friday.tv.ui.Brand;
import ai.futurespeak.friday.tv.ui.Layer;
import ai.futurespeak.friday.tv.ui.Look;

/**
 * "Pair with your Friday computer": fixed app UI (opened from Settings › About & keys, behind
 * the grown-up PIN). It shows the six digits and says, in the app's own words, to press on
 * the PC only if the PC shows the same digits. Nothing here can approve anything: the press
 * that pairs is on the PC.
 */
public final class PairLayer extends Layer {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PairFlow flow;
    private final LinearLayout col, actions;
    private final TextView heading, code, body;
    private final Runnable onPaired;

    public PairLayer(Context c, Host host, DesktopLink link, String tvName, Runnable onPaired) {
        super(c, host);
        this.onPaired = onPaired;
        setBackgroundColor(0xF2040610);
        col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackground(Brand.glass(c, 22));
        int p = Brand.dp(c, 24);
        col.setPadding(p, p, p, p);
        TextView label = Brand.text(c, "FRIDAY COMPUTER", 14, Look.accent(c), Brand.display(c));
        label.setLetterSpacing(0.15f);
        col.addView(label);
        heading = Brand.text(c, "", 24, Color.WHITE, Brand.bodyBold(c));
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, Brand.dp(c, 12), 0, Brand.dp(c, 6));
        col.addView(heading);
        code = Brand.text(c, "", 64, Brand.WARN, Brand.display(c));
        code.setLetterSpacing(0.12f);
        code.setVisibility(GONE);
        col.addView(code);
        body = Brand.text(c, "", 17, Brand.DIM, Brand.body(c));
        body.setGravity(Gravity.CENTER);
        body.setMaxWidth(Brand.dp(c, 640));
        col.addView(body);
        actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER_HORIZONTAL);
        actions.setPadding(0, Brand.dp(c, 14), 0, 0);
        col.addView(actions);
        TextView hint = Brand.text(c, "Back to cancel", 13, Brand.FAINT, Brand.body(c));
        hint.setPadding(0, Brand.dp(c, 10), 0, 0);
        col.addView(hint);
        addView(col, new FrameLayout.LayoutParams(Brand.dp(c, 720), -2, Gravity.CENTER));

        flow = new PairFlow(link, (step, detail, found) -> main.post(() -> render(step, detail, found)),
                PairFlow.lan(), Thread::sleep, tvName);
        background(flow::search);
    }

    private void background(Runnable r) {
        Thread t = new Thread(r, "pairing");
        t.setDaemon(true);
        t.start();
    }

    private void render(PairFlow.Step step, String detail, List<DesktopLink.Hint> found) {
        Context c = getContext();
        actions.removeAllViews();
        code.setVisibility(GONE);
        switch (step) {
            case SEARCHING:
                heading.setText("Looking for your Friday computer…");
                body.setText("It needs to be on, on this home network, with TV pairing turned on.");
                break;
            case NONE:
                heading.setText("No Friday computer answered");
                body.setText("On your PC, open Settings › Connections › Paired devices and turn on TV pairing. "
                        + "If it's on, the PC's firewall or VPN may be blocking the home network.");
                button(c, "Look again", () -> background(flow::search));
                break;
            case FOUND:
                heading.setText(found.size() == 1 ? "Found your Friday computer" : "Found " + found.size() + " Friday computers");
                body.setText("Choose one to pair with.");
                for (int i = 0; i < found.size(); i++) {
                    final int n = i;
                    button(c, "Pair with " + found.get(i).host, () -> background(() -> flow.choose(n)));
                }
                break;
            case CONNECTING:
                heading.setText("Asking your Friday computer…");
                body.setText("");
                break;
            case CODE:
                heading.setText("Does your PC show the same number?");
                code.setText(PairProtocol.spoken(detail));
                code.setVisibility(VISIBLE);
                body.setText("Your PC now shows a card. Press Approve there only if it shows this number. "
                        + "If the numbers are different, press Decline: something else answered.");
                break;
            case PAIRED:
                heading.setText("Paired with your Friday computer");
                body.setText("When the PC is on, Friday answers from it. When it's off, Friday answers right here on the TV, "
                        + "and the top of the screen says so. On your PC, you can choose what this TV may do.");
                button(c, "Done", () -> { host.pop(this); if (onPaired != null) onPaired.run(); });
                break;
            case REFUSED:
                heading.setText("Not paired");
                body.setText("The pairing was declined on the PC. Nothing was changed on this TV.");
                button(c, "Close", () -> host.pop(this));
                break;
            case EXPIRED:
                heading.setText("Not paired");
                body.setText("No one pressed Approve on the PC in time. Nothing was changed on this TV.");
                button(c, "Try again", () -> background(flow::search));
                break;
            case FAILED:
                heading.setText("Not paired");
                body.setText("Pairing stopped: " + (detail == null ? "something went wrong." : detail)
                        + " Nothing was changed on this TV.");
                button(c, "Try again", () -> background(flow::search));
                break;
        }
        if (actions.getChildCount() > 0) actions.getChildAt(0).requestFocus();
    }

    private void button(Context c, String label, Runnable onClick) {
        TextView b = Brand.button(c, label, Look.accent(c));
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Brand.dp(c, 8);
        actions.addView(b, lp);
    }

    @Override public void back() { flow.cancel(); host.pop(this); }

    @Override public void closed() { flow.cancel(); }

    @Override public void focusFirst() {
        if (actions.getChildCount() > 0) actions.getChildAt(0).requestFocus();
        else requestFocus();
    }
}
