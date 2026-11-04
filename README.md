# PocketCraft Server

An experimental, standalone **Android Minecraft server manager** with a custom midnight / block-world UI, implemented in native Kotlin. The APK does **not** require Termux to run the manager UI.

## Features implemented in v0.2
- A four-tab Dashboard, Mods, Worlds, Console experience with custom pixel-art and status cards.
- Server properties editor with numeric and whitelist validation; Java online authentication is kept enabled.
- Import an existing Fabric server launcher JAR, plus third-party mod JARs via Android's document picker.
- Enable / disable mod JAR files, inspect what's in the mods folder, remove a mod with confirmation.
- Import a Java world ZIP with path traversal checks and a 1 GiB extraction cap.
- View available worlds, mark an active world via `server.properties`, export a world ZIP using Android's document creation screen.
- Read and copy local console logs.
- GitHub Actions build uploads a debug APK automatically.

## Not implemented (important)
**This version CANNOT run Minecraft Java Edition or host a Bedrock crossplay game.**

Android ART cannot execute a regular Java SE Minecraft server JAR. The APK **does not currently bundle a compatible Android OpenJDK runtime / userspace Linux engine**, and server startup is intentionally not faked. External ports, Geyser/Floodgate, live player numbers, server commands and connection tunnels are not active.

The production engine needs:
1. A licensed and redistributable ARM64 Java 21/25 runtime that actually executes Minecraft Fabric on Android, likely in a PRoot/proot-distro-style environment or with native Android OpenJDK.
2. Robust foreground-service execution, proper process IO, notification controls, Android background limits, battery/power handling and safe JVM memory limits.
3. Fabric server setup/downloader, EULA acceptance, server version detection, compatible Geyser-Fabric and Floodgate installations; for older Minecraft versions, Geyser may need a separate proxy.
4. TCP and UDP reachability testing (including CGNAT mitigation and provider restrictions) before advertising public access.

This application is an honest **file manager + future server dashboard**. Do not claim it hosts a server yet.

## Build an APK
1. GitHub > Actions > **Build Android APK**.
2. Run the workflow from the default branch, or open a PR to test this development branch.
3. Download the `pocketcraft-debug-apk` artifact from a successful build.

Requires Android 8+ (API 26); intended for your realme 7 Pro. Java 17 in GitHub Actions compiles the Android app; this is **not** the Minecraft server runtime.

## Security and backup notes
Keep worlds backed up before deleting. Installed JARs are not executed by the app in v0.2. Only import files from trusted sources. The ZIP extraction code checks paths and expansion limits, but importing very large worlds may use substantial device storage.
