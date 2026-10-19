package io.github.samuelfonseca.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Launchable apps, TV inputs, and the saved dock order. */
final class Apps {
    static final class App {
        final String pkg; // package name, or INPUT + TV input id

        final String label;
        final Drawable banner; // 16:9 leanback banner, or null
        final Drawable icon;

        App(String pkg, String label, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
        }
    }

    /** Dock entries that start with this switch to a TV input (an HDMI port) instead of an app. */
    static final String INPUT = "input:";

    private static final String PREFS = "home";
    private static final String KEY_DOCK = "dock";

    private Apps() {}

    /**
     * Every app with a TV or phone launcher entry, sorted by name, then the TV's inputs.
     * Disabled apps don't appear.
     */
    static List<App> all(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<App> apps = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> e : resolve(ctx, null).entrySet()) {
            apps.add(load(pm, e.getKey(), e.getValue()));
        }
        Collator collator = Collator.getInstance();
        Collections.sort(apps, (a, b) -> collator.compare(a.label, b.label));
        apps.addAll(inputs(ctx));
        return apps;
    }

    /** HDMI and other passthrough inputs, except those hidden in Settings > Inputs. */
    private static List<App> inputs(Context ctx) {
        List<App> inputs = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return inputs;
        for (TvInputInfo info : tim.getTvInputList()) {
            // A CEC device gets its own input under its HDMI port's; the port's tile covers it.
            if (!info.isPassthroughInput() || info.getParentId() != null || info.isHidden(ctx)) continue;
            App app = input(ctx, INPUT + info.getId());
            if (app != null) inputs.add(app);
        }
        return inputs;
    }

    /** One app or input, or null if it isn't installed, is disabled, or has no launcher entry. */
    static App get(Context ctx, String pkg) {
        if (pkg.startsWith(INPUT)) return input(ctx, pkg);
        ResolveInfo ri = resolve(ctx, pkg).get(pkg);
        return ri == null ? null : load(ctx.getPackageManager(), pkg, ri);
    }

    /** Launcher entries by package, for one package or (pkg == null) all of them. */
    private static Map<String, ResolveInfo> resolve(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, ResolveInfo> byPkg = new LinkedHashMap<>();
        // Leanback entries first so they win over phone-style LAUNCHER entries.
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String p = ri.activityInfo.packageName;
                if (!p.equals(ctx.getPackageName()) && !byPkg.containsKey(p)) byPkg.put(p, ri);
            }
        }
        return byPkg;
    }

    // Loading labels and art opens each app's APK, so callers only load what they show.
    private static App load(PackageManager pm, String pkg, ResolveInfo ri) {
        // Google's Settings banner has a fold line drawn through the middle; use a clean one.
        Drawable banner = pkg.equals("com.android.tv.settings")
                ? Ui.iconBanner(Ui.roundGearIcon(), 0xFF50555C, 0xFF2E3236)
                : ri.activityInfo.loadBanner(pm);
        return new App(pkg, String.valueOf(ri.loadLabel(pm)), banner, banner == null ? ri.loadIcon(pm) : null);
    }

    /** A TV input, named by its label from Settings > Inputs (e.g. "Game console"), on a drawn banner. */
    private static App input(Context ctx, String key) {
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        TvInputInfo info = tim == null ? null : tim.getTvInputInfo(key.substring(INPUT.length()));
        if (info == null) return null;
        CharSequence label = info.loadCustomLabel(ctx);
        if (label == null || label.length() == 0) label = info.loadLabel(ctx);
        return new App(key, String.valueOf(label), Ui.textBanner(String.valueOf(label)), null);
    }

    static boolean launch(Context ctx, String pkg, android.os.Bundle options) {
        if (pkg.startsWith(INPUT)) {
            // Handled by Google's input player, the same screen the Inputs menu opens.
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(pkg.substring(INPUT.length())))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(intent, options);
                return true;
            } catch (ActivityNotFoundException e) {
                return false;
            }
        }
        PackageManager pm = ctx.getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent, options);
        return true;
    }

    /** The package a focused app tile is for (tiles are tagged with it), or null for any other view. */
    static String tilePkg(View focused) {
        return focused != null && focused.getTag() instanceof String ? (String) focused.getTag() : null;
    }

    /** Whether it can be uninstalled: apps that came with the TV can only be disabled. */
    static boolean canUninstall(Context ctx, String pkg) {
        if (pkg.startsWith(INPUT)) return false;
        try {
            return (ctx.getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Android's uninstall prompt. The home screen hears when the app goes (packagesChanged). */
    static void uninstall(Context ctx, String pkg) {
        ctx.startActivity(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)));
    }

    /** The app's page in Settings: storage, permissions, force stop. False for a TV input. */
    static boolean showInfo(Context ctx, String pkg) {
        if (pkg.startsWith(INPUT)) return false;
        ctx.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    /** Saved dock order, or the default from res/raw/dock.txt. */
    static List<String> dock(Context ctx) {
        String saved = prefs(ctx).getString(KEY_DOCK, null);
        List<String> pkgs = new ArrayList<>();
        if (saved != null) {
            for (String p : saved.split("\n")) if (!p.isEmpty()) pkgs.add(p);
            return pkgs;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getResources().openRawResource(R.raw.dock)))) {
            pkgs.addAll(Lists.entries(r));
        } catch (IOException ignored) {
        }
        return pkgs;
    }

    static void saveDock(Context ctx, List<String> pkgs) {
        prefs(ctx).edit().putString(KEY_DOCK, String.join("\n", pkgs)).apply();
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
