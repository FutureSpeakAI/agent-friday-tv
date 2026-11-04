package ai.futurespeak.friday.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small FutureSpeak line icons (accent strokes, round caps): the settings column, the rail and cards. One set, so icons match everywhere. */
public final class LineIcon extends View {
    private final String name;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = 0xFF00D4FF;

    public LineIcon(Context c, String name) {
        super(c);
        this.name = name;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setColor(int c) { color = c; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        p.setColor(color);
        draw(c, name, getWidth(), getHeight(), p);
    }

    /** As a Drawable (e.g. a rail tile's top icon): accent when the view is focused, soft white otherwise. */
    public static final class Glyph extends android.graphics.drawable.Drawable {
        private final String name;
        private final int on, off;
        private final Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean focused;
        public Glyph(String name, int on, int off) {
            this.name = name; this.on = on; this.off = off;
            gp.setStyle(Paint.Style.STROKE); gp.setStrokeCap(Paint.Cap.ROUND); gp.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            gp.setColor(focused ? on : off);
            c.save(); c.translate(b.left, b.top);
            LineIcon.draw(c, name, b.width(), b.height(), gp);
            c.restore();
        }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) {
            boolean f = false;
            for (int s : state) if (s == android.R.attr.state_focused) f = true;
            if (f == focused) return false;
            focused = f; invalidateSelf(); return true;
        }
        @Override public void setAlpha(int a) { gp.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { gp.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    static void draw(Canvas c, String name, float w, float h, Paint p) {
        float s = Math.min(w, h), u = s / 24f;
        c.save();
        c.translate((w - s) / 2f, (h - s) / 2f);
        p.setStrokeWidth(1.8f * u);
        p.setStyle(Paint.Style.STROKE);
        RectF r = new RectF();
        Path path = new Path();
        switch (name) {
            case "voice":      // microphone
                r.set(9 * u, 3 * u, 15 * u, 14 * u); c.drawRoundRect(r, 3 * u, 3 * u, p);
                path.moveTo(6 * u, 11 * u); path.cubicTo(6 * u, 19 * u, 18 * u, 19 * u, 18 * u, 11 * u); c.drawPath(path, p);
                c.drawLine(12 * u, 17.5f * u, 12 * u, 21 * u, p); c.drawLine(9 * u, 21 * u, 15 * u, 21 * u, p);
                break;
            case "picture":    // frame with a mountain and sun
                r.set(3 * u, 5 * u, 21 * u, 19 * u); c.drawRoundRect(r, 2.5f * u, 2.5f * u, p);
                path.moveTo(4 * u, 17 * u); path.lineTo(9.5f * u, 11 * u); path.lineTo(13 * u, 14.5f * u); path.lineTo(15.5f * u, 12.5f * u); path.lineTo(20 * u, 17 * u); c.drawPath(path, p);
                c.drawCircle(16 * u, 8.8f * u, 1.4f * u, p);
                break;
            case "screensaver": // crescent and stars
                path.addCircle(11 * u, 12 * u, 7 * u, Path.Direction.CW);
                c.drawArc(new RectF(4 * u, 5 * u, 18 * u, 19 * u), 60, 240, false, p);
                c.drawArc(new RectF(8.5f * u, 5.5f * u, 18.5f * u, 15.5f * u), 105, 150, false, p);
                star(c, 19 * u, 5 * u, 1.6f * u, p); star(c, 20 * u, 12 * u, 1.1f * u, p);
                break;
            case "listening":  // sound wave
                float[] hs = {4, 8, 13, 8, 4, 10, 6};
                for (int i = 0; i < hs.length; i++) { float x = (4 + i * 2.7f) * u, hh = hs[i] * u / 2; c.drawLine(x, 12 * u - hh, x, 12 * u + hh, p); }
                break;
            case "channels":   // TV with a play mark
                r.set(3 * u, 5 * u, 21 * u, 17 * u); c.drawRoundRect(r, 2.5f * u, 2.5f * u, p);
                c.drawLine(8 * u, 20.5f * u, 16 * u, 20.5f * u, p);
                path.moveTo(10.5f * u, 8.5f * u); path.lineTo(15 * u, 11 * u); path.lineTo(10.5f * u, 13.5f * u); path.close(); c.drawPath(path, p);
                break;
            case "privacy":    // shield with a check
                path.moveTo(12 * u, 3 * u); path.lineTo(19.5f * u, 6 * u); path.lineTo(19.5f * u, 11.5f * u);
                path.cubicTo(19.5f * u, 16.5f * u, 16 * u, 19.5f * u, 12 * u, 21 * u); path.cubicTo(8 * u, 19.5f * u, 4.5f * u, 16.5f * u, 4.5f * u, 11.5f * u);
                path.lineTo(4.5f * u, 6 * u); path.close(); c.drawPath(path, p);
                Path k = new Path(); k.moveTo(8.5f * u, 12 * u); k.lineTo(11 * u, 14.5f * u); k.lineTo(15.5f * u, 9.5f * u); c.drawPath(k, p);
                break;
            case "family":     // two people
                c.drawCircle(9 * u, 8 * u, 3 * u, p); c.drawCircle(16.5f * u, 9.5f * u, 2.3f * u, p);
                c.drawArc(new RectF(3 * u, 13 * u, 15 * u, 25 * u), 180, 180, false, p);
                c.drawArc(new RectF(12.5f * u, 14 * u, 20.5f * u, 22 * u), 180, 180, false, p);
                break;
            case "chart":      // bars with a slash: no analytics
                c.drawLine(6 * u, 19 * u, 6 * u, 13 * u, p); c.drawLine(11 * u, 19 * u, 11 * u, 8 * u, p); c.drawLine(16 * u, 19 * u, 16 * u, 11 * u, p);
                c.drawLine(3.5f * u, 20.5f * u, 20.5f * u, 3.5f * u, p);
                break;
            case "history":    // clock face
                c.drawCircle(12 * u, 12 * u, 8.5f * u, p);
                c.drawLine(12 * u, 12 * u, 12 * u, 7 * u, p); c.drawLine(12 * u, 12 * u, 15.5f * u, 14 * u, p);
                break;
            case "key":
                c.drawCircle(8 * u, 12 * u, 4 * u, p);
                c.drawLine(12 * u, 12 * u, 21 * u, 12 * u, p); c.drawLine(18 * u, 12 * u, 18 * u, 15 * u, p); c.drawLine(21 * u, 12 * u, 21 * u, 14.5f * u, p);
                break;
            case "globe":
                c.drawCircle(12 * u, 12 * u, 8.5f * u, p);
                c.drawOval(new RectF(8 * u, 3.5f * u, 16 * u, 20.5f * u), p);
                c.drawLine(3.5f * u, 12 * u, 20.5f * u, 12 * u, p);
                break;
            case "apps":       // a 2x2 grid of rounded tiles
                for (int i = 0; i < 4; i++) {
                    float x = (i % 2 == 0 ? 4 : 13) * u, y = (i < 2 ? 4 : 13) * u;
                    r.set(x, y, x + 7 * u, y + 7 * u); c.drawRoundRect(r, 1.8f * u, 1.8f * u, p);
                }
                break;
            case "did":        // a receipt with a zigzag foot and a check
                path.moveTo(6 * u, 3 * u); path.lineTo(18 * u, 3 * u); path.lineTo(18 * u, 20 * u);
                path.lineTo(16 * u, 18.5f * u); path.lineTo(14 * u, 20 * u); path.lineTo(12 * u, 18.5f * u);
                path.lineTo(10 * u, 20 * u); path.lineTo(8 * u, 18.5f * u); path.lineTo(6 * u, 20 * u); path.close(); c.drawPath(path, p);
                Path ck = new Path(); ck.moveTo(9 * u, 11 * u); ck.lineTo(11.2f * u, 13.2f * u); ck.lineTo(15 * u, 8.5f * u); c.drawPath(ck, p);
                break;
            case "news":       // a folded newspaper with headline lines
                r.set(4 * u, 5 * u, 18 * u, 19 * u); c.drawRoundRect(r, 2 * u, 2 * u, p);
                path.moveTo(18 * u, 9 * u); path.lineTo(20.5f * u, 9 * u); path.lineTo(20.5f * u, 17 * u);
                path.quadTo(20.5f * u, 19 * u, 18.5f * u, 19 * u); c.drawPath(path, p);
                c.drawLine(7 * u, 9 * u, 15 * u, 9 * u, p); c.drawLine(7 * u, 12.5f * u, 15 * u, 12.5f * u, p); c.drawLine(7 * u, 16 * u, 12 * u, 16 * u, p);
                break;
            case "live":       // a dot with two broadcast arcs each side
                c.drawCircle(12 * u, 12 * u, 2.2f * u, p);
                c.drawArc(new RectF(6.5f * u, 6.5f * u, 17.5f * u, 17.5f * u), 135, 90, false, p);
                c.drawArc(new RectF(6.5f * u, 6.5f * u, 17.5f * u, 17.5f * u), -45, 90, false, p);
                c.drawArc(new RectF(2.5f * u, 2.5f * u, 21.5f * u, 21.5f * u), 140, 80, false, p);
                c.drawArc(new RectF(2.5f * u, 2.5f * u, 21.5f * u, 21.5f * u), -40, 80, false, p);
                break;
            case "settings":   // a gear: ring and eight teeth
                c.drawCircle(12 * u, 12 * u, 3 * u, p);
                c.drawCircle(12 * u, 12 * u, 6.5f * u, p);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    c.drawLine(12 * u + (float) Math.cos(a) * 6.5f * u, 12 * u + (float) Math.sin(a) * 6.5f * u,
                            12 * u + (float) Math.cos(a) * 9f * u, 12 * u + (float) Math.sin(a) * 9f * u, p);
                }
                break;
            default:           // about: a four-point spark
                path.moveTo(12 * u, 3 * u); path.quadTo(13 * u, 11 * u, 21 * u, 12 * u); path.quadTo(13 * u, 13 * u, 12 * u, 21 * u);
                path.quadTo(11 * u, 13 * u, 3 * u, 12 * u); path.quadTo(11 * u, 11 * u, 12 * u, 3 * u); path.close(); c.drawPath(path, p);
        }
        c.restore();
    }

    private static void star(Canvas c, float x, float y, float r, Paint p) {
        c.drawLine(x - r, y, x + r, y, p);
        c.drawLine(x, y - r, x, y + r, p);
    }
}
