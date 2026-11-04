package ai.futurespeak.friday.tv.ui;

import android.content.Context;
import android.view.KeyEvent;
import android.widget.FrameLayout;

/** A full-screen layer over the home screen (cards, a channel, settings, an approval, the PIN pad). */
public abstract class Layer extends FrameLayout {
    public interface Host {
        void pop(Layer l);
        void push(Layer l);
    }

    protected final Host host;

    protected Layer(Context c, Host host) {
        super(c);
        this.host = host;
        setClickable(true);   // nothing behind a layer can be reached
        // Since Android 8 a clickable view is focusable by default; the layer itself must never
        // hold focus (it would let the arrows wander to the home screen behind it).
        setFocusable(NOT_FOCUSABLE);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setDefaultFocusHighlightEnabled(false);
    }

    /**
     * Focus never leaves a layer for the home screen behind it: arrows at an edge stay put.
     * (Without this, Down from the last control could land on the home screen's root view.)
     */
    @Override public android.view.View focusSearch(android.view.View focused, int direction) {
        android.view.View next = super.focusSearch(focused, direction);
        if (next == null) return focused;
        for (android.view.ViewParent p = next.getParent(); p != null; p = p.getParent()) if (p == this) return next;
        return focused;
    }

    /** Return true if the key was handled. Back is routed to {@link #back()}. */
    public boolean key(KeyEvent e) { return false; }

    /** Back pressed: by default the layer closes. */
    public void back() { host.pop(this); }

    /** Called once when removed. Free WebViews here. */
    public void closed() { }

    /** Fixed app UI (approvals, PIN, settings): talking is paused while it is up. */
    public boolean blocksTalk() { return true; }

    /** First view to focus. */
    public void focusFirst() { requestFocus(); }
}
