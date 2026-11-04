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
    @Volatile var accountConnected = false
        private set
    @Volatile var creationNeedsHelp = false
        private set

    private var daemon: Process? = null
    private var client: Process? = null
    private val logLock = Any()
    private val polling = AtomicBoolean(false)
    @Volatile private var generation = 0
    @Volatile private var requestedJavaPort = 25565
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

    private fun executable(c: Context, assetName: String): File {
        if (!android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a"))
            throw IllegalStateException("Playit requires Android ARM64")
        val dir = root(c)
        val target = File(dir, assetName)
        val asset = c.assets.open(assetName)
        asset.use { input ->
            if (!target.isFile) {
                val temp = File(dir, "$assetName.part")
                temp.outputStream().use { output -> input.copyTo(output) }
                if (!temp.setExecutable(true, true) || !temp.renameTo(target))
                    throw IllegalStateException("Could not prepare $assetName")
            }
        }
        if (!target.canExecute() && !target.setExecutable(true, true))
            throw IllegalStateException("$assetName is not executable")
        return target
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
    @Synchronized fun goOnline(c: Context, javaPort: Int) {
        requestedJavaPort = javaPort.coerceIn(1, 65535)
        autoCreateRequested = true
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
        status = "STARTING"
        detail = "Starting Playit in PocketCraft..."
        javaAddress = null
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
                    lines.forEach { append(c, "[daemon] $it") }
                }
                if (generation == token) {
                    status = "OFFLINE"
                    detail = "Playit stopped unexpectedly. Open diagnostics if it won't restart."
                    append(c, "Playit daemon exited with code ${proc.waitFor()}")
                }
            }.start()
            Thread {
                try {
                    Thread.sleep(1400)
                    if (generation != token || !proc.isAlive) return@Thread
                    val cli = env(c, ProcessBuilder(
                        clientExe.absolutePath, "--stdout", "--socket-path", sock.absolutePath
                    ).directory(dir).redirectErrorStream(true)).start()
                    client = cli
                    cli.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            append(c, "[client] $line")
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
        if (!isActive() || !polling.compareAndSet(false, true)) return
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
                var state = PlayitApiBridge.fetch(c)
                if (state.javaTunnelCount == 0 && state.pendingJavaCount == 0 && autoCreateRequested && !autoCreationAttempted) {
                    autoCreationAttempted = true
                    status = "CREATING FREE TUNNEL"
                    detail = "Requesting a free Minecraft Java tunnel from Playit..."
                    try {
                        PlayitApiBridge.createJava(c, state.agentId, requestedJavaPort)
                        append(c, "Requested free Java tunnel for port $requestedJavaPort")
                        // Allocations can take a moment; a later refresh will display the address.
                        state = PlayitApiBridge.fetch(c)
                    } catch (e:Exception) {
                        creationNeedsHelp = true
                        detail = "Automatic Java tunnel setup was rejected. Tap Finish Setup."
                        append(c, "Automatic tunnel creation failed: ${e.message}")
                    }
                }
                javaAddress = state.javaAddress
                status = when {
                    javaAddress != null -> "ADDRESS ASSIGNED"
                    creationNeedsHelp -> "SETUP HELP NEEDED"
                    else -> "CONFIGURING"
                }
                if (javaAddress != null) {
                    detail = "Playit assigned a Java address. Invite a friend to test from another network."
                } else if (!creationNeedsHelp) {
                    detail = if (state.pendingJavaCount > 0) "Playit is allocating your free Java address..." else "No Java tunnel yet. Tap Finish Setup if automatic creation is unavailable."
                }
            } catch(e:Exception) {
                if (accountConnected) {
                    status = "CHECKING CONNECTION"
                    detail = "Unable to check Playit: ${e.message?.take(90) ?: "network error"}"
                }
            } finally { polling.set(false) }
        }.start()
    }

    @Synchronized fun retryTunnel(c: Context) {
        autoCreateRequested = true
        autoCreationAttempted = false
        creationNeedsHelp = false
        refreshAsync(c)
    }

    @Synchronized fun stop() {
        generation++
        try { client?.destroy() } catch (_: Exception) {}
        try { daemon?.destroy() } catch (_: Exception) {}
        client = null
        daemon = null
        javaAddress = null
        accountConnected = false
        claimUrl = null
        autoCreateRequested = false
        autoCreationAttempted = false
        creationNeedsHelp = false
        status = "OFFLINE"
        detail = "Tunnel stopped. Start it when you want remote players to join."
    }

    fun recentLog(c: Context): String =
        try { File(root(c), "agent.log").takeIf { it.exists() }?.readText()?.takeLast(8000) ?: "No tunnel diagnostics" }
        catch (_: Exception) { "Could not read tunnel diagnostics" }
}
