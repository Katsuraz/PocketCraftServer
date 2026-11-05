# PocketCraft Server — Android (v0.3 experimental)

PocketCraft Server is a native Kotlin Android app for managing a Minecraft Java Fabric server, with a custom midnight / block-world UI. It does **not** depend on Termux.

## v0.5: genuine installer progress and Fabric modpacks

- Download/install dialogs now display a real byte-based progress bar when content size is available, current file, and phases such as resolving dependencies, verifying files and unpacking Java. Otherwise a visible indeterminate progress indicator is used; cancellation is cooperative.
- **Modrinth:** browse Fabric modpacks in the app, download a compatible release for the installed Minecraft version, or import a local `.mrpack` file. The installer checks SHA-512 when provided, skips client-only and optional content, and supports `overrides/` and `server-overrides/`.
- **CurseForge:** import a ZIP with `manifest.json` and Fabric loader. Mod downloads require an approved CurseForge developer API key entered for that install only; PocketCraft does not retain the key. Files withheld by CurseForge must be obtained through authorized means.
- Both importers avoid overwriting existing server files, refuse non-Fabric packs, check the installed Minecraft version and restrict extracted paths to server-compatible mod/config directories.
- Imports do **not** install client-side mods on Bedrock or guarantee crossplay. Back up the server and stop it before importing. Some modpacks also require client mods on Java players.

## v0.4: multiplayer controls and refreshed UI

- Redesigned server settings with visible touch-friendly selectors and switches, consistent dark theme, larger tap targets, and clearer sections for identity, access, gameplay and performance.
- Editable Java TCP port in `server.properties`; the *public* address is **not** changed by editing the app. A saved share hostname is a label/copy shortcut for an already configured domain or tunnel.
- Optional non-premium Java client access through `online-mode=false` with an explicit warning. This removes account verification and makes nickname impersonation possible. Whitelist alone cannot secure a public offline-mode server. RCON remains forcibly disabled.
- Quick connection card showing the LAN IP, custom share hostname (if entered), account authentication mode and port.
- Shared settings are saved only while the server is stopped; world and memory settings continue to work as before.
- **Still experimental:** a successful APK build does not prove Android JVM execution or public Java/Bedrock reachability. Remote connections still require a real DNS record/tunnel/port forwarding and matching Geyser support.

## Implemented

- **Dashboard:** setup journey, actual process state, real Start / Stop buttons, accessible LAN addresses and copy action.
- **Android OpenJDK installer:** opt-in download of ARM64 Java 25 from AngelAuraMC, pinned to the upstream SHA-256 checksum. Uses a safe, size-limited tar.xz extraction flow and an on-device `java -version` health check.
- **Experimental process host:** Android foreground notification, partial wake lock, launches the Fabric JAR with bounded heap, follows stdout/stderr into a rotating log, sends console commands over stdin, gracefully stops with `stop` and a 15-second fallback.
- **Fabric installer:** downloads official Fabric launcher versions and records EULA acceptance only with explicit agreement.
- **Crossplay files:** downloads Fabric API, Geyser-Fabric and Floodgate-Fabric for Minecraft Java 26.3 from official endpoints. Does not assert that crossplay is online merely because files are installed.
- **Mods:** import JARs, inspect Fabric metadata, enable/disable, delete with confirmation.
- **Worlds:** import world ZIP with extraction checks, select active world, export backups.
- **Server settings:** edit server.properties, keep online-mode enabled and RCON disabled.
- **Console:** view/copy logs, submit commands to the actual process only while running.
- **CI:** GitHub Actions builds a debug APK.

## Very important: proof-of-concept, not production hosting

**A successful APK compilation is not proof that Fabric, Java 25, or Geyser actually runs on a realme 7 Pro.** Execution of downloaded native binaries is restricted on some Android versions. The Java runtime used by the Amethyst Minecraft client may require additional Android-specific launcher integration to run a headless server. Java 25 on Android is not a standard JVM included in Android.

The app intentionally reports only process status and local addresses; it does **not** yet confirm that the server finished loading, show actual connected players or expose a public internet address. A working Java server and Bedrock connection still require device-level testing and potentially more platform integration.

**Version compatibility:** Current Geyser-Fabric supports Java 26.3 only, which requires Java 25. For older Fabric server releases, a separate compatible proxy such as ViaProxy is needed. Client-required Fabric content mods will not magically load on Bedrock.

**Internet access:** The LAN address shown in the app only works for devices on the same reachable network. Public hosting requires a TCP tunnel for Java (default 25565) and both TCP + UDP connectivity to Geyser's Bedrock port (default 19132), or suitable router forwarding.

## Test on realme 7 Pro

1. Build/download the latest APK from GitHub Actions. Confirm the APK is from **this** repository, not a lookalike.
2. Install PocketCraft. In the app, install Java 25. The runtime is downloaded only after you opt in.
3. Tap **TEST JAVA ENGINE**. Save its exact output, whether successful or not. If it fails, do not assume Minecraft can start.
4. Install Minecraft **26.3** Fabric server, review the Minecraft EULA, and install compatible crossplay files.
5. Tap **START**, visit **Console**, and refresh. Wait for an actual Minecraft ready message (usually `Done (...)`); a running Java process alone is not proof.
6. If it starts, connect Java from another device on the same Wi-Fi using the shown LAN IP:25565. For Bedrock, verify Geyser was initialized and connect to the shown LAN IP:19132.
7. If any step fails, share the console text. Keep backups of every important world before testing.

For internet play, use a tunnel supporting the latest Geyser NetherNet TCP+UDP requirements; a standalone Android tunnel implementation has not been integrated yet.

## APK build

GitHub repository → **Actions** → **Build Android APK** → latest passing run → **Artifacts** → `pocketcraft-debug-apk`.

Build uses Gradle/JDK 17 for the Android app; the phone separately downloads the experimental Java 25 runtime for Minecraft. v0.3 uses Android `targetSdk=28` to experiment with executable binaries in private app storage; this is not suitable for Play Store release and has compatibility/security tradeoffs. Runtime execution may still be denied by the device.

## Upstream sources & licenses

- Runtime: [AngelAuraMC Android OpenJDK releases](https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/tag/download_jre25). Java 25 ARM64 release file SHA-256: `d3eb7afe2240c26728a1bb440502c5f18ac3883e932d202dd7f0c9bcbbce4c37`. The APK does not redistribute that archive.
- Minecraft Fabric: https://fabricmc.net/
- Fabric API: https://modrinth.com/mod/fabric-api
- Geyser / Floodgate: https://geysermc.org/
- Apache Commons Compress (Apache-2.0) and XZ for Java (public domain) perform runtime extraction.

PocketCraft is an experimental community project, not an official Minecraft product, and is not affiliated with Mojang, Microsoft, GeyserMC, or AngelAuraMC. Only run trusted mods; mods are executable code and can access server files.


## Java ImageIO / Geyser Android temporary directory fix
The Android host explicitly creates `cacheDir/minecraft-java-tmp` and launches Java with
`-Djava.io.tmpdir=<the private writable path>` plus `TMPDIR`, `TMP` and `TEMP` pointing to it.
This avoids the `NoSuchFileException: /tmp/imageio*.tmp` / `Can't create cache file!` failure
when Geyser loads client skin textures. A server being ready does not prove Bedrock
connectivity: check Geyser startup messages and ensure port 19132 is reachable.

## Installing APK updates without uninstalling
Android requires every update to use **the same signing certificate**, package ID,
and a nondecreasing versionCode. GitHub's per-run default debug keys do not meet this
requirement. This project now optionally accepts repository Actions secrets:

- `POCKETCRAFT_KEYSTORE_BASE64`: base64 encoding of a privately held PKCS12
  keystore whose key alias is `pocketcraft`.
- `POCKETCRAFT_SIGNING_PASSWORD`: the keystore and key password.

Configure both secrets in GitHub → Settings → Secrets and variables → Actions.
Builds with these secrets use the stable key and verify the resulting APK using
`apksigner`. A build without secrets still produces a **temporary debug-signed**
APK, and a warning is shown; it cannot be relied on for upgrade compatibility.

**Keep the signing key secret, and back it up.** Never commit it to a public repo.
Existing builds signed using disposable debug keys cannot be retroactively upgraded
with this new certificate. Export important worlds and configurations before the
one-time migration to a permanently signed build. Thereafter, installing a later
APK signed with the same key can update the app without uninstalling it.


## PocketCraft 0.6 — readiness and experimental worldwide hosting

**Readiness badge**: PocketCraft now watches actual Fabric and Geyser server logs. Java is marked ready only after Minecraft reports `Done (...)! For help, type ...`. Geyser listener detection is tracked separately and will appear only if a Geyser startup log confirms the Bedrock port. The badge refreshes approximately every two seconds while the app is in the foreground. A detected listener does not prove that a remote device can successfully connect.

**Built-in Playit Android agent (experimental)**: GitHub Actions cross-compiles Playit 1.2.0 directly from its official pinned upstream source for Android ARM64 and embeds both `playit-cli` and `playitd` in the APK. PocketCraft copies the files to private app storage at first use and runs them using its existing Android foreground service. The agent does not use Termux. The app does NOT report remote connectivity just because the agent process is running.

To try internet hosting:
1. Back up your Minecraft world, then install the signed PocketCraft build.
2. Start Fabric and wait for **Java Ready**; verify Geyser's listener message and TCP/UDP configuration if you also want Bedrock.
3. Select **Start Tunnel** in the **Worldwide Multiplayer** card, then **Refresh Tunnel Status**.
4. If the agent prints a Playit claim link, tap **Claim Agent Account**. The Playit account and its service are operated by playit.gg.
5. In the Playit dashboard configure a Minecraft Java TCP tunnel forwarding to port 25565 (or your configured Java port). For Bedrock with NetherNet, configure a suitable tunnel forwarding **both TCP and UDP** to port 19132 (or your configured Geyser port).
6. Only share provider-assigned external addresses after checking actual connectivity from a different network.
7. Use **View Tunnel Log** if claim or connectivity fails. A working native ARM64 build does not guarantee compatibility with your particular Android version or mobile network.

The Playit native agent has been cross-compiled by GitHub Actions, but the end-to-end Android claim flow and public Minecraft connections require device-level testing. The native build workflow is included in `.github/workflows/playit-android-experiment.yml`.

Playit upstream: https://github.com/playit-cloud/playit-agent (copyright Developed Methods LLC; see `app/src/main/assets/PLAYIT-LICENSE.txt`). Its service terms, limitations and availability are controlled by the provider.

**APK updates:** The same private signing key must be configured in GitHub Actions secrets across versions. Without that configuration, GitHub's default debug-signing certificate changes between runners, so updates may require uninstalling; uninstalling removes app-private Minecraft files. Back up worlds first.


## One-tap hosting (v0.6.1)
PocketCraft now has **Go Online — Java + Bedrock** on the Home dashboard. The action starts Fabric, launches the embedded Playit agent, and (after an account is approved) attempts one free Minecraft Java tunnel and one Minecraft Bedrock NetherNet tunnel using the provider's supported API. On the first connection, approve the account in your browser once; PocketCraft does **not** collect your Playit password. If supported by the provider, the resulting Java and Bedrock addresses appear in the app with Copy buttons. Provider restrictions or unavailable allocation may require manual setup; a displayed address is not proof of successful remote connectivity. The Advanced Setup / Diagnostics screen preserves tunnel logs and a link to the Playit dashboard.

**Caution:** Online hosting exposes your server publicly. Use secure account authentication and whitelist trusted players. An app process running is not proof that TCP/UDP tunneling works on your network. Existing APKs must have the same signing certificate to install as updates; back up worlds and mods before migrating to a newly signed build.


## v0.8.1 — experimental Android NetherNet JNI integration

On Android, headless OpenJDK reports Linux/aarch64 but uses Android's
**bionic** C library, not glibc or musl. Geyser can load while NetherNet
fails with "Cannot identify the JVM's libc" / "Could not load its WebRTC
native library".

PocketCraft's APK build now **attempts** to package an Android ARM64 native
WebRTC library matching Geyser's pinned libdatachannel-java version from
the Open Collaboration repository. It tries the Android AAR and ARM64
classifier JAR; accepts a download only when the upstream SHA-256 sidecar
matches and the binary is ELF AArch64 with no glibc dependency. An absent
artifact is skipped with a prominent GitHub Actions warning, so building
an APK alone is **not proof of a fix**.

At runtime, the Java bootstrap points Geyser's libdatachannel-java JNI
loader to the packaged Android native library if present. This is a
focused attempt to bypass glibc/musl auto-detection, **not** a claim that
NetherNet works on phones. JNI symbol/version mismatch, linker namespace,
thread/stack, TLS/ICE and network issues may remain. Never force a Linux
glibc/musl binary into the Android Java process.

To verify on a real device:

1. Back up worlds and server settings before replacing any APK. Updates
   need the same signing certificate; do not uninstall without a backup.
2. Open the GitHub Actions build log. Require the message
   "Bundled verified Android ARM64 libdatachannel-java.so". If the log says
   "Matching Android WebRTC native not found" or has a checksum warning,
   this APK does **not** contain the experimental native library.
3. Start Minecraft and require "Android WebRTC JNI available" in Console.
   Check that NetherNet startup exceptions are absent.
4. Verify Geyser actually reports a listening Bedrock port; then test
   from another Bedrock device on the LAN. Java "Done" is not enough.
5. Finally test Playit/NetherNet from a different network. Public
   connectivity depends separately on signaling, TCP/UDP and tunnel setup.

GeyserMC upstream Android support issue:
https://github.com/GeyserMC/Geyser/issues/6848.

## v0.8.2 — Play Together guided experience (Anvil-inspired, original implementation)

- Home dashboard now includes **Play Together** with one-tap entry into a guided Java/Bedrock, LAN/internet connection workflow.
- The guide chooses the next supported action: start server, inspect Console, install Geyser, connect to Wi-Fi, start/approve Playit, open tunnel setup, or copy an assigned address.
- Native Android WebRTC errors prevent Bedrock addresses from being presented as joinable; Minecraft Java success remains separate from Bedrock success.
- Copy actions only appear after required local startup or tunnel assignment signals. A copied address is **not** a verified player connection; test with a second device.
- Home connection indicators refresh without resetting page scroll. Pure Kotlin tests cover typical blocked and ready states.
- PocketCraft remains an independent app. This does **not** reproduce Anvil-MC's automatic tunnel infrastructure, native Bedrock server hosting, UI assets, or other proprietary features.
- Experimental Android NetherNet support from v0.8.1 remains unverified. Do not uninstall older builds without world backups; APK update signing keys must match.

### Additional Android JNI build fallback

If the matching upstream AAR/classifier is unavailable or unverified, the Android APK
workflow attempts to compile libdatachannel-java from pinned upstream source commit
`6be4ad71e1f10dc56a09af6daeac0e1122e23e7a` in the Android ARM64
cross-build image. For v0.8.2 and later, a missing native binary fails the
build instead of silently publishing an APK that still has the original
NetherNet/Bionic failure. **A successful build with this binary is still not
proof of an operational Bedrock listener; test on a real phone.**

## PocketCraft 0.8.1 — Mods and Android Files integration

- **Mods:** simplified Fabric browser, installed-mod controls, import actions, and Modrinth search with category/version filters. Modrinth project installs attempt to install declared required Fabric dependencies first. Client-only releases are rejected; actual compatibility depends on each mod and the server runtime.
- **Minecraft versions:** choose a listed Fabric server version or type another official Minecraft version. The device must still provide a compatible Java runtime; choosing a version does not install a matching Android Java runtime automatically.
- **Live server folder in Android Files:** open PocketCraft → Server files → **Open in Android Files**. In the system picker, select **PocketCraft Server** under Browse locations. This is a virtual Documents Provider exposing the live `WorldStore.root` folder, so Minecraft continues to use private app storage; the project does **not** move or duplicate the world.
- Editing, deleting, renaming, and creating files through the provider are blocked while Minecraft is running. Large worlds can remain in private app storage; this prevents Android scoped-storage permissions from causing file access problems during hosting.
- Android third-party file managers do not all expose Documents Provider roots. If yours does not, use the Android system Files/Document picker or PocketCraft's built-in Server Files screen and full ZIP export.
- **Playit settings:** use **Paste Playit Settings** in Mods. Copy the complete Geyser YAML snippet from the Playit NetherNet tunnel configuration help, not an agent secret. Stop and restart the Minecraft server when editing settings.
- **Validation:** the GitHub code changes require GitHub Actions APK compilation and on-device verification before they can be considered a tested release. APK upgrades still require a matching Android signing certificate. Back up the world before changing builds.

## Release version tracking — safeguard against APK downgrade

The current verified baseline is **PocketCraft 0.8.4 (Android `versionCode = 26`)** in `app/build.gradle.kts`. Earlier uploaded APKs were build (17) with code 25 and build (18) with code 21; attempting to install the latter over the former triggers Android's downgrade protection.

The Android build workflow now fetches the project's Git history and **fails** if the Gradle versionCode is lower than the largest historical value (minimum floor: 26). This guards against repeat downgrades even if filenames or GitHub artifact numbers are confusing. Each subsequent release should increase versionCode by at least 1. `versionName` is user-facing only, while Android uses `versionCode` for update ordering.

This guard cannot test the APK already installed on the user's phone or prove matching signing certificates. Before any major install, export a server backup and verify the APK is signed with the existing signing certificate.
