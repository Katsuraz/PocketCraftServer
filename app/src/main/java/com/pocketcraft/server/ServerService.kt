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
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL = "pocketcraft-host"
        private const val NOTIFICATION_ID = 4812
        @Volatile var running = false
            private set
        @Volatile var status = "OFFLINE"
            private set
        @Volatile var failure = ""
            private set
    }
    private var server: Process? = null
    private var writer: BufferedWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val root by lazy { WorldStore.root(this) }
    private val log by lazy { File(root, "console.log") }
    private val lock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) begin()
            ACTION_STOP -> end()
            ACTION_COMMAND -> {
                val line = intent.getStringExtra(EXTRA_COMMAND)?.trim().orEmpty()
                if (line.isNotEmpty() && line.length <= 240 && !line.contains("\n") && !line.contains("\r")) {
                    Thread {
                        try {
                            synchronized(lock) { writer?.apply { write(line); newLine(); flush() } }
                            append("> " + line)
                        } catch(e:Exception) { append("Command failed: " + e.message) }
                    }.start()
                }
            }
        }
        return if (running) START_STICKY else START_NOT_STICKY
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
        failure = ""
        Thread {
            try {
                val launcher = File(root, "fabric-server-launch.jar")
                if (!launcher.isFile) throw IllegalStateException("Install a Fabric launcher JAR first")
                if (!ServerDownloads.eulaAccepted(this))
                    throw IllegalStateException("Review and accept the Minecraft EULA first")
                val java = RuntimeStore.javaFile(this)
                    ?: throw IllegalStateException("Install the Android ARM64 Java runtime first")
                if (!java.canExecute()) throw IllegalStateException("Java executable is not executable on this Android device")
                val prefs = getSharedPreferences("pocketcraft", 0)
                val requestedHeap = prefs.getInt("heapMB", 2048).coerceIn(512, 3072)
                val javaHome = java.parentFile!!.parentFile!!
                val cmd = listOf(
                    java.absolutePath,
                    "-Xms256M",
                    "-Xmx${requestedHeap}M",
                    "-Djava.awt.headless=true",
                    "-jar", launcher.name, "nogui"
                )
                append("Launching Fabric using ${java.absolutePath} (heap max: ${requestedHeap} MB)")
                val proc = ProcessBuilder(cmd)
                    .directory(root)
                    .redirectErrorStream(true)
                    .apply {
                        val env = environment()
                        env["JAVA_HOME"] = javaHome.absolutePath
                        env["HOME"] = filesDir.absolutePath
                        env["TMPDIR"] = cacheDir.absolutePath
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
                    lines.forEach { line -> append(line) }
                }
                val code = proc.waitFor()
                append("Java process exited with code $code")
                if (code != 0) failure = "Java exited with code $code; check console"
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
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
        status = if (failure.isBlank()) "OFFLINE" else "ERROR"
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        server?.takeIf { it.isAlive }?.destroy()
        release()
        super.onDestroy()
    }
}
