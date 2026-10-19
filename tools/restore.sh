#!/usr/bin/env bash
# Undo everything: re-enable every package tools/ disabled, then restore the original Home.
# Usage: tools/restore.sh [--dry-run] [--remove-launcher] [backups/<timestamp>]
set -euo pipefail
# shellcheck source=common.sh
source "$(dirname "$0")/common.sh"

DRY=0; REMOVE=0
for a in "$@"; do
  case "$a" in
    --dry-run) DRY=1 ;;
    --remove-launcher) REMOVE=1 ;;
    *) BACKUP_DIR="$(cd "$a" && pwd)"; APPLIED="$BACKUP_DIR/applied.txt" ;;
  esac
done
run() { echo "+ adb shell $*"; [ $DRY = 1 ] || adb shell "$@"; }

ensure_connected
require_backup
[ -f "$APPLIED" ] || { echo "no $APPLIED: nothing was disabled" >&2; }

# setupwraith is re-enabled LAST, only once the original Home is back in place. If it is on while
# Home is missing or changing, its RecoveryActivity takes over and deletes the saved Wi-Fi network.
WRAITH=com.google.android.tungsten.setupwraith
wraith=0

# Only re-enable what we disabled; anything already off at backup time stays off.
if [ -f "$APPLIED" ]; then
  while read -r pkg; do
    [ -n "$pkg" ] || continue
    if grep -qx "$pkg" "$BACKUP_DIR/disabled-baseline.txt"; then continue; fi
    if [ "$pkg" = "$WRAITH" ]; then wraith=1; continue; fi
    run pm enable --user 0 "$pkg"
  done < "$APPLIED"
fi

# Stop the launcher catching the remote's Settings key, so it reaches the Google TV panel again.
# First take back its permission to switch that on again. Fails harmlessly if it isn't installed.
run pm revoke io.github.samuelfonseca.tvhome android.permission.WRITE_SECURE_SETTINGS || true
orig_services=$(sed -n 's/^enabled_accessibility_services=//p' "$BACKUP_DIR/settings-secure.txt")
if [ -n "$orig_services" ] && [ "$orig_services" != null ]; then
  run settings put secure enabled_accessibility_services "$orig_services"
else
  run settings delete secure enabled_accessibility_services
fi
orig_a11y=$(sed -n 's/^accessibility_enabled=//p' "$BACKUP_DIR/settings-secure.txt")
run settings put secure accessibility_enabled "${orig_a11y:-0}"

orig_home=$(sed -n 's/^HOME=//p' "$BACKUP_DIR/device.txt")
[ -n "$orig_home" ] && run cmd package set-home-activity "$orig_home"

[ $REMOVE = 1 ] && run pm uninstall io.github.samuelfonseca.tvhome
# Let the original Home come up before setupwraith is back.
[ $DRY = 1 ] || { adb shell input keyevent KEYCODE_HOME; sleep 5; }
[ $wraith = 1 ] && run pm enable --user 0 "$WRAITH"
[ $DRY = 1 ] || echo "restored from $BACKUP_DIR"
