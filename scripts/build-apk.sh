#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GRADLE_VERSION="8.13"
GRADLE_SHA256="20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
BOOTSTRAP_ROOT="$PROJECT_ROOT/.gradle-bootstrap"
GRADLE_ZIP="$BOOTSTRAP_ROOT/gradle-$GRADLE_VERSION-bin.zip"
GRADLE_HOME="$BOOTSTRAP_ROOT/gradle-$GRADLE_VERSION"

command -v java >/dev/null || { echo "Java 17 is required." >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required for the first build." >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip is required for the first build." >&2; exit 1; }

mkdir -p "$BOOTSTRAP_ROOT"
if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  if [[ ! -f "$GRADLE_ZIP" ]]; then
    curl --fail --location "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" --output "$GRADLE_ZIP"
  fi
  echo "$GRADLE_SHA256  $GRADLE_ZIP" | sha256sum --check --status || {
    rm -f "$GRADLE_ZIP"
    echo "Gradle integrity check failed." >&2
    exit 1
  }
  unzip -q -o "$GRADLE_ZIP" -d "$BOOTSTRAP_ROOT"
fi

cd "$PROJECT_ROOT"
if [[ ! -f gradle/wrapper/gradle-wrapper.jar ]]; then
  "$GRADLE_HOME/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin
fi
./gradlew assembleDebug
echo "APK created: $PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
