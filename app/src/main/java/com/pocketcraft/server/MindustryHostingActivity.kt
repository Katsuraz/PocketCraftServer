package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Mindustry's separate experimental Homestead-themed game hosting screen. */
class MindustryHostingActivity : Activity() {
    private val ui=PocketUi
    private val handler=Handler(Looper.getMainLooper())
    private val reader=Executors.newSingleThreadExecutor()
    private val readPending=AtomicBoolean(false)
    private var stateView:TextView?=null
    private var logView:TextView?=null
    private var resultView:TextView?=null
    private var input:EditText?=null
    private var lastStamp=""
    private var installing=false
    private var progressMessage=""
    private var stateText=""
    private val ticker=object:Runnable {
        override fun run() {
            if(isFinishing || isDestroyed)return
            val latest=MindustryHostService.status+" • "+when(MindustryHostService.status) {
                "STARTING"->"Starting the Android Java engine"
                "RUNNING"->"JVM started; waiting for Mindustry to load"
                "READY"->"Server loaded; send 'host' to begin a map"
                "HOSTING"->"Mindustry reported that hosting started"
                "ERROR"->MindustryHostService.error
                else->"Offline"
            }
            if(latest!=stateText){stateText=latest;stateView?.text=latest}
            resultView?.text=progressMessage.ifEmpty {MindustryHostService.commandStatus}
            val f=MindustryHostFiles.log(this@MindustryHostingActivity)
            val stamp=f.length().toString()+"|"+f.lastModified()
            if(stamp!=lastStamp && readPending.compareAndSet(false,true)) {
                lastStamp=stamp
                reader.execute {
                    val data=MindustryHostFiles.readTail(this@MindustryHostingActivity)
                    handler.post {
                        readPending.set(false)
                        if(!isFinishing && !isDestroyed)logView?.text=data
                    }
                }
            }
            handler.postDelayed(this,2200)
        }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        ui.loadTheme(this)
        PocketSounds.prepare(this)
        window.statusBarColor=ui.BG
        window.navigationBarColor=ui.BG
        render()
    }
    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }
    override fun onPause(){handler.removeCallbacks(ticker);super.onPause()}
    override fun onDestroy(){handler.removeCallbacks(ticker);reader.shutdownNow();super.onDestroy()}
    private fun notifyUser(msg:String)=Toast.makeText(this,msg,Toast.LENGTH_LONG).show()
    private fun dispatch(action:String,command:String?=null):Boolean {
        val i=Intent(this,MindustryHostService::class.java).setAction(action)
        if(command!=null)i.putExtra(MindustryHostService.EXTRA_COMMAND,command)
        return try {
            if(action==MindustryHostService.ACTION_START)startForegroundService(i)
            else startService(i)
            true
        }catch(e:Exception){
            notifyUser("Game service error: "+(e.message?:"unknown"));false
        }
    }
    private fun send(raw:String) {
        val cmd=runCatching {ConsoleCommands.normalize(raw)}.getOrElse {
            notifyUser(it.message?:"Invalid command");return
        }
        if(!MindustryHostService.running) {
            notifyUser("Start Mindustry before sending commands");return
        }
        if(dispatch(MindustryHostService.ACTION_COMMAND,cmd)) {
            progressMessage=""
            resultView?.text="Queued: "+cmd
            input?.text?.clear()
        }
    }
    private fun updateProgress(msg:String) {
        handler.post {
            if(!isFinishing && !isDestroyed) {
                progressMessage=msg
                resultView?.text=msg
            }
        }
    }
    private fun install(label:String,action:()->Unit) {
        if(installing || MindustryHostService.active()){
            notifyUser("Stop Mindustry before changing the server JAR");return
        }
        installing=true
        updateProgress(label)
        Thread({
            val result=runCatching {action()}
            handler.post {
                installing=false
                if(!isFinishing && !isDestroyed) {
                    progressMessage=result.fold(
                        onSuccess={"Mindustry dedicated server installed and verified."},
                        onFailure={"Installation failed: "+(it.message?:"unknown")}
                    )
                    notifyUser(progressMessage)
                    render()
                }
            }
        },"PocketCraft-Mindustry-installer").start()
    }
    private fun installOfficial() {
        AlertDialog.Builder(this)
            .setTitle("Download official Mindustry server?")
            .setMessage("Download the dedicated server JAR from Anuken's official GitHub release "+MindustryHostFiles.OFFICIAL_VERSION+
                ". PocketCraft checks its pinned SHA-256 before installation. No Minecraft files are changed.")
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("DOWNLOAD") {_,_->
                install("Downloading official Mindustry") {
                    MindustryHostFiles.downloadOfficial(this) {updateProgress(it)}
                }
            }.show()
    }
    private fun importJar() {
        if(installing || MindustryHostService.active()){
            notifyUser("Stop Mindustry first");return
        }
        AlertDialog.Builder(this)
            .setTitle("Import Mindustry server JAR")
            .setMessage("Only choose server JARs you trust. This Java program can access PocketCraft's app permissions. The imported JAR must identify Mindustry's dedicated server main class.")
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("SELECT FILE"){_,_->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),721)
            }.show()
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==721 && resultCode==RESULT_OK) {
            val uri=data?.data ?: return
            install("Importing and checking Mindustry JAR") {
                MindustryHostFiles.importJar(this,uri) {updateProgress(it)}
            }
        }
    }
    private fun memorySettings() {
        if(MindustryHostService.active()){notifyUser("Stop the game first");return}
        val mb=intArrayOf(512,768,1024,1536)
        val labels=mb.map{"$it MB Java heap"}.toTypedArray()
        val value=getSharedPreferences("pocketcraft",0).getInt("mindustryHeapMB",768)
        AlertDialog.Builder(this).setTitle("Game Java memory")
            .setSingleChoiceItems(labels,mb.indexOf(value).coerceAtLeast(0)){dialog,selected->
                getSharedPreferences("pocketcraft",0).edit().putInt("mindustryHeapMB",mb[selected]).apply()
                dialog.dismiss();render()
            }.setNegativeButton("CANCEL",null).show()
    }
    private fun render() {
        val page=ui.column(this).apply {setBackgroundColor(ui.BG)}
        val view=ScrollView(this)
        val body=ui.column(this).apply {
            setPadding(ui.dp(this@MindustryHostingActivity,17),
                ui.dp(this@MindustryHostingActivity,18),
                ui.dp(this@MindustryHostingActivity,17),
                ui.dp(this@MindustryHostingActivity,25))
        }
        body.addView(ui.action(this,"‹ BACK TO POCKETCRAFT") {finish()})
        body.addView(ui.space(this,14))
        body.addView(ui.text(this,"Experimental Game Lab",22f,ui.WHITE,true))
        body.addView(ui.space(this,5))
        body.addView(ui.text(this,"Mindustry • experimental Android Java hosting",12f,ui.MUTED))
        body.addView(ui.space(this,13))
        val serverCard=ui.card(this)
        serverCard.addView(ui.pill(this,"EXPERIMENTAL • LAN FIRST",ui.CYAN))
        serverCard.addView(ui.space(this,9))
        serverCard.addView(ui.text(this,"Mindustry Dedicated Server",17f,ui.WHITE,true))
        serverCard.addView(ui.space(this,8))
        serverCard.addView(ui.text(this,MindustryHostService.status,12f,ui.MUTED).also {
            stateView=it
            stateText=""
        })
        serverCard.addView(ui.space(this,9))
        serverCard.addView(ui.text(this,
            if(MindustryHostFiles.installed(this))"✓ Mindustry server JAR installed"
            else "Server JAR not installed",12f,ui.MUTED))
        serverCard.addView(ui.space(this,8))
        val installButtons=ui.row(this)
        installButtons.addView(ui.action(this,"INSTALL OFFICIAL",true,
            !installing && !MindustryHostService.active()){installOfficial()},ui.weight(this))
        installButtons.addView(ui.action(this,"IMPORT JAR",false,
            !installing && !MindustryHostService.active()){importJar()},
            LinearLayout.LayoutParams(0,-2,1f))
        serverCard.addView(installButtons)
        serverCard.addView(ui.space(this,8))
        val actions=ui.row(this)
        // Keep both controls wired even when the game starts/stops after
        // initial render; PocketUi.action only attaches listeners if enabled.
        actions.addView(ui.action(this,"▶ START GAME",true) {
            when {
                installing -> notifyUser("Finish the JAR installation first")
                MindustryHostService.active() -> notifyUser("Mindustry is already running or starting")
                ServerService.running || ServerService.status in listOf("STARTING","STOPPING") ->
                    notifyUser("Stop Minecraft completely before hosting Mindustry")
                !MindustryHostFiles.installed(this) ->
                    notifyUser("Install the official Mindustry server JAR first")
                !RuntimeStore.isInstalled(this) ->
                    notifyUser("Install Android Java under Home → Advanced & Setup first")
                else -> dispatch(MindustryHostService.ACTION_START)
            }
        },ui.weight(this))
        actions.addView(ui.action(this,"■ STOP") {
            if(MindustryHostService.active())dispatch(MindustryHostService.ACTION_STOP)
            else notifyUser("Mindustry is already stopped")
        },LinearLayout.LayoutParams(0,-2,1f))
        serverCard.addView(actions)
        serverCard.addView(ui.space(this,8))
        serverCard.addView(ui.action(this,"MEMORY SETTINGS") {memorySettings()})
        serverCard.addView(ui.space(this,7))
        serverCard.addView(ui.text(this,"Stop Minecraft completely before starting Mindustry. Your Minecraft worlds, mods, and Playit tunnels are not changed.",11f,ui.MUTED))
        body.addView(serverCard)
        body.addView(ui.space(this,12))

        val lan=ui.card(this)
        lan.addView(ui.text(this,"CONNECT ON LOCAL WI-FI",11f,ui.CYAN,true))
        lan.addView(ui.space(this,6))
        val ip=NetworkInfo.lanAddress()
        lan.addView(ui.text(this,if(ip!=null)"$ip:6567 (default)"
            else "Your phone's Wi-Fi IP:6567",16f,ui.WHITE,true))
        lan.addView(ui.space(this,6))
        lan.addView(ui.text(this,"Use 'host' to open a Mindustry map after the Java process loads. Default port 6567 uses TCP and UDP. Address display is not a port test. Your Minecraft tunnel does not automatically support this game.",11f,ui.MUTED))
        body.addView(lan)
        body.addView(ui.space(this,12))

        val console=ui.card(this)
        console.addView(ui.text(this,"MINDUSTRY COMMAND PANEL",12f,ui.CYAN,true))
        console.addView(ui.space(this,8))
        val command=EditText(this).apply {
            hint="Try: host, maps all, players, status"
            setSingleLine(true)
            textSize=14f
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background=ui.shape(this@MindustryHostingActivity,ui.SURFACE,10,ui.BORDER)
            setPadding(ui.dp(this@MindustryHostingActivity,12),
                ui.dp(this@MindustryHostingActivity,12),
                ui.dp(this@MindustryHostingActivity,12),
                ui.dp(this@MindustryHostingActivity,12))
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        }
        input=command
        console.addView(command)
        command.setOnEditorActionListener{_,id,event->
            if(id==android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode==android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action==android.view.KeyEvent.ACTION_DOWN)){
                send(command.text.toString());true
            }else false
        }
        console.addView(ui.space(this,7))
        console.addView(ui.action(this,"SEND COMMAND →",true){send(command.text.toString())})
        console.addView(ui.space(this,7))
        console.addView(ui.text(this,progressMessage.ifEmpty {
            MindustryHostService.commandStatus
        },11f,ui.MUTED).also{resultView=it})
        console.addView(ui.space(this,11))
        fun quick(label:String,cmd:String)=ui.action(this,label){send(cmd)}
        val one=ui.row(this)
        one.addView(quick("HOST MAP","host"),ui.weight(this))
        one.addView(quick("MAP LIST","maps all"),LinearLayout.LayoutParams(0,-2,1f))
        console.addView(one)
        console.addView(ui.space(this,6))
        val two=ui.row(this)
        two.addView(quick("PLAYERS","players"),ui.weight(this))
        two.addView(quick("STATUS","status"),LinearLayout.LayoutParams(0,-2,1f))
        console.addView(two)
        console.addView(ui.space(this,12))
        console.addView(ui.text(this,"RECENT GAME LOG",11f,ui.CYAN,true))
        console.addView(ui.space(this,6))
        val log=ui.text(this,MindustryHostFiles.readTail(this),11f,ui.WHITE).apply {
            typeface=android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(false)
            maxLines=90
        }
        logView=log
        console.addView(log)
        console.addView(ui.space(this,9))
        console.addView(ui.action(this,"COPY GAME LOG") {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Mindustry recent log",
                    MindustryHostFiles.readTail(this)))
            notifyUser("Copied log. Review before sharing.")
        })
        body.addView(console)
        body.addView(ui.space(this,12))

        val limitations=ui.card(this)
        limitations.addView(ui.text(this,"EXPERIMENTAL LIMITATIONS",11f,ui.CYAN,true))
        limitations.addView(ui.space(this,7))
        limitations.addView(ui.text(this,
            "First adapter: Mindustry's official Java dedicated-server JAR. PC-only native server software is not automatically compatible with Android. The game must still be tested on your phone; GitHub build success does not prove multiplayer works.",
            12f,ui.MUTED))
        body.addView(limitations)
        view.addView(body)
        page.addView(view,LinearLayout.LayoutParams(-1,-1))
        ui.styleFormInputs(page)
        setContentView(page)
    }
}
