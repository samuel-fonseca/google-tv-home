package io.github.samuelfonseca.tvhome;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes the remote's Settings button open Settings again. The system sends that key to the
 * Google TV home's quick-settings panel, and only to that package (and only if the panel needs a
 * system permission), so with launcherx disabled the button does nothing and apps never see it.
 * An accessibility service gets keys before the system does. Enabled over adb by
 * {@code tools/set-home.sh}, and by the home screen again after a reinstall (see ensureEnabled).
 */
public class SettingsKey extends AccessibilityService {
    @Override protected boolean onKeyEvent(KeyEvent event) {
        // The TCL IR remote's gear sends NOTIFICATION; Google's Bluetooth remote sends SETTINGS.
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_NOTIFICATION && key != KeyEvent.KEYCODE_SETTINGS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return true;
    }

    /**
     * Turn this service back on if it's off. Android turns an app's accessibility services off
     * whenever the app is force-stopped, and every reinstall force-stops it. Needs
     * WRITE_SECURE_SETTINGS, which {@code tools/set-home.sh} grants over adb; without it this does
     * nothing.
     */
    static void ensureEnabled(Context ctx) {
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        ContentResolver cr = ctx.getContentResolver();
        ComponentName me = new ComponentName(ctx, SettingsKey.class);
        String on = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean listed = false;
        if (on != null && !on.isEmpty()) {
            for (String s : on.split(":")) listed |= me.equals(ComponentName.unflattenFromString(s));
        }
        if (listed && Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1) return;
        // Added to the list, keeping any other services the TV has on.
        if (!listed) {
            on = (on == null || on.isEmpty() ? "" : on + ":") + me.flattenToString();
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, on);
        }
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override public void onInterrupt() {
    }
}
