package com.pocketcraft.server

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

class ServerService : Service() {
    private var process: Process? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val root = File(filesDir, "minecraft").apply { mkdirs() }
        val log = File(root, "console.log")
        when (intent?.action) {
            "START" -> {
                // Android does not include a JVM capable of running a Minecraft server.
                // A compatible runtime and process lifecycle implementation are required.
                log.appendText("Server not started: Android-compatible Java runtime is not installed.\n")
            }
            "STOP" -> {
                process?.destroy()
                process = null
                log.appendText("Stop requested.\n")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
}
