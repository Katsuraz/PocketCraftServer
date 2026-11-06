# PocketCraft changelog


## v0.8.46 (versionCode 71) — All-Modrinth-Mod Dependency Version Selection

- Every Modrinth Fabric mod's required and optional dependencies now offer the same **Choose Version** action. The choices are the author's exact pinned release, an auto-selected compatible Fabric release that bypasses an unavailable pin, or a specific SHA-512-verified Modrinth Fabric release.
- Version choices are keyed to the immutable dependency project ID, not to Terralith, Lithostitched or any hardcoded mod. The installer applies these choices consistently wherever that project appears in a nested dependency graph; the originally selected parent mod remains pinned.
- Selecting a dependency release for another Minecraft version or a client-only build requires a separate warning and explicit confirmation. Other-loader releases remain in Download Only; only selected Fabric JARs can be installed on a Fabric server.
- The existing **Recover unavailable pinned dependencies (HTTP 404/410)** checkbox remains for user-authorized fallback when a dependency is left on its default pinned version. Choosing Auto-compatible bypasses the bad pin up front, without depending on an HTTP 404.
- Required dependencies cannot be unchecked. Optional dependencies remain opt-in, previously installed mods are preserved, dependency cycles and recursion limits are checked, SHA-512 and Fabric JAR verification remain enforced, and an overall installation failure attempts to remove newly introduced JARs.
- Added JVM coverage for generic per-project selection (multiple independent dependencies and conflict protection) and Android 14 emulator UI testing with an arbitrary, non-Terralith mod. No user worlds or mod files are changed by the update itself.

### Limitations
- A Fabric release targeting another Minecraft version may still break server startup. Choosing a version is not a compatibility guarantee; back up mods and worlds before installing.
- Dependencies already installed are kept as-is by the existing installer and are not silently replaced. For a different installed version, review/remove it safely first.
- Real-device end-to-end Modrinth CDN downloads and live Fabric server startup still require physical-phone validation.


## v0.8.45 (versionCode 70) — Stable: Pinned Dependency 404 Recovery

- Fix the real **Terralith 2.6.5+26.3 → Lithostitched** installation failure: a required Modrinth dependency can specify a pinned version whose CDN asset or version metadata returns HTTP 404/410. Previously this stopped installation even if the same dependency project published a working Fabric 26.3 version.
- Added an **explicit, reversible recovery checkbox** in the dependency-review dialog, enabled by default when a pin exists. First attempt the exact pinned release. Only when the pin is genuinely unavailable (404/410), and the option stays checked, try a different compatible Fabric release for the **same Modrinth project**. Skip the exact known-broken release ID.
- The **parent mod remains pinned** to the release the user selected. Compatibility fallback never changes the parent or arbitrarily chooses a different project or loader. Automatically resolved dependencies continue to require the current Fabric server Minecraft version.
- **No fallback** for SHA-512 mismatch, corrupt JAR, 500/network errors, cancellation, missing compatible releases or nested dependency errors. The installer still verifies downloads, checks Fabric metadata, stops when the server is running, never overwrites installed JARs and rolls back newly introduced JARs if the overall installation fails.
- Installation progress explains when a missing pinned asset is replaced by a compatible release, and the success message identifies recovered versions. Users may uncheck the recovery option to enforce exact dependency pins.
- Added pure JVM tests for pinned Lithostitched HTTP 404/410 recovery, disallowed error types, nested dependency failures, user opt-out and fallback failures. Added a deterministic Android emulator test that opens the Terralith/Lithostitched dependency dialog, captures screenshots and verifies the opt-in checkbox is changeable.
- Preserved the app package, signing identity, user worlds, Playit, Geyser/Floodgate, server runtime, Minecraft installations and the existing Modrinth browse-all behavior.

### Limitations and testing
- A compatible Fabric/Minecraft version is not guaranteed to satisfy every mod's *semantic version requirement*. Pinned-release recovery is a user-approved last resort, not a claim of compatibility.
- An HTTP 404 from all available verifiable sources can still prevent installation; the app explains what failed and points to the required Modrinth project.
- Physical-phone game startup, Bedrock joins and the full v0.9.0 roadmap remain unverified. Official v0.8.45 is published only after signed production Android build, JVM tests and Android emulator smoke tests pass.


## v0.8.44 (versionCode 69) — Stable: Required Dependency Recovery & Compact Release Dialogs

- **Fix for Terralith/Lithostitched HTTP 404 dependencies:** When the CDN URL for an exact required Fabric mod JAR returns HTTP 404/410, PocketCraft now tries Modrinth's **official documented Maven repository** for the same project/version before attempting other compatible releases (for unpinned dependencies). Maven downloads are accepted only after matching the original Modrinth SHA-512 and passing Fabric JAR checks. The user is never silently given an unverified file or an incompatible loader.
- Use immutable project/version IDs first, with the documented slug+version-number coordinate as another source. Validate all Maven path tokens and refuse traversal, unsupported schemes, and untrusted hosts.
- Improved error reporting when every verified source is unavailable. Dependency installation still rolls back newly added JARs on failure and never overwrites existing mods.
- When a required dependency fails, offer a **VIEW REQUIRED MOD** link so the user can retrieve and import a proper matching Fabric dependency manually.
- Show **automatic compatible release** versus **pinned version** status alongside required dependencies.
- Replaced the stretched default three-button **Release options** popup with a bounded Homestead-themed dialog using full-width install, download-only and cancel buttons, with scrollable warning text.
- Added unit tests for safe Modrinth Maven source generation and an Android emulator screenshot/navigation regression for both normal and incompatible release options.
- Run a non-blocking GitHub CI diagnostic request to test the public Maven artifact endpoint without blocking offline or outage-safe Android builds.

### Limitations and safety

- A publicly listed mod is not proof that its current download URL or Maven mirror is reachable from every location. Actual physical-device download/install success still needs on-device testing.
- If an explicitly pinned dependency release is unavailable, PocketCraft does not silently replace it with another version. Unpinned dependencies can try alternative compatible versions.
- Server version, signing certificate, app ID, Minecraft worlds, Playit, Geyser and current install paths are unchanged. The full v0.9.0 roadmap remains open.


## v0.8.43 (versionCode 68) — Stable: Browse Every Modrinth Version

- **Removed restrictive Modrinth search filters** for the selected Minecraft release, Fabric loader and server environment. The Workshop now shows all mods and modpacks of the selected project type; category, search and sorting remain available. Client-only projects are labeled rather than hidden.
- **Choose any available published release**, including older Minecraft versions and client-only/other-loader files. The picker displays each release's Minecraft versions, loaders, channel and compatibility warnings. Extensive catalogs are paginated visually in batches for performance, without discarding older versions. Version search includes loader and supported Minecraft versions.
- **Risk confirmation before installing mismatched Fabric releases**: users can explicitly proceed with the selected version even when it does not match their Fabric server's Minecraft version. Required dependencies retain their own compatibility checks, file checksums are validated, and an incompatible mod can crash Minecraft.
- **Download Only**: save a SHA-512 verified original JAR or MRPACK through the Android file picker without altering installed mods or worlds. Files for other loaders remain downloadable but cannot be installed directly into a Fabric server.
- Preserved server-stopped checks for installation, existing-file protection, safe JAR checks, trusted HTTPS hosts, file size limits, and modpack protections.
- Expanded JVM tests and Android emulator UI smoke tests for broad discovery, compatibility warnings and selectable older versions. Current app ID, signing identity, Playit, Geyser/Floodgate and worlds are unchanged.

### Known constraints

- A compatibility warning does not imply a mod will run. Incompatible game versions or dependencies may prevent server startup. Back up server files before applying an override.
- Mods for Forge/NeoForge/Quilt and client-only rendering features cannot function as server-side Fabric mods, although their verified files can be saved separately.
- The broader v0.9.0 Ultimate Hosting roadmap and device-level crossplay validation remain open.


## v0.8.42 (versionCode 67) — Stable: Modrinth Server Compatibility Fix

- Fixed Modrinth Workshop listing **client-only Minecraft mods** as server-installable search results. The catalog now combines Fabric loader and Minecraft version filters with the official Modrinth v2 **server-capable environment** facets, including valid server-optional and server-only variants. A second check rejects clearly client-only results if the search index returns them anyway.
- As an example, **Iris Shaders is a Java client-side shader loader**, not something to install into the PocketCraft dedicated Fabric server. It should no longer appear as a server-install option.
- Improved version selection failure reporting: distinguishes no matching Fabric Minecraft release, client-only releases, and releases without valid server downloads. Provides a direct link to the project's Modrinth page for comparison.
- Only offers release files with trusted HTTPS Modrinth CDN URLs and valid SHA-512 metadata; never installs a client-only release on the server. Existing mod folders and automatic dependency review behavior remain unchanged.
- Added JVM regression coverage for live API facet syntax, Fabric 26.3 server/loader filtering, the client-only environment behavior, safe download URLs and valid checksum metadata.
- Preserves the new Homestead version picker, existing Android package and signing identity, Playit, NetherNet, Geyser/Floodgate, all server worlds, backups and installed files. Android unit tests, APK signing checks and emulator UI smoke tests must pass before public release.

### Scope and remaining verification
- A project may list Fabric and Minecraft 26.3 globally without publishing a downloadable Fabric **server** build for that exact version. Such projects remain incompatible.
- Shader/rendering mods such as Iris can be installed in a supported Java client, not in PocketCraft's Android phone server.
- Real-device hosting, Bedrock remote connection, mod interoperability and the broader v0.9.0 roadmap still require separate verification.


## v0.8.41 (versionCode 66) — Stable: Homestead UI & File Browser Polish

- Replaced Modrinth's giant default-Android **version popup** with a bounded, scrollable, Homestead-styled release picker. Compact cards show exact version numbers, release/beta/alpha status, publication date and downloads. Zero-dependency lines are omitted, while nonzero required/optional/incompatible counts remain visible.
- Added version search, release-channel filters, oldest/newest ordering, accessibility descriptions and deliberate release selection before installation. The underlying Modrinth API and permission-safe installation behavior remain intact.
- Styled remaining native Android alert dialogs with a consistent dark woodland palette and readable text, avoiding the large flat gray Android default look.
- Added persistent **Files sorting** by name, file type, size and modification date, with folders first. Search status is shown separately so the buttons don't crowd small screens. No server files are modified simply by sorting.
- Added offline version picker filtering tests, file sorting tests and a **deterministic Android 14 emulator UI test** for the release picker (including screenshots of the unfiltered, beta-only and sorted states). CI still blocks GitHub release publication when build or UI smoke checks fail.
- Preserved application ID, signing configuration, Minecraft data paths, worlds, mod configurations, Java engine, Playit, Geyser, Floodgate and all other existing server features.

### Verification boundaries
- CI builds, UI automation and screenshots are necessary but not a substitute for real-device visual review, Android OEM compatibility, live Minecraft hosting or Bedrock join testing.
- The v0.9.0 Ultimate Hosting roadmap remains unfinished; v0.8.41 is a scoped, non-beta stable maintenance update.


## v0.8.40 (versionCode 65) — Official stable: Modrinth Workshop

A **non-beta, incremental stable release** focused on the existing Fabric mod/modpack manager.
This release does not claim the remaining v0.9.0 Ultimate Hosting roadmap is finished.

- New full-screen **Modrinth Workshop** for Fabric server mods and modpacks with searchable results, 20-at-a-time pagination, search categories, and official relevance/downloads/updated/newest/follows sorting.
- Select **any listed compatible Fabric release** for the installed Minecraft version, rather than silently installing only the newest one. See exact version numbers, release channel, dates, and dependency counts.
- Inspect the **chosen mod release's** required/optional/incompatible dependencies, distinguish installed mods, choose optional dependencies, and prevent unchecking required entries. Existing conflicting mods are called out before installation.
- Install selected versions through the existing allowlisted Modrinth installer with file type checks and SHA-512 verification, preserving installed JARs. Attempt to roll back newly introduced JARs if a dependency installation fails.
- For a selected Modrinth modpack version, verify the downloaded .mrpack SHA-512 first, review the archive's optional server files, and choose which optional files to include. Required server files remain mandatory. Existing server files are not overwritten.
- Preserve the app package, signing identity, themes, worlds, server settings, Fabric runtime, installed mods, Geyser/Floodgate, Playit and update channels. New activity is not externally exported.

### Honest constraints and compatibility
- Only server-oriented **Fabric** mods and Fabric .mrpack modpacks matching the app's selected Minecraft version are supported. Modrinth's catalog is not a promise that two mods work together or that a server-side mod is crossplay-compatible with Geyser.
- Already installed mods are not silently replaced or upgraded; disable/remove conflicting versions only after backups. Complex version constraints, nested/bundled dependencies, custom modpack scripts, and mod interoperability still require manual checks.
- Real Android installation, preservation across signing-key changes, Minecraft launches, Bedrock live joins, and performance on specific phones require device testing. Automated CI and signing checks do not establish those facts.
- The full v0.9.0 hosting overhaul remains an open roadmap. This release intentionally uses an incremental stable version number rather than calling an incomplete overhaul complete.


## v0.9.0-beta.3 (versionCode 64) — Console Shortcuts & Accurate Player Lists

**Incremental official beta, not a completed v0.9.0 stable release.**

- Console quick commands: edit labels and commands for all four shortcut slots, save preferences locally, and reset to defaults. Changes never execute commands by themselves. Custom actions show a confirmation before sending anything to the live Minecraft server.
- Correct player tracking for Minecraft's modern "System chat: There are N ... players online" /list responses, including Floodgate-prefixed names, an empty roster, and advertised max player count.
- Ignore misleading player-chat text and reject malformed, contradictory, or duplicate-name status lists instead of overwriting known player data.
- Add command validation and roster regression tests; preserve all existing logs, worlds, mods, networking, settings, signing identity, themes, and update channels.

### Beta limitations
- Physical Android testing, real Java/Bedrock joins, Playit/NetherNet validation, and final UI QA remain outstanding.
- Custom server commands may be dangerous; check each command before confirming. Fabric mod command discovery remains limited to commands exposed in server /help.
- Only the most recent observed server roster is shown, not a guaranteed real-time player session API. No automatic scheduling or full JVM TPS measurement is claimed.


## v0.9.0-beta.2 (versionCode 63) — Reliability, Backup Safety & Management QoL

**Official public beta; stable users remain on v0.8.39 unless they opt in.**

- **Verify ZIP**: added read-only integrity checking for uploaded server backup archives before restoration, with CRC, path traversal, duplicate entry, and expanded-size limits.
- **Safer restores**: preflight backups before touching server data, restore through staging, reject collisions with existing files, and remove partially restored files if an operation fails. Stop/starting/stopping guard prevents unsafe backup operations during server lifecycle changes.
- **Mod dependency review**: inspect Fabric mod IDs, required dependencies, and provided aliases from installed JAR metadata, then flag possible missing or duplicate IDs in a compact on-demand review. These are advisory, not compatibility guarantees, and never modify installed mods automatically.
- **Safer console sharing**: add an optional redacted log-copy action that masks recognizable NXS tokens and labeled credentials. The original raw-log option remains available with its security warning; always review shared logs.
- **Device storage monitoring**: display actual Android app-storage free capacity and a low-storage warning alongside phone RAM, battery, thermal status and uptime.
- Add unit tests covering archive validation, restore rollback/collisions, mod dependency advisories and log redaction.
- Preserve theme selection, Java/Fabric hosting, Playit agent, Geyser/Floodgate, user worlds, installed mods, signatures and beta/stable update channels.

### Beta limitations

- On-device, real-play, and live networking verification remains outstanding; CI compilation cannot certify a physical Bedrock join.
- Complete mod dependency **version constraint resolution**, automatic scheduling, unrestricted large backups, full server profiler, and the remaining v0.9.0 redesign roadmap are not implemented in this beta.
- ZIP verification checks integrity and bounded structure, not whether each mod or Minecraft world will run successfully.
- Redaction is best effort; never share logs without reviewing the output first.


## v0.9.0-beta.1 (versionCode 62) — Official public beta: Appearance Studio and update channels

**Experimental prerelease:** Core Minecraft hosting is preserved; visual and updater changes require physical-device beta testing.

### Implemented
- Ten built-in UI color palettes: Homestead, Midnight, Obsidian, Aurora, Nether, Ender, Oceanic, Classic Minecraft, Material You and Minimal. Existing Homestead and Midnight preferences migrate without loss.
- Centralized palette tokens across existing UI components, with original Homestead timber look preserved and compact density support.
- New **Appearance Studio**: scrollable theme gallery, theme-specific screen previews, apply, custom RGB accent (contrast checked), density choices, and reset. UI changes persist without writing Minecraft files.
- Explicit opt-in **Beta / Stable** release channel selector with a clear downgrade warning. Stable continues to use GitHub's latest stable release; Beta checks eligible GitHub prereleases and stable releases.
- Signed APK verification still checks SHA-256, versionCode, package identity and signing certificate; beta releases use matching channel metadata and are marked **prerelease** on GitHub.
- Retained customizable Hosting & Tools Minimal/Cards/Detailed layouts, server-world/mod storage, runtime, Geyser, Floodgate and Playit integrations from v0.8.39.
- Added theme and channel-policy unit tests.

### Beta limitations / not yet implemented
- Not a completed overhaul of the console, mod manager, backup engine, crossplay runtime or server-performance profiler. These remain in the v0.9.0 roadmap.
- On-device visual QA across all 10 themes and a real remote Bedrock join are still required; CI compilation is not proof of either.
- No arbitrary JSON theme import/export, custom icon packs, or unverified performance gains in this beta.
- Dynamic Material You color extraction requires Android 12 or later; older versions use a tested static palette.
- If running a newer beta, Android will not downgrade to an older stable build. Back up your worlds before joining Beta.


## v0.8.39 (versionCode 61) — Customizable Hosting & Tools layouts

- Rework **Home → Hosting & Tools** to end the long wall of stacked explanatory panels.
- Add three selectable views: **Minimal** (short single-line tool rows, default), **Cards** (two-column shortcut tiles), and **Detailed** (original full hosting panels and all diagnostics).
- Provide **Customize → Show / Hide Tools** with independently toggleable Friends, Local, Playit, Server, Tools and Health shortcuts. Save layout and visibility in app preferences, and provide an easy reset to defaults.
- Keep every original hosting action, Java / Bedrock connection chooser, local-server panel, Playit panel, engine controls, setup utilities, health checks and troubleshooting available on demand; existing Detailed view retains the original live-refresh panels.
- All feature changes are UI-only and additive. Keep Java runtime, Geyser, Playit, server data, world and mod files, signing identity and update pipeline unchanged. Include regression tests for layout defaults and visibility behavior.
- Three editable design studies added to the existing PocketCraft Figma Homestead file.
- **Validation:** Android CI build and unit tests must pass. Physical phone UI and Bedrock connectivity still require an on-device smoke test.


## v0.8.38 (versionCode 60) — Minimal Homestead interface overhaul

- Rebuild the Geyser/Bedrock settings flow around **Connect**, **Advanced** and **Files**. The primary screen shows only current configuration state and Playit import; all detailed ports, transport, signaling, STUN, NXS and credentials remain accessible in Advanced.
- Replace permanently visible field descriptions with accessible **ⓘ** help dialogs across crossplay, global Settings and world preferences. Preserve full setting labels, validation and save behavior.
- Simplify shared Homestead navigation: clean title-only menu rows, shorter page headers, compact sections with optional help, consistent button sizing and reduced card padding while keeping touch targets accessible.
- Remove repetitive Home, Mods, world, file browser and Playit instructions; show diagnostics and setup guidance on demand.
- Group secondary Playit controls (agent switching, diagnostics, setup help) under an expandable **More options** control. Retain the correct NetherNet public Bedrock joining address behavior from v0.8.37.
- Keep signed in-place upgrades, server worlds, mods, runtime, Playit credentials, Geyser configuration, and core server operations unchanged.
- **Verification:** CI compilation and unit tests required; real Android UI/Bedrock connection tests still require a device. No changes to Minecraft server networking, Java runtime or world formats.


## v0.8.37 (versionCode 59) — NetherNet reliability and safer Playit setup

- Fix Bedrock NetherNet public join address display: normalize a Playit `nethernet` tunnel's Bedrock client hostname to port 19132 instead of showing its reported UDP allocation port, without rewriting Java or legacy Bedrock tunnel addresses.
- Preserve the unmodified Playit-reported endpoint as an explicitly labeled diagnostic when it differs from the Bedrock join address.
- Restore a guided **Paste Playit Geyser Settings** action to Geyser Settings → Files. Safely merge supported `bedrock` and `java.auth-type` values from Playit's Configuration Help into the existing Geyser config.
- Preserve unrelated YAML, existing NXS credentials when omitted from the pasted snippet, world and server data. Normalize legacy misplaced advertised UDP addresses into Geyser's signaling section.
- Block edits while the server is starting, running or stopping; use an atomic write and a private backup before replacement.
- Add address-regression and Geyser-import tests, including duplicate-field rejection and no-secret error handling.
- **Limitations:** Tunnel assignment alone does not establish successful public Bedrock connectivity. Verify Android WebRTC JNI, Playit agent startup and a real Bedrock join on hardware before claiming full crossplay success.


## v0.8.36 (versionCode 58) — Homestead 2 UI overhaul

- Completely rebuild Home as a woodland server launchpad with compact server status, cabin artwork, quick-entry tiles and a dedicated advanced hosting panel. Existing Java, Playit, and Bedrock services remain unchanged.
- Replace the old main navigation chrome with a consistent Homestead header and touch-friendly Home / Worlds / Mods / Console bottom dock. Preserve existing internal tab IDs and server controls.
- Rework Worlds into folder-style rows and dedicated per-world management actions. Split individual world preferences into Gameplay, Performance and Generation panels with a persistent Save action.
- Replace Mods' crowded layout with a workshop, library cards, and a single Geyser configuration entry.
- Categorize global Settings into Appearance, Connections, Access and System. Organize Geyser into Local, Internet and Files panels with sensitive tokens never shown.
- Bring file browser and updater screens into the same visual system; preserve custom Minecraft-like fonts, Homestead palette, sounds, user data and existing servers.
- Add navigation regression tests and fix a start/stop button edge case. Verified build and release are required before users should install.


## v0.8.35 (versionCode 57) — Safe world switching and Geyser imports

- Fix world switching to preserve existing Minecraft settings when the destination world has no saved PocketCraft profile; avoid silently resetting difficulty, gamemode, PvP, view/simulation distance, and other server properties.
- An unconfigured world's settings screen now previews inherited server values before its first dedicated profile is saved.
- Tighten full Geyser `config.yml` imports to reject duplicate critical YAML keys, including the Bedrock port and private NXS token path, instead of silently choosing a conflicting value. No secret values are included in diagnostics.
- Prevent Geyser config save, import and restore while the server is starting or stopping, not only after it reaches RUNNING.
- Add focused regression tests for world inheritance, explicit world profiles, and ambiguous Geyser imports.
- Preserve existing worlds, Playit credentials, server engine, and all Minecraft modpacks. On-device crossplay testing is still required.

## v0.8.34 (versionCode 56) — World folders and guided configuration

- Add a simplified Home menu and individual world folder pages for settings, files, datapacks, backups, and world actions.
- Introduce per-world hosting preferences and a guided Geyser/NetherNet settings editor with `config.yml` import/restore and private backups.
- Remove duplicate Settings navigation controls.


## v0.8.33 (versionCode 55) — Crossplay diagnostics and first-run guide

- Add a first-run installation guide after a fresh app-data install and a permanent Home action to reopen it. Show the existing core setup checklist immediately when prerequisites are missing.
- Separate NetherNet signaling registration from a **verified Bedrock player login**. A successful Playit registration alone no longer shows `BEDROCK JOIN VERIFIED`.
- Read only public Geyser transport/signaling settings, flag duplicate YAML modes and unsuitable local-IP configurations without reading private NXS tokens.
- Explain external-only NXS versus built-in/hybrid LAN signaling in the join wizard. Do not supply a misleading local Bedrock join address when `transport: nethernet` and `signaling.mode: nxs`.
- Make the local hosting panel and health diagnostics report configuration problems; show distinct Java/Geyser readiness and player join evidence.
- Classify Android-specific C2ME/JNA, LOD hard-link, native zstd and lag warnings as non-secret diagnostic hints.
- Add regression tests for signaling configurations, connection guidance, and truthful join readiness.
- No automatic Geyser config rewrites, no token exposure, no changes to saved worlds or existing Minecraft hosting features.


## v0.8.32 (versionCode 54) — Remove experimental game hosting

- Fully revert the experimental Mindustry/Game Lab feature introduced in v0.8.30 and v0.8.31, restoring the Minecraft-only v0.8.29 application source and UI.
- Remove Mindustry hosting activity, foreground service, game installer, console, native game launcher changes, tests, and associated manifest entries.
- Preserve the existing Minecraft, Fabric, Geyser, Playit, console, worlds, and update functionality from v0.8.29.
- Increase the app version so devices on v0.8.30 or v0.8.31 can install this removal release as a normal update.


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
