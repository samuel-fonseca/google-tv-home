package io.github.samuelfonseca.tvhome;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * What the drawers list. Preferences: the background, its photo categories and how often it
 * changes, with what this launcher is at the foot. An app's menu: what the home screen can do
 * with its tile, an input's icon and color, App info and Uninstall. Choices save straight to the
 * preferences; the home screen listens for them and redraws what changed.
 */
final class Menus {
    static final String PREFERENCES = "Preferences";
    private static final String PHOTOS = "Photos";
    private static final String[] EVERY = {"Every minute", "Every 5 minutes", "Every 15 minutes", "Every hour", "Never"};
    private static final String AUTHOR = "Sam Fonseca · @oneofthesamuels";
    private static final String REPO = "github.com/samuel-fonseca/google-tv-home";

    private final Context ctx;
    private final Drawer drawer;

    Menus(Context ctx, Drawer drawer) {
        this.ctx = ctx;
        this.drawer = drawer;
    }

    /** Preferences, from its button, Info, or Options away from the tiles. */
    void preferences(Runnable onClose) {
        drawer.open(PREFERENCES, this::preferences, onClose);
    }

    private void preferences(Drawer.Page p) {
        Palette.Swatch plain = Wallpapers.color(ctx);
        p.link(Ui.photoIcon(), "Background", plain == null ? PHOTOS : plain.name, this::backgrounds);
        // Only photos have categories and change.
        if (plain == null) {
            p.link(Ui.gridIcon(), "Photo categories", categories(), this::categories);
            p.link(Ui.clockIcon(), "Change photo", EVERY[indexOf(Wallpapers.MINUTES, Wallpapers.minutes(ctx))],
                    this::intervals);
        }
        p.note("Google TV Home", "A minimal, ad-free home screen.\n" + version() + "\n" + AUTHOR + "\n"
                + REPO + "\nMIT License");
    }

    /** Photos, or one of the plain colors, each with a preview. */
    private void backgrounds(Drawer.Page p) {
        p.choice(Ui.iconBanner(Ui.photoIcon(), 0xFF50555C, 0xFF2E3236), PHOTOS, null,
                () -> Wallpapers.color(ctx) == null, () -> Wallpapers.usePhotos(ctx));
        for (Palette.Swatch s : Palette.BACKGROUNDS) {
            p.choice(swatch(s), s.name, null, () -> Wallpapers.color(ctx) == s,
                    () -> Wallpapers.setColor(ctx, s.name));
        }
    }

    /** Which photo categories to rotate through, each with how many photos it has. */
    private void categories(Drawer.Page p) {
        List<String[]> walls = Wallpapers.list(ctx);
        List<String> all = Lists.categories(walls);
        for (String c : all) {
            int n = 0;
            for (String[] w : walls) if (w[4].equals(c)) n++;
            p.check(null, c, n == 1 ? "1 photo" : n + " photos",
                    () -> Lists.ticked(all, Wallpapers.categories(ctx), c),
                    () -> Wallpapers.setCategories(ctx, Lists.toggle(all, Wallpapers.categories(ctx), c)));
        }
    }

    private void intervals(Drawer.Page p) {
        for (int i = 0; i < EVERY.length; i++) {
            int minutes = Wallpapers.MINUTES[i];
            p.choice(null, EVERY[i], null, () -> Wallpapers.minutes(ctx) == minutes,
                    () -> Wallpapers.setMinutes(ctx, minutes));
        }
    }

    /** "All", or the ones picked: "Lakes, Coasts". */
    private String categories() {
        List<String> all = Lists.categories(Wallpapers.list(ctx));
        Set<String> chosen = Wallpapers.categories(ctx);
        List<String> shown = new ArrayList<>();
        for (String c : all) if (Lists.ticked(all, chosen, c)) shown.add(c);
        return shown.size() == all.size() ? "All" : String.join(", ", shown);
    }

    /**
     * An app's menu, titled with its name under its banner. {@code tile} adds what the home screen
     * can do with it, such as moving it in the dock.
     */
    void app(Apps.App app, Drawer.Content tile, Runnable onClose) {
        drawer.open(app.label, p -> {
            Apps.App now = Apps.get(ctx, app.pkg); // its own banner, in an input's latest look
            if (now != null && now.banner != null) p.header(now.banner, 176, 99);
            tile.fill(p);
            p.divider();
            if (app.pkg.startsWith(Apps.INPUT)) {
                p.link(Ui.inputIcon(), "Icon", Apps.glyph(ctx, app.pkg), sub -> icons(sub, app));
                p.link(Ui.dropIcon(), "Color", Apps.color(ctx, app.pkg), sub -> colors(sub, app));
            } else {
                p.action(Ui.infoIcon(), "App info", "Storage, permissions, force stop", () -> {
                    drawer.close();
                    Apps.showInfo(ctx, app.pkg);
                });
                if (Apps.canUninstall(ctx, app.pkg)) {
                    p.action(Ui.binIcon(), "Uninstall", null, () -> {
                        drawer.close();
                        Apps.uninstall(ctx, app.pkg);
                    });
                }
            }
        }, onClose);
    }

    /** Each glyph, previewed on the input's own tile. */
    private void icons(Drawer.Page p, Apps.App app) {
        Palette.Swatch color = Palette.find(Palette.TILES, Apps.color(ctx, app.pkg));
        for (String g : Ui.GLYPHS) {
            p.choice(Ui.textBanner(app.label, g, color), g, null, () -> Apps.glyph(ctx, app.pkg).equals(g),
                    () -> Apps.setGlyph(ctx, app.pkg, g));
        }
    }

    /** Each color, previewed on the input's own tile. */
    private void colors(Drawer.Page p, Apps.App app) {
        String glyph = Apps.glyph(ctx, app.pkg);
        for (Palette.Swatch s : Palette.TILES) {
            p.choice(Ui.textBanner(app.label, glyph, s), s.name, null,
                    () -> Apps.color(ctx, app.pkg).equals(s.name), () -> Apps.setColor(ctx, app.pkg, s.name));
        }
    }

    /** A plain background's gradient, edged so Black shows on the dark panel. */
    private Drawable swatch(Palette.Swatch s) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {s.from, s.to});
        d.setStroke(Math.max(1, Ui.dp(ctx, 1)), 0x40FFFFFF);
        return d;
    }

    /** "Version 1.10, installed October 19, 2026". */
    private String version() {
        try {
            PackageInfo p = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return "Version " + p.versionName + ", installed "
                    + DateFormat.getDateInstance(DateFormat.LONG).format(new Date(p.lastUpdateTime));
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return 0;
    }
}
