package io.github.samuelfonseca.tvhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Every launchable app in a grid, over the dimmed home screen. Long-press to add to the dock. */
public class AllAppsActivity extends Activity {
    private static final int COLUMNS = 5;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    private void toggleDock(Apps.App app) {
        List<String> dock = Apps.dock(this);
        boolean inDock = dock.contains(app.pkg);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(new String[] {inDock ? "Remove from dock" : "Add to dock"}, (d, w) -> {
                    if (inDock) dock.remove(app.pkg); else dock.add(app.pkg);
                    Apps.saveDock(this, dock);
                })
                .show();
    }
}
