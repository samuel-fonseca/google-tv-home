package io.github.samuelfonseca.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
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
    private final Backdrop backdrop;
    private final float radius, edge;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shade = new Paint();
    private final Paint tint = new Paint();
    private final Paint gloss = new Paint();
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final RectF screen = new RectF();
    private final int[] where = new int[2];
    private int alpha = 255;

    Glass(Backdrop backdrop, float radius, float edge) {
        this.backdrop = backdrop;
        this.radius = radius;
        this.edge = edge;
        // Dark, smoky glass: the blur mostly shaded, with a trace of lift.
        shade.setColor(0x8C000000);
        tint.setColor(0x08FFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(edge);
        // Softened, so the edge blends into the glass rather than outlining it.
        rim.setMaskFilter(new BlurMaskFilter(edge * 1.5f, BlurMaskFilter.Blur.NORMAL));
    }

    @Override protected void onBoundsChange(Rect b) {
        box.set(b);
        clip.reset();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        gloss.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.5f,
                0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        // Brightest top-left, faint through the middle, a little light again bottom-right.
        rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[] {0x8CFFFFFF, 0x1AFFFFFF, 0x33FFFFFF}, new float[] {0, 0.5f, 1},
                Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas c) {
        if (alpha == 0) return;
        int save = alpha < 255 ? c.saveLayerAlpha(box, alpha) : c.save();
        c.clipPath(clip);
        View host = getCallback() instanceof View ? (View) getCallback() : null;
        if (host != null && backdrop.current != null) {
            // The blurred wallpaper, placed where the real one is on screen.
            View root = host.getRootView();
            host.getLocationInWindow(where);
            screen.set(-where[0], -where[1], root.getWidth() - where[0], root.getHeight() - where[1]);
            if (backdrop.previous != null && backdrop.fade < 1) {
                image.setAlpha(255);
                c.drawBitmap(backdrop.previous, null, screen, image);
            }
            image.setAlpha(Math.round(255 * backdrop.fade));
            c.drawBitmap(backdrop.current, null, screen, image);
        } else {
            c.drawColor(0x40000000);
        }
        c.drawRect(box, shade);
        c.drawRect(box, tint);
        c.drawRect(box, gloss);
        c.restoreToCount(save);

        float o = edge / 2;
        box.inset(o, o);
        int s = alpha < 255 ? c.saveLayerAlpha(null, alpha) : -1;
        c.drawRoundRect(box, radius - o, radius - o, rim);
        if (s >= 0) c.restoreToCount(s);
        box.inset(-o, -o);
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

        /** A small, blurred copy of {@code wallpaper}. Slow-ish: call it off the main thread. */
        static Bitmap blur(Bitmap wallpaper) {
            int w = Math.max(1, wallpaper.getWidth() / SHRINK), h = Math.max(1, wallpaper.getHeight() / SHRINK);
            Bitmap small = Bitmap.createScaledBitmap(wallpaper, w, h, true);
            int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            Blur.box(px, w, h, 3, 3);
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
