package com.pocketcraft.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
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
        const val ACTION_RESTART = "com.pocketcraft.server.RESTART"
        const val ACTION_TUNNEL_START = "com.pocketcraft.server.TUNNEL_START"
        const val ACTION_TUNNEL_STOP = "com.pocketcraft.server.TUNNEL_STOP"
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL = "pocketcraft-host"
        private const val EVENTS = "pocketcraft-events"
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
        @Volatile var nethernetNativeFailure = false
            private set
        @Volatile var playersOnline: Int? = null
            private set
        @Volatile var playersMaximum: Int? = null
            private set
        @Volatile var startedAt = 0L
            private set
    }
    private val readiness = ReadinessSignals()
    private var server: Process? = null
    private var writer: BufferedWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val root by lazy { WorldStore.root(this) }
    private val log by lazy { File(root, "console.log") }
    private val lock = Any()
    private var restartAfterStop = false
    private var lastListRequest = 0L
    private var previousPlayitActive = false
    private val notificationHandler = Handler(Looper.getMainLooper())
    private val pulse = object : Runnable {
        override fun run() {
            if (running || PlayitManager.isActive()) {
                val tunnelActive=PlayitManager.isActive()
                if(previousPlayitActive && !tunnelActive) {
                    event("Playit agent stopped. Internet connections may no longer work.")
                }
                previousPlayitActive=tunnelActive
                refreshNotification()
                if (javaReady && System.currentTimeMillis()-lastListRequest >= 60_000L) {
                    lastListRequest=System.currentTimeMillis()
                    Thread { sendLine("list") }.start()
                }
            }
            notificationHandler.postDelayed(this,15_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationHandler.post(pulse)
    }

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
                previousPlayitActive=false
                PlayitManager.stop()
                if (!running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                else notice("Minecraft server running on LAN")
            }
            ACTION_START -> if (!running && status != "STARTING") begin()
            ACTION_STOP -> { restartAfterStop=false; end() }
            ACTION_RESTART -> {
                if (running) {
                    restartAfterStop=true
                    end()
                } else if (status != "STARTING") begin()
            }
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
        return if (running || PlayitManager.isActive()) START_STICKY else START_NOT_STICKY
    }

    private fun manager(): NotificationManager {
        val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Hosting status",NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(EVENTS,"Player and crash alerts",NotificationManager.IMPORTANCE_DEFAULT))
        return manager
    }

    private fun commandAction(action:String,id:Int):PendingIntent =
        PendingIntent.getService(this,id,Intent(this,ServerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun activityAction(action:String,id:Int):PendingIntent =
        PendingIntent.getActivity(this,id,
            Intent(this,MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun uptime():String {
        if(!running || startedAt==0L)return "Offline"
        val seconds=((System.currentTimeMillis()-startedAt)/1000L).coerceAtLeast(0L)
        val hours=seconds/3600
        val minutes=(seconds%3600)/60
        return if(hours>0) "$hours h $minutes m" else "$minutes m"
    }

    private fun buildNotification(message:String?=null):Notification {
        val stage=message ?: when {
            !running && PlayitManager.isActive() -> "Playit active • server offline"
            !running -> "Server offline"
            javaReady && bedrockReady -> "Java + Bedrock ready"
            javaReady -> "Java ready • Bedrock unverified"
            else -> "Starting Minecraft"
        }
        val world=runCatching {WorldStore.active(this)}.getOrDefault("world")
        val players=playersOnline?.let { online ->
            val maximum=playersMaximum
            if(maximum==null) "$online online" else "$online / $maximum players"
        } ?: "Players not checked"
        val local=NetworkInfo.lanAddress()
        val props=java.util.Properties()
        runCatching {File(root,"server.properties").takeIf{it.isFile}?.inputStream()?.use{props.load(it)}}
        val port=props.getProperty("server-port","25565")
        val javaAddress=PlayitManager.javaAddress ?: (local?.let{"$it:$port"} ?: "unavailable")
        val bedrockAddress=PlayitManager.bedrockAddress ?: (local?.let{"$it:19132"} ?: "unavailable")
        val detail=listOf(
            "World: $world  •  Uptime: "+uptime(),
            "Players (last check): $players",
            "Java: "+(if(javaReady)javaAddress else "not ready"),
            "Bedrock: "+(if(bedrockReady)bedrockAddress else "not confirmed"),
            "Playit: "+(if(PlayitManager.isActive())"active" else "offline"),
            "NetherNet: "+(if(nethernetReady)"registered" else "unverified")
        ).joinToString("\n")
        val builder=Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("PocketCraft • $stage")
            .setContentText("$world • $players • "+uptime())
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(activityAction(MainActivity.ACTION_OPEN_CONSOLE,60))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_view,
                if(running)"RESTART" else "CONSOLE",
                if(running) commandAction(ACTION_RESTART,61)
                else activityAction(MainActivity.ACTION_OPEN_CONSOLE,61)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause,
                if(running)"STOP" else "START",
                commandAction(if(running)ACTION_STOP else ACTION_START,62)).build())
        if(javaReady) {
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_share,
                if(bedrockReady)"COPY BEDROCK" else "COPY JAVA",
                activityAction(if(bedrockReady)MainActivity.ACTION_COPY_BEDROCK
                    else MainActivity.ACTION_COPY_JAVA,63)).build())
        }
        return builder.build()
    }

    private fun notice(message:String) {
        manager()
        startForeground(NOTIFICATION_ID,buildNotification(message))
    }

    private fun refreshNotification() {
        try { manager().notify(NOTIFICATION_ID,buildNotification()) }
        catch(e:Exception) {Log.w("PocketCraft","Notification refresh unavailable",e)}
    }

    private fun event(message:String) {
        if(!getSharedPreferences("pocketcraft",0).getBoolean("eventNotifications",true))return
        if(Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        try {
            manager().notify(4900+(System.currentTimeMillis()/1000L%15L).toInt(),
                Notification.Builder(this,EVENTS)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("PocketCraft")
                    .setContentText(message)
                    .setContentIntent(activityAction(MainActivity.ACTION_OPEN_CONSOLE,64))
                    .setAutoCancel(true).build())
        } catch(e:Exception) { Log.w("PocketCraft","Event alert unavailable",e) }
    }

    private fun sendLine(line:String) {
        try { synchronized(lock) { writer?.apply { write(line); newLine(); flush() } } }
        catch(e:Exception) {append("Command failed: "+e.message)}
    }

    private fun observePlayers(line:String) {
        Regex("There are (\\d+) of a max of (\\d+) players online",RegexOption.IGNORE_CASE)
            .find(line)?.let {
                playersOnline=it.groupValues[1].toIntOrNull()
                playersMaximum=it.groupValues[2].toIntOrNull()
            }
        Regex("\\b([A-Za-z0-9_]{3,16}) (joined|left) the game\\b")
            .find(line)?.let {
                playersOnline=null
                lastListRequest=0L
                val verb=if(it.groupValues[2]=="joined")"joined" else "left"
                event(it.groupValues[1]+" $verb the server")
            }
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
        readiness.reset()
        javaReady = false
        bedrockReady = false
        nethernetReady = false
        nethernetNativeFailure = false
        playersOnline = null
        playersMaximum = null
        startedAt = 0L
        lastListRequest = 0L
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
                startedAt = System.currentTimeMillis()
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
                        observePlayers(line)
                        val previous = status
                        val hadJava=javaReady
                        val hadBedrock=bedrockReady
                        readiness.observe(line)
                        javaReady = readiness.javaReady
                        bedrockReady = readiness.bedrockListener
                        nethernetReady = readiness.nethernetRegistered
                        nethernetNativeFailure = readiness.nethernetNativeFailure
                        if(!hadJava && javaReady) event("Minecraft Java server is ready")
                        if(!hadBedrock && bedrockReady) event("Geyser Bedrock listener is ready")
                        if (javaReady) {
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
                if (code != 0) {
                    failure = "Java exited with code $code; check console"
                    event("Minecraft stopped unexpectedly (exit $code). Check Console.")
                }
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
                append("Launch failed: $failure")
                event("Minecraft failed to start. Check Console.")
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
        val stoppingProcess = server
        Thread {
            try {
                synchronized(lock) { writer?.apply { write("stop"); newLine(); flush() } }
                Thread.sleep(15_000)
                // Do not accidentally destroy a replacement server after restart.
                stoppingProcess?.takeIf { it.isAlive }?.destroy()
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
        startedAt = 0L
        playersOnline = null
        playersMaximum = null
        javaReady = false
        bedrockReady = false
        nethernetReady = false
        nethernetNativeFailure = false
        readiness.reset()
        status = if (failure.isBlank()) "OFFLINE" else "ERROR"
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        if (restartAfterStop) {
            restartAfterStop=false
            notice("Restarting Minecraft")
            notificationHandler.postDelayed({ begin() },800L)
        } else if (PlayitManager.isActive()) notice("Playit tunnel agent active • Minecraft offline")
        else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onDestroy() {
        restartAfterStop=false
        notificationHandler.removeCallbacks(pulse)
        PlayitManager.stop()
        server?.takeIf { it.isAlive }?.destroy()
        release()
        super.onDestroy()
    }
}
