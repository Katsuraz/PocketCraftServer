# PocketCraft changelog

## v0.8.9 (versionCode 31) — Minimal design and menu audio

- Replaced menu click and tab-select audio with original crisp Minecraft-inspired PCM WAV cues.
- Preserved existing server start, ready, stop and error sounds and media-volume SoundPool playback.
- Replaced timber-textured panels, gradients and artwork with simple charcoal surfaces, sandstone highlights and streamlined navigation.
- Replaced the old detailed cabin icon with a bold vector Minecraft-style server block.
- Kept Mojangles import and all hosting, settings and update functionality.


## v0.8.8 (versionCode 30) — Audible sound fix

- Replaced short-lived AudioTrack synthesis on Android's easily muted UI-effects stream with SoundPool playing through **media volume**.
- Included six small, original mono PCM WAV sound files in the APK (menu click, tab select, server start, Java ready, stop, error).
- Preloads sounds on launch, queues the first sound while Android loads it, and preserves previous sound settings.
- Settings → Sound effects now displays media-volume and sample-load diagnostics, a test button, and a shortcut to Android sound settings.
- Removed silent playback failures and background sleep-based sequencing. No copyrighted Minecraft audio files are used.
- Kept the existing versionCode signing/update mechanism and all world/server data untouched.



## v0.8.7 (versionCode 29) — Mojangles-inspired UI and sounds

- Added user-importable Mojangles `.ttf` / `.otf` font using Android's document picker; imported fonts stay in private app files and are never redistributed in APKs.
- Bundled SIL OFL Monocraft for the default Minecraft-like font when no custom Mojangles font is imported, replacing the former Pixelify Sans/Press Start 2P look.
- Added original synthesized wooden click/tap and tab-change effects, with server start/Java-ready/stop/error cues tied to actual process state.
- Added sound mute switch, volume control, and preview buttons under Settings. No copyrighted Minecraft sounds are used.
- Replaced midnight-teal bottom navigation and blue console accents in the woodland theme with oak, amber and dark walnut.
- Replaced the neon cube app icon with an original warm lantern-and-workshop vector icon.
- Updated package metadata and bundled font license verification.

**Validation:** CI build success verifies APK creation, not real-device sound timing, font import from every file provider, or actual server/Geyser/Playit operation.


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
