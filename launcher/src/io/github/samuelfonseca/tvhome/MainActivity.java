package io.github.samuelfonseca.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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
 * The home screen: rotating nature wallpaper, clock, what's playing, and a dock of favorite apps.
 * Down from the dock scrolls the page up to a grid of every other app, like tvOS. Preferences and
 * each app's menu slide in from the right (see Drawer).
 */
public class MainActivity extends Activity {
    private static final long FADE_MS = 1_500;
    // The page scrolls on a spring, critically damped so it never overshoots: it eases in and out,
    // and a press during a scroll bends it towards the new row instead of restarting it.
    private static final float SCROLL_STIFFNESS = 500f;
    private static final int DIM = 0x0A0C10; // the color the photo darkens towards in the grid
    private static final int COLUMNS = 6; // dock cards and grid columns line up
    private static final String KEY_WALL = "wall";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final List<String[]> walls = new ArrayList<>(); // {resName, place, author, license, category}
    private Palette.Swatch plain; // the background color instead of photos, or null

    private ImageView back, front;
    private View topScrim, bottomScrim;
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
    private final Spring scroll = new Spring(SCROLL_STIFFNESS, 1f);
    private boolean scrolling;
    private long lastScrollFrame;
    private int scrollTarget;
    private NowPlaying nowPlaying;
    private Drawer drawer;
    private Menus menus;
    private int wall;
    private Highlight highlight;
    // The dock app to come back to. Kept by package as well as position, so it survives the dock
    // being rebuilt or reordered.
    private int focusedIndex;
    private String focusedPkg;

    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            showWallpaper(wall + 1, true);
            scheduleRotate();
        }
    };

    // Posted, so the two keys one choice can save make one change on screen.
    private final Runnable backgroundChanged = () -> {
        loadBackground();
        showWallpaper(wall, true);
        scheduleRotate();
    };
    private final Runnable tilesChanged = () -> {
        gridDock = null;
        buildDock();
    };

    // Preferences and the input menus save their choices; redraw what they change.
    private final SharedPreferences.OnSharedPreferenceChangeListener customized = (prefs, key) -> {
        Runnable r = key == null ? null
                : key.startsWith(Wallpapers.KEY_PREFIX) ? backgroundChanged
                : key.startsWith(Apps.KEY_GLYPH) || key.startsWith(Apps.KEY_COLOR) ? tilesChanged
                : null;
        if (r == null) return;
        main.removeCallbacks(r);
        main.post(r);
    };

    private final Choreographer.FrameCallback scrollFrame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long now) {
            float dt = lastScrollFrame == 0 ? 1 / 60f : Math.min((now - lastScrollFrame) / 1e9f, 0.1f);
            lastScrollFrame = now;
            boolean moving = scroll.step(dt);
            page.setScrollY(Math.round(scroll.value));
            if (moving) Choreographer.getInstance().postFrameCallback(this);
            else scrolling = false;
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
        loadBackground();
        setContentView(buildLayout());
        // The root paints black under the photo already; a window background too would fill the
        // screen once more every frame.
        getWindow().setBackgroundDrawable(null);
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
        Apps.prefs(this).registerOnSharedPreferenceChangeListener(customized);
    }

    @Override protected void onStart() {
        super.onStart();
        SettingsKey.ensureEnabled(this);
        scheduleRotate();
        nowPlaying.start();
    }

    /**
     * Back from an app: an input may have been renamed in Settings. Nothing else needs a rebuild,
     * which would reload every banner from its APK on the way back: apps that come, go or change
     * are caught by packagesChanged.
     */
    @Override protected void onRestart() {
        super.onRestart();
        if (inputRenamed()) {
            gridDock = null;
            buildDock();
        }
    }

    @Override protected void onResume() {
        super.onResume();
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
        Choreographer.getInstance().removeFrameCallback(scrollFrame);
        unregisterReceiver(packagesChanged);
        Apps.prefs(this).unregisterOnSharedPreferenceChangeListener(customized);
        main.removeCallbacksAndMessages(null);
        decoder.shutdownNow();
        nowPlaying.destroy();
        super.onDestroy();
    }

    /** Home pressed while already home: close any drawer and go back to the first app. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        drawer.close();
        focusedIndex = 0;
        dockScroll.smoothScrollTo(0, 0);
        focusDock(0); // and the page scrolls back up with it
    }

    // The remote's Options key opens the focused tile's menu, like a long press, and Preferences
    // anywhere else; Info opens Preferences, which also says what this launcher is. Either closes
    // an open drawer. On key up, like a click, so the press doesn't also reach the drawer.
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_MENU && keyCode != KeyEvent.KEYCODE_INFO) {
            return super.onKeyUp(keyCode, event);
        }
        View f = getCurrentFocus();
        if (drawer.isOpen()) drawer.close();
        else if (keyCode == KeyEvent.KEYCODE_MENU && Apps.tilePkg(f) != null) f.performLongClick();
        else openPreferences(f);
        return true;
    }

    @Override public void onBackPressed() {
        // Home is the bottom of the stack; Back only steps back through a drawer, or returns from
        // the grid to the dock.
        if (drawer.back()) return;
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

        // Soft scrims so text and the dock stay legible on bright photos. They fade out as the
        // page scrolls down to the grid, where the photo itself darkens (see onScroll).
        topScrim = scrim(GradientDrawable.Orientation.TOP_BOTTOM, 0x99000000);
        root.addView(topScrim, new FrameLayout.LayoutParams(-1, Ui.dp(this, 170), Gravity.TOP));
        bottomScrim = scrim(GradientDrawable.Orientation.BOTTOM_TOP, 0xB3000000);
        root.addView(bottomScrim, new FrameLayout.LayoutParams(-1, Ui.dp(this, 230), Gravity.BOTTOM));

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
        page.setOnScrollChangeListener((v, x, y, oldX, oldY) -> onScroll(y));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        page.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // One glass frame over the tiles that springs to whichever has focus (see followFocus).
        // In the page's overlay, so it scrolls with the page and draws over the cards.
        highlight = new Highlight(this, Ui.dp(this, 10) * Ui.FOCUS_SCALE, Ui.dp(this, 1), Ui.dp(this, 6));
        content.getOverlay().add(highlight);
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                followFocus(getCurrentFocus())); // tiles may have moved

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

        // Top right: inputs, preferences and settings buttons, with the photo credit underneath.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        LinearLayout buttons = new LinearLayout(this);
        // On glass like the dock, each its own panel: they widen on focus.
        View inputs = Ui.labelButton(this, Ui.inputIcon(), "Inputs", glass(20));
        inputs.setOnClickListener(v -> openInputs());
        View preferences = Ui.labelButton(this, Ui.tuneIcon(), Menus.PREFERENCES, glass(20));
        preferences.setOnClickListener(this::openPreferences);
        View settings = Ui.labelButton(this, Ui.gearIcon(), "Settings", glass(20));
        settings.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        // The ends of the row stop the remote, instead of falling through to the nearest tile.
        Ui.endOfRow(inputs, View.FOCUS_LEFT);
        Ui.endOfRow(settings, View.FOCUS_RIGHT);
        buttons.addView(inputs);
        for (View b : new View[] {preferences, settings}) {
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, Ui.dp(this, 40));
            gap.leftMargin = Ui.dp(this, 14);
            buttons.addView(b, gap);
        }
        backdrop.addPanel(inputs);
        backdrop.addPanel(preferences);
        backdrop.addPanel(settings);
        // Wrap content, so each row hugs the right edge and the column resizes with the caption,
        // or with a button as it widens.
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
        // No names under dock cards, so even padding centers them in the band.
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

        // Preferences and the app menus slide in over everything, from the right.
        drawer = new Drawer(this);
        menus = new Menus(this, drawer);
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
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
                dockMenu(app, v);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (dock.getChildCount() > 0) lp.leftMargin = gap;
            dock.addView(tile, lp);
        }
        // The dock's ends stop the remote: when it fits without scrolling, Android would otherwise
        // search the whole screen and land on a grid tile or a top button.
        int n = dock.getChildCount();
        if (n > 0) {
            Ui.endOfRow(dock.getChildAt(0), View.FOCUS_LEFT);
            Ui.endOfRow(dock.getChildAt(n - 1), View.FOCUS_RIGHT);
        }
        if (!shownDock.equals(gridDock)) buildGrid();
        focusDock(Math.min(focusedIndex, n - 1));
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
            tile.setTag(app.pkg);
            tile.setOnClickListener(v -> {
                Bundle opts = ActivityOptions.makeScaleUpAnimation(
                        v, 0, 0, v.getWidth(), v.getHeight()).toBundle();
                Apps.launch(this, app.pkg, opts);
            });
            tile.setOnLongClickListener(v -> {
                gridMenu(app, v);
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
            // Each row's ends stop the remote too, rather than jumping to the row above or below.
            if (grid.getChildCount() % COLUMNS == 0) Ui.endOfRow(tile, View.FOCUS_LEFT);
            if (grid.getChildCount() % COLUMNS == COLUMNS - 1) Ui.endOfRow(tile, View.FOCUS_RIGHT);
            lp.bottomMargin = Ui.dp(this, 10);
            grid.addView(tile, lp);
        }
        int n = grid.getChildCount();
        if (n > 0) Ui.endOfRow(grid.getChildAt(n - 1), View.FOCUS_RIGHT); // a short last row
    }

    /** Whether a TV input on screen has a new name from Settings > Inputs, or is gone. */
    private boolean inputRenamed() {
        for (ViewGroup row : new ViewGroup[] {dock, grid}) {
            for (int i = 0; i < row.getChildCount(); i++) {
                View tile = row.getChildAt(i);
                String pkg = (String) tile.getTag();
                if (pkg == null || !pkg.startsWith(Apps.INPUT)) continue;
                Apps.App app = Apps.get(this, pkg); // cheap for an input: no APK to open
                if (app == null || !app.label.contentEquals(tile.getContentDescription())) return true;
            }
        }
        return false;
    }

    /** A grid tile's menu: it can join the dock, at the end. */
    private void gridMenu(Apps.App app, View tile) {
        menus.app(app, p -> p.action(Ui.plusIcon(false), "Add to dock", null, () -> {
            drawer.close();
            List<String> pkgs = Apps.dock(this);
            pkgs.add(app.pkg);
            Apps.saveDock(this, pkgs);
            focusedPkg = app.pkg; // land on it in the dock
            buildDock();
        }), () -> refocus(tile, app.pkg));
    }

    /**
     * A dock tile's menu. Moving it keeps the drawer open, so it can move again, and the dock
     * rearranges behind it.
     */
    private void dockMenu(Apps.App app, View tile) {
        menus.app(app, p -> {
            List<String> pkgs = Apps.dock(this);
            int pos = pkgs.indexOf(app.pkg);
            if (pos > 0) p.action(Ui.arrowIcon(true), "Move left", null, () -> moveInDock(app.pkg, -1));
            if (pos < pkgs.size() - 1) {
                p.action(Ui.arrowIcon(false), "Move right", null, () -> moveInDock(app.pkg, 1));
            }
            p.action(Ui.plusIcon(true), "Remove from dock", null, () -> {
                drawer.close();
                List<String> rest = Apps.dock(this);
                rest.remove(app.pkg);
                Apps.saveDock(this, rest);
                buildDock();
            });
        }, () -> refocus(tile, app.pkg));
    }

    /** Swap an app with its neighbor in the dock, {@code by} -1 for left or 1 for right. */
    private void moveInDock(String pkg, int by) {
        List<String> pkgs = Apps.dock(this);
        int pos = pkgs.indexOf(pkg);
        if (pos < 0 || pos + by < 0 || pos + by >= pkgs.size()) return;
        pkgs.add(pos + by, pkgs.remove(pos));
        Apps.saveDock(this, pkgs);
        focusedPkg = pkg; // focus follows it by package (see buildDock)
        buildDock();
        drawer.reload(); // at either end, one of the moves goes
    }

    /** Preferences, from its button or a remote key; focus comes back to {@code from}. */
    private void openPreferences(View from) {
        menus.preferences(() -> refocus(from, Apps.tilePkg(from)));
    }

    /**
     * Focus back where it was before a drawer opened: the same view, or if its row was rebuilt
     * meanwhile, the same app's new tile; failing both, the dock.
     */
    private void refocus(View before, String pkg) {
        if (before != null && before.isAttachedToWindow() && before.requestFocus()) return;
        if (pkg != null) {
            for (ViewGroup row : new ViewGroup[] {dock, grid}) {
                for (int i = 0; i < row.getChildCount(); i++) {
                    View tile = row.getChildAt(i);
                    if (pkg.equals(tile.getTag()) && tile.requestFocus()) return;
                }
            }
        }
        focusDock(focusedIndex);
    }

    /** Google TV's input switcher, the panel the remote's Input button opens. */
    private void openInputs() {
        try {
            startActivity(new Intent("com.android.tv.action.VIEW_INPUTS"));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /** Focus a dock tile, unless a drawer has focus: the dock rebuilds behind one as apps move. */
    private void focusDock(int index) {
        if (drawer.isOpen() || index < 0 || index >= dock.getChildCount()) return;
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
        if (!scrolling) scroll.snap(page.getScrollY()); // from rest; mid-scroll keeps its speed
        scroll.target = target;
        if (scrolling) return;
        scrolling = true;
        lastScrollFrame = 0;
        Choreographer.getInstance().postFrameCallback(scrollFrame);
    }

    /**
     * Down the page towards the grid, the photo darkens, and the dock's band and the now-playing
     * card fade away. The darkening is a color filter on the photo, which costs nothing extra to
     * draw; a dark view over it would cover the whole screen with another layer.
     */
    private void onScroll(int y) {
        float p = Math.min(1f, y / (float) Math.max(1, gridScroll()));
        int dim = Math.round(255 * p * 0.85f);
        for (ImageView iv : new ImageView[] {back, front}) {
            if (dim == 0) iv.clearColorFilter();
            else iv.setColorFilter(dim << 24 | DIM, PorterDuff.Mode.SRC_ATOP);
        }
        topScrim.setAlpha(1 - p);
        bottomScrim.setAlpha(1 - p);
        bandBg.setAlpha(Math.round(255 * (1 - p))); // in the grid the dock is just the top row
        nowPlaying.setAlpha(1 - p); // it sits just above the dock, so it would peek in at the top
        backdrop.redraw(); // the panels moved over the wallpaper
    }

    /** The highlight springs to a focused tile, and fades out anywhere else. */
    private void followFocus(View focused) {
        if (focused != null && (focused.getParent() == dock || focused.getParent() == grid)) {
            highlight.moveTo(cardRect(focused));
        } else {
            highlight.hide();
        }
    }

    /** A tile's card as it looks focused (scaled up from its center), in page coordinates. */
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

    /** The background picked in Preferences: photos from some categories, or a plain color. */
    private void loadBackground() {
        plain = Wallpapers.color(this);
        walls.clear();
        if (plain == null) walls.addAll(Wallpapers.chosen(this));
    }

    /** The next photo, after the wait picked in Preferences. None for a plain color, or Never. */
    private void scheduleRotate() {
        main.removeCallbacks(rotate);
        int minutes = Wallpapers.minutes(this);
        if (plain == null && minutes > 0 && walls.size() > 1) main.postDelayed(rotate, minutes * 60_000L);
    }

    /** Decode off the main thread (or draw the plain color), then crossfade. */
    private void showWallpaper(int index, boolean fade) {
        final Palette.Swatch color = plain;
        if (color == null && walls.isEmpty()) return;
        final int i = color != null ? wall : ((index % walls.size()) + walls.size()) % walls.size();
        final String[] w = color != null ? null : walls.get(i);
        decoder.execute(() -> {
            Bitmap bmp = w == null ? Wallpapers.plain(color) : Wallpapers.decode(this, w[0]);
            Bitmap blurred = bmp == null ? null : Glass.Backdrop.blur(bmp);
            if (bmp != null) {
                // Upload to the GPU now, rather than in the first frame of the crossfade.
                bmp.prepareToDraw();
                blurred.prepareToDraw();
            }
            main.post(() -> {
                if (isDestroyed() || bmp == null) return;
                backdrop.set(blurred, fade ? FADE_MS : 0);
                wall = i;
                Apps.prefs(this).edit().putInt(KEY_WALL, i).apply();
                place.setText(w == null ? "" : w[1]);
                credit.setText(w == null ? "" : "Photo: " + w[2] + " · " + w[3]);
                if (!fade) {
                    back.setImageBitmap(bmp);
                    return;
                }
                front.setAlpha(0f);
                front.setImageBitmap(bmp);
                front.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
                    back.setImageBitmap(bmp);
                    // Hidden, but keeps the photo (the same bitmap as back's): emptying it would
                    // change its size and lay out the whole page again.
                    front.setAlpha(0f);
                }).start();
            });
        });
    }

    /** The glass behind the dock and the now-playing card: 24dp corners, a 1dp edge. */
    private Glass glass() {
        return glass(24);
    }

    private Glass glass(float radiusDp) {
        return new Glass(backdrop, Ui.dp(this, radiusDp), Math.max(1, Ui.dp(this, 1)));
    }

    private View scrim(GradientDrawable.Orientation o, int color) {
        View v = new View(this);
        v.setBackground(new GradientDrawable(o, new int[] {color, 0x00000000}));
        v.forceHasOverlappingRendering(false); // so fading it doesn't draw it offscreen first
        return v;
    }
}
