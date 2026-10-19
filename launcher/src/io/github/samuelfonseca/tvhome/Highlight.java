package io.github.samuelfonseca.tvhome;

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
 * One glass frame that springs from tile to tile: a bright rim and a sheen across the top of the
 * focused card. Added to the page's overlay, so it scrolls with the page and draws over the tiles.
 * Its bounds must cover the page (see MainActivity); it draws wherever the springs put it.
 */
final class Highlight extends Drawable implements Choreographer.FrameCallback {
    private static final float STIFFNESS = 420f, DAMPING = 0.78f;
    private static final long FADE_NS = 140_000_000L;

    // Centre and size rather than edges, so a move between same-sized tiles keeps its shape.
    private final Spring cx = new Spring(STIFFNESS, DAMPING), cy = new Spring(STIFFNESS, DAMPING);
    private final Spring w = new Spring(STIFFNESS, DAMPING), h = new Spring(STIFFNESS, DAMPING);
    private final float radius, rimWidth;
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();

    private float alpha; // 0..1, fading towards shown ? 1 : 0
    private boolean shown;
    private long lastFrame;
    private boolean scheduled;

    /** @param radius the card's corner radius, before the focus scale */
    Highlight(float radius, float rimWidth) {
        this.radius = radius;
        this.rimWidth = rimWidth;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(rimWidth);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(rimWidth * 4);
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
        boolean moving = cx.step(dt) | cy.step(dt) | w.step(dt) | h.step(dt);
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
        int a = Math.round(alpha * 255);

        // Soft outer glow, then the rim just outside the card's edge.
        glow.setColor(0x26FFFFFF);
        glow.setAlpha(Math.round(alpha * 0x26));
        c.drawRoundRect(rect, radius, radius, glow);

        // Sheen: white fading out across the top half, clipped to the card.
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        sheen.setShader(new LinearGradient(0, rect.top, 0, rect.top + rect.height() * 0.6f,
                0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        sheen.setAlpha(a);
        c.save();
        c.clipPath(clip);
        c.drawRect(rect, sheen);
        c.restore();

        rim.setColor(0xE6FFFFFF);
        rim.setAlpha(Math.round(alpha * 0xE6));
        float o = rimWidth / 2;
        rect.inset(-o, -o);
        c.drawRoundRect(rect, radius + o, radius + o, rim);
    }

    @Override public void setAlpha(int a) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
