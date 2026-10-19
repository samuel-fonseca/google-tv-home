package io.github.samuelfonseca.tvhome;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.Choreographer;

/**
 * The light on the focused card, springing from tile to tile: a small soft halo, a hairline edge
 * that fades out down the card, and a diagonal sheen. Added to the page's overlay, so it scrolls
 * with the page and draws over the tiles. Its bounds must cover the page (see MainActivity); it
 * draws wherever the springs put it.
 */
final class Highlight extends Drawable implements Choreographer.FrameCallback {
    private static final float STIFFNESS = 420f, DAMPING = 0.78f;
    private static final long FADE_NS = 140_000_000L;

    // Centre and size rather than edges, so a move between same-sized tiles keeps its shape.
    private final Spring cx = new Spring(STIFFNESS, DAMPING), cy = new Spring(STIFFNESS, DAMPING);
    private final Spring w = new Spring(STIFFNESS, DAMPING), h = new Spring(STIFFNESS, DAMPING);
    private final Spring[] springs = {cx, cy, w, h};
    private final float radius, hairline;
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();

    private float alpha; // 0..1, fading towards shown ? 1 : 0
    private boolean shown;
    private long lastFrame;
    private boolean scheduled;

    /**
     * @param radius the focused card's corner radius
     * @param hairline width of the edge
     * @param halo how far the soft glow reaches past the card
     */
    Highlight(float radius, float hairline, float halo) {
        this.radius = radius;
        this.hairline = hairline;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(hairline);
        glow.setMaskFilter(new BlurMaskFilter(halo, BlurMaskFilter.Blur.OUTER));
        glow.setColor(0xFFFFFFFF);
    }

    /** Spring to {@code target}, or jump there if the highlight was hidden. */
    void moveTo(RectF target) {
        boolean jump = !shown && alpha == 0;
        set(cx, target.centerX(), jump);
        set(cy, target.centerY(), jump);
        set(w, target.width(), jump);
        set(h, target.height(), jump);
        shown = true;
        schedule();
    }

    /** Fade out where it is, when focus leaves the tiles. */
    void hide() {
        shown = false;
        schedule();
    }

    boolean isShown() {
        return shown;
    }

    /** Move rigidly with what it's on, as when the dock scrolls sideways under it. */
    void offset(float dx, float dy) {
        cx.offset(dx);
        cy.offset(dy);
        invalidateSelf();
    }

    private static void set(Spring s, float v, boolean jump) {
        if (jump) s.snap(v); else s.target = v;
    }

    private void schedule() {
        if (scheduled) return;
        scheduled = true;
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long now) {
        scheduled = false;
        float dt = lastFrame == 0 ? 1 / 60f : Math.min((now - lastFrame) / 1e9f, 0.1f);
        lastFrame = now;
        boolean moving = false;
        for (Spring s : springs) {
            if (s.step(dt)) moving = true; // every spring steps, even once one has settled
        }
        float fade = dt * 1e9f / FADE_NS;
        alpha = shown ? Math.min(1, alpha + fade) : Math.max(0, alpha - fade);
        boolean fading = shown ? alpha < 1 : alpha > 0;
        invalidateSelf();
        if (moving || fading) {
            scheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            lastFrame = 0;
        }
    }

    @Override public void draw(Canvas c) {
        if (alpha == 0) return;
        float hw = w.value / 2, hh = h.value / 2;
        rect.set(cx.value - hw, cy.value - hh, cx.value + hw, cy.value + hh);

        // Halo: a faint blur just outside the card, not a ring.
        glow.setAlpha(Math.round(alpha * 0x40));
        c.drawRoundRect(rect, radius, radius, glow);

        // Sheen: light falling diagonally from the top-left corner, gone by the middle.
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        sheen.setShader(new LinearGradient(rect.left, rect.top,
                rect.left + rect.width() * 0.5f, rect.top + rect.height() * 0.9f,
                0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        sheen.setAlpha(Math.round(alpha * 255));
        c.save();
        c.clipPath(clip);
        c.drawRect(rect, sheen);
        c.restore();

        // Hairline edge just inside the card: bright along the top, fading out downwards.
        float o = hairline / 2;
        edge.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                0xB3FFFFFF, 0x0DFFFFFF, Shader.TileMode.CLAMP));
        edge.setAlpha(Math.round(alpha * 255));
        rect.inset(o, o);
        c.drawRoundRect(rect, radius - o, radius - o, edge);
    }

    @Override public void setAlpha(int a) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
