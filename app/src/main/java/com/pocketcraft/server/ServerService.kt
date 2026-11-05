package com.pocketcraft.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.Properties

/**
 * Experimental, Android-native process supervisor. No root or Termux.
 * It only reports RUNNING when a JVM process really exists. A compiled APK or
 * imported JAR alone is never reported as a functioning server.
 */
class ServerService : Service() {
    companion object {
        const val ACTION_START = "com.pocketcraft.server.START"
        const val ACTION_STOP = "com.pocketcraft.server.STOP"
        const val ACTION_COMMAND = "com.pocketcraft.server.COMMAND"
        const val ACTION_CONFIRM_BEDROCK = "com.pocketcraft.server.CONFIRM_BEDROCK_JOIN"
        const val ACTION_TUNNEL_START = "com.pocketcraft.server.TUNNEL_START"
        const val ACTION_TUNNEL_STOP = "com.pocketcraft.server.TUNNEL_STOP"
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL = "pocketcraft-host"
        private const val NOTIFICATION_ID = 4812
        @Volatile var running = false
            private set
        @Volatile var status = "OFFLINE"
            private set
        @Volatile var failure = ""
            private set
        @Volatile var javaReady = false
            private set
        @Volatile var bedrockReady = false
            private set
        @Volatile var nethernetReady = false
            private set
        @Volatile var nethernetMode = "NONE"
            private set
        @Volatile var bedrockEvidence = "No Geyser readiness message captured"
            private set
        @Volatile var nethernetNativeFailure = false
            private set
    }
    private val readiness = ReadinessSignals()
    private var server: Process? = null
    private var writer: BufferedWriter? = null
    // Per-server-session upper bound for extra /help pages (not game commands).
    private var requestedHelpPages = 1
    private var wakeLock: PowerManager.WakeLock? = null
    private val root by lazy { WorldStore.root(this) }
    private val log by lazy { File(root, "console.log") }
    private val lock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TUNNEL_START -> {
                // Start the tunnel BEFORE attempting Java. Otherwise a failed JVM start
                // may stop this foreground service and kill Playit during setup.
                notice("Setting up online Minecraft hosting")
                Thread {
                    val javaPort = try {
                        val config = java.util.Properties()
                        File(root, "server.properties").takeIf { it.isFile }?.inputStream()?.use { config.load(it) }
                        config.getProperty("server-port", "25565").toIntOrNull()?.takeIf { it in 1..65535 } ?: 25565
                    } catch (_: Exception) { 25565 }
                    PlayitManager.goOnline(this, javaPort)
                    if (!running && status != "STARTING") begin()
                    val tunnelStarted = PlayitManager.isActive()
                    if (!tunnelStarted) {
                        append("Playit failed: ${PlayitManager.detail}; open Internet Hosting diagnostics")
                    }
                    notice(if(tunnelStarted) "Playit agent active • check PocketCraft for setup"
                        else "Playit startup failed • check diagnostics")
                }.start()
            }
            ACTION_TUNNEL_STOP -> {
                PlayitManager.stop()
                if (!running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                else notice("Minecraft server running on LAN")
            }
            ACTION_START -> if (!running && status != "STARTING") begin()
            ACTION_STOP -> end()
            ACTION_CONFIRM_BEDROCK -> {
                val geyserInstalled = File(root, "mods").listFiles()?.any {
                    it.isFile && it.name.startsWith("Geyser", ignoreCase = true) &&
                        it.name.endsWith(".jar", ignoreCase = true)
                } == true
                if (running && javaReady && geyserInstalled) {
                    readiness.confirmBedrockJoin()
                    bedrockReady = true
                    bedrockEvidence = readiness.bedrockEvidence
                    status = readiness.state()
                    append("Bedrock connection manually confirmed by the phone's owner for this server session")
                    notice("Minecraft Java + owner-confirmed Bedrock connection")
                } else {
                    append("Cannot confirm Bedrock connection: server, Java readiness or Geyser mod missing")
                }
            }
            ACTION_COMMAND -> {
                val line = intent.getStringExtra(EXTRA_COMMAND)?.trim().orEmpty()
                if (line.isNotEmpty() && line.length <= 240 && !line.contains("\n") && !line.contains("\r")) {
                    if (line == "help" || line == "/help" || line == "help 1" || line == "/help 1") requestedHelpPages = 1
                    Thread {
                        try {
                            synchronized(lock) {
                                val input = writer ?: throw IllegalStateException(
                                    "Server command input is not available")
                                input.write(line)
                                input.newLine()
                                input.flush()
                            }
                            append("> " + line)
                        } catch(e:Exception) { append("Command failed: " + e.message) }
                    }.start()
                }
            }
        }
        return if (running || PlayitManager.isActive()) START_STICKY else START_NOT_STICKY
    }

    private fun notice(message: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "PocketCraft server", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("PocketCraft Server")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun append(line: String) {
        synchronized(lock) {
            try {
                if (log.length() > 2_000_000L) {
                    val tail = log.readText().takeLast(800_000)
                    log.writeText(tail)
                }
                log.appendText("[PocketCraft] $line\n")
            } catch(e:Exception) { Log.e("PocketCraft", "log failure", e) }
        }
    }

    private fun begin() {
        notice("Checking server prerequisites…")
        status = "STARTING"
        PocketSounds.play(this, PocketSounds.Event.SERVER_START)
        failure = ""
        readiness.reset()
        // Floodgate's username prefix is public config, not an authentication
        // secret. Read it to identify real Bedrock logins in Java server output.
        runCatching {
            val floodgateConfig = File(root, "config/floodgate/config.yml")
            if (floodgateConfig.isFile && floodgateConfig.length() in 1..131072) {
                val configured = Regex("""(?m)^\s*username-prefix:\s*(?:"([^"]*)"|'([^']*)'|([^\s#]*))""")
                    .find(floodgateConfig.readText(Charsets.UTF_8))
                if (configured != null) {
                    val prefix = configured.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
                    readiness.setFloodgateUsernamePrefix(prefix)
                }
            }
        }
        ServerCommandHints.reset()
        requestedHelpPages = 1
        javaReady = false
        bedrockReady = false
        nethernetReady = false
        nethernetMode = "NONE"
        bedrockEvidence = readiness.bedrockEvidence
        nethernetNativeFailure = false
        Thread {
            try {
                val launcher = File(root, "fabric-server-launch.jar")
                if (!launcher.isFile) throw IllegalStateException("Install a Fabric launcher JAR first")
                if (!ServerDownloads.eulaAccepted(this))
                    throw IllegalStateException("Review and accept the Minecraft EULA first")
                val java = RuntimeStore.javaFile(this)
                    ?: throw IllegalStateException("Install the Android ARM64 Java runtime first")
                // Android 10+ target API 29+ prohibits exec() from writable app storage.
                // The signed APK must provide the native Java launcher and JVM shared libs.
                val nativeJava = RuntimeStore.nativeJavaLauncher(this)
                val prefs = getSharedPreferences("pocketcraft", 0)
                val requestedHeap = prefs.getInt("heapMB", 2048).coerceIn(512, 3072)
                val javaHome = java.parentFile!!.parentFile!!
                // Android does not provide /tmp. ImageIO and Geyser use java.io.tmpdir,
                // which does not automatically follow TMPDIR on the JVM.
                val javaTmp = File(cacheDir, "minecraft-java-tmp")
                if (!javaTmp.isDirectory && !javaTmp.mkdirs()) {
                    throw IllegalStateException("Could not create the Java temporary directory")
                }
                if (!javaTmp.canWrite()) throw IllegalStateException("Java temporary directory is not writable")
                append("Java temporary files: ${javaTmp.absolutePath}")
                val (mainClass, classPath) = RuntimeStore.fabricEntryPoint(launcher)
                val cmd = listOf(
                    nativeJava.absolutePath,
                    "--server",
                    javaHome.absolutePath,
                    applicationInfo.nativeLibraryDir,
                    javaTmp.absolutePath,
                    requestedHeap.toString(),
                    classPath,
                    mainClass
                )
                if (File(applicationInfo.nativeLibraryDir, "libdatachannel-java.so").isFile) {
                    append("Experimental Android ARM64 WebRTC JNI is packaged; NetherNet startup still needs verification.")
                } else {
                    append("Android ARM64 WebRTC JNI is not packaged; Geyser NetherNet is expected to fail on bionic.")
                }
                append("Launching Fabric through Android-safe packaged JNI Java engine (heap max: ${requestedHeap} MB)")
                val proc = ProcessBuilder(cmd)
                    .directory(root)
                    .redirectErrorStream(true)
                    .apply {
                        val env = environment()
                        env["JAVA_HOME"] = javaHome.absolutePath
                        env["HOME"] = filesDir.absolutePath
                        env["TMPDIR"] = javaTmp.absolutePath
                        env["TMP"] = javaTmp.absolutePath
                        env["TEMP"] = javaTmp.absolutePath
                        // The Java VM must see its normal JRE layout. The native
                        // launcher itself is APK-installed and executable on Android.
                        val libs = listOf(
                            File(javaHome, "lib").absolutePath,
                            File(javaHome, "lib/server").absolutePath,
                            applicationInfo.nativeLibraryDir,
                            env["LD_LIBRARY_PATH"].orEmpty()
                        ).filter { it.isNotBlank() }
                        env["LD_LIBRARY_PATH"] = libs.joinToString(":")
                    }.start()
                server = proc
                synchronized(lock) { writer = BufferedWriter(OutputStreamWriter(proc.outputStream)) }
                running = true
                status = "RUNNING"
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketCraft:Server").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                notice("Minecraft Java process active • check console")
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        append(line)
                        ServerCommandHints.observe(line)
                        // Vanilla's /help is paginated. Read every reported page
                        // once to capture mod commands without guessing their names.
                        // Limit to 24 pages to prevent command floods on huge packs.
                        if (javaReady && requestedHelpPages < ServerCommandHints.helpPageCount) {
                            val pages = ServerCommandHints.helpPageCount
                            val start = requestedHelpPages + 1
                            synchronized(lock) {
                                writer?.let { input ->
                                    for (page in start..pages) {
                                        input.write("help " + page)
                                        input.newLine()
                                    }
                                    input.flush()
                                    requestedHelpPages = pages
                                }
                            }
                            append("Collecting /help pages " + start + "–" + pages +
                                " for console command suggestions")
                        }
                        val previous = status
                        val wasJavaReady = javaReady
                        readiness.observe(line)
                        javaReady = readiness.javaReady
                        bedrockReady = readiness.bedrockListener
                        nethernetReady = readiness.nethernetSignalingReady
                        nethernetMode = readiness.nethernetSignalDescription
                        bedrockEvidence = readiness.bedrockEvidence
                        nethernetNativeFailure = readiness.nethernetNativeFailure
                        if (javaReady) {
                            if (!wasJavaReady) {
                                PocketSounds.play(this,PocketSounds.Event.SERVER_READY)
                                // The server's /help output contains registered Fabric
                                // command roots, including those provided by loaded mods.
                                // Suggestion discovery never installs or runs external code.
                                try {
                                    synchronized(lock) {
                                        writer?.apply { write("help"); newLine(); flush() }
                                    }
                                    append("Discovering server and Fabric mod commands via help")
                                } catch(e:Exception) {
                                    append("Could not load command help: " + e.message)
                                }
                            }
                            status = readiness.state()
                            if (previous != status) {
                                append("Readiness changed: $status")
                                notice(if(bedrockReady) "Java + Geyser listener detected" else "Minecraft Java server ready")
                            }
                        }
                    }
                }
                val code = proc.waitFor()
                append("Java process exited with code $code")
                if (code != 0) failure = "Java exited with code $code; check console"
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
                PocketSounds.play(this,PocketSounds.Event.ERROR)
                append("Launch failed: $failure")
            } finally {
                release()
            }
        }.start()
    }

    private fun end() {
        if (server == null) {
            release()
            return
        }
        status = "STOPPING"
        PocketSounds.play(this,PocketSounds.Event.SERVER_STOP)
        append("Graceful stop requested")
        Thread {
            try {
                synchronized(lock) { writer?.apply { write("stop"); newLine(); flush() } }
                Thread.sleep(15_000)
                server?.takeIf { it.isAlive }?.destroy()
            } catch (e: Exception) { append("Shutdown: " + e.message) }
        }.start()
    }

    private fun release() {
        synchronized(lock) {
            try { writer?.close() } catch (_: Exception) {}
            writer = null
        }
        server = null
        running = false
        javaReady = false
        bedrockReady = false
        nethernetReady = false
        nethernetMode = "NONE"
        nethernetNativeFailure = false
        readiness.reset()
        bedrockEvidence = readiness.bedrockEvidence
        status = if (failure.isBlank()) "OFFLINE" else "ERROR"
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        if (PlayitManager.isActive()) notice("Playit tunnel agent active • Minecraft offline")
        else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onDestroy() {
        PlayitManager.stop()
        server?.takeIf { it.isAlive }?.destroy()
        release()
        super.onDestroy()
    }
}
