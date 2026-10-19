package io.github.samuelfonseca.tvhome;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver: the home screen's photos, crossfading every minute, with the time and the photo
 * credit. The clock moves to a new spot each minute so nothing burns in. Any key wakes the TV.
 */
public class WallpaperDream extends DreamService {
    private static final long STEP_MS = 60_000;
    private static final long FADE_MS = 2_000;
    private static final long MOVE_MS = 700;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private List<String[]> walls;
    private int wall;

    private FrameLayout root;
    private ImageView back, front;
    private LinearLayout text;
    private TextView place, credit;

    private final Runnable step = new Runnable() {
        @Override public void run() {
            showWallpaper(wall + 1, true);
            drift(true);
            main.postDelayed(this, STEP_MS);
        }
    };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        walls = Wallpapers.list(this);
        wall = walls.isEmpty() ? 0 : random.nextInt(walls.size());
        setContentView(buildLayout());
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        showWallpaper(wall, false);
        text.post(() -> drift(false)); // once it has a size
        main.postDelayed(step, STEP_MS);
    }

    @Override public void onDreamingStopped() {
        main.removeCallbacks(step);
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        decoder.shutdownNow();
        super.onDetachedFromWindow();
    }

    private View buildLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = new ImageView(this);
        front = new ImageView(this);
        for (ImageView iv : new ImageView[] {back, front}) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        // Dimmer than the home screen: it's on while nobody is looking.
        View dim = new View(this);
        dim.setBackgroundColor(0x59000000);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        Ui.style(time, 64, Ui.LIGHT, 0xFFFFFFFF);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, d MMMM");
        Ui.style(date, 18, Ui.MEDIUM, 0xD9FFFFFF);
        place = new TextView(this);
        Ui.style(place, 13, Ui.MEDIUM, 0xCCFFFFFF);
        place.setPadding(0, Ui.dp(this, 14), 0, 0);
        credit = new TextView(this);
        Ui.style(credit, 10, Ui.LIGHT, 0xA6FFFFFF);
        text.addView(time);
        text.addView(date);
        text.addView(place);
        text.addView(credit);
        root.addView(text, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        return root;
    }

    /** Move the clock to a random spot inside the screen's margins; fade it across if animating. */
    private void drift(boolean animate) {
        int margin = Ui.dp(this, 48);
        int maxX = Math.max(margin, root.getWidth() - text.getWidth() - margin);
        int maxY = Math.max(margin, root.getHeight() - text.getHeight() - margin);
        float x = margin + random.nextInt(maxX - margin + 1), y = margin + random.nextInt(maxY - margin + 1);
        if (!animate) {
            text.setTranslationX(x);
            text.setTranslationY(y);
            return;
        }
        text.animate().alpha(0f).setDuration(MOVE_MS).withEndAction(() -> {
            text.setTranslationX(x);
            text.setTranslationY(y);
            text.animate().alpha(1f).setDuration(MOVE_MS).start();
        }).start();
    }

    private void showWallpaper(int index, boolean fade) {
        if (walls.isEmpty()) return;
        final int i = ((index % walls.size()) + walls.size()) % walls.size();
        final String[] w = walls.get(i);
        decoder.execute(() -> {
            Bitmap bmp = Wallpapers.decode(this, w[0]);
            if (bmp != null) bmp.prepareToDraw(); // upload it now, not in the crossfade's first frame
            main.post(() -> {
                if (bmp == null || root == null || !root.isAttachedToWindow()) return;
                wall = i;
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
                    front.setAlpha(0f); // keeps the photo: emptying it would lay out again
                }).start();
            });
        });
    }
}
