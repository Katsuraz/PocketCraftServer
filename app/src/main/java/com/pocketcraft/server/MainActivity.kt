package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/** PocketCraft's offline-first dashboard. Does not pretend that a JVM is available on Android. */
class MainActivity : Activity() {
    private val ui = PocketUi
    private val base by lazy { WorldStore.root(this) }
    private val mods by lazy { File(base,"mods").apply { mkdirs() } }
    private var tab = 0
    private var pendingBackup: File? = null
    private val serverJar get() = File(base, "fabric-server-launch.jar")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ui.BG
        window.navigationBarColor = ui.BG
        window.decorView.systemUiVisibility = 0
        tab = savedInstanceState?.getInt("tab") ?: 0
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab); super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); render() }

    private fun h(label: String, sub: String? = null): LinearLayout {
        val item = ui.column(this)
        item.addView(ui.text(this, label, 21f, ui.WHITE, true))
        if (sub != null) {
            item.addView(ui.space(this, 5))
            item.addView(ui.text(this, sub, 12f, ui.MUTED))
        }
        return item
    }
    private fun render() {
        val screen = ui.column(this).apply { setBackgroundColor(ui.BG) }
        val top = ui.row(this).apply { ui.run { padding(this@MainActivity, 20, 19, 20, 13) } }
        top.addView(ui.text(this,"▦", 28f, ui.GREEN,true).apply {
            background = ui.shape(this@MainActivity, ui.SURFACE, 12)
            ui.run { padding(this@MainActivity, 9, 7, 9, 7) }
        })
        top.addView(ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,12),0,0,0)
            addView(ui.text(this@MainActivity,"POCKETCRAFT",17f,ui.WHITE,true))
            addView(ui.text(this@MainActivity,"YOUR WORLD, EVERYWHERE",9f,ui.MUTED,true))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(ui.text(this,"⋮",26f,ui.MUTED,true).apply {
            setOnClickListener { about() }; contentDescription="About and setup"
            ui.run { padding(this@MainActivity, 10, 3, 10, 3) }
        })
        screen.addView(top)
        val scroller = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val body = ui.column(this).apply { setPadding(ui.dp(this@MainActivity,18), ui.dp(this@MainActivity,7), ui.dp(this@MainActivity,18), ui.dp(this@MainActivity,18)) }
        when(tab) {
            0 -> dashboard(body)
            1 -> modsPage(body)
            2 -> worldsPage(body)
            else -> consolePage(body)
        }
        scroller.addView(body)
        screen.addView(scroller, LinearLayout.LayoutParams(-1,0,1f))
        val navbar = ui.row(this).apply {
            background = ui.shape(this@MainActivity, 0xFF101A2D.toInt(), 0)
            setPadding(ui.dp(this@MainActivity,6),ui.dp(this@MainActivity,10),ui.dp(this@MainActivity,6),ui.dp(this@MainActivity,12))
        }
        val icons = listOf("◈", "⬡", "▧", "⌘")
        val labels = listOf("HOME", "MODS", "WORLDS", "CONSOLE")
        repeat(4) { i ->
            val item = ui.column(this)
            item.gravity = Gravity.CENTER
            item.addView(ui.text(this,icons[i],24f,if(i==tab) ui.GREEN else ui.MUTED,true).apply { gravity=Gravity.CENTER })
            item.addView(ui.space(this,5))
            item.addView(ui.text(this,labels[i],10f,if(i==tab) ui.GREEN else ui.MUTED,true).apply { gravity=Gravity.CENTER })
            item.setOnClickListener { tab=i; render() }
            navbar.addView(item,LinearLayout.LayoutParams(0,ui.dp(this,52),1f))
        }
        screen.addView(navbar)
        setContentView(screen)
    }
    private fun dashboard(body: LinearLayout) {
        body.addView(h("Your pocket server","A cozy home for your Minecraft worlds"))
        body.addView(ui.space(this,16))
        val hero = ui.column(this).apply {
            background = ui.gradient(this@MainActivity,0xFF29366A.toInt(),0xFF17354D.toInt(),0xFF145044.toInt())
            ui.run { padding(this@MainActivity,20,18,20,18) }
        }
        val header = ui.row(this)
        header.addView(ui.pill(this,"●  OFFLINE",ui.RED))
        header.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        header.addView(ui.pill(this,"FABRIC  •  JAVA + BEDROCK",ui.CYAN))
        hero.addView(header)
        hero.addView(ui.space(this,10))
        hero.addView(ui.text(this,"Your next adventure\nstarts here.",25f,ui.WHITE,true))
        hero.addView(WorldArt(this))
        hero.addView(ui.text(this,"Server engine setup required",13f,ui.WHITE,true))
        hero.addView(ui.space(this,6))
        hero.addView(ui.text(this,"Android needs a native Java runtime before Fabric can actually start. Importing JARs alone is not enough.",12f,0xFFB7CFE0.toInt()))
        hero.addView(ui.space(this,16))
        hero.addView(ui.action(this,"VIEW SETUP STATUS  →",true) { setup() })
        body.addView(hero)
        body.addView(ui.space(this,16))
        body.addView(h("Overview"))
        body.addView(ui.space(this,11))
        val row = ui.row(this)
        row.addView(stat("0 / 5", "PLAYERS", "♟"), ui.weight(this))
        row.addView(stat(modFiles().count { it.name.endsWith(".jar") }.toString(), "MODS", "⬡"), LinearLayout.LayoutParams(0,-2,1f))
        body.addView(row)
        body.addView(ui.space(this,10))
        val row2 = ui.row(this)
        row2.addView(stat(WorldStore.worlds(this).size.toString(), "WORLDS", "▧"),ui.weight(this))
        row2.addView(stat("2 GB", "RAM TARGET", "◉"),LinearLayout.LayoutParams(0,-2,1f))
        body.addView(row2)
        body.addView(ui.space(this,17))
        body.addView(h("Quick actions"))
        body.addView(ui.space(this,11))
        val actions = ui.card(this)
        actions.addView(actionRow("⚙","Server settings","Difficulty, players, world options") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        actions.addView(line())
        actions.addView(actionRow("✦","Download Fabric server","Official Fabric download • select Minecraft version") { installFabricPrompt() })
        actions.addView(line())
        actions.addView(actionRow("↓","Import Fabric server","Choose an existing Fabric server launcher JAR") { picker(1) })
        actions.addView(line())
        actions.addView(actionRow("◈","Manage mod library","Import, disable, and remove JAR files") {tab=1;render()})
        actions.addView(line())
        actions.addView(actionRow("▧","World vault","Backups, ZIP imports, and active world") {tab=2;render()})
        body.addView(actions)
        body.addView(ui.space(this,14))
        val note = ui.card(this)
        note.addView(ui.pill(this,"COMPATIBILITY CHECK",ui.PURPLE))
        note.addView(ui.space(this,9))
        note.addView(ui.text(this,"Crossplay is not configured yet",15f,ui.WHITE,true))
        note.addView(ui.space(this,7))
        note.addView(ui.text(this,"Geyser-Fabric + Floodgate need builds that match your Fabric server version. Most client-required content mods cannot be used by Bedrock players.",12f,ui.MUTED))
        body.addView(note)
    }
    private fun stat(value:String, label:String, symbol:String):LinearLayout {
        val card=ui.card(this)
        val row=ui.row(this)
        row.addView(ui.text(this,symbol,17f,ui.GREEN,true))
        row.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        card.addView(row);card.addView(ui.space(this,8))
        card.addView(ui.text(this,value,23f,ui.WHITE,true))
        card.addView(ui.space(this,5))
        card.addView(ui.text(this,label,10f,ui.MUTED,true))
        return card
    }
    private fun line():View = View(this).apply {
        setBackgroundColor(0xFF26354C.toInt())
        layoutParams=LinearLayout.LayoutParams(-1,ui.dp(this@MainActivity,1)).apply {
            topMargin=ui.dp(this@MainActivity,12);bottomMargin=ui.dp(this@MainActivity,12)
        }
    }
    private fun actionRow(icon:String,title:String,desc:String,click:()->Unit):LinearLayout {
        val row=ui.row(this)
        row.addView(ui.text(this,icon,22f,ui.CYAN,true).apply {
            gravity=Gravity.CENTER
            background=ui.shape(this@MainActivity,ui.SURFACE,12)
            layoutParams=LinearLayout.LayoutParams(ui.dp(this@MainActivity,46),ui.dp(this@MainActivity,46))
        })
        val labels=ui.column(this).apply { setPadding(ui.dp(this@MainActivity,12),0,0,0) }
        labels.addView(ui.text(this,title,14f,ui.WHITE,true))
        labels.addView(ui.space(this,4))
        labels.addView(ui.text(this,desc,11f,ui.MUTED))
        row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(ui.text(this,"›",24f,ui.MUTED))
        row.setOnClickListener {click()}
        row.contentDescription=title
        return row
    }
    private fun modFiles():List<File> =
        mods.listFiles()?.filter {it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".jar.disabled"))}
            ?.sortedBy { it.name.lowercase(Locale.ROOT) } ?: emptyList()

    private fun modsPage(body:LinearLayout) {
        body.addView(h("Mod library","Manage your Fabric server add-ons"))
        body.addView(ui.space(this,15))
        body.addView(ui.action(this,"+  IMPORT MOD JAR",true) {picker(2)})
        body.addView(ui.space(this,10))
        body.addView(ui.action(this,"↓  DOWNLOAD GEYSER + FLOODGATE") {
            AlertDialog.Builder(this).setTitle("Install crossplay bridge?")
                .setMessage("Downloads the latest Geyser-Fabric and Floodgate JARs for Minecraft Java 26.3. Fabric API and a working Android JVM are also required. Existing matching JARs may be replaced.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Download") {_,_-> downloadAsync("crossplay") {ServerDownloads.installCrossplay(this)} }
                .show()
        })
        body.addView(ui.space(this,12))
        val list=modFiles()
        body.addView(ui.text(this,list.size.toString() + " total  •  " + list.count {it.name.endsWith(".jar")} + " enabled",12f,ui.MUTED))
        body.addView(ui.space(this,12))
        if (list.isEmpty()) {
            body.addView(empty("⬡","A blank slate","Import .jar mods that support your server's Fabric and Minecraft versions."))
        }
        list.forEach { file ->
            val enabled=file.name.endsWith(".jar")
            val card=ui.card(this)
            val top=ui.row(this)
            top.addView(ui.text(this,"⬡",22f,ui.PURPLE,true))
            top.addView(ui.text(this,file.name.removeSuffix(".disabled"),13f,ui.WHITE,true).apply {
                setPadding(ui.dp(this@MainActivity,10),0,0,0); maxLines=2
            },LinearLayout.LayoutParams(0,-2,1f))
            top.addView(ui.pill(this,if(enabled)"ON" else "OFF",if(enabled)ui.GREEN else ui.MUTED))
            card.addView(top)
            card.addView(ui.space(this,10))
            val lower=ui.row(this)
            lower.addView(ui.action(this,if(enabled)"Disable" else "Enable") {
                val target=File(mods,if(enabled) file.name+".disabled" else file.name.removeSuffix(".disabled"))
                if(target.exists() || !file.renameTo(target)) toast("Could not change mod status") else render()
            },ui.weight(this))
            lower.addView(ui.action(this,"Delete") {
                AlertDialog.Builder(this).setTitle("Delete mod?")
                    .setMessage(file.name).setNegativeButton("Cancel",null)
                    .setPositiveButton("Delete"){_,_->if(file.delete())render()else toast("Unable to delete")}.show()
            },LinearLayout.LayoutParams(0,-2,1f))
            card.addView(lower)
            body.addView(card); body.addView(ui.space(this,10))
        }
        val help=ui.card(this)
        help.addView(ui.text(this,"Crossplay checklist",16f,ui.WHITE,true))
        help.addView(ui.space(this,8))
        listOf("Fabric API","Geyser-Fabric","Floodgate-Fabric").forEach { name ->
            val installed=list.any { it.name.contains(name.substringBefore("-"),true) && it.name.endsWith(".jar") }
            help.addView(ui.text(this,(if(installed)"✓  " else "○  ") + name,
                13f,if(installed)ui.GREEN else ui.MUTED))
            help.addView(ui.space(this,7))
        }
        help.addView(ui.text(this,"File presence does not confirm version compatibility or that the server can start.",11f,ui.MUTED))
        body.addView(help)
    }
    private fun worldsPage(body:LinearLayout) {
        body.addView(h("World vault","Protect every block you've placed"))
        body.addView(ui.space(this,15))
        body.addView(ui.action(this,"+  IMPORT WORLD ZIP",true) {picker(3)})
        body.addView(ui.space(this,16))
        val list=WorldStore.worlds(this)
        val current=WorldStore.active(this)
        if (list.isEmpty()) body.addView(empty("▧","No worlds yet","Import a Java Edition world ZIP containing level.dat."))
        list.forEach { file ->
            val card=ui.card(this)
            val heading=ui.row(this)
            heading.addView(ui.text(this,"▧",23f,ui.GREEN,true))
            heading.addView(ui.text(this,file.name,15f,ui.WHITE,true).apply {
                setPadding(ui.dp(this@MainActivity,9),0,0,0)
            },LinearLayout.LayoutParams(0,-2,1f))
            if(file.name==current) heading.addView(ui.pill(this,"ACTIVE",ui.GREEN))
            card.addView(heading)
            card.addView(ui.space(this,11))
            val controls=ui.row(this)
            controls.addView(ui.action(this,"Use world") {
                try { WorldStore.activate(this,file); render();toast("Active world changed") }
                catch(e:Exception) { toast(e.message ?: "Unable to activate") }
            },ui.weight(this))
            controls.addView(ui.action(this,"Export ZIP") { pendingBackup=file;createBackupDocument(file) },
                LinearLayout.LayoutParams(0,-2,1f))
            card.addView(controls)
            card.addView(ui.space(this,7))
            card.addView(ui.text(this,"Delete world",12f,ui.RED).apply {
                gravity=Gravity.END
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("Delete this world?")
                        .setMessage("This permanently deletes "+file.name+". Export a backup first.")
                        .setNegativeButton("Cancel",null)
                        .setPositiveButton("Delete") {_,_->if(file.deleteRecursively()) render() else toast("Could not delete world")}
                        .show()
                }
            })
            body.addView(card);body.addView(ui.space(this,10))
        }
        val note=ui.card(this)
        note.addView(ui.text(this,"World safety",15f,ui.WHITE,true))
        note.addView(ui.space(this,8))
        note.addView(ui.text(this,"Exports are saved to a location you choose. Imports use path checks and a 1 GB extracted-size limit. Always back up a world before replacing it.",12f,ui.MUTED))
        body.addView(note)
    }
    private fun consolePage(body:LinearLayout) {
        body.addView(h("Server console","Real logs, no pretend online status"))
        body.addView(ui.space(this,16))
        val actions=ui.row(this)
        actions.addView(ui.action(this,"↻  Refresh",true){render()},ui.weight(this))
        actions.addView(ui.action(this,"Copy log"){
            val manager=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("PocketCraft logs",getLog()))
            toast("Log copied")
        },LinearLayout.LayoutParams(0,-2,1f))
        body.addView(actions)
        body.addView(ui.space(this,13))
        val log=ui.column(this).apply {
            background=ui.shape(this@MainActivity,0xFF0F1728.toInt(),18,0xFF26354D.toInt())
            ui.run {padding(this@MainActivity,16,16,16,16)}
        }
        log.addView(ui.pill(this,"●  LOCAL LOG",ui.CYAN))
        log.addView(ui.space(this,12))
        log.addView(ui.text(this,getLog(),12f,0xFFC0D7D7.toInt()))
        body.addView(log)
        body.addView(ui.space(this,15))
        body.addView(ui.text(this,"Command input becomes available when a compatible server runtime is integrated and running.",12f,ui.MUTED))
    }
    private fun getLog():String {
        val file=File(base,"console.log")
        return if(file.exists()) file.readText().takeLast(12000) else
            "[PocketCraft] No server process has been started.\n[Setup] Android Java runtime is not bundled in this build.\n[Tip] Imported JARs are stored safely for later use."
    }
    private fun empty(symbol:String,title:String,detail:String):LinearLayout=ui.card(this).apply {
        gravity=Gravity.CENTER_HORIZONTAL
        addView(ui.text(this@MainActivity,symbol,36f,ui.PURPLE,true))
        addView(ui.space(this@MainActivity,11))
        addView(ui.text(this@MainActivity,title,17f,ui.WHITE,true))
        addView(ui.space(this@MainActivity,7))
        addView(ui.text(this@MainActivity,detail,12f,ui.MUTED).apply {gravity=Gravity.CENTER})
    }
    private fun installFabricPrompt() {
        val versions=arrayOf("26.3  •  latest, Java 25","1.21.11  •  Java 21","1.20.1  •  Java 17")
        val values=arrayOf("26.3","1.21.11","1.20.1")
        AlertDialog.Builder(this)
            .setTitle("Choose Minecraft version")
            .setMessage("Downloads the official Fabric server launcher. This does not install an Android Java runtime.")
            .setItems(versions) {_,which->
                val version=values[which]
                AlertDialog.Builder(this).setTitle("Install Fabric "+version+"?")
                    .setMessage("The launcher will be downloaded from meta.fabricmc.net into PocketCraft app storage. An existing launcher JAR will be replaced. Your worlds and mods will not be deleted.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Install") {_,_->
                        downloadAsync("Fabric "+version) {ServerDownloads.installFabric(this,version)}
                    }.show()
            }.show()
    }
    private fun downloadAsync(label:String, action:()->String) {
        val waiting=AlertDialog.Builder(this).setTitle("Downloading "+label)
            .setMessage("Retrieving files from the official project. Network connection required.")
            .setCancelable(false).create()
        waiting.show()
        Thread {
            val outcome=try {Result.success(action())}catch(e:Exception){Result.failure<String>(e)}
            runOnUiThread {
                waiting.dismiss()
                outcome.onSuccess {message->
                    AlertDialog.Builder(this).setTitle("Download complete")
                        .setMessage(message).setPositiveButton("OK",null).show()
                    render()
                    if(label.startsWith("Fabric") && !ServerDownloads.eulaAccepted(this)) offerEula()
                }.onFailure {error->
                    AlertDialog.Builder(this).setTitle("Download failed")
                        .setMessage(error.message ?: "Please check your connection and try again.")
                        .setPositiveButton("OK",null).show()
                }
            }
        }.start()
    }
    private fun offerEula() {
        AlertDialog.Builder(this).setTitle("Minecraft EULA")
            .setMessage("Before starting a Minecraft server, review and agree to Mojang's EULA at minecraft.net/en-us/eula. The app will only write eula=true if you explicitly agree.")
            .setNeutralButton("Read EULA") {_,_->
                startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.minecraft.net/en-us/eula")))
            }
            .setNegativeButton("Not now",null)
            .setPositiveButton("I have read and agree") {_,_->
                ServerDownloads.acceptEula(this);toast("EULA acceptance saved")
            }.show()
    }
    private fun setup() {
        AlertDialog.Builder(this).setTitle("Server engine status")
            .setMessage("Fabric JAR: "+(if(serverJar.isFile)"Imported" else "Missing")+
                "\nAndroid Java runtime: Not integrated"+
                "\nEULA acceptance: "+(if(ServerDownloads.eulaAccepted(this))"Recorded" else "Required before launch")+
                "\nGeyser/Floodgate: Requires compatible Fabric versions"+
                "\nInternet tunnel: Not configured"+
                "\n\nThis APK is an offline file manager and server control foundation. It cannot host Minecraft yet.")
            .setPositiveButton("OK",null).show()
    }
    private fun about() {
        AlertDialog.Builder(this).setTitle("PocketCraft Server 0.2")
            .setMessage("A native, dark-mode Minecraft server manager for Android. File imports, mod activation, world backups, and server property editing work offline. Running a Java server, player telemetry, internet tunneling, and crossplay are still under development.")
            .setPositiveButton("Got it",null).show()
    }
    private fun picker(code:Int) {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type=if(code==3)"application/zip" else "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent,code)
    }
    private fun createBackupDocument(world:File) {
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type="application/zip"
            putExtra(Intent.EXTRA_TITLE,world.name+"-backup.zip")
        }
        startActivityForResult(intent,4)
    }
    private fun displayName(uri:android.net.Uri):String {
        var value:String?=null
        contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
            if(cursor.moveToFirst()) value=cursor.getString(0)
        }
        return value ?: "imported-"+System.currentTimeMillis()+".jar"
    }
    private fun safeName(original:String):String=original.replace(Regex("[^A-Za-z0-9._-]"),"_")
        .take(120).trimStart('.')
    private fun importJar(uri:android.net.Uri,kind:Int) {
        val filename=safeName(displayName(uri))
        if(!filename.endsWith(".jar",true)) {toast("Please select a .jar file");return}
        val target=if(kind==1) serverJar else File(mods,filename)
        if(kind==2 && target.exists()) {toast("Mod already imported");return}
        Thread {
            val temp=File(base,"import-"+System.currentTimeMillis()+".tmp")
            try {
                var length=0L
                val input=contentResolver.openInputStream(uri) ?: error("Cannot open file")
                input.use { source ->
                    temp.outputStream().use { output ->
                        val data=ByteArray(32768)
                        while(true) {
                            val n=source.read(data)
                            if(n<0) break
                            length+=n
                            if(length>250L*1024L*1024L) error("JAR exceeds 250 MB import limit")
                            output.write(data,0,n)
                        }
                    }
                }
                temp.inputStream().use { stream ->
                    val magic=ByteArray(2)
                    if(stream.read(magic)!=2 || magic[0]!=0x50.toByte() || magic[1]!=0x4B.toByte())
                        error("Not a valid JAR/ZIP file")
                }
                val oldFile=File(base,"previous-fabric-server.jar")
                if(kind==1 && target.exists()) {
                    oldFile.delete()
                    if(!target.renameTo(oldFile)) error("Could not preserve previous server JAR")
                }
                if(!temp.renameTo(target)) {
                    if(kind==1 && oldFile.exists()) oldFile.renameTo(target)
                    error("Unable to install imported JAR")
                }
                runOnUiThread {toast("Imported "+filename);render()}
            } catch(e:Exception) {runOnUiThread {toast("Import failed: "+e.message)}}
            finally {temp.delete()}
        }.start()
    }
    @Deprecated("Activity result callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK) return
        val uri=data?.data ?: return
        when(requestCode) {
            1,2 -> importJar(uri,requestCode)
            3 -> {
                Thread {
                    try {
                        val imported=WorldStore.importZip(this,uri)
                        runOnUiThread {toast("World imported: "+imported.name);tab=2;render()}
                    } catch(e:Exception) {runOnUiThread {toast("World import failed: "+e.message)}}
                }.start()
            }
            4 -> {
                val world=pendingBackup ?: return
                pendingBackup=null
                Thread {
                    try {
                        WorldStore.backup(this,world,uri)
                        runOnUiThread {toast("Backup exported successfully")}
                    } catch(e:Exception) {runOnUiThread {toast("Backup error: "+e.message)}}
                }.start()
            }
        }
    }
    private fun toast(value:String) = Toast.makeText(this,value,Toast.LENGTH_LONG).show()
}
