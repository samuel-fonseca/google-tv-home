package io.github.samuelfonseca.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared look: rounded app cards that lift on focus, round icon buttons, top-bar buttons that spell out their names on focus, and their icons. */
final class Ui {
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    /** How much a focused card grows. The home screen's highlight is drawn to match. */
    static final float FOCUS_SCALE = 1.08f;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);

    private Ui() {}

    static int dp(Context ctx, float dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    /**
     * A focusable app card: 16:9 rounded banner, and if {@code labelled} the app name underneath,
     * shown only on focus. The card scales up and casts a shadow when focused, like tvOS.
     */
    static LinearLayout appCard(Context ctx, Apps.App app, int width, boolean labelled) {
        int height = width * 9 / 16;
        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setFocusable(true);
        tile.setClipChildren(false);
        tile.setContentDescription(app.label);

        FrameLayout card = new FrameLayout(ctx);
        card.setClipToOutline(true);
        int radius = dp(ctx, 10);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        ImageView art = new ImageView(ctx);
        if (app.banner != null) {
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageDrawable(app.banner);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // No TV banner: the app icon on a frosted tile.
            card.setBackgroundColor(0x40FFFFFF);
            art.setImageDrawable(app.icon);
            int s = height * 3 / 5;
            card.addView(art, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        tile.addView(card, new LinearLayout.LayoutParams(width, height));

        TextView label = new TextView(ctx);
        label.setText(app.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setTypeface(MEDIUM);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setShadowLayer(dp(ctx, 3), 0, dp(ctx, 1), 0xCC000000);
        label.setAlpha(0f);
        // Text and shadow can take the fade's alpha separately; otherwise each frame of it renders
        // the label offscreen first.
        label.forceHasOverlappingRendering(false);
        // Same width as the card, so the label never pushes neighbouring tiles apart.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, -2);
        lp.topMargin = dp(ctx, 12);
        if (labelled) tile.addView(label, lp);

        tile.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? FOCUS_SCALE : 1f;
            card.setPivotX(width / 2f);
            card.setPivotY(height / 2f);
            card.animate().scaleX(s).scaleY(s).translationZ(focused ? dp(ctx, 14) : 0)
                    .setDuration(160).setInterpolator(EASE).start();
            label.animate().alpha(focused ? 1f : 0f).translationY(focused ? dp(ctx, 6) : 0)
                    .setDuration(160).start();
        });
        return tile;
    }

    /** Text over a photo: size, face, colour, and a soft shadow so it reads on bright skies. */
    static void style(TextView t, float sp, Typeface face, int color) {
        t.setTextSize(sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setShadowLayer(6f, 0, 2f, 0x99000000);
    }

    /** Make {@code v} a dead end for the remote in one direction (FOCUS_LEFT or FOCUS_RIGHT). */
    static void endOfRow(View v, int direction) {
        if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
        if (direction == View.FOCUS_LEFT) v.setNextFocusLeftId(v.getId());
        else v.setNextFocusRightId(v.getId());
    }

    /** A small round button for the top bar; white with dark icon when focused. */
    static View roundButton(Context ctx, Drawable icon, String description) {
        int size = dp(ctx, 40);
        FrameLayout b = new FrameLayout(ctx);
        b.setFocusable(true);
        b.setContentDescription(description);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x33FFFFFF);
        b.setBackground(bg);
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(icon);
        int pad = dp(ctx, 10);
        b.addView(iv, new FrameLayout.LayoutParams(size - 2 * pad, size - 2 * pad, Gravity.CENTER));
        b.setLayoutParams(new ViewGroup.MarginLayoutParams(size, size));
        b.setOnFocusChangeListener((v, focused) -> {
            bg.setColor(focused ? Color.WHITE : 0x33FFFFFF);
            icon.setTint(focused ? 0xFF111111 : Color.WHITE);
            v.animate().scaleX(focused ? 1.15f : 1f).scaleY(focused ? 1.15f : 1f).setDuration(140).start();
        });
        return b;
    }

    /**
     * A top-bar button: round with just its icon, and on focus white, widening into a pill that
     * spells out {@code label} beside the icon.
     */
    static View labelButton(Context ctx, Drawable icon, String label) {
        int size = dp(ctx, 40), pad = dp(ctx, 10);
        LinearLayout b = new LinearLayout(ctx);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setFocusable(true);
        b.setContentDescription(label);
        b.setPadding(pad, 0, pad, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(size / 2f); // a circle while it's only as wide as it is tall
        bg.setColor(0x33FFFFFF);
        b.setBackground(bg);
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(icon);
        b.addView(iv, new LinearLayout.LayoutParams(size - 2 * pad, size - 2 * pad));

        // The label's width grows from nothing on focus, clipping the text, so it reads out from
        // the icon as the pill widens.
        TextView text = new TextView(ctx);
        text.setText(label);
        text.setTextSize(14);
        text.setTypeface(MEDIUM);
        text.setTextColor(0xFF111111);
        text.setSingleLine(true);
        text.setPadding(dp(ctx, 8), 0, dp(ctx, 4), 0);
        text.setAlpha(0f);
        int full = (int) Math.ceil(text.getPaint().measureText(label))
                + text.getPaddingLeft() + text.getPaddingRight();
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2);
        b.addView(text, tlp);
        b.setLayoutParams(new ViewGroup.MarginLayoutParams(-2, size));

        ValueAnimator grow = new ValueAnimator();
        grow.setDuration(160);
        grow.setInterpolator(EASE);
        grow.addUpdateListener(a -> {
            tlp.width = (int) a.getAnimatedValue();
            text.requestLayout();
        });
        b.setOnFocusChangeListener((v, focused) -> {
            bg.setColor(focused ? Color.WHITE : 0x33FFFFFF);
            icon.setTint(focused ? 0xFF111111 : Color.WHITE);
            grow.cancel();
            grow.setIntValues(tlp.width, focused ? full : 0);
            grow.start();
            text.animate().alpha(focused ? 1f : 0f).setDuration(160).start();
        });
        return b;
    }

    /** What an input tile can show beside its name, in the order its menu lists them. The first is the default. */
    static final String[] GLYPHS = {"HDMI port", "Game console", "Set-top box", "Disc player", "Computer", "Name only"};

    /**
     * A 16:9 banner for things without one (TV inputs): a glyph from {@link #GLYPHS} and the name, in
     * white on one of the {@link Palette#TILES} gradients.
     */
    static Drawable textBanner(String text, String glyph, Palette.Swatch colour) {
        return new Drawable() {
            final Paint bg = new Paint();
            final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Paint port = new Paint(Paint.ANTI_ALIAS_FLAG);

            {
                ink.setColor(Color.WHITE);
                ink.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                port.setColor(Color.WHITE);
            }

            @Override public void draw(Canvas c) {
                android.graphics.Rect b = getBounds();
                bg.setShader(new android.graphics.LinearGradient(b.left, b.top, b.right, b.bottom,
                        colour.from, colour.to, android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(b, bg);

                // Glyph, gap, then name, centred as one group.
                float[] box = glyphBox(glyph);
                float ih = b.height() * box[0], iw = ih * box[1], gap = iw > 0 ? b.height() * 0.12f : 0;
                ink.setTextSize(b.height() * 0.3f);
                float max = b.width() * 0.8f - iw - gap, w = ink.measureText(text);
                if (w > max) {
                    ink.setTextSize(ink.getTextSize() * max / w);
                    w = max;
                }
                float x = b.exactCenterX() - (iw + gap + w) / 2, cy = b.exactCenterY();
                drawGlyph(c, port, glyph, x, cy - ih / 2, iw, ih, b.height() * 0.026f);
                c.drawText(text, x + iw + gap, cy - (ink.ascent() + ink.descent()) / 2, ink);
            }

            @Override public void setAlpha(int alpha) { bg.setAlpha(alpha); ink.setAlpha(alpha); port.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter cf) {
                bg.setColorFilter(cf);
                ink.setColorFilter(cf);
                port.setColorFilter(cf);
            }
            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
    }

    /**
     * A glyph's size on a banner: {height as a fraction of the banner's, width / height}. Each is
     * sized to look about as heavy as the others beside the same name.
     */
    private static float[] glyphBox(String glyph) {
        switch (glyph) {
            case "Game console": return new float[] {0.3f, 1.6f};
            case "Set-top box": return new float[] {0.24f, 2.2f};
            case "Disc player": return new float[] {0.34f, 1f};
            case "Computer": return new float[] {0.34f, 1.3f};
            case "Name only": return new float[] {0, 0};
            default: return new float[] {0.2f, 2.6f}; // HDMI port
        }
    }

    /** One of the {@link #GLYPHS}, in outline like the HDMI port, in the box at x, y. */
    private static void drawGlyph(Canvas c, Paint p, String glyph, float x, float y, float w, float h, float stroke) {
        p.setStrokeWidth(stroke);
        p.setStrokeJoin(Paint.Join.ROUND);
        switch (glyph) {
            case "Game console": {
                // A pill with a grip hanging from each end; a d-pad on the left, two buttons on the right.
                Path pad = new Path(), grips = new Path();
                float in = stroke / 2; // keep the outline inside the box
                pad.addRoundRect(x + in, y + in, x + w - in, y + h * 0.66f, h * 0.3f, h * 0.3f, Path.Direction.CW);
                float r = h * 0.3f;
                grips.addCircle(x + r + in, y + h - r - in, r, Path.Direction.CW);
                grips.addCircle(x + w - r - in, y + h - r - in, r, Path.Direction.CW);
                pad.op(grips, Path.Op.UNION);
                p.setStyle(Paint.Style.STROKE);
                c.drawPath(pad, p);
                p.setStyle(Paint.Style.FILL);
                float dx = x + w * 0.27f, dy = y + h * 0.36f, arm = h * 0.15f, t = h * 0.055f;
                c.drawRect(dx - arm, dy - t, dx + arm, dy + t, p);
                c.drawRect(dx - t, dy - arm, dx + t, dy + arm, p);
                c.drawCircle(x + w * 0.67f, y + h * 0.42f, h * 0.065f, p);
                c.drawCircle(x + w * 0.78f, y + h * 0.27f, h * 0.065f, p);
                break;
            }
            case "Set-top box": {
                // A flat box on two feet, with a slot and a power light.
                float in = stroke / 2, bottom = y + h * 0.8f;
                p.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(x + in, y + in, x + w - in, bottom, h * 0.12f, h * 0.12f, p);
                p.setStyle(Paint.Style.FILL);
                float mid = (y + bottom) / 2;
                c.drawRect(x + w * 0.12f, mid - h * 0.05f, x + w * 0.5f, mid + h * 0.05f, p);
                c.drawCircle(x + w * 0.82f, mid, h * 0.09f, p);
                c.drawRect(x + w * 0.1f, bottom, x + w * 0.2f, y + h, p);
                c.drawRect(x + w * 0.8f, bottom, x + w * 0.9f, y + h, p);
                break;
            }
            case "Disc player": {
                // A disc: the rim, the hub, and a glint between them.
                float cx = x + w / 2, cy = y + h / 2, r = Math.min(w, h) / 2 - stroke / 2;
                p.setStyle(Paint.Style.STROKE);
                c.drawCircle(cx, cy, r, p);
                c.drawArc(cx - r * 0.65f, cy - r * 0.65f, cx + r * 0.65f, cy + r * 0.65f, 200, 60, false, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, r * 0.22f, p);
                break;
            }
            case "Computer": {
                // A monitor on a stand.
                float in = stroke / 2, bottom = y + h * 0.72f, cx = x + w / 2;
                p.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(x + in, y + in, x + w - in, bottom, h * 0.06f, h * 0.06f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawRect(cx - w * 0.06f, bottom, cx + w * 0.06f, y + h * 0.9f, p);
                c.drawRoundRect(cx - w * 0.26f, y + h * 0.88f, cx + w * 0.26f, y + h, stroke / 2, stroke / 2, p);
                break;
            }
            case "Name only":
                break;
            default:
                drawHdmi(c, p, x, y, w, h, stroke);
        }
    }

    /** A 16:9 banner for apps whose own is unusable: a white icon centred on a diagonal gradient. */
    static Drawable iconBanner(Drawable icon, int from, int to) {
        return new Drawable() {
            final Paint bg = new Paint();

            @Override public void draw(Canvas c) {
                android.graphics.Rect b = getBounds();
                bg.setShader(new android.graphics.LinearGradient(b.left, b.top, b.right, b.bottom,
                        from, to, android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(b, bg);
                int s = Math.round(b.height() * 0.6f);
                int x = b.centerX() - s / 2, y = b.centerY() - s / 2;
                icon.setBounds(x, y, x + s, y + s);
                icon.draw(c);
            }

            @Override public void setAlpha(int alpha) { bg.setAlpha(alpha); icon.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter cf) {
                bg.setColorFilter(cf);
                icon.setColorFilter(cf);
            }
            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
    }

    /** An HDMI socket face-on: a box with its lower corners cut, and the pin tongue inside. */
    private static void drawHdmi(Canvas c, Paint p, float x, float y, float w, float h, float stroke) {
        float cut = h * 0.4f;
        Path shell = new Path();
        shell.moveTo(x, y);
        shell.lineTo(x + w, y);
        shell.lineTo(x + w, y + h - cut);
        shell.lineTo(x + w - cut, y + h);
        shell.lineTo(x + cut, y + h);
        shell.lineTo(x, y + h - cut);
        shell.close();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeJoin(Paint.Join.ROUND);
        c.drawPath(shell, p);
        p.setStyle(Paint.Style.FILL);
        float inset = stroke + h * 0.14f;
        c.drawRect(x + inset, y + inset, x + w - inset, y + inset + h * 0.2f, p);
    }

    /** An HDMI port: "inputs". */
    static Drawable inputIcon() {
        return new IconDrawable() {
            @Override void draw(Canvas c, float w, float h) {
                float iw = w * 0.92f, ih = iw * 0.55f;
                drawHdmi(c, paint, (w - iw) / 2, (h - ih) / 2, iw, ih, ih * 0.2f);
            }
        };
    }

    /** Eight-tooth gear: "settings". */
    static Drawable gearIcon() {
        return new IconDrawable() {
            @Override void draw(Canvas c, float w, float h) {
                float cx = w / 2, cy = h / 2, outer = w * 0.48f, body = w * 0.36f, hole = w * 0.15f;
                Path gear = new Path(), tooth = new Path();
                Matrix turn = new Matrix();
                for (int i = 0; i < 8; i++) {
                    tooth.reset();
                    tooth.addRect(cx - w * 0.08f, cy - outer, cx + w * 0.08f, cy, Path.Direction.CW);
                    turn.setRotate(i * 45, cx, cy);
                    tooth.transform(turn);
                    gear.op(tooth, Path.Op.UNION);
                }
                Path round = new Path();
                round.addCircle(cx, cy, body, Path.Direction.CW);
                gear.op(round, Path.Op.UNION);
                c.drawPath(punch(gear, cx, cy, hole), paint);
            }
        };
    }

    /** Six rounded teeth around a large hole, like Google's Settings banner, without its fold line. */
    static Drawable roundGearIcon() {
        return new IconDrawable() {
            @Override void draw(Canvas c, float w, float h) {
                float cx = w / 2, cy = h / 2, outer = w * 0.5f, body = w * 0.37f;
                Path p = new Path();
                for (int i = 0; i < 6; i++) {
                    double a = Math.toRadians(i * 60 - 90);
                    point(p, i == 0, cx, cy, body, a - Math.toRadians(20));
                    point(p, false, cx, cy, outer, a - Math.toRadians(12));
                    point(p, false, cx, cy, outer, a + Math.toRadians(12));
                    point(p, false, cx, cy, body, a + Math.toRadians(20));
                }
                p.close();
                // Round the corners into the outline itself, so the hole can be cut out of it.
                Paint corners = new Paint();
                corners.setPathEffect(new android.graphics.CornerPathEffect(w * 0.06f));
                Path rounded = new Path();
                corners.getFillPath(p, rounded);
                c.drawPath(punch(rounded, cx, cy, w * 0.17f), paint);
            }

            private void point(Path p, boolean first, float cx, float cy, float r, double a) {
                float x = cx + r * (float) Math.cos(a), y = cy + r * (float) Math.sin(a);
                if (first) p.moveTo(x, y); else p.lineTo(x, y);
            }
        };
    }

    /**
     * {@code shape} with a round hole through its centre. Cut from the path rather than cleared
     * after drawing, which would need an offscreen layer every time the icon is drawn.
     */
    private static Path punch(Path shape, float cx, float cy, float r) {
        Path hole = new Path();
        hole.addCircle(cx, cy, r, Path.Direction.CW);
        shape.op(hole, Path.Op.DIFFERENCE);
        return shape;
    }

    /** Bar and triangle pointing left: "previous track". */
    static Drawable prevIcon() {
        return new IconDrawable() {
            @Override void draw(Canvas c, float w, float h) {
                c.drawRect(w * 0.12f, h * 0.15f, w * 0.26f, h * 0.85f, paint);
                c.drawPath(triangle(w * 0.88f, w * 0.26f, h), paint);
            }
        };
    }

    /** Triangle pointing right and a bar: "next track". */
    static Drawable nextIcon() {
        return new IconDrawable() {
            @Override void draw(Canvas c, float w, float h) {
                c.drawRect(w * 0.74f, h * 0.15f, w * 0.88f, h * 0.85f, paint);
                c.drawPath(triangle(w * 0.12f, w * 0.74f, h), paint);
            }
        };
    }

    /** Play triangle, or two pause bars while something is playing. */
    static final class PlayPauseIcon extends IconDrawable {
        private boolean playing;

        void setPlaying(boolean playing) {
            if (this.playing == playing) return;
            this.playing = playing;
            invalidateSelf();
        }

        @Override void draw(Canvas c, float w, float h) {
            if (playing) {
                c.drawRect(w * 0.2f, h * 0.12f, w * 0.4f, h * 0.88f, paint);
                c.drawRect(w * 0.6f, h * 0.12f, w * 0.8f, h * 0.88f, paint);
            } else {
                c.drawPath(triangle(w * 0.2f, w * 0.9f, h), paint);
            }
        }
    }

    /** A triangle with its flat side at x = base and its point at x = tip, centred vertically. */
    private static Path triangle(float base, float tip, float h) {
        Path p = new Path();
        p.moveTo(base, h * 0.12f);
        p.lineTo(tip, h * 0.5f);
        p.lineTo(base, h * 0.88f);
        p.close();
        return p;
    }

    private abstract static class IconDrawable extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconDrawable() {
            paint.setColor(Color.WHITE);
        }

        abstract void draw(Canvas c, float w, float h);

        @Override public void draw(Canvas c) {
            int save = c.save();
            c.translate(getBounds().left, getBounds().top);
            draw(c, getBounds().width(), getBounds().height());
            c.restoreToCount(save);
        }

        @Override public void setTint(int color) {
            paint.setColor(color);
            invalidateSelf();
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
