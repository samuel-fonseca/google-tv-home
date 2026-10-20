package io.github.samuelfonseca.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Every launchable app in a grid, over the dimmed home screen. Long-press or Options to add to the
 * dock, uninstall, or for a TV input change its tile's icon and colour.
 */
public class AllAppsActivity extends Activity {
    private static final int COLUMNS = 5;
    private static final String UNINSTALL = "Uninstall";
    private static final String KEY_FOCUS = "focus";

    private Customize customize;

    // An app uninstalled from here: lay the grid out again without it.
    private final BroadcastReceiver removed = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            recreate();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        customize = new Customize(this, this::recreate); // show an input's new look here too
        int margin = Ui.dp(this, 48);
        int gap = Ui.dp(this, 26);
        int width = (getResources().getDisplayMetrics().widthPixels - 2 * margin - (COLUMNS - 1) * gap) / COLUMNS;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false); // focused cards in the edge rows and columns grow into the margin
        page.setPadding(margin, Ui.dp(this, 36), margin, Ui.dp(this, 48));

        TextView title = new TextView(this);
        title.setText("Apps");
        title.setTextSize(30);
        title.setTypeface(Ui.LIGHT);
        title.setTextColor(Color.WHITE);
        page.addView(title);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, Ui.dp(this, 24), 0, 0);
        List<Apps.App> apps = Apps.all(this);
        for (int i = 0; i < apps.size(); i++) {
            Apps.App app = apps.get(i);
            View tile = Ui.appCard(this, app, width, true);
            tile.setTag(app.pkg);
            tile.setOnClickListener(v -> {
                Bundle opts = ActivityOptions.makeScaleUpAnimation(
                        v, 0, 0, v.getWidth(), v.getHeight()).toBundle();
                if (Apps.launch(this, app.pkg, opts)) finish();
            });
            tile.setOnLongClickListener(v -> {
                toggleDock(app);
                return true;
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.leftMargin = i % COLUMNS == 0 ? 0 : gap;
            lp.bottomMargin = Ui.dp(this, 10);
            grid.addView(tile, lp);
        }
        page.addView(grid);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(0xE6101418); // the window dim alone lets the dock show through
        scroll.addView(page);
        setContentView(scroll);
        // Laid out again for an input's new look: stay on the tile that was changed.
        View back = state == null ? null : grid.findViewWithTag(state.getString(KEY_FOCUS));
        if (back != null) back.requestFocus();
        else if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();

        IntentFilter f = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        registerReceiver(removed, f);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(KEY_FOCUS, Apps.tilePkg(getCurrentFocus()));
    }

    @Override protected void onDestroy() {
        customize.dismiss();
        unregisterReceiver(removed);
        super.onDestroy();
    }

    // The remote's Options key opens the focused tile's menu, like a long press; Info, the app's
    // page in Settings. On key up, like a click, so the press doesn't also reach what opens.
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        View f = getCurrentFocus();
        String pkg = Apps.tilePkg(f);
        if (pkg != null && keyCode == KeyEvent.KEYCODE_MENU) return f.performLongClick();
        if (pkg != null && keyCode == KeyEvent.KEYCODE_INFO && Apps.showInfo(this, pkg)) return true;
        return super.onKeyUp(keyCode, event);
    }

    private void toggleDock(Apps.App app) {
        List<String> dock = Apps.dock(this);
        boolean inDock = dock.contains(app.pkg);
        List<String> choices = new ArrayList<>(Arrays.asList(inDock ? "Remove from dock" : "Add to dock"));
        choices.addAll(Customize.tileItems(app.pkg));
        if (Apps.canUninstall(this, app.pkg)) choices.add(UNINSTALL);
        choices.add(Customize.HOME);
        String[] items = choices.toArray(new String[0]);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(items, (d, w) -> {
                    if (customize.onTileItem(app, items[w])) return;
                    if (items[w].equals(UNINSTALL)) {
                        Apps.uninstall(this, app.pkg);
                        return;
                    }
                    if (inDock) dock.remove(app.pkg); else dock.add(app.pkg);
                    Apps.saveDock(this, dock);
                })
                .show();
    }
}
