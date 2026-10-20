#!/usr/bin/env bash
# Unit tests on a plain JVM: the Android-free classes (Lists, Scroll, Spring, Grid, Blur, Palette) and the
# repo's data files.
# Downloads JUnit once into ~/.cache/tvhome. Needs JDK 17+.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"

JUNIT_VERSION=6.1.3
JUNIT_SHA256=e62b96ac475dbcde8599ea905d088f65d90778f86e259b856a49fa5c4ea256ec
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/tvhome"
JUNIT="$CACHE/junit-platform-console-standalone-$JUNIT_VERSION.jar"
if [ ! -f "$JUNIT" ]; then
  mkdir -p "$CACHE"
  curl -sSfL -o "$JUNIT.part" "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$JUNIT_VERSION/junit-platform-console-standalone-$JUNIT_VERSION.jar"
  echo "$JUNIT_SHA256  $JUNIT.part" | shasum -a 256 -c - >/dev/null
  mv "$JUNIT.part" "$JUNIT"
fi

# Only the classes under test, without android.jar: if one of them starts using Android, this fails.
OUT=build/test-classes
rm -rf "$OUT" && mkdir -p "$OUT"
SRC=src/io/github/samuelfonseca/tvhome
# shellcheck disable=SC2046  # test paths come from the repo and have no spaces
javac --release 17 -classpath "$JUNIT" -d "$OUT" "$SRC/Lists.java" "$SRC/Scroll.java" "$SRC/Spring.java" "$SRC/Grid.java" "$SRC/Blur.java" "$SRC/Palette.java" $(find test -name '*.java')
java -Drepo=.. -jar "$JUNIT" execute --class-path "$OUT" --scan-class-path \
  --disable-banner --details=tree --fail-if-no-tests
