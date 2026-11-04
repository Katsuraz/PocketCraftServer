package com.pocketcraft.server

import android.content.Context
import android.util.Log
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PocketCraft's streamlined Playit assistant. A single Go Online request
 * starts the agent, awaits account approval, creates ONE free Java tunnel
 * when permitted by the provider, and retrieves the provider's public address.
 * It never auto-creates a paid tunnel or claims remote connectivity was tested.
 */
object PlayitManager {
    @Volatile var status = "OFFLINE"
        private set
    @Volatile var claimUrl: String? = null
        private set
    @Volatile var detail = "Tap Go Online to set up free Java hosting"
        private set
    @Volatile var javaAddress: String? = null
        private set
    @Volatile var bedrockAddress: String? = null
        private set
    @Volatile var accountConnected = false
        private set
    @Volatile var creationNeedsHelp = false
        private set

    private var daemon: Process? = null
    private var client: Process? = null
    private val logLock = Any()
    private val polling = AtomicBoolean(false)
    @Volatile private var generation = 0
    @Volatile private var accountBlocked = false
    @Volatile private var requestedJavaPort = 25565
    @Volatile private var requestedBedrockPort = 19132
    @Volatile private var autoBedrockRequested = false
    @Volatile private var bedrockCreationAttempted = false
    @Volatile private var bedrockCreationNeedsHelp = false
    @Volatile private var autoCreateRequested = false
    @Volatile private var autoCreationAttempted = false

    fun isActive() = daemon?.isAlive == true
    private fun root(c: Context) = File(c.filesDir, "playit").apply { mkdirs() }

    private fun append(c: Context, line: String) {
        try {
            val clean = line.replace(Regex("(?i)(secret|token|key)[=:]\\S+"), "[credential hidden]")
            synchronized(logLock) {
                val log = File(root(c), "agent.log")
                if (log.length() > 600_000) log.writeText(log.readText().takeLast(200_000))
                log.appendText(clean.take(900) + "\n")
            }
        } catch (e: Exception) { Log.e("PocketCraft", "Playit log error", e) }
    }

    /**
     * Android 10+ target 29+ cannot execute ELF binaries copied to writable
     * filesDir. The package manager extracts executable ELF files under
     * nativeLibraryDir from the APK's ARM64 native-library entries at install time.
     */
    /**
     * Playit account limits and rejected agent registrations are server-side.
     * Expose these conditions clearly instead of displaying endless "Setting up".
     * Do not remove stored credentials or try creating more agents automatically.
     */
    private fun observeProviderMessage(line: String) {
        val message = line.lowercase(java.util.Locale.ROOT)
        when {
            message.contains("over the agent limit") ||
            message.contains("over agent limit") ||
            message.contains("agent limit exceeded") ||
            message.contains("disabledoverlimit") -> {
                accountBlocked = true
                status = "ACCOUNT LIMIT"
                detail = "Playit agent limit reached. Open Playit Agent Management, disable unused agents, then tap Retry."
            }
            message.contains("failed to sign and register") ||
            message.contains("http 403") ||
            message.contains("status 403") -> {
                if (status != "ACCOUNT LIMIT") {
                    accountBlocked = true
                    status = "ACCOUNT ACTION"
                    detail = "Playit rejected agent authentication (possibly account limit or HTTP 403). Check your Playit account and agent status, then retry."
                }
            }
        }
    }

    private fun executable(c: Context, assetName: String): File {
        if (!android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a"))
            throw IllegalStateException("Playit requires Android ARM64")
        val libraryName = when (assetName) {
            "playitd-arm64" -> "libpocketplayitd.so"
            "playit-cli-arm64" -> "libpocketplayitcli.so"
            else -> throw IllegalArgumentException("Unknown Playit executable")
        }
        val file = File(c.applicationInfo.nativeLibraryDir, libraryName)
        if (!file.isFile || !file.canExecute()) {
            throw IllegalStateException("Missing native Playit executable: $libraryName. Install the latest ARM64 PocketCraft APK.")
        }
        return file
    }

    private fun env(c: Context, builder: ProcessBuilder): ProcessBuilder {
        val e = builder.environment()
        val dir = root(c)
        e["HOME"] = dir.absolutePath
        e["TMPDIR"] = c.cacheDir.absolutePath
        e["XDG_CONFIG_HOME"] = dir.absolutePath
        e["LD_LIBRARY_PATH"] = listOf(c.applicationInfo.nativeLibraryDir, e["LD_LIBRARY_PATH"].orEmpty())
            .filter { it.isNotBlank() }.joinToString(":")
        return builder
    }

    /** User's Go Online action explicitly authorizes creating ONE free Java tunnel. */
    @Synchronized fun goOnline(c: Context, javaPort: Int, bedrockPort: Int = 19132, enableBedrock: Boolean = true) {
        requestedJavaPort = javaPort.coerceIn(1, 65535)
        requestedBedrockPort = bedrockPort.coerceIn(1, 65535)
        autoBedrockRequested = false // Existing Bedrock tunnels are still detected
        bedrockCreationAttempted = false
        bedrockCreationNeedsHelp = false
        autoCreateRequested = false
        autoCreationAttempted = false
        creationNeedsHelp = false
        if (isActive()) {
            refreshAsync(c)
        } else {
            start(c)
        }
    }

    @Synchronized fun start(c: Context) {
        if (isActive()) return
        val token = ++generation
        accountBlocked = false
        status = "STARTING"
        detail = "Starting Playit in PocketCraft..."
        javaAddress = null
        bedrockAddress = null
        accountConnected = false
        claimUrl = null
        val dir = root(c)
        try {
            val daemonExe = executable(c, "playitd-arm64")
            val clientExe = executable(c, "playit-cli-arm64")
            val sock = File(dir, "playit.sock")
            sock.delete()
            val args = listOf(
                daemonExe.absolutePath,
                "--socket-path", sock.absolutePath,
                "--secret-path", File(dir, "secret.toml").absolutePath,
                "--log-path", File(dir, "daemon.log").absolutePath
            )
            val proc = env(c, ProcessBuilder(args).directory(dir).redirectErrorStream(true)).start()
            daemon = proc
            status = "STARTING"
            Thread {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach {
                        append(c, "[daemon] $it")
                        observeProviderMessage(it)
                    }
                }
                if (generation == token) {
                    if (!accountBlocked) {
                        status = "OFFLINE"
                        detail = "Playit stopped unexpectedly. Open diagnostics if it won't restart."
                    }
                    append(c, "Playit daemon exited with code ${proc.waitFor()}")
                }
            }.start()
            Thread {
                try {
                    Thread.sleep(1400)
                    if (generation != token || !proc.isAlive) return@Thread
                    // With an existing secret, avoid the interactive setup wizard
                    // (there is no console for answering its questions on Android).
                    val cliArgs = mutableListOf(
                        clientExe.absolutePath, "--stdout", "--socket-path", sock.absolutePath
                    )
                    if (PlayitApiBridge.isClaimed(c)) cliArgs.add("attach")
                    val cli = env(c, ProcessBuilder(cliArgs)
                        .directory(dir).redirectErrorStream(true)).start()
                    client = cli
                    cli.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            append(c, "[client] $line")
                            observeProviderMessage(line)
                            val found = Regex("https://playit\\.gg/claim/[a-fA-F0-9]+")
                                .find(line)?.value
                            if (found != null && generation == token) {
                                try {
                                    val uri = URI(found)
                                    if (uri.scheme == "https" && uri.host == "playit.gg") {
                                        claimUrl = found
                                        if (!accountConnected) {
                                            status = "APPROVAL REQUIRED"
                                            detail = "Tap Connect Account once to approve PocketCraft in Playit"
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    append(c, "Playit setup helper exited with code ${cli.waitFor()}")
                } catch(e: Exception) { append(c, "Setup helper: ${e.message}") }
            }.start()
            Thread {
                while (generation == token && proc.isAlive) {
                    refreshAsync(c)
                    try { Thread.sleep(8000) } catch (_: InterruptedException) { break }
                }
            }.start()
        } catch (e: Exception) {
            val message = e.message ?: "Agent could not start"
            stop()
            status = "ERROR"
            detail = message
            append(c, "Tunnel launch failed: $message")
        }
    }

    /** API calls happen only on worker threads and never expose the agent key to the UI. */
    fun refreshAsync(c: Context) {
        if (!isActive() || accountBlocked || !polling.compareAndSet(false, true)) return
        Thread {
            try {
                if (!PlayitApiBridge.isClaimed(c)) {
                    accountConnected = false
                    if (claimUrl == null) {
                        status = "WAITING FOR ACCOUNT"
                        detail = "Preparing a one-time Playit approval link..."
                    }
                    return@Thread
                }
                accountConnected = true
                claimUrl = null
                status = "CHECKING"
                detail = "Finding your Minecraft Java address..."
                // The agent key may not be allowed to CREATE tunnels
                // through the API. Discover tunnels the user assigned in
                // Playit's account dashboard; don't generate repeated 403s.
                val state = PlayitApiBridge.fetch(c)
                if (accountBlocked) return@Thread
                javaAddress = state.javaAddress
                bedrockAddress = state.bedrockAddress
                creationNeedsHelp = javaAddress == null && state.pendingJavaCount == 0
                status = when {
                    javaAddress != null && bedrockAddress != null -> "ADDRESSES ASSIGNED"
                    javaAddress != null -> "JAVA ADDRESS ASSIGNED"
                    state.pendingJavaCount > 0 -> "WAITING FOR TUNNEL"
                    else -> "SETUP HELP NEEDED"
                }
                detail = when {
                    javaAddress != null && bedrockAddress != null ->
                        "Public Java and Bedrock addresses assigned. Verify both from another network."
                    javaAddress != null ->
                        "Java address ready. Create or assign a supported Bedrock tunnel in Playit dashboard."
                    state.pendingJavaCount > 0 ->
                        "Waiting for your Playit Java tunnel address to be allocated."
                    else ->
                        "Playit agent connected, but has no Minecraft Java tunnel. Create one in Playit dashboard to 127.0.0.1:$requestedJavaPort. Bedrock needs a supported tunnel to 127.0.0.1:$requestedBedrockPort."
                }
            } catch(e:Exception) {
                val message = e.message.orEmpty()
                if (message.contains("HTTP 403") || message.contains("HTTP 401")) {
                    accountBlocked = true
                    status = "ACCOUNT ACTION"
                    detail = "Playit API refused this agent (${message.take(80)}). Check agent status at playit.gg/account/agents, then retry."
                    append(c, "Playit API authorization rejected. Manual account action required.")
                } else {
                    status = "CHECKING CONNECTION"
                    detail = "Unable to check Playit: ${message.take(90).ifBlank { "network error" }}"
                }
            } finally { polling.set(false) }
        }.start()
    }

    @Synchronized fun retryTunnel(c: Context) {
        accountBlocked = false
        status = "CHECKING"
        detail = "Retrying Playit account status…"
        autoCreateRequested = false
        autoCreationAttempted = false
        bedrockCreationAttempted = false
        bedrockCreationNeedsHelp = false
        creationNeedsHelp = false
        refreshAsync(c)
    }

    @Synchronized fun stop() {
        generation++
        accountBlocked = false
        try { client?.destroy() } catch (_: Exception) {}
        try { daemon?.destroy() } catch (_: Exception) {}
        client = null
        daemon = null
        javaAddress = null
        bedrockAddress = null
        accountConnected = false
        claimUrl = null
        autoCreateRequested = false
        autoBedrockRequested = false
        autoCreationAttempted = false
        bedrockCreationAttempted = false
        bedrockCreationNeedsHelp = false
        creationNeedsHelp = false
        status = "OFFLINE"
        detail = "Tunnel stopped. Start it when you want remote players to join."
    }

    fun recentLog(c: Context): String =
        try { File(root(c), "agent.log").takeIf { it.exists() }?.readText()?.takeLast(8000) ?: "No tunnel diagnostics" }
        catch (_: Exception) { "Could not read tunnel diagnostics" }
}
