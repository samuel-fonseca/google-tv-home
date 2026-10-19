package io.github.samuelfonseca.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen: rotating nature wallpaper, clock, what's playing, and a dock of favourite apps.
 * Down from the dock scrolls the page up to a grid of every other app, like tvOS.
 */
public class MainActivity extends Activity {
    private static final long ROTATE_MS = 60_000;
    private static final long FADE_MS = 1_500;
    private static final long SCROLL_MS = 350;
    private static final int COLUMNS = 6; // dock cards and grid columns line up
    private static final String KEY_WALL = "wall";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final List<String[]> walls = new ArrayList<>(); // {resName, place, author, licence}

    private ImageView back, front;
    private View dim;
    private TextView place, credit;
    private ScrollView page;
    private LinearLayout content;
    private FrameLayout band; // the dock's glass panel
    private Drawable bandBg;
    private final Glass.Backdrop backdrop = new Glass.Backdrop(); // what the glass panels show
    private LinearLayout dock;
    private List<String> shownDock; // what buildDock last drew
    private List<String> gridDock; // the dock the grid was built without
    private HorizontalScrollView dockScroll;
    private GridLayout grid;
    private ObjectAnimator scroller;
    private int scrollTarget;
    private NowPlaying nowPlaying;
    private AlertDialog dialog;
    private int wall;
    private Highlight highlight;
    // The dock app to come back to. Kept by package as well as position, so it survives the dock
    // being rebuilt or reordered.
    private int focusedIndex;
    private String focusedPkg;

    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            showWallpaper(wall + 1, true);
            main.postDelayed(this, ROTATE_MS);
        }
    };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            gridDock = null; // an app came or went: rebuild the grid too
            buildDock();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        walls.addAll(Wallpapers.list(this));
        setContentView(buildLayout());
        getWindow().getDecorView().getViewTreeObserver()
                .addOnGlobalFocusChangeListener((was, now) -> {
                    scrollFor(now);
                    followFocus(now);
                });
        wall = Apps.prefs(this).getInt(KEY_WALL, 0);
        showWallpaper(wall, false);
        buildDock();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
    }

    @Override protected void onStart() {
        super.onStart();
        main.postDelayed(rotate, ROTATE_MS);
        nowPlaying.start();
    }

    /** Back from an app or All apps: the dock may have been edited there. */
    @Override protected void onRestart() {
        super.onRestart();
        buildDock();
    }

    // All apps is translucent, so this activity only pauses behind it and onRestart doesn't run.
    @Override protected void onResume() {
        super.onResume();
        if (!Apps.dock(this).equals(shownDock)) buildDock();
        // Back from an app opened from the grid: stay where you were.
        View f = getCurrentFocus();
        if (f == null || !isInside(f, grid)) focusDock(focusedIndex);
    }

    @Override protected void onStop() {
        main.removeCallbacks(rotate);
        nowPlaying.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        decoder.shutdownNow();
        nowPlaying.destroy();
        super.onDestroy();
    }

    /** Home pressed while already home: close any menu and go back to the first app. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (dialog != null) dialog.dismiss();
        focusedIndex = 0;
        dockScroll.smoothScrollTo(0, 0);
        focusDock(0); // and the page scrolls back up with it
    }

    @Override public void onBackPressed() {
        // Home is the bottom of the stack; Back only returns from the grid to the dock.
        if (scrollTarget > 0) focusDock(focusedIndex);
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        back = new ImageView(this);
        front = new ImageView(this);
        for (ImageView iv : new ImageView[] {back, front}) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }

        // Soft scrims so text and the dock stay legible on bright photos.
        root.addView(scrim(GradientDrawable.Orientation.TOP_BOTTOM, 0x99000000),
                new FrameLayout.LayoutParams(-1, Ui.dp(this, 170), Gravity.TOP));
        root.addView(scrim(GradientDrawable.Orientation.BOTTOM_TOP, 0xB3000000),
                new FrameLayout.LayoutParams(-1, Ui.dp(this, 230), Gravity.BOTTOM));

        // Darkens the photo as the page scrolls down to the grid.
        dim = new View(this);
        dim.setBackgroundColor(0xFF0A0C10);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        // Everything else scrolls: the first screen, then the grid. We scroll it ourselves (see
        // scrollFor), so focus moves don't also nudge it.
        page = new ScrollView(this) {
            @Override public boolean executeKeyEvent(KeyEvent event) {
                return false;
            }

            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                return 0;
            }
        };
        page.setVerticalScrollBarEnabled(false);
        page.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            float p = Math.min(1f, y / (float) Math.max(1, gridScroll()));
            dim.setAlpha(p * 0.85f);
            bandBg.setAlpha(Math.round(255 * (1 - p))); // in the grid the dock is just the top row
            nowPlaying.setAlpha(1 - p); // it sits just above the dock, so it would peek in at the top
            backdrop.redraw(); // the panels moved over the wallpaper
        });
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        page.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // One glass frame over the tiles that springs to whichever has focus (see followFocus).
        // In the page's overlay, so it scrolls with the page and draws over the cards.
        highlight = new Highlight(Ui.dp(this, 10) * Ui.FOCUS_SCALE, Ui.dp(this, 1), Ui.dp(this, 6));
        content.getOverlay().add(highlight);
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            highlight.setBounds(0, 0, r - l, b - t);
            followFocus(getCurrentFocus()); // tiles may have moved
        });

        int margin = Ui.dp(this, 48);
        int screen = getResources().getDisplayMetrics().heightPixels;
        FrameLayout first = new FrameLayout(this);
        first.setClipChildren(false);
        content.addView(first, new LinearLayout.LayoutParams(-1, screen));

        // Top left: time and date.
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        Ui.style(time, 46, Ui.LIGHT, 0xFFFFFFFF);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, d MMMM");
        Ui.style(date, 15, Ui.MEDIUM, 0xD9FFFFFF);
        clock.addView(time);
        clock.addView(date);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        clp.setMargins(margin, Ui.dp(this, 28), 0, 0);
        first.addView(clock, clp);

        // Top right: inputs, all apps and settings buttons, with the photo credit underneath.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.setClipChildren(false); // focused buttons scale past their bounds
        LinearLayout buttons = new LinearLayout(this);
        buttons.setClipChildren(false);
        View inputs = Ui.roundButton(this, Ui.inputIcon(), "Inputs");
        inputs.setOnClickListener(v -> openInputs());
        View allApps = Ui.roundButton(this, Ui.gridIcon(), "All apps");
        allApps.setOnClickListener(v -> startActivity(new Intent(this, AllAppsActivity.class)));
        View settings = Ui.roundButton(this, Ui.gearIcon(), "Settings");
        settings.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        // The ends of the row stop the remote, instead of falling through to the nearest tile.
        Ui.endOfRow(inputs, View.FOCUS_LEFT);
        Ui.endOfRow(settings, View.FOCUS_RIGHT);
        buttons.addView(inputs);
        for (View b : new View[] {allApps, settings}) {
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
            gap.leftMargin = Ui.dp(this, 14);
            buttons.addView(b, gap);
        }
        // Wrap content, so each row hugs the right edge and the column resizes with the caption.
        right.addView(buttons, new LinearLayout.LayoutParams(-2, -2));
        place = new TextView(this);
        Ui.style(place, 13, Ui.MEDIUM, 0xE6FFFFFF);
        place.setPadding(0, Ui.dp(this, 16), 0, 0);
        credit = new TextView(this);
        Ui.style(credit, 10, Ui.LIGHT, 0xB3FFFFFF);
        right.addView(place, new LinearLayout.LayoutParams(-2, -2));
        right.addView(credit, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rlp.setMargins(0, Ui.dp(this, 36), margin, 0);
        first.addView(right, rlp);

        // Bottom of the first screen: what's playing in the background, then the dock.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        first.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // Card and dock share a left edge, and the album art lines up with the first dock card.
        int inset = Ui.dp(this, 32);
        nowPlaying = new NowPlaying(this, () -> focusDock(focusedIndex), glass());
        backdrop.addPanel(nowPlaying);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.setMargins(inset, 0, 0, Ui.dp(this, 16));
        bottom.addView(nowPlaying, nlp);

        // The dock sits on a glass band, like the top row on tvOS.
        band = new FrameLayout(this);
        band.setClipChildren(false);
        bandBg = glass();
        band.setBackground(bandBg);
        backdrop.addPanel(band);
        dockScroll = new HorizontalScrollView(this);
        dockScroll.setHorizontalScrollBarEnabled(false);
        dockScroll.setClipChildren(false);
        dockScroll.setClipToPadding(false);
        dockScroll.setFillViewport(true);
        // No names under dock cards, so even padding centres them in the band.
        dockScroll.setPadding(margin - inset, Ui.dp(this, 18), margin - inset, Ui.dp(this, 18));
        // A long dock scrolls sideways under the highlight; carry it along rather than chase.
        dockScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            View f = getCurrentFocus();
            if (f != null && f.getParent() == dock) highlight.offset(oldX - x, 0);
        });
        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        dock.setClipChildren(false);
        dockScroll.addView(dock, new FrameLayout.LayoutParams(-1, -2));
        band.addView(dockScroll, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.setMargins(inset, 0, inset, Ui.dp(this, 24));
        bottom.addView(band, blp);

        // Below the first screen: every app not in the dock. At least a screen tall, so the
        // page can always scroll far enough to put the dock at the top.
        grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(margin, 0, margin, Ui.dp(this, 48));
        grid.setMinimumHeight(screen);
        content.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    private void buildDock() {
        dock.removeAllViews();
        int gap = Ui.dp(this, 22);
        int width = cardWidth();
        shownDock = Apps.dock(this);
        for (String pkg : shownDock) {
            Apps.App app = Apps.get(this, pkg);
            if (app == null) continue; // not installed or disabled
            View tile = Ui.appCard(this, app, width, false);
            tile.setTag(app.pkg);
            if (app.pkg.equals(focusedPkg)) focusedIndex = dock.getChildCount();
            tile.setOnClickListener(v -> {
                Bundle opts = ActivityOptions.makeScaleUpAnimation(
                        v, 0, 0, v.getWidth(), v.getHeight()).toBundle();
                Apps.launch(this, app.pkg, opts);
            });
            tile.setOnLongClickListener(v -> {
                editDock(app);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (dock.getChildCount() > 0) lp.leftMargin = gap;
            dock.addView(tile, lp);
        }
        if (!shownDock.equals(gridDock)) buildGrid();
        focusDock(Math.min(focusedIndex, dock.getChildCount() - 1));
    }

    /** Six cards fill the width; a dock with more than that scrolls. */
    private int cardWidth() {
        int gap = Ui.dp(this, 22);
        int avail = getResources().getDisplayMetrics().widthPixels - 2 * Ui.dp(this, 48);
        return Math.min(Ui.dp(this, 150), (avail - (COLUMNS - 1) * gap) / COLUMNS);
    }

    private void buildGrid() {
        grid.removeAllViews();
        gridDock = shownDock;
        int gap = Ui.dp(this, 22);
        int width = cardWidth();
        for (Apps.App app : Apps.all(this)) {
            if (gridDock.contains(app.pkg)) continue;
            View tile = Ui.appCard(this, app, width, true);
            tile.setOnClickListener(v -> {
                Bundle opts = ActivityOptions.makeScaleUpAnimation(
                        v, 0, 0, v.getWidth(), v.getHeight()).toBundle();
                Apps.launch(this, app.pkg, opts);
            });
            tile.setOnLongClickListener(v -> {
                addToDock(app);
                return true;
            });
            // Down lands on the last tile of a shorter row, and stops at the bottom instead of
            // searching the screen for something lower.
            tile.setOnKeyListener((v, keyCode, e) -> {
                if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int next = Grid.below(grid.indexOfChild(v), grid.getChildCount(), COLUMNS);
                if (e.getAction() == KeyEvent.ACTION_DOWN && next >= 0) grid.getChildAt(next).requestFocus();
                return true;
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.leftMargin = grid.getChildCount() % COLUMNS == 0 ? 0 : gap;
            lp.bottomMargin = Ui.dp(this, 10);
            grid.addView(tile, lp);
        }
    }

    private void addToDock(Apps.App app) {
        dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(new String[] {"Add to dock"}, (d, which) -> {
                    List<String> pkgs = Apps.dock(this);
                    pkgs.add(app.pkg);
                    Apps.saveDock(this, pkgs);
                    focusedPkg = app.pkg; // land on it in the dock
                    buildDock();
                })
                .show();
    }

    /** Google TV's input switcher, the panel the remote's Input button opens. */
    private void openInputs() {
        try {
            startActivity(new Intent("com.android.tv.action.VIEW_INPUTS"));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void editDock(Apps.App app) {
        List<String> pkgs = Apps.dock(this);
        int pos = pkgs.indexOf(app.pkg);
        String[] choices = {"Move left", "Move right", "Remove from dock"};
        dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(choices, (d, which) -> {
                    // Focus follows a moved app by package (see buildDock).
                    if (which == 0 && pos > 0) {
                        pkgs.add(pos - 1, pkgs.remove(pos));
                    } else if (which == 1 && pos < pkgs.size() - 1) {
                        pkgs.add(pos + 1, pkgs.remove(pos));
                    } else if (which == 2) {
                        pkgs.remove(pos);
                    }
                    Apps.saveDock(this, pkgs);
                    buildDock();
                })
                .show();
    }

    private void focusDock(int index) {
        if (index < 0 || index >= dock.getChildCount()) return;
        View v = dock.getChildAt(index);
        v.post(v::requestFocus);
    }

    // --- Scrolling between the first screen and the grid ---

    /**
     * Anything on the first screen shows it whole. In the grid, the dock moves to the top, and
     * further rows scroll just far enough to keep the focused one on screen.
     */
    private void scrollFor(View focused) {
        if (focused == null || !isInside(focused, content)) return;
        if (focused.getParent() == dock) { // for Back, and for coming back from an app
            focusedIndex = dock.indexOfChild(focused);
            focusedPkg = (String) focused.getTag();
        }
        int target = 0;
        if (isInside(focused, grid)) {
            Rect r = rectIn(focused);
            int edge = Ui.dp(this, 40);
            target = Scroll.toShow(scrollTarget, r.top - edge, r.bottom + edge, page.getHeight(),
                    gridScroll(), content.getHeight() - page.getHeight());
        }
        if (target == scrollTarget) return;
        scrollTarget = target;
        if (scroller != null) scroller.cancel();
        scroller = ObjectAnimator.ofInt(page, "scrollY", target).setDuration(SCROLL_MS);
        scroller.setInterpolator(new DecelerateInterpolator(2f));
        scroller.start();
    }

    /** The highlight springs to a focused tile, and fades out anywhere else. */
    private void followFocus(View focused) {
        if (focused != null && (focused.getParent() == dock || focused.getParent() == grid)) {
            highlight.moveTo(cardRect(focused));
        } else {
            highlight.hide();
        }
    }

    /** A tile's card as it looks focused (scaled up from its centre), in page coordinates. */
    private RectF cardRect(View tile) {
        RectF r = new RectF(rectIn(((ViewGroup) tile).getChildAt(0)));
        float grow = (Ui.FOCUS_SCALE - 1) / 2;
        r.inset(-r.width() * grow, -r.height() * grow);
        return r;
    }

    /** How far down the page is when the dock sits at the top of the screen. */
    private int gridScroll() {
        return rectIn(band).top - Ui.dp(this, 36);
    }

    private Rect rectIn(View v) {
        Rect r = new Rect();
        v.getDrawingRect(r);
        content.offsetDescendantRectToMyCoords(v, r);
        return r;
    }

    private static boolean isInside(View v, ViewGroup group) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
            if (p == group) return true;
        }
        return false;
    }

    // --- Wallpapers ---

    /** Decode off the main thread, then crossfade. */
    private void showWallpaper(int index, boolean fade) {
        if (walls.isEmpty()) return;
        final int i = ((index % walls.size()) + walls.size()) % walls.size();
        final String[] w = walls.get(i);
        decoder.execute(() -> {
            Bitmap bmp = Wallpapers.decode(this, w[0]);
            Bitmap blurred = bmp == null ? null : Glass.Backdrop.blur(bmp);
            main.post(() -> {
                if (isDestroyed() || bmp == null) return;
                backdrop.set(blurred, fade ? FADE_MS : 0);
                wall = i;
                Apps.prefs(this).edit().putInt(KEY_WALL, i).apply();
                place.setText(w[1]);
                credit.setText("Photo: " + w[2] + " · " + w[3]);
                if (!fade) {
                    back.setImageBitmap(bmp);
                    return;
                }
                front.setAlpha(0f);
                front.setImageBitmap(bmp);
                front.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
                    back.setImageBitmap(bmp);
                    front.setImageDrawable(null);
                    front.setAlpha(0f);
                }).start();
            });
        });
    }

    /** The glass behind the dock and the now-playing card: 24dp corners, a 1dp edge. */
    private Glass glass() {
        return new Glass(backdrop, Ui.dp(this, 24), Math.max(1, Ui.dp(this, 1)));
    }

    private View scrim(GradientDrawable.Orientation o, int color) {
        View v = new View(this);
        v.setBackground(new GradientDrawable(o, new int[] {color, 0x00000000}));
        return v;
    }
}
