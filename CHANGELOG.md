# PocketCraft changelog

## v0.8.6 (versionCode 28) — Woodland workshop

- Homestead Woodland style now defaults to original carved timber panels with moss and parchment colors.
- Minecraft-inspired Pixelify Sans and Press Start 2P fonts, bundled for offline use, with a persistent Classic Android fallback.
- Settings → App Updates checks official GitHub Releases for a stable update.
- APK updates require a release manifest, exact SHA-256 match, correct Android app ID, a newer versionCode, and matching signing certificates before Android's installer opens.
- Optional daily update checks never download or install an APK by themselves.
- GitHub Releases publishing workflow supports a verified APK plus checksum manifest.
- Minecraft server worlds, settings, Playit tunnels, Geyser configuration, and server files are not migrated or modified by the updater.

### Notes

- A successful build does not prove that Fabric, Geyser/NetherNet, and Playit work on every Android device.
- **No GitHub Release currently exists.** The in-app updater will show that status until an appropriately tagged, persistently signed release is published.
- APKs signed with a different certificate cannot install over one another. Export server data before a one-time migration; never uninstall before backing up.
