package io.github.samuelfonseca.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The bundled photos listed in res/raw/walls.txt, shared by the home screen and the screensaver,
 * and the background picked in Customize home: photos from some categories, or a plain colour.
 */
final class Wallpapers {
    // The home screen reloads its background when a key with this prefix changes.
    static final String KEY_PREFIX = "bg_";
    private static final String KEY_COLOUR = "bg_colour"; // a Palette.BACKGROUNDS name; unset for photos
    private static final String KEY_CATEGORIES = "bg_categories";
    private static final String KEY_MINUTES = "bg_minutes";
    /** How often the photo can change, in minutes; 0 never. The first is the default. */
    static final int[] MINUTES = {1, 5, 15, 60, 0};

    private Wallpapers() {}

    /** {resName, place, author, licence, category} for each photo. */
    static List<String[]> list(Context ctx) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getResources().openRawResource(R.raw.walls)))) {
            return Lists.wallpapers(r);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /** One photo as RGB_565 (half the memory), or null. Slow: call it off the main thread. */
    @SuppressLint("DiscouragedApi") // wallpapers are named in walls.txt, so looked up by name
    static Bitmap decode(Context ctx, String resName) {
        int res = ctx.getResources().getIdentifier(resName, "drawable", ctx.getPackageName());
        if (res == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inScaled = false;
        return BitmapFactory.decodeResource(ctx.getResources(), res, o);
    }

    /** The photos in the chosen categories: every photo until some are chosen. */
    static List<String[]> chosen(Context ctx) {
        return Lists.inCategories(list(ctx), categories(ctx));
    }

    static Set<String> categories(Context ctx) {
        return new HashSet<>(Apps.prefs(ctx).getStringSet(KEY_CATEGORIES, new HashSet<>()));
    }

    /** Photos from these categories, instead of a plain colour. */
    static void setCategories(Context ctx, Set<String> categories) {
        Apps.prefs(ctx).edit().putStringSet(KEY_CATEGORIES, categories).remove(KEY_COLOUR).apply();
    }

    /** The plain background picked instead of photos, or null for photos. */
    static Palette.Swatch colour(Context ctx) {
        String name = Apps.prefs(ctx).getString(KEY_COLOUR, null);
        return Palette.has(Palette.BACKGROUNDS, name) ? Palette.find(Palette.BACKGROUNDS, name) : null;
    }

    static void setColour(Context ctx, String name) {
        Apps.prefs(ctx).edit().putString(KEY_COLOUR, name).apply();
    }

    static int minutes(Context ctx) {
        return Apps.prefs(ctx).getInt(KEY_MINUTES, MINUTES[0]);
    }

    static void setMinutes(Context ctx, int minutes) {
        Apps.prefs(ctx).edit().putInt(KEY_MINUTES, minutes).apply();
    }

    /**
     * A plain background, the size of the photos: the swatch's gradient corner to corner, dithered
     * so a dark gradient that wide doesn't band.
     */
    static Bitmap plain(Palette.Swatch s) {
        int w = 1280, h = 720;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, s.from, s.to, Shader.TileMode.CLAMP));
        new Canvas(b).drawPaint(p);
        return b;
    }
}
