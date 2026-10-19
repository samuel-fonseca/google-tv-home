package io.github.samuelfonseca.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** The bundled photos listed in res/raw/walls.txt, shared by the home screen and the screensaver. */
final class Wallpapers {
    private Wallpapers() {}

    /** {resName, place, author, licence} for each photo. */
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
}
