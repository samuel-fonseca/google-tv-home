package io.github.samuelfonseca.tvhome;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A card with whatever is playing in the background (Spotify, say): album art, title,
 * artist, progress, and previous / play-pause / next. Selecting the art opens the playing app.
 * Hidden when nothing is playing or paused.
 */
final class NowPlaying extends LinearLayout {
    private static final long TICK_MS = 1_000;
    private static final long KEEP_PLAYING_MS = 8_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final MediaSessionManager sessions;
    private final ComponentName listener;
    private final Runnable onHide;

    private final ImageView art;
    private final TextView title, artist;
    private final View progress;
    private final Ui.PlayPauseIcon playPauseIcon = new Ui.PlayPauseIcon();

    private MediaController controller; // the session on the card
    private List<MediaController> active = Collections.emptyList(); // all of them, watched
    private String artKey; // URI of the art shown (or loading), so each cover is loaded once

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::update;

    // On every active session, not just the card's: an idle one (Spotify before you press play)
    // starts playing without the list of sessions changing.
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { pick(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { pick(); }
        @Override public void onSessionDestroyed() { update(null); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            showProgress();
            main.postDelayed(this, TICK_MS);
        }
    };

    /**
     * {@code onHide} runs when the card disappears while one of its buttons has focus;
     * {@code background} is the card's panel.
     */
    NowPlaying(Context ctx, Runnable onHide, android.graphics.drawable.Drawable background) {
        super(ctx);
        this.onHide = onHide;
        sessions = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        listener = new ComponentName(ctx, MediaListener.class);

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        // Focused buttons scale past their bounds; with padding, clipToPadding would cut off Next.
        setClipChildren(false);
        setClipToPadding(false);
        setVisibility(GONE);
        int pad = Ui.dp(ctx, 16);
        setPadding(pad, pad, pad, pad);
        setBackground(background);

        art = new ImageView(ctx);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setClipToOutline(true);
        int radius = Ui.dp(ctx, 8);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        art.setFocusable(true);
        art.setContentDescription("Open the playing app");
        art.setOnClickListener(v -> {
            if (controller == null) return;
            if (isPlaying(controller.getPlaybackState())) keepPlaying(controller);
            // Opens the app's launcher entry, which brings back its running task.
            Apps.launch(ctx, controller.getPackageName(), ActivityOptions.makeScaleUpAnimation(
                    v, 0, 0, v.getWidth(), v.getHeight()).toBundle());
        });
        art.setOnFocusChangeListener((v, focused) -> v.animate()
                .scaleX(focused ? 1.15f : 1f).scaleY(focused ? 1.15f : 1f)
                .translationZ(focused ? Ui.dp(ctx, 10) : 0).setDuration(160).start());
        int artSize = Ui.dp(ctx, 56);
        addView(art, new LayoutParams(artSize, artSize));

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(VERTICAL);
        title = line(ctx, 15, Ui.MEDIUM, 0xFFFFFFFF);
        artist = line(ctx, 12, Ui.LIGHT, 0xCCFFFFFF);
        text.addView(title);
        text.addView(artist);
        FrameLayout track = new FrameLayout(ctx);
        track.setBackground(pill(ctx, 0x40FFFFFF));
        progress = new View(ctx);
        progress.setBackground(pill(ctx, 0xFFFFFFFF));
        progress.setPivotX(0);
        progress.setScaleX(0);
        track.addView(progress, new FrameLayout.LayoutParams(-1, -1));
        LayoutParams tlp = new LayoutParams(-1, Ui.dp(ctx, 3));
        tlp.topMargin = Ui.dp(ctx, 8);
        text.addView(track, tlp);
        LayoutParams textLp = new LayoutParams(Ui.dp(ctx, 190), -2);
        textLp.leftMargin = Ui.dp(ctx, 14);
        textLp.rightMargin = Ui.dp(ctx, 18);
        addView(text, textLp);

        addButton(Ui.prevIcon(), "Previous", () -> controller.getTransportControls().skipToPrevious());
        addButton(playPauseIcon, "Play or pause", () -> {
            if (isPlaying(controller.getPlaybackState())) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        });
        View next = addButton(Ui.nextIcon(), "Next", () -> controller.getTransportControls().skipToNext());
        // Ends of the row stop the remote instead of falling through to the dock below.
        Ui.endOfRow(art, View.FOCUS_LEFT);
        Ui.endOfRow(next, View.FOCUS_RIGHT);
    }

    /** Start following media sessions. Call from onStart. */
    void start() {
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, main);
            update(sessions.getActiveSessions(listener));
        } catch (SecurityException e) {
            // MediaListener isn't enabled (see its comment), so there's nothing to show.
            update(null);
        }
    }

    /** Stop following; nothing on screen needs updating while Home is in the background. */
    void stop() {
        sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        watch(null);
        controller = null;
        main.removeCallbacks(tick);
    }

    void destroy() {
        loader.shutdownNow();
    }

    /**
     * If Spotify was cast to the TV and its screen isn't running, opening it ends the Cast session
     * (cast_shell logs "App stopping: AndroidNativeApp (Spotify)") and playback stops a few
     * seconds later. Press play once if it stops shortly after opening. This doesn't use the
     * card's own callback, which is unregistered as soon as Home goes to the background.
     */
    private void keepPlaying(MediaController c) {
        MediaController.Callback cb = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState s) {
                if (s == null || isPlaying(s)) return;
                c.unregisterCallback(this);
                c.getTransportControls().play();
            }
        };
        c.registerCallback(cb, main);
        main.postDelayed(() -> c.unregisterCallback(cb), KEEP_PLAYING_MS);
    }

    private void update(List<MediaController> list) {
        watch(list);
        pick();
    }

    private void watch(List<MediaController> list) {
        for (MediaController c : active) c.unregisterCallback(callback);
        active = list == null ? Collections.emptyList() : list;
        for (MediaController c : active) c.registerCallback(callback, main);
    }

    /** Show the session that's playing, or failing that one that's paused. */
    private void pick() {
        MediaController pick = null;
        for (MediaController c : active) {
            PlaybackState s = c.getPlaybackState();
            if (s == null || c.getMetadata() == null) continue;
            if (isPlaying(s)) {
                pick = c;
                break;
            }
            if (pick == null && s.getState() == PlaybackState.STATE_PAUSED) pick = c;
        }
        controller = pick;
        refresh();
    }

    private void refresh() {
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        MediaMetadata meta = controller == null ? null : controller.getMetadata();
        boolean show = state != null && meta != null
                && (isPlaying(state) || state.getState() == PlaybackState.STATE_PAUSED);
        if (!show) {
            main.removeCallbacks(tick);
            boolean hadFocus = hasFocus();
            setVisibility(GONE);
            if (hadFocus) onHide.run();
            return;
        }
        setVisibility(VISIBLE);
        title.setText(meta.getString(MediaMetadata.METADATA_KEY_TITLE));
        String by = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(by)) by = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        artist.setText(by);
        playPauseIcon.setPlaying(isPlaying(state));
        showArt(meta);
        main.removeCallbacks(tick);
        tick.run(); // shows progress now, and keeps it moving while playing
        if (!isPlaying(state)) main.removeCallbacks(tick);
    }

    private void showProgress() {
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        MediaMetadata meta = controller == null ? null : controller.getMetadata();
        long duration = meta == null ? 0 : meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (state == null || duration <= 0) {
            progress.setScaleX(0);
            return;
        }
        long pos = state.getPosition();
        if (isPlaying(state)) {
            pos += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime())
                    * state.getPlaybackSpeed());
        }
        progress.setScaleX(Math.max(0f, Math.min(1f, pos / (float) duration)));
    }

    /**
     * Album art. Prefer the URI: Spotify resends metadata several times per change, and the bitmap
     * in some of those is its logo rather than the cover, so following bitmaps makes the art
     * flicker. The URI stays the same per track, so the cover is downloaded once and the previous
     * art stays up until it arrives. Apps without a URI get their bitmap, or the app's icon.
     */
    private void showArt(MediaMetadata meta) {
        String uri = null;
        for (String k : new String[] {MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            if (uri == null) uri = meta.getString(k);
        }
        if (uri == null) {
            artKey = null;
            Bitmap bmp = null;
            for (String k : new String[] {MediaMetadata.METADATA_KEY_ALBUM_ART,
                    MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
                if (bmp == null) bmp = meta.getBitmap(k);
            }
            if (bmp != null) art.setImageBitmap(bmp);
            else showAppIcon(controller.getPackageName());
            return;
        }
        if (uri.equals(artKey)) return;
        artKey = uri;
        if (art.getDrawable() == null) showAppIcon(controller.getPackageName());
        final String src = uri;
        final String pkg = controller.getPackageName();
        loader.execute(() -> {
            Bitmap loaded = load(src);
            main.post(() -> {
                if (!Objects.equals(artKey, src)) return; // the track changed meanwhile
                if (loaded != null) art.setImageBitmap(loaded);
                else showAppIcon(pkg);
            });
        });
    }

    /** Until (or instead of) album art: the playing app's icon. */
    private void showAppIcon(String pkg) {
        try {
            art.setImageDrawable(getContext().getPackageManager().getApplicationIcon(pkg));
        } catch (PackageManager.NameNotFoundException e) {
            art.setImageDrawable(null);
        }
    }

    private Bitmap load(String uri) {
        try (InputStream in = uri.startsWith("http")
                ? new URL(uri).openStream()
                : getContext().getContentResolver().openInputStream(Uri.parse(uri))) {
            Bitmap full = BitmapFactory.decodeStream(in);
            // Spotify's covers are 640px; keep only what the art view (scaled up on focus) needs.
            int size = Ui.dp(getContext(), 64);
            if (full == null || full.getWidth() <= size) return full;
            return Bitmap.createScaledBitmap(full, size, full.getHeight() * size / full.getWidth(), true);
        } catch (Exception e) {
            return null;
        }
    }

    private View addButton(android.graphics.drawable.Drawable icon, String description, Runnable action) {
        View b = Ui.roundButton(getContext(), icon, description);
        b.setOnClickListener(v -> {
            if (controller != null) action.run();
        });
        LayoutParams lp = new LayoutParams(Ui.dp(getContext(), 40), Ui.dp(getContext(), 40));
        if (getChildCount() > 2) lp.leftMargin = Ui.dp(getContext(), 12);
        addView(b, lp);
        return b;
    }

    private static boolean isPlaying(PlaybackState s) {
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING
                || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    private static TextView line(Context ctx, float sp, android.graphics.Typeface face, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static GradientDrawable pill(Context ctx, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(Ui.dp(ctx, 2));
        return d;
    }
}
