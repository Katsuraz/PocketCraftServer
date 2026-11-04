package com.pocketcraft.server

import android.content.Context
import android.util.Log
import java.io.File
import java.net.URI

/**
 * Experimental embedded playit.gg 1.2.0 agent.
 * Uses bundled Android ARM64 executables; does not require Termux or root.
 * A running agent is NOT proof that public tunnels are configured or reachable.
 */
object PlayitManager {
    @Volatile var status = "NOT INSTALLED"
        private set
    @Volatile var claimUrl: String? = null
        private set
    @Volatile var detail = "Android agent has not been started"
        private set

    private var daemon: Process? = null
    private var client: Process? = null
    private val logLock = Any()

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
            throw IllegalStateException("The Playit tunnel agent requires Android ARM64")
        val target = File(root(c), assetName)
        if (!target.isFile) {
            val temp = File(root(c), "$assetName.part")
            c.assets.open(assetName).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temp.setExecutable(true, true)) throw IllegalStateException("Could not mark $assetName executable")
            if (!temp.renameTo(target)) throw IllegalStateException("Could not install $assetName")
        }
        return target
    }

    private fun env(c: Context, builder: ProcessBuilder): ProcessBuilder {
        val e = builder.environment()
        val dir = root(c)
        e["HOME"] = dir.absolutePath
        e["TMPDIR"] = c.cacheDir.absolutePath
        e["XDG_CONFIG_HOME"] = dir.absolutePath
        val inherited = e["LD_LIBRARY_PATH"].orEmpty()
        e["LD_LIBRARY_PATH"] = listOf(c.applicationInfo.nativeLibraryDir, inherited)
            .filter { it.isNotBlank() }.joinToString(":")
        return builder
    }

    /** Called by the foreground ServerService, on a worker thread. */
    @Synchronized fun start(c: Context) {
        if (isActive()) return
        status = "STARTING"
        claimUrl = null
        detail = "Launching embedded Playit daemon"
        val dir = root(c)
        try {
            val daemonExe = executable(c, "playitd-arm64")
            val clientExe = executable(c, "playit-cli-arm64")
            val sock = File(dir, "playit.sock")
            sock.delete()
            val cmd = listOf(
                daemonExe.absolutePath,
                "--socket-path", sock.absolutePath,
                "--secret-path", File(dir, "secret.toml").absolutePath,
                "--log-path", File(dir, "daemon.log").absolutePath
            )
            daemon = env(c, ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)).start()
            status = "AGENT PROCESS ACTIVE"
            detail = "Follow the account claim link, then set TCP and TCP+UDP tunnels in your Playit dashboard."
            Thread {
                daemon?.inputStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { line -> append(c, "[daemon] $line") }
                }
                status = "OFFLINE"
                detail = "Playit daemon exited. Open tunnel logs."
            }.start()
            Thread {
                Thread.sleep(1700)
                if (!isActive()) return@Thread
                val cli = env(c, ProcessBuilder(
                    clientExe.absolutePath, "--stdout", "--socket-path", sock.absolutePath
                ).directory(dir).redirectErrorStream(true)).start()
                client = cli
                cli.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        append(c, "[client] $line")
                        val found = Regex("https://playit\\.gg/[^\\s<>\"']+").find(line)?.value
                            ?.trimEnd('.', ',', ')', ';')
                        if (found != null) {
                            try {
                                val uri=URI(found)
                                if (uri.scheme=="https" && uri.host=="playit.gg") {
                                    claimUrl=found
                                    detail="Open the Playit setup link to claim your tunnel agent"
                                }
                            } catch (_:Exception) {}
                        }
                    }
                }
                append(c, "Playit CLI exited with code ${cli.waitFor()}")
            }.start()
        } catch(e:Exception) {
            status = "ERROR"
            detail = e.message ?: "Playit failed to launch"
            append(c, "Agent launch failed: $detail")
            stop()
        }
    }

    @Synchronized fun stop() {
        try { client?.destroy() } catch (_:Exception) {}
        try { daemon?.destroy() } catch (_:Exception) {}
        client = null
        daemon = null
        status = "OFFLINE"
        detail = "Playit tunnel agent stopped"
    }

    fun recentLog(c: Context): String =
        try { File(root(c), "agent.log").takeIf { it.exists() }?.readText()?.takeLast(8000) ?: "No tunnel logs yet" }
        catch (_:Exception) { "Cannot read Playit logs" }
}
