package io.github.samuelfonseca.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;

/**
 * The light on the focused card, springing from tile to tile: a small soft halo, a hairline edge
 * that fades out down the card, and a diagonal sheen. A view in the page's overlay, so it scrolls
 * with the page and draws over the tiles. The light is drawn once into a bitmap for the card's
 * size; each frame only moves the view and sets its alpha, so the page isn't recorded again and
 * the halo's blur, slow on the TV's GPU, isn't redone.
 */
final class Highlight extends View implements Choreographer.FrameCallback {
    private static final float STIFFNESS = 420f, DAMPING = 0.78f;
    private static final long FADE_NS = 140_000_000L;

    // Centre and size rather than edges, so a move between same-sized tiles keeps its shape.
    private final Spring cx = new Spring(STIFFNESS, DAMPING), cy = new Spring(STIFFNESS, DAMPING);
    private final Spring w = new Spring(STIFFNESS, DAMPING), h = new Spring(STIFFNESS, DAMPING);
    private final Spring[] springs = {cx, cy, w, h};
    private final float radius, hairline, halo;
    private final int margin; // room around the card in the bitmap for the halo
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);

    private Bitmap light;
    private float lightW, lightH; // the card size it was drawn for
    private float alpha; // 0..1, fading towards shown ? 1 : 0
    private boolean shown;
    private long lastFrame;
    private boolean scheduled;

    /**
     * @param radius the focused card's corner radius
     * @param hairline width of the edge
     * @param halo how far the soft glow reaches past the card
     */
    Highlight(Context ctx, float radius, float hairline, float halo) {
        super(ctx);
        this.radius = radius;
        this.hairline = hairline;
        this.halo = halo;
        margin = (int) Math.ceil(halo * 2) + 1; // a blur reaches about 1.7 times its radius
        setPivotX(0);
        setPivotY(0);
        setAlpha(0f);
    }

    /** Spring to {@code target}, or jump there if the highlight was hidden. */
    void moveTo(RectF target) {
        boolean jump = !shown && alpha == 0;
        set(cx, target.centerX(), jump);
        set(cy, target.centerY(), jump);
        set(w, target.width(), jump);
        set(h, target.height(), jump);
        shown = true;
        if (light == null || Math.abs(target.width() - lightW) > 0.5f
                || Math.abs(target.height() - lightH) > 0.5f) {
            drawLight(target.width(), target.height());
        }
        place();
        schedule();
    }

    /** Fade out where it is, when focus leaves the tiles. */
    void hide() {
        shown = false;
        schedule();
    }

    /** Move rigidly with what it's on, as when the dock scrolls sideways under it. */
    void offset(float dx, float dy) {
        cx.offset(dx);
        cy.offset(dy);
        place();
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
        place();
        if (moving || fading) {
            scheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            lastFrame = 0;
        }
    }

    /**
     * Put the bitmap where the springs are. Only view properties change, so nothing is redrawn;
     * while the size springs between two card sizes, the bitmap stretches to fit.
     */
    private void place() {
        float sx = (w.value + 2 * margin) / (lightW + 2 * margin);
        float sy = (h.value + 2 * margin) / (lightH + 2 * margin);
        setScaleX(sx);
        setScaleY(sy);
        setTranslationX(cx.value - (lightW / 2 + margin) * sx);
        setTranslationY(cy.value - (lightH / 2 + margin) * sy);
        setAlpha(alpha);
    }

    /** Draw the light for a {@code cw} x {@code ch} card into the bitmap, and size the view to it. */
    private void drawLight(float cw, float ch) {
        int bw = (int) Math.ceil(cw + 2 * margin), bh = (int) Math.ceil(ch + 2 * margin);
        if (light != null) light.recycle();
        light = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        lightW = cw;
        lightH = ch;
        Canvas c = new Canvas(light);
        RectF rect = new RectF(margin, margin, margin + cw, margin + ch);

        // Halo: a faint blur just outside the card, not a ring. Blurred by hand: on the TV, a
        // BlurMaskFilter's outer blur drawn into a bitmap comes out as a stray bar on the card.
        Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
        shape.setColor(0xFFFFFFFF);
        c.drawRoundRect(rect, radius, radius, shape);
        int[] px = new int[bw * bh];
        light.getPixels(px, 0, bw, 0, 0, bw, bh);
        // Box passes as wide as the Gaussian a BlurMaskFilter of this radius would use.
        float sigma = 0.57735f * halo + 0.5f;
        int box = Math.max(1, Math.round(((float) Math.sqrt(4 * sigma * sigma + 1) - 1) / 2));
        Blur.glow(px, bw, bh, box, 0x40FFFFFF);
        light.setPixels(px, 0, bw, 0, 0, bw, bh);

        // Sheen: light falling diagonally from the top-left corner, gone by the middle.
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheen.setShader(new LinearGradient(rect.left, rect.top,
                rect.left + cw * 0.5f, rect.top + ch * 0.9f,
                0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, radius, radius, sheen);

        // Hairline edge just inside the card: bright along the top, fading out downwards.
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(hairline);
        edge.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                0xB3FFFFFF, 0x0DFFFFFF, Shader.TileMode.CLAMP));
        float o = hairline / 2;
        rect.inset(o, o);
        c.drawRoundRect(rect, radius - o, radius - o, edge);

        layout(0, 0, bw, bh); // in an overlay, views are laid out by hand
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (light != null) c.drawBitmap(light, 0, 0, image);
    }

    // One bitmap: alpha can apply to it directly, without an offscreen layer.
    @Override public boolean hasOverlappingRendering() {
        return false;
    }
}
