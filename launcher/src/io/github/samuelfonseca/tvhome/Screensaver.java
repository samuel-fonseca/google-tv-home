package io.github.samuelfonseca.tvhome;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Which screensaver the TV runs: this launcher's photos (WallpaperDream), the TV's own, or none.
 * Set in Settings.Secure, so it needs WRITE_SECURE_SETTINGS, which {@code tools/set-home.sh}
 * grants over adb. Google TV's Settings has no screen to pick a screensaver from an app.
 */
final class Screensaver {
    static final int PHOTOS = 0, TV = 1, OFF = 2;
    // Hidden Settings.Secure names.
    private static final String ENABLED = "screensaver_enabled", COMPONENTS = "screensaver_components";
    // What the TV ran before Photos was picked, to put back for TV. Unset means its default.
    private static final String KEY_WAS = "screensaver_was";

    private Screensaver() {}

    /** Whether this app may change it. */
    static boolean canSet(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** PHOTOS, TV or OFF. */
    static int current(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        if (Settings.Secure.getInt(cr, ENABLED, 1) == 0) return OFF;
        return ours(ctx, Settings.Secure.getString(cr, COMPONENTS)) ? PHOTOS : TV;
    }

    static void set(Context ctx, int which) {
        ContentResolver cr = ctx.getContentResolver();
        String now = Settings.Secure.getString(cr, COMPONENTS);
        if (which == PHOTOS && !ours(ctx, now)) {
            Apps.prefs(ctx).edit().putString(KEY_WAS, now).apply();
            Settings.Secure.putString(cr, COMPONENTS, me(ctx).flattenToString());
        } else if (which == TV && ours(ctx, now)) {
            Settings.Secure.putString(cr, COMPONENTS, Apps.prefs(ctx).getString(KEY_WAS, null));
        }
        Settings.Secure.putInt(cr, ENABLED, which == OFF ? 0 : 1);
    }

    /** Whether the first of the screensaver components, the one that runs, is WallpaperDream. */
    private static boolean ours(Context ctx, String components) {
        if (components == null || components.isEmpty()) return false;
        return me(ctx).equals(ComponentName.unflattenFromString(components.split(",")[0]));
    }

    private static ComponentName me(Context ctx) {
        return new ComponentName(ctx, WallpaperDream.class);
    }
}
