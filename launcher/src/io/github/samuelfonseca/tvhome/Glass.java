package io.github.samuelfonseca.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A frosted glass panel, like tvOS: the wallpaper behind it, blurred, under a light tint, a soft
 * gloss across the top, and a fine edge that catches the light top-left. Android can't blur
 * what's behind a view before 12, so each panel draws its own blurred copy of the wallpaper,
 * lined up with the screen (see {@link Backdrop}).
 */
final class Glass extends Drawable {
    // Smoky glass: the blur shaded, so the photo shows through faintly, with a trace of lift.
    // Laid into the blurred wallpaper once per photo (see Backdrop.blur) rather than drawn over
    // it every frame.
    private static final int SHADE = 0x70000000, TINT = 0x08FFFFFF;

    private final Backdrop backdrop;
    private final float radius, edge;
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int washColor;
    private final RectF box = new RectF();
    private final Matrix place = new Matrix();
    private final int[] where = new int[2];
    private Bitmap shaderCurrent, shaderPrevious; // what the two shaders below show
    private BitmapShader current, previous;
    private int alpha = 255;

    Glass(Backdrop backdrop, float radius, float edge) {
        this.backdrop = backdrop;
        this.radius = radius;
        this.edge = edge;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(edge);
        // Softened, so the edge blends into the glass rather than outlining it.
        rim.setMaskFilter(new BlurMaskFilter(edge * 1.5f, BlurMaskFilter.Blur.NORMAL));
    }

    @Override protected void onBoundsChange(Rect b) {
        box.set(b);
        gloss.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.5f,
                0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        // Brightest top-left, faint through the middle, a little light again bottom-right.
        rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[] {0x8CFFFFFF, 0x1AFFFFFF, 0x33FFFFFF}, new float[] {0, 0.5f, 1},
                Shader.TileMode.CLAMP));
    }

    /**
     * Each part is a round rect with the panel's alpha in its paint: no clip, and no offscreen
     * layer, which on the TV's GPU costs more than the panel itself. The one exception is a
     * crossfade while the panel fades, where the old photo would show through the new one.
     */
    @Override public void draw(Canvas c) {
        if (alpha == 0) return;
        View host = getCallback() instanceof View ? (View) getCallback() : null;
        boolean photo = host != null && backdrop.current != null;
        boolean crossfading = photo && backdrop.previous != null && backdrop.fade < 1;
        boolean layer = crossfading && alpha < 255;
        int save = layer ? c.saveLayerAlpha(box, alpha) : -1;
        int a = layer ? 255 : alpha;
        if (photo) {
            // The blurred wallpaper, placed where the real one is on screen.
            View root = host.getRootView();
            host.getLocationInWindow(where);
            Bitmap now = backdrop.current;
            place.setScale(root.getWidth() / (float) now.getWidth(), root.getHeight() / (float) now.getHeight());
            place.postTranslate(-where[0], -where[1]);
            if (crossfading) {
                if (shaderPrevious != backdrop.previous) previous = shader(shaderPrevious = backdrop.previous);
                drawImage(c, previous, a);
            }
            if (shaderCurrent != now) current = shader(shaderCurrent = now);
            drawImage(c, current, Math.round(a * backdrop.fade));
        } else {
            for (int color : new int[] {0x40000000, SHADE, TINT}) {
                fill.setColor(color);
                fill.setAlpha(Math.round((color >>> 24) * a / 255f));
                c.drawRoundRect(box, radius, radius, fill);
            }
        }
        gloss.setAlpha(a);
        c.drawRoundRect(box, radius, radius, gloss);
        if (washColor != 0) {
            wash.setColor(washColor);
            wash.setAlpha(Math.round((washColor >>> 24) * a / 255f));
            c.drawRoundRect(box, radius, radius, wash);
        }
        if (layer) c.restoreToCount(save);

        float o = edge / 2;
        box.inset(o, o);
        rim.setAlpha(alpha);
        c.drawRoundRect(box, radius - o, radius - o, rim);
        box.inset(-o, -o);
    }

    private static BitmapShader shader(Bitmap b) {
        return new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    }

    private void drawImage(Canvas c, BitmapShader s, int a) {
        s.setLocalMatrix(place);
        image.setShader(s);
        image.setAlpha(a);
        c.drawRoundRect(box, radius, radius, image);
    }

    /** A color laid over the glass, as when a button lights up on focus; 0 for none. */
    void setWash(int color) {
        washColor = color;
        invalidateSelf();
    }

    @Override public void setAlpha(int a) {
        alpha = a;
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    /**
     * The current wallpaper, shrunk and blurred, shared by every glass panel. Crossfades with
     * the wallpaper itself, and redraws the panels as it does.
     */
    static final class Backdrop {
        private static final int SHRINK = 16;
        private final List<View> panels = new ArrayList<>();
        Bitmap current, previous;
        float fade = 1;
        private ValueAnimator fader;

        void addPanel(View v) {
            panels.add(v);
        }

        /** A small, blurred, shaded copy of {@code wallpaper}. Slow-ish: call it off the main thread. */
        static Bitmap blur(Bitmap wallpaper) {
            int w = Math.max(1, wallpaper.getWidth() / SHRINK), h = Math.max(1, wallpaper.getHeight() / SHRINK);
            Bitmap small = Bitmap.createScaledBitmap(wallpaper, w, h, true);
            int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            Blur.box(px, w, h, 2, 3); // soft, but the shapes behind still read through
            Blur.over(px, SHADE);
            Blur.over(px, TINT);
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, w, 0, 0, w, h);
            if (small != wallpaper) small.recycle();
            return out;
        }

        /** Show {@code blurred}, crossfading over {@code ms} (0 to switch at once). Main thread. */
        void set(Bitmap blurred, long ms) {
            if (fader != null) fader.cancel();
            previous = ms > 0 ? current : null;
            current = blurred;
            if (ms <= 0 || previous == null) {
                fade = 1;
                redraw();
                return;
            }
            fader = ValueAnimator.ofFloat(0, 1).setDuration(ms);
            fader.addUpdateListener(a -> {
                fade = (float) a.getAnimatedValue();
                redraw();
            });
            fader.start();
        }

        /** Redraw the panels, as when they move with the page. */
        void redraw() {
            for (View v : panels) v.invalidate();
        }
    }
}
