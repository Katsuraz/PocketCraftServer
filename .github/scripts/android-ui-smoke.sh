#!/usr/bin/env bash
# Emulator-only, throwaway APK smoke test. Never use this APK as an update.
set -uo pipefail
mkdir -p ui-test-output/screenshots
set +e
gradle :app:connectedDebugAndroidTest -Ppocketcraft.uiSmokeOnly=true \
  --no-daemon --stacktrace 2>&1 | tee "$RUNNER_TEMP/pocketcraft-ui-gradle.log"
result=${PIPESTATUS[0]}
set -e
adb pull /sdcard/Download/pocketcraft-ui-smoke \
  ui-test-output/screenshots/ 2>&1 || true
adb exec-out screencap -p > ui-test-output/screenshots/last-screen.png || true
adb logcat -d -v time -s AndroidRuntime:E PocketCraftCrash:E PocketCraftUISmoke:I '*:S' \
  > ui-test-output/android-logcat.txt || true
cp "$RUNNER_TEMP/pocketcraft-ui-gradle.log" ui-test-output/instrumentation-gradle.log || true
if [ "$result" -ne 0 ]; then
  echo "::group::Emulator crash log and UI test diagnostics"
  tail -n 140 ui-test-output/android-logcat.txt || true
  echo "::endgroup::"
fi
exit "$result"
