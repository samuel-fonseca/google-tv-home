#!/usr/bin/env bash
# Install the launcher and make it the Home app. Undo with tools/restore.sh.
set -euo pipefail
# shellcheck source=common.sh
source "$(dirname "$0")/common.sh"
ensure_connected
require_backup

adb install -r "$ROOT/launcher/build/tvhome.apk"
# Launchers are opened constantly; compile ahead of time instead of waiting for the idle job.
adb shell cmd package compile -m speed -f io.github.samuelfonseca.tvhome
adb shell cmd package set-home-activity io.github.samuelfonseca.tvhome/.MainActivity
# The now-playing card needs to see other apps' media sessions, which takes a notification listener.
adb shell cmd notification allow_listener io.github.samuelfonseca.tvhome/io.github.samuelfonseca.tvhome.MediaListener
# The remote's Settings button only opens launcherx's quick-settings panel; this service catches
# the key and opens Settings instead. This replaces any other enabled accessibility services.
adb shell settings put secure enabled_accessibility_services io.github.samuelfonseca.tvhome/io.github.samuelfonseca.tvhome.SettingsKey
adb shell settings put secure accessibility_enabled 1
# Every reinstall force-stops the launcher, and Android turns a force-stopped app's accessibility
# services off. This lets the launcher turn its own back on when it next starts.
adb shell pm grant io.github.samuelfonseca.tvhome android.permission.WRITE_SECURE_SETTINGS
# ORDER MATTERS. setupwraith must be off before launcherx: if the Google TV home disappears while
# setupwraith is enabled, its RecoveryActivity becomes Home and wipes the saved Wi-Fi network
# (it has happened).
"$ROOT/tools/disable.sh" com.google.android.tungsten.setupwraith
"$ROOT/tools/disable.sh" com.google.android.apps.tv.launcherx
adb shell input keyevent KEYCODE_HOME
