package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native, in-app browser for individual Modrinth Fabric mods.
 * No copy/paste URLs, WebView, API key, or third-party client is required.
 */
class ModrinthModsActivity : Activity() {
    private val ui=PocketUi
    private lateinit var query:EditText
    private lateinit var results:LinearLayout
    private lateinit var sortControl:TextView
    private lateinit var categoryControl:TextView
    private lateinit var versionLabel:TextView
    private lateinit var versionOnly:CheckBox
    private lateinit var serverOnly:CheckBox
    private lateinit var scroll:ScrollView
    private var category:String?=null
    private var ordering="downloads"
    private var requestSequence=0

    private val categories=listOf(
        "All" to null,
        "Performance" to "optimization",
        "World generation" to "worldgen",
        "Utilities" to "utility",
        "Adventure" to "adventure",
        "Mobs" to "mobs",
        "Building & decor" to "decoration",
        "Storage" to "storage",
        "Technology" to "technology",
        "Equipment" to "equipment",
        "Food" to "food",
        "Game mechanics" to "game-mechanics",
        "Multiplayer" to "social"
    )
    private val sorts=listOf(
        "Popular" to "downloads",
        "Relevant" to "relevance",
        "Recently updated" to "updated",
        "Newest" to "newest"
    )

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=ui.BG
        window.navigationBarColor=ui.BG
        buildScreen()
        search(0)
    }

    private fun toast(message:String) {
        Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    }

    private fun buildScreen() {
        val info=ServerVersionManager.current(this)
        val root=ui.column(this).apply {setBackgroundColor(ui.BG)}
        val bar=ui.row(this).apply {
            setPadding(ui.dp(this@ModrinthModsActivity,18),ui.dp(this@ModrinthModsActivity,19),
                ui.dp(this@ModrinthModsActivity,18),ui.dp(this@ModrinthModsActivity,14))
        }
        val back=ui.action(this,"‹") {finish()}
        bar.addView(back,LinearLayout.LayoutParams(ui.dp(this,52),ui.dp(this,52)))
        bar.addView(ui.space(this,12))
        val heading=ui.column(this)
        heading.addView(ui.text(this,"Find Fabric Mods",21f,ui.WHITE,true))
        heading.addView(ui.space(this,5))
        heading.addView(ui.text(this,"MODRINTH  •  IN-APP BROWSER",10f,ui.CYAN,true))
        bar.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        root.addView(bar)

        scroll=ScrollView(this).apply {
            isFillViewport=true
            isVerticalScrollBarEnabled=false
        }
        val content=ui.column(this).apply {
            setPadding(ui.dp(this@ModrinthModsActivity,16),0,
                ui.dp(this@ModrinthModsActivity,16),ui.dp(this@ModrinthModsActivity,28))
        }
        val filters=ui.card(this)
        filters.addView(ui.text(this,"Discover server mods",17f,ui.WHITE,true))
        filters.addView(ui.space(this,7))
        filters.addView(ui.text(this,
            "Search and install Fabric mods directly. PocketCraft checks releases against your Minecraft version and warns about client-only mods.",
            12f,ui.MUTED))
        filters.addView(ui.space(this,13))
        query=EditText(this).apply {
            hint="Search mods, e.g. Terralith or Lithium"
            setSingleLine(true)
            imeOptions=EditorInfo.IME_ACTION_SEARCH
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            textSize=15f
            background=ui.shape(this@ModrinthModsActivity,ui.SURFACE,13,ui.BORDER)
            setPadding(ui.dp(this@ModrinthModsActivity,12),ui.dp(this@ModrinthModsActivity,12),
                ui.dp(this@ModrinthModsActivity,12),ui.dp(this@ModrinthModsActivity,12))
            setOnEditorActionListener {_,actionId,_->
                if(actionId==EditorInfo.IME_ACTION_SEARCH) {
                    search(0);true
                }else false
            }
        }
        filters.addView(query)
        filters.addView(ui.space(this,10))
        val controls=ui.row(this)
        categoryControl=ui.action(this,"CATEGORY: ALL") {pickCategory()}
        sortControl=ui.action(this,"SORT: POPULAR") {pickSort()}
        controls.addView(categoryControl,ui.weight(this))
        controls.addView(sortControl,LinearLayout.LayoutParams(0,-2,1f))
        filters.addView(controls)
        filters.addView(ui.space(this,10))
        versionLabel=ui.text(this,versionInfo(info),12f,ui.CYAN,true)
        filters.addView(versionLabel)
        filters.addView(ui.space(this,9))
        versionOnly=CheckBox(this).apply {
            text="Only mods for my Minecraft version"
            setTextColor(ui.WHITE)
            isChecked=info.version!=null
            isEnabled=info.version!=null
        }
        filters.addView(versionOnly)
        serverOnly=CheckBox(this).apply {
            text="Hide mods marked client-only"
            setTextColor(ui.WHITE)
            isChecked=true
        }
        filters.addView(serverOnly)
        filters.addView(ui.space(this,8))
        filters.addView(ui.action(this,"SEARCH MODRINTH",true){search(0)})
        filters.addView(ui.space(this,9))
        filters.addView(ui.action(this,"CONFIRM EXISTING FABRIC VERSION"){confirmVersion()})
        filters.addView(ui.space(this,9))
        filters.addView(ui.text(this,
            "Modrinth's server/client labels are author-provided. Geyser cannot automatically convert custom Java items and blocks for Bedrock. Stop Minecraft before installing.",
            11f,ui.MUTED))
        content.addView(filters)
        content.addView(ui.space(this,17))
        results=ui.column(this)
        content.addView(results)
        scroll.addView(content)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }

    private fun versionInfo(info:ServerVersionManager.Info):String = when {
        !info.launcherPresent -> "Fabric server launcher: not installed"
        info.version==null -> "Fabric installed • Minecraft version unknown"
        else -> "Fabric "+info.version+" • "+info.source
    }

    private fun confirmVersion(after:(()->Unit)?=null) {
        val info=ServerVersionManager.current(this)
        if(!info.launcherPresent) {
            AlertDialog.Builder(this).setTitle("Fabric server missing")
                .setMessage("Install or import a Fabric launcher in PocketCraft first. Browsing mods is still available.")
                .setPositiveButton("OK",null).show()
            return
        }
        val panel=ui.column(this).apply {
            setPadding(ui.dp(this@ModrinthModsActivity,16),ui.dp(this@ModrinthModsActivity,10),
                ui.dp(this@ModrinthModsActivity,16),ui.dp(this@ModrinthModsActivity,10))
        }
        panel.addView(ui.text(this,
            "This only records the Minecraft version of your existing Fabric server. It does not download or replace anything. Check Console first if unsure.",
            12f,ui.MUTED))
        panel.addView(ui.space(this,12))
        val entry=EditText(this).apply {
            hint="Minecraft version, e.g. 26.3"
            setSingleLine(true)
            setText(info.version.orEmpty())
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background=ui.shape(this@ModrinthModsActivity,ui.SURFACE,12,ui.BORDER)
            setPadding(ui.dp(this@ModrinthModsActivity,12),ui.dp(this@ModrinthModsActivity,12),
                ui.dp(this@ModrinthModsActivity,12),ui.dp(this@ModrinthModsActivity,12))
        }
        panel.addView(entry)
        panel.addView(ui.space(this,11))
        val presets=ui.row(this)
        listOf("26.3","1.21.11","1.20.1").forEach {ver->
            presets.addView(ui.action(this,ver) {entry.setText(ver)},
                LinearLayout.LayoutParams(0,-2,1f))
        }
        panel.addView(presets)
        val dialog=AlertDialog.Builder(this).setTitle("Your installed Fabric version")
            .setView(panel).setNegativeButton("CANCEL",null)
            .setPositiveButton("SAVE",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val selected=entry.text.toString().trim()
                    if(info.source=="detected in Fabric server console" &&
                        info.version!=null && info.version!=selected) {
                        throw IllegalArgumentException("Server Console reports "+info.version+
                            ". Use the real Minecraft version, not a modpack's version.")
                    }
                    ServerVersionManager.confirm(this,selected)
                    dialog.dismiss()
                    val updated=ServerVersionManager.current(this)
                    versionLabel.text=versionInfo(updated)
                    versionOnly.isEnabled=updated.version!=null
                    versionOnly.isChecked=updated.version!=null
                    toast("Version saved without reinstalling Fabric.")
                    after?.invoke()
                }catch(e:Exception){entry.error=e.message ?: "Invalid Minecraft version"}
            }
        }
        dialog.show()
    }

    private fun pickCategory() {
        AlertDialog.Builder(this).setTitle("Mod category")
            .setItems(categories.map{it.first}.toTypedArray()){_,i->
                category=categories[i].second
                categoryControl.text="CATEGORY: "+categories[i].first.uppercase(Locale.ROOT)
                search(0)
            }.setNegativeButton("CANCEL",null).show()
    }

    private fun pickSort() {
        AlertDialog.Builder(this).setTitle("Sort by")
            .setItems(sorts.map{it.first}.toTypedArray()){_,i->
                ordering=sorts[i].second
                sortControl.text="SORT: "+sorts[i].first.uppercase(Locale.ROOT)
                search(0)
            }.setNegativeButton("CANCEL",null).show()
    }

    private fun search(offset:Int) {
        val current=ServerVersionManager.current(this)
        val version=if(versionOnly.isChecked)current.version else null
        val searchText=query.text.toString().trim()
        val categorySnapshot=category
        val orderingSnapshot=ordering
        val serverSnapshot=serverOnly.isChecked
        requestSequence++
        val sequence=requestSequence
        results.removeAllViews()
        results.addView(ui.text(this,"Searching Modrinth…",14f,ui.MUTED))
        Thread {
            val outcome=runCatching {
                ModrinthModBrowser.search(searchText,version,categorySnapshot,
                    orderingSnapshot,offset,serverSnapshot)
            }
            runOnUiThread {
                if(isFinishing || isDestroyed || sequence!=requestSequence)return@runOnUiThread
                outcome.onSuccess {renderResults(it,offset)}
                    .onFailure {e->
                        results.removeAllViews()
                        results.addView(ui.text(this,"Couldn't load mods: "+e.message,
                            13f,ui.RED))
                        results.addView(ui.space(this,10))
                        results.addView(ui.action(this,"TRY AGAIN",true){search(offset)})
                    }
            }
        }.start()
    }

    private fun renderResults(page:ModrinthModBrowser.Page,offset:Int) {
        results.removeAllViews()
        val start=if(page.mods.isEmpty())0 else offset+1
        val last=offset+page.mods.size
        results.addView(ui.text(this,"Browse mods",19f,ui.WHITE,true))
        results.addView(ui.space(this,5))
        results.addView(ui.text(this,
            "Showing "+start+"–"+last+" of "+page.total+" • Modrinth / Fabric",
            12f,ui.MUTED))
        results.addView(ui.space(this,12))
        if(page.mods.isEmpty()) {
            results.addView(ui.text(this,
                "No matching mods. Try another category, search without a version filter, or disable the server-only filter.",
                13f,ui.MUTED))
        }
        page.mods.forEach {mod->
            val card=ui.card(this)
            val header=ui.row(this)
            val title=ui.column(this)
            title.addView(ui.text(this,mod.title,15f,ui.WHITE,true))
            title.addView(ui.space(this,4))
            title.addView(ui.text(this,"by "+mod.author,11f,ui.CYAN))
            header.addView(title,LinearLayout.LayoutParams(0,-2,1f))
            card.addView(header)
            card.addView(ui.space(this,8))
            card.addView(ui.text(this,mod.description,12f,ui.MUTED))
            card.addView(ui.space(this,8))
            val downloads=when {
                mod.downloads>=1_000_000 ->
                    String.format(Locale.ROOT,"%.1fM",mod.downloads/1_000_000.0)
                mod.downloads>=1_000 ->
                    String.format(Locale.ROOT,"%.1fK",mod.downloads/1_000.0)
                else -> mod.downloads.toString()
            }
            card.addView(ui.text(this,downloads+" downloads • "+
                mod.categories.filter{it!="fabric"}.take(2).joinToString(" • "),
                11f,ui.MUTED))
            card.addView(ui.space(this,9))
            val status=when(mod.serverSide) {
                "required" -> "SERVER MOD • CLIENT MAY NEED IT"
                "optional" -> "SERVER OPTIONAL"
                "unsupported" -> "CLIENT ONLY • NOT FOR SERVER"
                else -> "SERVER COMPATIBILITY UNKNOWN"
            }
            card.addView(ui.pill(this,status,
                if(mod.clientOnly)ui.RED else if(mod.unknownServerSupport)ui.PURPLE else ui.GREEN))
            card.addView(ui.space(this,11))
            card.addView(ui.action(this,"VIEW MOD & INSTALL",true) {showDetails(mod)})
            results.addView(card)
            results.addView(ui.space(this,11))
        }
        val navigation=ui.row(this)
        if(offset>0) {
            navigation.addView(ui.action(this,"‹ PREVIOUS") {
                search((offset-ModrinthModBrowser.PAGE_SIZE).coerceAtLeast(0))
            },ui.weight(this))
        }
        if(page.mods.isNotEmpty() && offset+page.mods.size<page.total && offset<240) {
            navigation.addView(ui.action(this,"NEXT PAGE ›",true) {
                search(offset+ModrinthModBrowser.PAGE_SIZE)
            },LinearLayout.LayoutParams(0,-2,1f))
        }
        if(navigation.childCount>0) results.addView(navigation)
    }

    private fun showDetails(mod:ModrinthModBrowser.Mod) {
        if(mod.clientOnly) {
            AlertDialog.Builder(this).setTitle(mod.title+" is client-only")
                .setMessage("Modrinth marks this mod as unsupported on dedicated servers. Install it on the Java player's Minecraft client, not on PocketCraft. Bedrock players cannot run Java client mods.")
                .setNegativeButton("CLOSE",null)
                .setPositiveButton("OPEN MODRINTH"){_,_->openProject(mod)}
                .show()
            return
        }
        val server=ServerVersionManager.current(this)
        if(!server.launcherPresent) {
            AlertDialog.Builder(this).setTitle("Fabric server not installed")
                .setMessage("You can browse mods, but a Fabric server launcher is required to install one.")
                .setPositiveButton("OK",null).show()
            return
        }
        if(server.version==null) {
            confirmVersion {showDetails(mod)}
            return
        }
        val dialog=AlertDialog.Builder(this).setTitle("Checking "+mod.title)
            .setMessage("Checking for a real Fabric "+server.version+" release…")
            .setCancelable(false).create()
        dialog.show()
        Thread {
            val result=runCatching {ModrinthModBrowser.release(mod,server.version)}
            runOnUiThread {
                if(isFinishing || isDestroyed)return@runOnUiThread
                dialog.dismiss()
                result.onSuccess {release->showRelease(mod,server.version,release)}
                    .onFailure {e->
                        AlertDialog.Builder(this).setTitle("Not available for "+server.version)
                            .setMessage(e.message ?: "No matching Fabric JAR release.")
                            .setNegativeButton("CLOSE",null)
                            .setPositiveButton("VIEW PROJECT"){_,_->openProject(mod)}
                            .show()
                    }
            }
        }.start()
    }

    private fun openProject(mod:ModrinthModBrowser.Mod) {
        startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(mod.projectUrl)))
    }

    private fun showRelease(
        mod:ModrinthModBrowser.Mod,
        version:String,
        release:ModrinthModBrowser.Release
    ) {
        val message=buildString {
            append(mod.description)
            append("\n\nMinecraft: ");append(version)
            append("\nFabric release: ");append(release.number)
            append("\nServer-side: ");append(mod.serverSide)
            append("\nJava client-side: ");append(mod.clientSide)
            append("\nRequired dependencies: ");append(release.requiredDependencies)
            append("\nOptional dependencies: ");append(release.optionalDependencies)
            if(release.incompatibleDependencies>0)
                append("\nIncompatible dependencies declared: " + release.incompatibleDependencies)
            if(release.requiredDependencies>0)
                append("\n\nIMPORTANT: Required dependencies are not installed automatically. Install compatible dependencies first or your server may not start.")
            append("\n\nGeyser/Bedrock warning: Custom Java items, blocks and client-required mechanics may not work in Bedrock.")
            append("\n\nYour existing mod files will not be overwritten.")
        }
        val builder=AlertDialog.Builder(this).setTitle(mod.title)
            .setMessage(message)
            .setNegativeButton("CANCEL",null)
            .setNeutralButton("MODRINTH PAGE"){_,_->openProject(mod)}
        if(!ServerService.running && ServerService.status!="STARTING" &&
            ServerService.status!="STOPPING") {
            builder.setPositiveButton("INSTALL MOD"){_,_->runInstall(mod)}
        } else {
            builder.setPositiveButton("STOP SERVER FIRST"){_,_->
                toast("Stop Minecraft before installing mods.")
            }
        }
        builder.show()
    }

    private fun runInstall(mod:ModrinthModBrowser.Mod) {
        if(ServerService.running || ServerService.status=="STARTING" ||
            ServerService.status=="STOPPING") {
            toast("Stop Minecraft before installing mods.")
            return
        }
        val cancelled=AtomicBoolean(false)
        val layout=ui.column(this).apply {
            setPadding(ui.dp(this@ModrinthModsActivity,20),ui.dp(this@ModrinthModsActivity,16),
                ui.dp(this@ModrinthModsActivity,20),ui.dp(this@ModrinthModsActivity,16))
        }
        val stage=ui.text(this,"Preparing verified download…",13f,ui.WHITE)
        layout.addView(stage)
        layout.addView(ui.space(this,12))
        val bar=android.widget.ProgressBar(this,null,
            android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate=true
            max=1000
        }
        layout.addView(bar)
        val dialog=AlertDialog.Builder(this).setTitle("Installing "+mod.title)
            .setView(layout)
            .setNegativeButton("CANCEL"){_,_->cancelled.set(true)}
            .setCancelable(false).create()
        dialog.show()
        Thread {
            val result=runCatching {
                InstallProgress.withSession({progress->
                    runOnUiThread {
                        if(!isFinishing && !isDestroyed && dialog.isShowing) {
                            stage.text=progress.stage
                            if(progress.total>0) {
                                bar.isIndeterminate=false
                                bar.progress=((progress.done.toDouble()/progress.total)
                                    .coerceIn(0.0,1.0)*1000).toInt()
                            } else bar.isIndeterminate=true
                        }
                    }
                }, {cancelled.get()}) {
                    // Reuse the existing allowlisted, checksum-aware Modrinth installer.
                    // The project ID is supplied by Modrinth, not a user-pasted URL.
                    WebsiteModInstaller.install(this,mod.projectUrl)
                }
            }
            runOnUiThread {
                if(isFinishing || isDestroyed)return@runOnUiThread
                dialog.dismiss()
                result.onSuccess {message->
                    AlertDialog.Builder(this).setTitle("Mod installed")
                        .setMessage(message+"\n\nRestart Minecraft to load the mod.")
                        .setPositiveButton("DONE",null).show()
                }.onFailure {e->
                    AlertDialog.Builder(this)
                        .setTitle(if(cancelled.get())"Download cancelled" else "Installation failed")
                        .setMessage(e.message ?: "Check the Modrinth download and server version.")
                        .setPositiveButton("OK",null).show()
                }
            }
        }.start()
    }
}
