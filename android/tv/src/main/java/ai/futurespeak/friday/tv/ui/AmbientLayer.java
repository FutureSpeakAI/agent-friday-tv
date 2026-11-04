package ai.futurespeak.friday.tv.ui;

import android.content.Context;
import android.view.KeyEvent;

import ai.futurespeak.friday.tv.ambient.AmbientView;

/** The screensaver, previewed inside the app. Any key closes it. */
public final class AmbientLayer extends Layer {
    private final AmbientView view;

    public AmbientLayer(Context c, Host host, AmbientView view) {
        super(c, host);
        this.view = view;
        addView(view, new LayoutParams(-1, -1));
        setFocusable(true);
        view.start();
    }

    @Override public boolean key(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) host.pop(this);
        return true;
    }

    @Override public void focusFirst() { requestFocus(); }
    @Override public void closed() { view.stop(); }
}
