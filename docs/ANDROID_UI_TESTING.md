# PocketCraft Android UI emulator tests

This workflow installs and **launches the real Kotlin Android app** in a disposable Android 14 (API 34) x86_64 emulator. It does not emulate a working ARM64 Fabric/Playit server, and it never claims that Bedrock can connect.

## What gets tested

- Home, Worlds, Mods and Console navigation.
- Modrinth Workshop Mods/Modpacks navigation, including the search and selected-version dialog when Modrinth responds over the network.
- Settings and built-in server file browser.
- Screenshot creation and accessibility-label-based UI assertions.
- Java/Kotlin app crashes visible in AndroidRuntime logs.

The Modrinth search/version screenshot is best-effort because the live API can be down or rate-limited; core navigation tests fail when a screen is missing or crashes.

## Where to find screenshots

1. Open GitHub Actions, then Build Android APK, then select a run.
2. Choose the Android emulator UI smoke and screenshots job.
3. Under Artifacts, download pocketcraft-emulator-ui-report.
4. Inspect ui-test-output/screenshots/PocketCraftUISmoke/*.png, the last-screen.png screenshot, android-logcat.txt and the instrumented test reports.

If an emulator test fails, available logs and screenshots are still uploaded.

## Why the emulator build does not include the server JNI

Production PocketCraft packages ARM64 JNI runtime libraries. GitHub-hosted accelerated emulators use x86_64, so CI builds a disposable UI-only debug APK using the Gradle property pocketcraft.uiSmokeOnly=true.

This excludes ARM64 JNI only in the emulator test APK. The production build does not set this property and continues to verify ARM64 Java, WebRTC and Playit binaries, signing and update metadata. Do not distribute the emulator APK.

## Release gate

The official GitHub release job needs both the build job and the ui-smoke job to succeed. An emulator failure blocks new releases.

## Limitations

This test does not prove physical-phone performance, Minecraft hosting, Playit/NetherNet Bedrock joins, or compatibility with previous APK signers. Screenshots are captured for review, but automated visual-diff and text-overflow assertions still require baseline development.
