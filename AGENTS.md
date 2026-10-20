# Agent instructions

Notes for coding agents (Claude Code, Codex, Copilot and the like) working in this repo. Read
[README.md](README.md) for what the project is and how a person sets it up; this file covers
what an agent needs on top of that.

## Layout

| Path | What it is |
|---|---|
| `launcher/src/` | The launcher app, plain Java (no Kotlin, no AndroidX, no libraries) |
| `launcher/test/` | JUnit tests for the Android-free classes and the repo's data files |
| `launcher/res/raw/` | `dock.txt` (default dock), `walls.txt` (wallpapers and categories) |
| `launcher/WALLPAPERS.md` | Credits and licences for every wallpaper |
| `launcher/*.sh` | `build.sh`, `test.sh`, `lint.sh`: the whole build system, no Gradle |
| `tools/` | adb scripts that change the TV; all source `tools/common.sh` |
| `config/debloat.txt` | Debloat list for the reference TV (TCL G13, Android 14) |
| `examples/` | Debloat lists for other models |
| `backups/`, `shots/`, `launcher/build/`, `launcher/keystore/` | Local only, gitignored. Never commit them |

## Checks

Run from the repo root, in this order. CI runs the same and must be green before a PR is
handed over.

```sh
launcher/build.sh   # build first: lint reads the compiled classes
launcher/test.sh    # JUnit tests on a plain JVM
launcher/lint.sh    # Android Lint; fails on any warning
shellcheck tools/*.sh launcher/*.sh
```

None of these need a TV. Things that trip agents up:

- `test.sh` compiles only the classes it names, without `android.jar`. A new Android-free class
  with tests must be added to its `javac` line, and if a tested class starts importing
  `android.*`, the tests stop compiling: move that code out instead.
- The app compiles with `javac --release 11` and `minSdk 26`: no Java 17 syntax in `src/`.
- Lint fails on warnings. Fix the cause; suppress (`tools:ignore`, `lint.xml`) only with a
  comment saying why.
- `RepoFilesTest` checks the data files: every wallpaper in `res/drawable-nodpi/` needs a line
  in `walls.txt` and a row in `WALLPAPERS.md`, and debloat lists must be valid package names
  that never include `launcherx` or `setupwraith`.

## Conventions

- Match the surrounding code: short classes, terse comments that say *why*, no new
  dependencies. Keep logic that doesn't need Android in its own class so it can be unit tested.
- For a user-visible launcher change, bump `versionCode` and `versionName` in
  `launcher/AndroidManifest.xml` and update the README's feature list if it changes.
- Shell scripts use `set -euo pipefail`, source `common.sh`, call `ensure_connected`, and call
  `require_backup` before changing anything on the TV.
- Work on a branch and open a PR. `main` needs an approving review and the maintainer merges:
  push, wait for green CI, then hand over the link. Never `gh pr merge --admin`.

## Working with the TV

Most changes can be built and tested without a TV. When you do need one, these rules come from
real incidents.

- **Ask before anything that changes Home or could drop the network** (`set-home.sh`,
  `restore.sh`, `disable.sh`, `adb install -r`, enabling or disabling packages). The person
  is often the only one who can recover the TV, with the remote.
- **`setupwraith` wipes Wi-Fi.** On TCL firmware, if `com.google.android.tungsten.setupwraith`
  is enabled while Home is missing or changing, its recovery screen takes over and deletes the
  saved Wi-Fi network. Disable it before `com.google.android.apps.tv.launcherx`, re-enable it
  last, and let the scripts do it rather than running `pm enable`/`pm disable-user` by hand.
  Before `adb install -r`, check it is disabled: `adb shell pm list packages -d | grep setupwraith`.
- **Every change goes through a backup.** Run `tools/backup.sh` first; `restore.sh` can only
  undo what `disable.sh` logged.
- **Remote buttons: capture, don't guess.** Keycodes vary by remote (the TCL gear button sends
  `KEYCODE_NOTIFICATION`, not `KEYCODE_SETTINGS`). Before fixing a button, ask the person to
  press it while `adb shell timeout 120 getevent -lt` runs, and code against what it shows.
  `adb shell input keyevent` bypasses accessibility services, so it can't test `SettingsKey`;
  inject through `adb shell uinput -` instead.
- **Performance:** the reference TV renders 1280x720 on a Mali-G31 and redraws the whole screen
  every frame. Avoid offscreen layers (`saveLayer`, alpha on overlapping views) and per-frame
  blurs. Measure before and after:
  ```sh
  adb shell dumpsys gfxinfo io.github.samuelfonseca.tvhome reset
  # drive the remote: adb shell input keyevent KEYCODE_DPAD_DOWN ...
  adb shell dumpsys gfxinfo io.github.samuelfonseca.tvhome framestats
  ```
- **Check what you changed:** `tools/shot.sh <name>` saves a screenshot to `shots/`; look at
  it rather than assuming a layout change worked.

### Connecting, and when developer mode is off

The scripts reach the TV through `ANDROID_SERIAL=<tv-ip>:5555`, or the one device `adb devices`
already lists. Start with:

```sh
adb connect "$ANDROID_SERIAL"
adb devices
```

| What you see | What it means | What to do |
|---|---|---|
| `<ip>:5555  device` | Connected | Carry on |
| `<ip>:5555  unauthorized` | The TV is showing an *Allow USB debugging?* prompt | Ask the person to accept it on the TV, ticking *Always allow from this computer* |
| `<ip>:5555  offline` | A stale connection | `adb disconnect "$ANDROID_SERIAL"`, then connect again |
| `failed to connect` / `Connection refused` | Debugging is off, or developer options are gone | Have the person enable developer mode (below) |
| `No route to host` / timeout | The TV is off, asleep, or its IP changed | Ask the person to wake the TV and check its IP |

Developer options can be missing even on a TV that worked before: a factory reset, some
firmware updates, and turning developer options off all hide them again, and debugging goes
with them. An agent can't turn them back on over adb, because adb is what's missing. **Stop
and ask the person** to do this with the remote, then retry the connection:

1. **Settings > System > About**, scroll to **Android TV OS build** and select it 7 times,
   until the TV says *You are now a developer*. (It may ask for the PIN.)
2. **Settings > System > Developer options**: turn on **USB debugging**. On some models
   there's a separate **Network debugging** or **Wireless debugging** switch: turn that on too.
3. Note the IP under **Settings > Network & Internet > (your network)**. It can change after
   a router restart, so check it matches `ANDROID_SERIAL`.
4. Run `adb connect "$ANDROID_SERIAL"` and accept the prompt on the TV.

Don't guess IPs, scan the network for the TV, or retry connecting in a loop: ask.

Turning developer options off only blocks adb; the launcher and the debloat stay. A factory
reset removes both, so after one the setup starts again from `tools/backup.sh`.
