# PocketCraft changelog

## v0.8.29 (versionCode 51) — Keep the latest commands visible

- Command Activity now shows the newest 18 events even when the full in-memory transcript contains more lines. This prevents the Android TextView's line limit from hiding the latest issued commands after a busy session.
- Copy Command Activity continues to include the full bounded transcript.
- Add a regression test that sends 30 sequential commands and confirms the latest five remain visible in the compact preview.
- Retain v0.8.28's no-auto-help fix, simplified Homestead Console, searchable command catalog and full log tools, with no changes to server data or networking.


## v0.8.28 (versionCode 50) — Quiet command panel, no automatic help spam

- Remove all automatic `help` and `help N` commands on server readiness or help-pagination detection. Modern Minecraft prints large command lists as System chat, which hid real commands in the live Console.
- Add a clean, compact Homestead Console: input at the top, Send button, four quick command actions, and a dedicated recent command activity transcript. Transcript shows queued commands, stdin delivery/failure and nearby Minecraft system-chat responses without promising that an individual game command executed successfully.
- Keep full logs, searchable command catalog, raw log copying, and optional `/help` discovery behind one expandable Full Logs & Tools panel.
- Default full logs to CLEAN so verbose `System chat: /command` and Chunky help listings are hidden unless the operator selects ALL.
- Recognize `System chat: /command` when explicitly scanning server /help, including Fabric/Geyser/Chunky commands, without automatically scanning additional pages.
- Avoid decoding/relaying full log output while the compact command panel is displayed. Preserve async bounded log reading for optional Full Logs.
- Add unit regression tests for command transcript, help suppression, capture time bounds, and modern command discovery.
- Preserve worlds, modpacks, datapacks, world snapshots, Geyser, Playit, Homestead visuals, existing signing, and the console stdin command delivery fix.


## v0.8.27 (versionCode 49) — Reliable Console command delivery

- Fix the command field and Send button becoming permanently disabled when Console is opened while Minecraft is starting. Command entry remains editable; submission checks the live server state.
- Route Android keyboard Send and hardware Enter through the same validated command path. Accept in-game commands with a leading slash and display errors for empty, multiline, or overlong input.
- Add a persistent Console delivery status independent of log filters, distinguishing queued, delivered-to-stdin, and failed commands without falsely claiming the Minecraft server executed them.
- Send Minecraft stdin commands through a dedicated serial queue so rapid commands preserve order; report process/stdin failures rather than silently clearing the field.
- Separate the log file's synchronization from command stdin to avoid log floods and large file rotation delaying commands.
- Keep all Homestead styling, saved worlds, snapshots, Geyser/Bedrock, Playit, mod handling, server configuration, and existing button layouts.
- Android unit tests check command normalization and invalid input. Live JNI Minecraft stdin handling still requires on-device confirmation.


## v0.8.26 (versionCode 48) — Homestead professional server management

- Keep the existing Homestead wood-and-parchment UI, simplify the Home page to one main start/stop button and a small management menu; move repetitive setup buttons into collapsible Advanced & Setup.
- Add a world manager: create and assign a new world, select any existing world, rename, duplicate, export and safely delete inactive worlds, with strict stopped-server checks. New worlds generate on first server start.
- Add offline local ZIP snapshots for generated worlds (maximum five per world, size and file-count limits) and restore snapshots into a new world without overwriting existing worlds. Encourage exporting ZIP backups off the phone.
- Add a dedicated player management dialog with observed online names, refresh player list, whitelist list, confirmed kick and trusted permission commands through actual server stdin.
- Add a phone-safe status summary: local available RAM, battery level, Android thermal status, observed players and server uptime. Do not claim unavailable JVM TPS/heap data.
- Improve Console usability with log searching, error/player filters, and an in-place refresh that does not redraw the page. Keep the existing bounded asynchronous log tail.
- Preserve Java/Fabric, Geyser + Floodgate crossplay, Playit, datapacks, modpack installers, signing, sound effects, and existing server data.
- Android build and unit tests validate compilation; real-device hosting, memory pressure and joining must still be tested on a phone.


## v0.8.25 (versionCode 47) — Detect Bedrock joins from Minecraft's normal logs

- Detect successful Floodgate-prefixed Bedrock player logins in Minecraft's ordinary server log, not only Geyser-tagged startup messages; reject generic Java joins, fake chat messages and failed logins.
- Respect Floodgate's configured username prefix; when it is empty, do not infer Bedrock from Java usernames.
- Recognize an additional successful Geyser UDP startup message format.
- Add a visible, live Bedrock status panel to Home and one-tap confirmation for players who already connected successfully; confirmations apply to the current server session only.
- Add regression tests without changing server networking, Geyser configuration or saved worlds.
- Preserve existing v0.8.24 improvements, console commands, datapacks, Homestead UI, and update signing.


## v0.8.22 (versionCode 44) — Reliable Bedrock and Geyser phone-hosting status

- Recognize Geyser's modern NetherNet built-in HTTP/HTTPS signaling startup messages, external signaling registration, both official RakNet startup formats, and Geyser-tagged successful Bedrock player joins. Never equate a Geyser mod JAR with a working listener.
- Keep NetherNet built-in and external signaling distinct in visible diagnostics and correctly clear stale WebRTC failure flags when a working NetherNet startup is later reported.
- Read the Bedrock and WebRTC ports, and RakNet/NetherNet transport type, from the installed Geyser-Fabric config instead of hard-coding 19132. Respect different ports in LAN copy buttons, hosting details, and direct port-forwarding instructions.
- Let players copy a BEDROCK TEST ADDRESS even when a startup log is missed; do not falsely call the listener offline. Offer a manually acknowledged successful Bedrock join that applies only to the current server session.
- Avoid blocking working RakNet LAN when a separate NetherNet native error exists. For internet hosting, only require NetherNet signaling if the configured transport explicitly uses NetherNet.
- Add unit regression tests for real Geyser message variants, invalid messages, custom ports, local testing and native-error behavior.
- Preserve the Homestead UI, Java/Fabric servers, Geyser settings, Playit agents, console, server worlds, datapacks, mod downloads, and app signing.


## v0.8.21 (versionCode 43) — Crash diagnostics and independent server startup

- Start Server no longer automatically opens Console; the Java foreground-service startup can proceed independently of console UI rendering.
- Added a local-only uncaught Java/Kotlin exception recorder, saving the most recent crash report to private app storage for user-initiated review and copying.
- Added Home → Crash diagnostics, and an alert link when a previous crash report is available; no logs are uploaded by the app.
- Added a fallback screen when Console initialization throws a managed exception, with a link to the saved report.
- Simplified live Console log rendering to a bounded, nonselectable TextView within the main page's scroll area instead of nested ScrollViews; live command suggestions appear only when text is entered.
- Preserved existing Homestead styling, server files, worlds, datapacks, Fabric mods, Geyser configuration, app signing, and sounds.


## v0.8.20 (versionCode 42) — Console freeze fix

- Console now opens immediately with cached output and loads its logfile tail on a dedicated background worker rather than blocking the Android UI thread.
- Replaced focus-scrolling over selectable nested log text with focus-free scroll positioning to avoid Android text selection/scroll focus stalls.
- Limited live console rendering to a bounded logfile tail; coalesced reads and ignored outdated results after switching tabs.
- Kept command input, autocomplete, command discovery, copy-log and refresh actions intact.
- Preserved original Homestead UI, datapack management, Geyser, RAM slider, icon and six existing sound effects.


## v0.8.19 (versionCode 41) — Datapack installer

- Added a Datapacks manager accessible from Worlds and Mods. It shows installed ZIPs for the active world, supports safe removal, and opens the datapack folder in the file browser.
- Import a datapack ZIP using Android's file picker; PocketCraft validates `pack.mcmeta` at the ZIP root, checks entry paths and size limits, and atomically copies the ZIP into `<active world>/datapacks/` without overwriting existing packs.
- Search Modrinth for datapacks matching the selected Minecraft version and install the appropriate ZIP automatically with SHA-512 verification, safe HTTPS redirects, and 404/410 fallback across compatible versions.
- Installs require a generated active world and a stopped Minecraft server. No Java/Fabric mods, Geyser settings, worlds, or server core files are modified.
- Preserves the original Homestead GUI, PS monogram icon, RAM slider, and built-in update signing.


## v0.8.12 (versionCode 34) — Mod compatibility checks & broken download fallback

- Mod browser shows the published Fabric version plus an initial server-support hint. Tap "Check compatibility" for exact release metadata, server-only/client-only support, and declared required dependencies or incompatibilities before installing.
- Check labels distinguish compatible published metadata, incompatible releases, and uncertain server support. They cannot guarantee mods work together or run well on Android.
- Resolve required dependencies and retry alternate Fabric-compatible versions/JAR artifacts when Modrinth returns HTTP 404/410 for a stale file, while retaining SHA-512 checks and server-side Fabric JAR validation.
- Preserve original Homestead GUI, PS launcher icon, RAM slider, hosting files, and all six sounds.


## v0.8.11 (versionCode 33) — Original Homestead and RAM control

- Restored the exact original v0.8.8 Homestead GUI design system, including timber buttons/cards, woodland colors, gradients and woodland artwork. The compact v0.8.10 Home screen remains.
- Replaced the Java maximum heap textbox with a 512–3072 MB slider (128 MB steps), saving to the existing heapMB preference when users tap Save. The real server launcher still reads that same value on its next launch.
- Replaced the launcher and header cube with a clean pixel-style PS monogram (PocketCraft Server), including adaptive color, round and monochrome icon layers without baked white.
- Preserved six existing audio effects, Mojangles import, Android package/signing, worlds, mods, settings, and update workflow.


## v0.8.10 (versionCode 32) — Homestead, simplified

- Restored the warm oak-and-parchment Homestead palette while preserving clean, subtle cards.
- Redesigned Home around one compact status card and single-line actions without descriptive paragraphs.
- Moved hosting, connection, setup and crossplay diagnostics into a collapsible section, all still accessible.
- Removed the "Fix update installation" shortcut from Home.
- Created proper adaptive and round launcher icons with a dark background and a clean voxel server mark; no baked white circle.
- Shortened the app label to PocketCraft and retained package ID, update signing workflow, worlds, server files and all six existing sounds.


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
