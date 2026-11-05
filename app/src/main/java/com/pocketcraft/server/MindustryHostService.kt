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
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

/** Experimental, one-game-at-a-time Java headless game host. */
class MindustryHostService : Service() {
    companion object {
        const val ACTION_START = "com.pocketcraft.server.MINDUSTRY_START"
        const val ACTION_STOP = "com.pocketcraft.server.MINDUSTRY_STOP"
        const val ACTION_COMMAND = "com.pocketcraft.server.MINDUSTRY_COMMAND"
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL = "pocketcraft-experimental-host"
        private const val NOTIFICATION_ID = 4813
        @Volatile var running = false
            private set
        @Volatile var status = "OFFLINE"
            private set
        @Volatile var error = ""
            private set
        @Volatile var commandStatus = "No game commands sent yet"
            private set
        @Volatile var startedMs = 0L
            private set
        fun active() = running || status=="STARTING" || status=="STOPPING"
    }
    private val ioLock=Any()
    private val logLock=Any()
    private var proc:Process?=null
    private var stdin:BufferedWriter?=null
    private var wakeLock:PowerManager.WakeLock?=null
    private val commands=Executors.newSingleThreadExecutor { work ->
        Thread(work,"PocketCraft-Mindustry-stdin").apply {isDaemon=true}
    }
    private val root by lazy { MindustryHostFiles.directory(this) }
    private fun notice(text:String) {
        val mgr=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=26) mgr.createNotificationChannel(
            NotificationChannel(CHANNEL,"PocketCraft experimental game host",
                NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,1,
            Intent(this,MindustryHostingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("PocketCraft • Mindustry (experimental)")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open).build()
        startForeground(NOTIFICATION_ID,n)
    }
    private fun append(line:String) = synchronized(logLock) {
        try {
            val f=MindustryHostFiles.log(this)
            if(f.length()>1_500_000L) {
                val recent=java.io.RandomAccessFile(f,"r").use {r ->
                    val n=minOf(550_000L,r.length()).toInt()
                    r.seek(r.length()-n)
                    ByteArray(n).also {r.readFully(it)}.toString(Charsets.UTF_8)
                }
                f.writeText(recent)
            }
            f.appendText("[Mindustry] "+line.take(1600)+"\n")
        } catch(e:Exception) {Log.e("PocketCraft-Mindustry","Could not append game log",e)}
    }
    override fun onBind(intent:Intent?):IBinder? = null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action) {
            ACTION_START -> if(!active()) startGame()
            ACTION_STOP -> stopGame()
            ACTION_COMMAND -> sendCommand(intent.getStringExtra(EXTRA_COMMAND).orEmpty())
        }
        return START_NOT_STICKY
    }
    private fun startGame() {
        if(ServerService.running ||
            ServerService.status=="STARTING" || ServerService.status=="STOPPING") {
            error="Stop Minecraft completely before starting Mindustry"
            status="ERROR"
            append(error)
            stopSelf()
            return
        }
        error=""
        status="STARTING"
        commandStatus="Starting Mindustry…"
        notice("Starting Android Java game server")
        Thread({
            try {
                val gameJar=MindustryHostFiles.serverJar(this)
                MindustryHostFiles.validateJar(gameJar)
                val java=RuntimeStore.javaFile(this)
                    ?: throw IllegalStateException("Install the Android Java runtime from PocketCraft first")
                val native=RuntimeStore.nativeJavaLauncher(this)
                val javaHome=java.parentFile!!.parentFile!!
                val temp=File(cacheDir,"mindustry-java-tmp")
                if(!temp.isDirectory && !temp.mkdirs())
                    throw IllegalStateException("Cannot create Mindustry temporary files")
                val ram=getSharedPreferences("pocketcraft",0)
                    .getInt("mindustryHeapMB",768).coerceIn(384,1536)
                val args=listOf(
                    native.absolutePath,"--game",
                    javaHome.absolutePath,
                    applicationInfo.nativeLibraryDir,
                    temp.absolutePath,
                    ram.toString(),
                    gameJar.absolutePath,
                    "mindustry.server.ServerLauncher",
                    root.absolutePath)
                append("Launching official-style Mindustry server via Android JNI Java (heap "+ram+" MB)")
                append("Data directory: "+root.absolutePath)
                val process=ProcessBuilder(args)
                    .directory(root)
                    .redirectErrorStream(true)
                    .apply {
                        val env=environment()
                        env["JAVA_HOME"]=javaHome.absolutePath
                        env["HOME"]=root.absolutePath
                        env["TMPDIR"]=temp.absolutePath
                        env["TMP"]=temp.absolutePath
                        env["TEMP"]=temp.absolutePath
                        env["LD_LIBRARY_PATH"]=listOf(
                            File(javaHome,"lib").absolutePath,
                            File(javaHome,"lib/server").absolutePath,
                            applicationInfo.nativeLibraryDir,
                            env["LD_LIBRARY_PATH"].orEmpty()
                        ).filter {it.isNotBlank()}.joinToString(":")
                    }.start()
                synchronized(ioLock) {proc=process;stdin=BufferedWriter(OutputStreamWriter(process.outputStream))}
                running=true
                startedMs=SystemClock.elapsedRealtime()
                status="RUNNING"
                val pm=getSystemService(POWER_SERVICE) as PowerManager
                wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PocketCraft:Mindustry").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                notice("Process active • use 'host' to start a map")
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        append(line)
                        if(line.contains("Server loaded.",true)) {
                            status="READY"
                            commandStatus="Mindustry loaded. Use 'host' to start a map."
                            notice("Game server loaded • host a map to accept players")
                        }
                        if(line.contains("Server opened",true) ||
                           line.contains("Listening on port",true) ||
                           line.contains("Server started on port",true)) {
                            status="HOSTING"
                            notice("Mindustry hosting • local port 6567 by default")
                        }
                        if(line.contains("Stopped server.",true))status="READY"
                    }
                }
                val rc=process.waitFor()
                append("Game process exited with code "+rc)
                if(rc!=0) error="Mindustry Java exited with code "+rc+". Check its log."
            } catch(e:Exception) {
                error=e.message ?: e.javaClass.simpleName
                append("Mindustry launch failed: "+error)
            } finally {finish()}
        },"PocketCraft-Mindustry-launch").start()
    }
    private fun sendCommand(raw:String) {
        val command=runCatching { ConsoleCommands.normalize(raw) }.getOrElse {
            commandStatus="Not sent: "+(it.message ?: "invalid command")
            return
        }
        if(!running || status=="STOPPING") {
            commandStatus="Not sent: Mindustry is not running"
            return
        }
        commandStatus="Queued: "+command
        commands.execute {
            try {
                synchronized(ioLock) {
                    val input=stdin
                    if(!running || proc?.isAlive!=true || input==null)
                        throw IllegalStateException("Mindustry stdin is unavailable")
                    input.write(command)
                    input.newLine()
                    input.flush()
                }
                commandStatus="Sent to Mindustry stdin: "+command
                append("> "+command)
            } catch(e:Exception) {
                commandStatus="Command failed: "+(e.message ?: "stdin write error")
                append(commandStatus)
            }
        }
    }
    private fun stopGame() {
        if(!active())return
        status="STOPPING"
        commandStatus="Stopping Mindustry…"
        append("Graceful exit requested")
        Thread({
            try {
                synchronized(ioLock) {
                    stdin?.apply { write("exit");newLine();flush() }
                }
                Thread.sleep(7000)
                proc?.takeIf {it.isAlive}?.destroy()
                Thread.sleep(1000)
                proc?.takeIf {it.isAlive}?.destroyForcibly()
            } catch(e:Exception) {append("Shutdown issue: "+e.message)}
        },"PocketCraft-Mindustry-stop").start()
    }
    private fun finish() {
        synchronized(ioLock) {
            try{stdin?.close()}catch(_:Exception){}
            stdin=null
            proc=null
        }
        running=false
        startedMs=0
        status=if(error.isBlank()) "OFFLINE" else "ERROR"
        commandStatus=if(error.isBlank())"Mindustry stopped" else error
        try{wakeLock?.takeIf {it.isHeld}?.release()}catch(_:Exception){}
        wakeLock=null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        commands.shutdownNow()
        proc?.takeIf {it.isAlive}?.destroy()
        try{wakeLock?.takeIf {it.isHeld}?.release()}catch(_:Exception){}
        super.onDestroy()
    }
}
