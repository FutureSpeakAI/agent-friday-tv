package ai.futurespeak.friday.tv.ambient;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * Friday's constellation as a still picture: a faint violet/cyan nebula, a field of stars and
 * a few constellation lines, drawn once into a bitmap (no WebGL, no animation loop). It is
 * redrawn with a new seed every few minutes so no star sits on the same pixels for long.
 */
public final class Starfield extends View {
    private Bitmap bmp;
    private long seed;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Starfield(Context c, long seed) { super(c); this.seed = seed; }

    public void reseed(long s) { seed = s; bmp = null; invalidate(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { bmp = null; }

    @Override protected void onDraw(Canvas c) {
        if (getWidth() == 0 || getHeight() == 0) return;
        if (bmp == null) bmp = render(getWidth(), getHeight(), seed);
        c.drawBitmap(bmp, 0, 0, null);
    }

    static Bitmap render(int w, int h, long seed) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);   // half the memory of ARGB
        Canvas c = new Canvas(b);
        c.drawColor(0xFF010208);
        Random r = new Random(seed);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // nebula: two soft glows
        float cx = w * (0.30f + r.nextFloat() * 0.4f), cy = h * (0.35f + r.nextFloat() * 0.3f);
        p.setShader(new RadialGradient(cx, cy, w * 0.55f, new int[]{0x3A3B2A8F, 0x1A0B3A5A, 0x00000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        float cx2 = w - cx * 0.7f, cy2 = h - cy * 0.6f;
        p.setShader(new RadialGradient(cx2, cy2, w * 0.35f, new int[]{0x2600B4D8, 0x00000000}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // stars
        int n = (int) (w * h / 5200f);
        float[] bx = new float[24], by = new float[24];
        int bright = 0;
        for (int i = 0; i < n; i++) {
            float x = r.nextFloat() * w, y = r.nextFloat() * h;
            float m = r.nextFloat();
            float rad = m > 0.985f ? 2.2f : m > 0.9f ? 1.4f : 0.8f;
            int a = (int) (60 + 195 * Math.pow(r.nextFloat(), 2));
            int tint = r.nextFloat() < 0.25f ? 0x9FE6FF : r.nextFloat() < 0.1f ? 0xC9B8FF : 0xFFFFFF;
            p.setColor((a << 24) | tint);
            c.drawCircle(x, y, rad, p);
            if (rad > 2f && bright < bx.length) { bx[bright] = x; by[bright] = y; bright++; }
        }
        // constellation lines between nearby bright stars
        p.setStrokeWidth(1.2f);
        p.setColor(0x3300D4FF);
        for (int i = 0; i < bright; i++) {
            int best = -1; float bd = Float.MAX_VALUE;
            for (int j = 0; j < bright; j++) {
                if (j == i) continue;
                float d = (bx[i] - bx[j]) * (bx[i] - bx[j]) + (by[i] - by[j]) * (by[i] - by[j]);
                if (d < bd) { bd = d; best = j; }
            }
            if (best >= 0 && bd < (w * 0.18f) * (w * 0.18f)) c.drawLine(bx[i], by[i], bx[best], by[best], p);
        }
        // Friday's core: a soft glow where the avatar lives
        float ox = w * 0.5f, oy = h * 0.48f;
        p.setShader(new RadialGradient(ox, oy, h * 0.22f, new int[]{0x2200D4FF, 0x107B61FF, 0x00000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(ox, oy, h * 0.22f, p);
        return b;
    }
}
