package io.github.samuelfonseca.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A panel that slides in from the right, in the style of Google TV's own Inputs panel: a dark
 * rounded sheet of rows, each an icon, a name and its current value, the focused one lit. A row
 * can open another page in the same panel, and Back returns to the one before. Part of the home
 * screen's window rather than a dialog, so it keeps focus to itself (see focusSearch).
 */
final class Drawer extends FrameLayout {
    /** Fills a page with rows. Run each time the page shows, so the values it lists are current. */
    interface Content {
        void fill(Page page);
    }

    /** Hears how far in the panel is as it slides, 0 to 1, to shade the screen behind it. */
    interface Shade {
        void shade(float in);
    }

    // Google TV's panel: slate rows on a darker sheet, the focused row near white.
    private static final int SHEET = 0xFF1F232A, ROW = 0xFF2A2F37, LIT = 0xFFE3EEFC;
    private static final int CHIP = 0xFF1A1E24, CHIP_LIT = 0xFF0B4F7C, ICON_LIT = 0xFFD3E3FD;
    private static final int TEXT = 0xFFE8EAED, TEXT_DIM = 0xFF9AA0A6;
    private static final int INK = 0xFF1F1F1F, INK_DIM = 0xFF5F6368;
    private static final float LIFT = 1.05f; // the focused row grows a little, as Google's does
    private static final long SLIDE_MS = 240, TURN_MS = 180;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);

    private final FrameLayout sheet;
    private final int width, margin;
    private final List<Entry> stack = new ArrayList<>();
    private Page page;
    private Runnable onClose;

    private static final class Entry {
        final String title;
        final Content content;
        // The row to come back to from a page it opened, or after a reload: by name, else by place.
        String focusedName;
        int focused;

        Entry(String title, Content content) {
            this.title = title;
            this.content = content;
        }
    }

    /**
     * @param shade darkens what's behind the panel. Not a scrim over the screen: on the TV's GPU, a
     *     full-screen fill fading in made the slide drop frames.
     */
    Drawer(Context ctx, Shade shade) {
        super(ctx);
        setVisibility(GONE);
        width = Ui.dp(ctx, 272);
        margin = Ui.dp(ctx, 16);
        sheet = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SHEET);
        bg.setCornerRadius(Ui.dp(ctx, 20));
        sheet.setBackground(bg);
        LayoutParams lp = new LayoutParams(width, -1, Gravity.END);
        lp.setMargins(0, margin, margin, margin);
        addView(sheet, lp);
        sheet.animate().setUpdateListener(a -> shade.shade(1 - sheet.getTranslationX() / (width + margin)));
    }

    boolean isOpen() {
        return !stack.isEmpty();
    }

    /**
     * Slide in, showing a first page titled {@code title}. {@code onClose} runs as it closes, to
     * hand focus back to the home screen.
     */
    void open(String title, Content content, Runnable onClose) {
        boolean shown = isOpen();
        if (shown && this.onClose != null) this.onClose.run();
        this.onClose = onClose;
        stack.clear();
        stack.add(new Entry(title, content));
        boolean hidden = getVisibility() != VISIBLE; // else it's still sliding out: turn it round
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setVisibility(VISIBLE);
        show(0);
        if (shown) return;
        sheet.animate().cancel();
        if (hidden) sheet.setTranslationX(width + margin);
        sheet.animate().translationX(0).setDuration(SLIDE_MS).setInterpolator(EASE).start();
    }

    /** Open another page over this one; Back returns to the row that opened it. */
    void push(String title, Content content) {
        remember();
        stack.add(new Entry(title, content));
        show(1);
    }

    /**
     * Build the page again in place, as when what it lists has changed, staying on the same row.
     * If that row has gone, the one before it.
     */
    void reload() {
        if (!isOpen()) return;
        remember();
        show(0);
        page.settle();
    }

    private void remember() {
        Entry e = stack.get(stack.size() - 1);
        e.focused = page.focusedRow();
        e.focusedName = page.focusedName();
    }

    /** Back a page, or close from the first. False if it wasn't open. */
    boolean back() {
        if (!isOpen()) return false;
        if (stack.size() == 1) {
            close();
        } else {
            stack.remove(stack.size() - 1);
            show(-1);
        }
        return true;
    }

    /** Slide out. Focus goes back to the home screen at once, not when the panel has gone. */
    void close() {
        if (!isOpen()) return;
        stack.clear();
        Runnable then = onClose;
        onClose = null;
        if (then != null) then.run();
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS); // nothing to land on while it leaves
        sheet.animate().translationX(width + margin).setDuration(SLIDE_MS)
                .setInterpolator(new AccelerateInterpolator(2f)).withEndAction(() -> {
                    setVisibility(GONE);
                    sheet.removeAllViews();
                    page = null;
                }).start();
    }

    /** Build the top page and focus its row, then drop the old page, nudged in from {@code direction}. */
    private void show(int direction) {
        Entry e = stack.get(stack.size() - 1);
        Page old = page;
        page = new Page(e.title);
        e.content.fill(page);
        sheet.addView(page.view, new LayoutParams(-1, -1));
        // Focus moves before the old page goes: removing a focused view hands focus to the first
        // focusable in the window, which could scroll the home screen.
        page.focus(e.focusedName, e.focused);
        if (old != null) sheet.removeView(old.view);
        if (direction != 0) {
            page.view.setTranslationX(direction * Ui.dp(getContext(), 24));
            page.view.animate().translationX(0).setDuration(TURN_MS).setInterpolator(EASE).start();
        }
    }

    // The remote never leaves the panel: up, down, left or right only ever find its own rows.
    @Override public View focusSearch(View focused, int direction) {
        if (!isOpen()) return super.focusSearch(focused, direction);
        View next = FocusFinder.getInstance().findNextFocus(this, focused, direction);
        return next != null ? next : focused;
    }

    /** One page of the panel: its title, rows that scroll, and an optional note at the foot. */
    final class Page {
        final LinearLayout view;
        private final LinearLayout list;
        private final List<Row> rows = new ArrayList<>();
        private final Context ctx = getContext();

        Page(String title) {
            view = new LinearLayout(ctx);
            view.setOrientation(LinearLayout.VERTICAL);
            TextView t = text(title, 15, TEXT);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(16), dp(16), dp(16), dp(12));
            view.addView(t, new LinearLayout.LayoutParams(-1, -2));

            ScrollView scroll = new ScrollView(ctx);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(OVER_SCROLL_NEVER);
            list = new LinearLayout(ctx);
            list.setOrientation(LinearLayout.VERTICAL);
            // Room at the sides and ends for a focused row to grow into.
            list.setPadding(dp(12), dp(4), dp(12), dp(12));
            list.setClipToPadding(false);
            scroll.addView(list, new LayoutParams(-1, -2));
            view.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        }

        /** A picture at the top, such as the app's banner, centered. */
        void header(Drawable art, int widthDp, int heightDp) {
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(art);
            rounded(iv, dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(16);
            list.addView(iv, lp);
        }

        /** A row that does something. */
        void action(Drawable icon, String title, String value, Runnable click) {
            row(icon, false, title, value, Mark.NONE, null, click);
        }

        /** A row that opens another page, titled as the row is, and shows its current value. */
        void link(Drawable icon, String title, String value, Content content) {
            row(icon, false, title, value, Mark.MORE, null, () -> push(title, content));
        }

        /** One of a set: marked while {@code on}; picking it saves at once and stays on the page. */
        void choice(Drawable thumb, String title, String value, BooleanSupplier on, Runnable pick) {
            row(thumb, true, title, value, Mark.RADIO, on, pick);
        }

        /** A tick box: ticked while {@code on}; {@code toggle} saves at once. */
        void check(Drawable thumb, String title, String value, BooleanSupplier on, Runnable toggle) {
            row(thumb, true, title, value, Mark.CHECK, on, toggle);
        }

        /** A short rule between groups of rows, like Google's. */
        void divider() {
            View bar = new View(ctx);
            GradientDrawable d = new GradientDrawable();
            d.setColor(INK_DIM);
            d.setCornerRadius(dp(2));
            bar.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(3));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(12);
            lp.bottomMargin = dp(4);
            list.addView(bar, lp);
        }

        /** Text at the foot of the page that can't be focused, such as what this launcher is. */
        void note(String title, String body) {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(24), dp(12), dp(24), dp(20));
            TextView t = text(title, 12, TEXT);
            TextView b = text(body, 11, TEXT_DIM);
            b.setTypeface(Ui.REGULAR);
            b.setSingleLine(false);
            b.setEllipsize(null);
            b.setLineSpacing(dp(3), 1f);
            b.setPadding(0, dp(4), 0, 0);
            box.addView(t);
            box.addView(b);
            view.addView(box, new LinearLayout.LayoutParams(-1, -2));
        }

        private void row(Drawable art, boolean thumb, String title, String value, int mark,
                BooleanSupplier on, Runnable click) {
            LinearLayout v = new LinearLayout(ctx);
            v.setGravity(Gravity.CENTER_VERTICAL);
            v.setFocusable(true);
            v.setMinimumHeight(dp(56));
            v.setPadding(dp(12), dp(8), dp(14), dp(8));
            v.setContentDescription(title);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            v.setBackground(bg);

            // Leading: a 16:9 preview, or an icon on a round chip.
            GradientDrawable chip = null;
            if (art != null && thumb) {
                ImageView iv = new ImageView(ctx);
                iv.setScaleType(ImageView.ScaleType.FIT_XY);
                iv.setImageDrawable(art);
                rounded(iv, dp(5));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(27));
                lp.rightMargin = dp(12);
                v.addView(iv, lp);
            } else if (art != null) {
                FrameLayout c = new FrameLayout(ctx);
                chip = new GradientDrawable();
                chip.setShape(GradientDrawable.OVAL);
                c.setBackground(chip);
                ImageView iv = new ImageView(ctx);
                iv.setImageDrawable(art);
                c.addView(iv, new LayoutParams(dp(18), dp(18), Gravity.CENTER));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
                lp.rightMargin = dp(12);
                v.addView(c, lp);
            }

            LinearLayout words = new LinearLayout(ctx);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(title, 13, TEXT);
            words.addView(name);
            TextView sub = null;
            if (value != null) {
                sub = text(value, 11, TEXT_DIM);
                sub.setTypeface(Ui.REGULAR);
                words.addView(sub);
            }
            v.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));

            Mark m = null;
            if (mark != Mark.NONE) {
                m = new Mark(mark);
                ImageView iv = new ImageView(ctx);
                iv.setImageDrawable(m);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
                lp.leftMargin = dp(10);
                v.addView(iv, lp);
            }

            Row r = new Row(v, bg, chip, chip != null ? art : null, name, sub, m, on);
            rows.add(r);
            r.light(false);
            v.setOnFocusChangeListener((view, focused) -> r.light(focused));
            v.setOnClickListener(view -> {
                click.run();
                if (page == this) refresh(); // what changed may move a mark
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (list.getChildCount() > 0) lp.topMargin = dp(8);
            list.addView(v, lp);
        }

        /** Re-mark every row from what's saved, after a pick. */
        void refresh() {
            for (Row r : rows) r.mark();
        }

        int focusedRow() {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).view.hasFocus()) return i;
            return 0;
        }

        String focusedName() {
            return String.valueOf(rows.get(focusedRow()).view.getContentDescription());
        }

        /** The row called {@code name}; without one, the row before {@code index}. */
        void focus(String name, int index) {
            if (rows.isEmpty()) return;
            for (Row r : rows) {
                if (r.view.getContentDescription().toString().equals(name)) {
                    r.view.requestFocus();
                    return;
                }
            }
            int i = name == null ? index : index - 1;
            rows.get(Math.max(0, Math.min(i, rows.size() - 1))).view.requestFocus();
        }

        /** Lift the focused row at once, as it was before a reload, rather than grow it again. */
        void settle() {
            View f = rows.get(focusedRow()).view;
            f.animate().cancel();
            f.setScaleX(LIFT);
            f.setScaleY(LIFT);
        }

        private TextView text(String s, float sp, int color) {
            TextView t = new TextView(ctx);
            t.setText(s);
            t.setTextSize(sp);
            t.setTypeface(Ui.MEDIUM);
            t.setTextColor(color);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            return t;
        }

        private int dp(float dp) {
            return Ui.dp(ctx, dp);
        }
    }

    /** A row's views, lit while focused: near white with dark text, like Google's. */
    private static final class Row {
        final View view;
        private final GradientDrawable bg, chip;
        private final Drawable icon;
        private final TextView name, sub;
        private final Mark mark;
        private final BooleanSupplier on;
        private boolean lit;

        Row(View view, GradientDrawable bg, GradientDrawable chip, Drawable icon, TextView name,
                TextView sub, Mark mark, BooleanSupplier on) {
            this.view = view;
            this.bg = bg;
            this.chip = chip;
            this.icon = icon;
            this.name = name;
            this.sub = sub;
            this.mark = mark;
            this.on = on;
        }

        void light(boolean focused) {
            lit = focused;
            bg.setColor(focused ? LIT : ROW);
            if (chip != null) chip.setColor(focused ? CHIP_LIT : CHIP);
            if (icon != null) icon.setTint(focused ? ICON_LIT : TEXT);
            name.setTextColor(focused ? INK : TEXT);
            if (sub != null) sub.setTextColor(focused ? INK_DIM : TEXT_DIM);
            mark();
            float s = focused ? LIFT : 1f;
            view.animate().scaleX(s).scaleY(s).setDuration(140).setInterpolator(EASE).start();
        }

        void mark() {
            if (mark == null) return;
            boolean checked = on != null && on.getAsBoolean();
            mark.set(checked, lit ? INK : checked ? TEXT : TEXT_DIM, lit ? LIT : ROW);
        }
    }

    /** What sits at a row's end: a radio, a tick box, or an arrow to another page. */
    private static final class Mark extends Drawable {
        static final int NONE = 0, RADIO = 1, CHECK = 2, MORE = 3;
        private final int type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean on;
        private int color, behind; // the mark, and the row under it, for the tick cut out of the box

        Mark(int type) {
            this.type = type;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void set(boolean on, int color, int behind) {
            this.on = on;
            this.color = color;
            this.behind = behind;
            invalidateSelf();
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()), x = b.left, y = b.top, t = s * 0.1f;
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            paint.setColor(color);
            paint.setStrokeWidth(t);
            switch (type) {
                case RADIO:
                    paint.setStyle(Paint.Style.STROKE);
                    c.drawCircle(cx, cy, s * 0.42f - t / 2, paint);
                    if (on) {
                        paint.setStyle(Paint.Style.FILL);
                        c.drawCircle(cx, cy, s * 0.22f, paint);
                    }
                    break;
                case CHECK: {
                    float in = s * 0.1f, r = s * 0.14f;
                    if (on) {
                        paint.setStyle(Paint.Style.FILL);
                        c.drawRoundRect(x + in, y + in, x + s - in, y + s - in, r, r, paint);
                        path.reset();
                        path.moveTo(x + s * 0.29f, y + s * 0.52f);
                        path.lineTo(x + s * 0.44f, y + s * 0.67f);
                        path.lineTo(x + s * 0.72f, y + s * 0.37f);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(t * 1.2f);
                        paint.setColor(behind);
                        c.drawPath(path, paint);
                    } else {
                        paint.setStyle(Paint.Style.STROKE);
                        in += t / 2;
                        c.drawRoundRect(x + in, y + in, x + s - in, y + s - in, r, r, paint);
                    }
                    break;
                }
                default: // MORE
                    paint.setStyle(Paint.Style.STROKE);
                    path.reset();
                    path.moveTo(x + s * 0.4f, y + s * 0.24f);
                    path.lineTo(x + s * 0.66f, y + s * 0.5f);
                    path.lineTo(x + s * 0.4f, y + s * 0.76f);
                    c.drawPath(path, paint);
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static void rounded(View v, int radius) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
    }
}
