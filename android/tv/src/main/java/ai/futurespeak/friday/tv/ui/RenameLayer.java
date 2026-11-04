package ai.futurespeak.friday.tv.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/** Rename a channel: the TV's own keyboard, then Save. Fixed app UI. */
public final class RenameLayer extends Layer {
    private final EditText field;

    public RenameLayer(Context c, Host host, String title, String current, Consumer<String> onSave) {
        super(c, host);
        setBackgroundColor(0xE6020309);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Brand.glass(c, 20));
        int p = Brand.dp(c, 30);
        box.setPadding(p, p, p, p);
        TextView eyebrow = Brand.text(c, "RENAME CHANNEL", 10.5f, Look.accent(c), Brand.display(c));
        eyebrow.setLetterSpacing(0.3f);
        box.addView(eyebrow);
        TextView t = Brand.text(c, title, 22, 0xFFFFFFFF, Brand.bodyBold(c));
        t.setPadding(0, Brand.dp(c, 8), 0, Brand.dp(c, 18));
        box.addView(t);

        field = new EditText(c);
        field.setText(current);
        field.setSelectAllOnFocus(true);
        field.setSingleLine(true);
        field.setTextSize(20);
        field.setTextColor(0xFFFFFFFF);
        field.setTypeface(Brand.body(c));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x26FFFFFF);
        g.setCornerRadius(Brand.dp(c, 12));
        g.setStroke(Brand.dp(c, 2), Look.accent(c));
        field.setBackground(g);
        field.setPadding(Brand.dp(c, 16), Brand.dp(c, 12), Brand.dp(c, 16), Brand.dp(c, 12));
        box.addView(field, new LinearLayout.LayoutParams(Brand.dp(c, 460), -2));

        LinearLayout buttons = new LinearLayout(c);
        buttons.setPadding(0, Brand.dp(c, 20), 0, 0);
        TextView save = Brand.button(c, "Save name", Brand.OK);
        TextView cancel = Brand.button(c, "Cancel", Brand.NEUTRAL);
        save.setOnClickListener(v -> { String s = field.getText().toString().trim(); if (!s.isEmpty()) { host.pop(this); onSave.accept(s); } });
        cancel.setOnClickListener(v -> host.pop(this));
        buttons.addView(save);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Brand.dp(c, 12);
        buttons.addView(cancel, lp);
        box.addView(buttons);
        field.setOnEditorActionListener((v, a, e) -> { save.requestFocus(); return true; });

        addView(box, new LayoutParams(-2, -2, Gravity.CENTER));
    }

    @Override public void focusFirst() {
        field.requestFocus();
        field.postDelayed(() -> {
            InputMethodManager im = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }
}
