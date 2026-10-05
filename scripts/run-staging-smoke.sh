#!/usr/bin/env bash
set -euo pipefail

adb install -r staging-apks/debug/app-debug.apk
adb install -r staging-apks/androidTest/debug/app-debug-androidTest.apk

result="$(adb shell am instrument -w \
  -e class tv.mars.app.ui.HomeClockTest,tv.mars.app.ui.RemoteSelectionTest,tv.mars.app.ui.LibraryScreenTest \
  tv.mars.app.test/androidx.test.runner.AndroidJUnitRunner)"

mkdir -p artifacts
printf '%s\n' "$result" | tee artifacts/instrumentation.txt
grep -Eq '^OK \([0-9]+ tests?\)$' <<< "$result"
