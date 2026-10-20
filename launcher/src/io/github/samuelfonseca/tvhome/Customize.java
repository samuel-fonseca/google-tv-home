package io.github.samuelfonseca.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The menus behind Options: an input tile's glyph and colour, and Customize home (the background,
 * and how often it changes). Choices save straight to the preferences; the home screen listens
 * for them and redraws what changed.
 */
final class Customize {
    static final String ICON = "Change icon";
    static final String COLOUR = "Change colour";
    static final String HOME = "Customize home";
    private static final String PHOTOS = "Photos";
    private static final String[] EVERY = {"Every minute", "Every 5 minutes", "Every 15 minutes", "Every hour", "Never"};

    private final Activity activity;
    private final Runnable tilesChanged;
    private AlertDialog dialog;

    /** {@code tilesChanged} runs after an input tile's look changes, for a screen that draws them itself. */
    Customize(Activity activity, Runnable tilesChanged) {
        this.activity = activity;
        this.tilesChanged = tilesChanged;
    }

    /** What a tile's menu adds: the input items for a TV input. Callers end the menu with HOME. */
    static List<String> tileItems(String pkg) {
        List<String> items = new ArrayList<>();
        if (pkg.startsWith(Apps.INPUT)) {
            items.add(ICON);
            items.add(COLOUR);
        }
        return items;
    }

    /** Open what a tile menu item from here asks for. False for an item that isn't one of these. */
    boolean onTileItem(Apps.App app, String item) {
        switch (item) {
            case ICON: icon(app); return true;
            case COLOUR: colour(app); return true;
            case HOME: home(); return true;
            default: return false;
        }
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    void home() {
        int every = indexOf(Wallpapers.MINUTES, Wallpapers.minutes(activity));
        String[] items = {"Background: " + background(), "Change photo: " + EVERY[every]};
        dialog = builder().setTitle(HOME).setItems(items, (d, which) -> {
            if (which == 0) pickBackground(); else pickInterval();
        }).show();
    }

    /** "Photos (Lakes, Coasts)", "Photos (all)", or a colour's name. */
    private String background() {
        Palette.Swatch plain = Wallpapers.colour(activity);
        if (plain != null) return plain.name;
        List<String> shown = new ArrayList<>();
        Set<String> chosen = Wallpapers.categories(activity);
        for (String c : Lists.categories(Wallpapers.list(activity))) if (chosen.contains(c)) shown.add(c);
        return PHOTOS + " (" + (shown.isEmpty() ? "all" : String.join(", ", shown)) + ")";
    }

    /** Photos, or one of the plain colours, each shown with a swatch. */
    private void pickBackground() {
        Palette.Swatch plain = Wallpapers.colour(activity);
        List<CharSequence> items = new ArrayList<>();
        items.add(PHOTOS);
        int checked = 0;
        for (Palette.Swatch s : Palette.BACKGROUNDS) {
            if (s == plain) checked = items.size();
            GradientDrawable chip = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {s.from, s.to});
            chip.setCornerRadius(Ui.dp(activity, 4));
            chip.setStroke(Math.max(1, Ui.dp(activity, 1)), 0x59FFFFFF); // so Black shows on the dark dialog
            items.add(withPreview(chip, s.name));
        }
        dialog = builder().setTitle("Background")
                .setSingleChoiceItems(items.toArray(new CharSequence[0]), checked, (d, which) -> {
                    d.dismiss();
                    if (which == 0) pickCategories();
                    else Wallpapers.setColour(activity, Palette.BACKGROUNDS.get(which - 1).name);
                })
                .show();
    }

    /** Which photo categories to rotate through. None ticked means all of them. */
    private void pickCategories() {
        List<String> categories = Lists.categories(Wallpapers.list(activity));
        Set<String> chosen = Wallpapers.categories(activity);
        boolean[] checked = new boolean[categories.size()];
        for (int i = 0; i < checked.length; i++) checked[i] = chosen.isEmpty() || chosen.contains(categories.get(i));
        dialog = builder().setTitle(PHOTOS)
                .setMultiChoiceItems(categories.toArray(new String[0]), checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("Done", (d, w) -> {
                    chosen.clear();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) chosen.add(categories.get(i));
                    // All of them, saved as none: categories added in an update then show up too.
                    if (chosen.size() == categories.size()) chosen.clear();
                    Wallpapers.setCategories(activity, chosen);
                })
                .show();
    }

    private void pickInterval() {
        int checked = indexOf(Wallpapers.MINUTES, Wallpapers.minutes(activity));
        dialog = builder().setTitle("Change photo")
                .setSingleChoiceItems(EVERY, checked, (d, which) -> {
                    d.dismiss();
                    Wallpapers.setMinutes(activity, Wallpapers.MINUTES[which]);
                })
                .show();
    }

    /** Each glyph, previewed on the input's own tile. */
    private void icon(Apps.App app) {
        Palette.Swatch colour = Palette.find(Palette.TILES, Apps.colour(activity, app.pkg));
        CharSequence[] items = new CharSequence[Ui.GLYPHS.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = withPreview(Ui.textBanner(app.label, Ui.GLYPHS[i], colour), Ui.GLYPHS[i]);
        }
        int checked = indexOf(Ui.GLYPHS, Apps.glyph(activity, app.pkg));
        dialog = builder().setTitle(app.label)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    d.dismiss();
                    Apps.setGlyph(activity, app.pkg, Ui.GLYPHS[which]);
                    tilesChanged.run();
                })
                .show();
    }

    /** Each colour, previewed on the input's own tile. */
    private void colour(Apps.App app) {
        String glyph = Apps.glyph(activity, app.pkg);
        String[] names = Palette.names(Palette.TILES);
        CharSequence[] items = new CharSequence[names.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = withPreview(Ui.textBanner(app.label, glyph, Palette.TILES.get(i)), names[i]);
        }
        int checked = indexOf(names, Apps.colour(activity, app.pkg));
        dialog = builder().setTitle(app.label)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    d.dismiss();
                    Apps.setColour(activity, app.pkg, names[which]);
                    tilesChanged.run();
                })
                .show();
    }

    /** A menu item with a small 16:9 picture before its name. */
    private CharSequence withPreview(Drawable preview, String name) {
        int h = Ui.dp(activity, 27);
        preview.setBounds(0, 0, h * 16 / 9, h);
        SpannableString s = new SpannableString("  " + name);
        s.setSpan(new ImageSpan(preview, ImageSpan.ALIGN_BOTTOM), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private AlertDialog.Builder builder() {
        return new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return 0;
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return 0;
    }
}
