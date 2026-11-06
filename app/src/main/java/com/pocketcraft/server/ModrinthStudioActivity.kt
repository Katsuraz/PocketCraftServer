package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Real Modrinth v2 browser: search, sort, filter, page, pick version and dependencies. */
class ModrinthStudioActivity : Activity() {
    companion object {
        const val EXTRA_TYPE = "modrinth_type"
        private const val SAVE_VERSION_FILE = 42719
    }
    private var pendingDownload: ModrinthCatalog.Version? = null
    private val ui = PocketUi
    private var kind = "mod"
    private var category = "all"
    private var sort = "relevance"
    private var offset = 0
    private var requestId = 0
    private var count = 0
    private lateinit var minecraft: String
    private lateinit var search: EditText
    private lateinit var listing: LinearLayout
    private lateinit var status: TextView
    private lateinit var page: TextView
    private lateinit var prev: TextView
    private lateinit var next: TextView
    private lateinit var categoryButton: TextView
    private lateinit var sortButton: TextView
    private lateinit var modsButton: TextView
    private lateinit var packsButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.loadTheme(this)
        minecraft = getSharedPreferences("pocketcraft",0).getString("gameVersion",null).orEmpty()
        if(minecraft.isBlank()) {
            AlertDialog.Builder(this).setTitle("Select your Fabric version first")
                .setMessage("Set up the Minecraft server before browsing compatible Modrinth downloads.")
                .setPositiveButton("OK") { _, _ -> finish() }.show()
            return
        }
        kind=if(intent.getStringExtra(EXTRA_TYPE)=="modpack")"modpack" else "mod"
        createScreen()
        load()
    }
    override fun onDestroy() { requestId++;super.onDestroy() }
    private fun info(message: String) = Toast.makeText(this,message,Toast.LENGTH_LONG).show()

    private fun createScreen() {
        val frame=ui.column(this).apply {
            setBackgroundColor(ui.BG)
            setPadding(ui.dp(this@ModrinthStudioActivity,14),ui.dp(this@ModrinthStudioActivity,12),
                ui.dp(this@ModrinthStudioActivity,14),ui.dp(this@ModrinthStudioActivity,10))
        }
        val title=ui.row(this)
        title.addView(ui.action(this,"‹ BACK") { finish() })
        title.addView(ui.text(this,"  Modrinth Workshop",17f,ui.WHITE,true))
        frame.addView(title)
        frame.addView(ui.space(this,8))
        frame.addView(ui.pill(this,"ALL MODRINTH PROJECTS  •  SERVER FABRIC " + minecraft,ui.CYAN))
        frame.addView(ui.space(this,7))
        frame.addView(ui.text(this,"Browse every mod and modpack. Pick any release, see compatibility warnings, or download the file without installing.",12f,ui.MUTED))
        frame.addView(ui.space(this,10))
        val tabs=ui.row(this)
        modsButton=ui.action(this,"MODS",kind=="mod") { changeKind("mod") }
        packsButton=ui.action(this,"MODPACKS",kind=="modpack") { changeKind("modpack") }
        tabs.addView(modsButton,ui.weight(this))
        tabs.addView(packsButton,LinearLayout.LayoutParams(0,-2,1f))
        frame.addView(tabs)
        frame.addView(ui.space(this,8))
        search=EditText(this).apply {
            hint="Search Modrinth or leave blank to explore"
            setSingleLine(true)
            textSize=14f
            setTextColor(ui.WHITE);setHintTextColor(ui.MUTED)
            background=ui.shape(this@ModrinthStudioActivity,ui.SURFACE,12,ui.BORDER)
            setPadding(ui.dp(this@ModrinthStudioActivity,12),ui.dp(this@ModrinthStudioActivity,11),
                ui.dp(this@ModrinthStudioActivity,12),ui.dp(this@ModrinthStudioActivity,11))
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        search.setOnEditorActionListener { _, actionId, _ ->
            if(actionId==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){
                offset=0;load();true
            } else false
        }
        frame.addView(search)
        frame.addView(ui.space(this,6))
        frame.addView(ui.action(this,"⌕ SEARCH",true) {offset=0;load()})
        frame.addView(ui.space(this,7))
        val filters=ui.row(this)
        categoryButton=ui.action(this,"CATEGORY: ALL") {chooseCategory()}
        sortButton=ui.action(this,"SORT: RELEVANCE") {chooseSort()}
        filters.addView(categoryButton,ui.weight(this))
        filters.addView(sortButton,LinearLayout.LayoutParams(0,-2,1f))
        frame.addView(filters)
        frame.addView(ui.space(this,7))
        status=ui.text(this,"Searching Modrinth…",12f,ui.MUTED)
        frame.addView(status)
        frame.addView(ui.space(this,7))
        listing=ui.column(this)
        frame.addView(ScrollView(this).apply {addView(listing)},
            LinearLayout.LayoutParams(-1,0,1f))
        frame.addView(ui.space(this,6))
        val pager=ui.row(this)
        prev=ui.action(this,"‹ PREV") {
            if(offset>0){offset-=ModrinthCatalog.PAGE_SIZE;load()}
        }
        next=ui.action(this,"NEXT ›") {
            if(offset+ModrinthCatalog.PAGE_SIZE<count){offset+=ModrinthCatalog.PAGE_SIZE;load()}
        }
        page=ui.text(this,"",11f,ui.MUTED).apply {gravity=Gravity.CENTER}
        pager.addView(prev)
        pager.addView(page,LinearLayout.LayoutParams(0,-2,1f))
        pager.addView(next)
        frame.addView(pager)
        ui.styleFormInputs(frame)
        setContentView(frame)
    }
    private fun changeKind(newKind:String){
        kind=newKind;category="all";offset=0
        categoryButton.text="CATEGORY: ALL"
        modsButton.setTextColor(if(kind=="mod")ui.GREEN else ui.WHITE)
        packsButton.setTextColor(if(kind=="modpack")ui.GREEN else ui.WHITE)
        load()
    }
    private fun chooseCategory(){
        val values=ModrinthCatalog.categories
        AlertDialog.Builder(this).setTitle("Choose category")
            .setItems(values.map {it.uppercase(Locale.ROOT)}.toTypedArray()){_,which ->
                category=values[which];offset=0
                categoryButton.text="CATEGORY: "+category.uppercase(Locale.ROOT)
                load()
            }.setNegativeButton("CANCEL",null).show()
    }
    private fun chooseSort(){
        val values=ModrinthCatalog.sorts
        AlertDialog.Builder(this).setTitle("Sort by")
            .setItems(values.map {it.uppercase(Locale.ROOT)}.toTypedArray()){_,which ->
                sort=values[which];offset=0
                sortButton.text="SORT: "+sort.uppercase(Locale.ROOT)
                load()
            }.setNegativeButton("CANCEL",null).show()
    }
    private fun load(){
        val request=++requestId
        val type=kind;val term=search.text.toString().trim()
        val selectedCategory=category;val selectedSort=sort;val selectedOffset=offset
        status.text="Searching all "+type+" projects…"
        listing.removeAllViews()
        prev.isEnabled=false;next.isEnabled=false
        Thread {
            val result=runCatching {
                ModrinthCatalog.search(type,term,minecraft,selectedCategory,selectedSort,selectedOffset)
            }
            runOnUiThread {
                if(isFinishing||isDestroyed||request!=requestId)return@runOnUiThread
                result.onSuccess {showHits(it,type)}.onFailure {
                    status.text="Search error: "+(it.message?:"Network unavailable")
                    listing.addView(ui.action(this,"RETRY",true){load()})
                }
            }
        }.start()
    }
    private fun showHits(results:ModrinthCatalog.Page,type:String){
        count=results.totalHits
        val first=if(results.hits.isEmpty())0 else results.offset+1
        status.text=first.toString()+"–"+(results.offset+results.hits.size)+" of "+count+
            " projects • all Minecraft versions"
        page.text="PAGE "+(offset/ModrinthCatalog.PAGE_SIZE+1)
        prev.isEnabled=offset>0
        next.isEnabled=offset+ModrinthCatalog.PAGE_SIZE<count && offset<2000
        prev.alpha=if(prev.isEnabled)1f else .4f
        next.alpha=if(next.isEnabled)1f else .4f
        listing.removeAllViews()
        if(results.hits.isEmpty())listing.addView(ui.text(this,
            "No matching projects. Try another category or a shorter search.",13f,ui.MUTED))
        results.hits.forEach { hit ->
            val card=ui.card(this)
            card.addView(ui.text(this,hit.title,15f,ui.WHITE,true))
            card.addView(ui.space(this,5))
            card.addView(ui.text(this,"by "+hit.author+"  •  "+hit.downloads+" downloads",11f,ui.CYAN))
            if(hit.description.isNotBlank()){
                card.addView(ui.space(this,7))
                card.addView(ui.text(this,hit.description,12f,ui.MUTED))
            }
            card.addView(ui.space(this,6))
            card.addView(ui.pill(this,hit.serverHint,
                if(hit.serverHint.startsWith("Client-only"))ui.RED else ui.CYAN))
            card.addView(ui.space(this,9))
            card.addView(ui.action(this,"CHOOSE VERSION",true){showVersions(hit,type)})
            card.addView(ui.space(this,6))
            card.addView(ui.action(this,"OPEN MODRINTH PAGE"){
                startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(
                    "https://modrinth.com/"+type+"/"+hit.slug)))
            })
            listing.addView(card)
            listing.addView(ui.space(this,9))
        }
    }
    private fun showVersions(hit:ModrinthCatalog.Hit,type:String){
        val dialog=AlertDialog.Builder(this).setTitle("Releases · "+hit.title)
            .setMessage("Loading all available releases…").setCancelable(false).create()
        dialog.show()
        Thread {
            val outcome=runCatching {ModrinthCatalog.versionReport(hit.id,type,minecraft)}
            runOnUiThread {
                if(isFinishing||isDestroyed)return@runOnUiThread
                dialog.dismiss()
                outcome.onFailure {
                    AlertDialog.Builder(this).setTitle("Could not load versions")
                        .setMessage(it.message?:"Network error").setPositiveButton("OK",null).show()
                }
                outcome.onSuccess { report ->
                    val versions=report.versions
                    if(versions.isEmpty()){
                        AlertDialog.Builder(this).setTitle("No verifiable download")
                            .setMessage(report.unavailableReason)
                            .setNeutralButton("VIEW ON MODRINTH") { _, _ ->
                                startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(
                                    "https://modrinth.com/"+type+"/"+hit.slug)))
                            }.setPositiveButton("OK",null).show()
                    }else{
                        ModrinthVersionPicker.show(this,hit.title,minecraft,versions) { picked ->
                            selectVersion(hit,picked,type)
                        }
                    }
                }
            }
        }.start()
    }
    /**
     * Compatibility is advisory. The user can download ANY verified release,
     * including client-only or other-loader files, without touching the server.
     * Fabric installations require a separate explicit risk confirmation.
     */
    // A fixed-size, scrollable Homestead card avoids Android's default
    // three-button AlertDialog layout splitting actions into tall rows.
    internal fun selectVersion(hit:ModrinthCatalog.Hit,v:ModrinthCatalog.Version,type:String){
        val warnings=ModrinthCatalog.compatibilityWarnings(v,minecraft)
        val canInstall="fabric" in v.loaders
        val panel=ui.column(this).apply {
            background=ui.shape(this@ModrinthStudioActivity,ui.PANEL,15,ui.BORDER)
            setPadding(ui.dp(this@ModrinthStudioActivity,15),
                ui.dp(this@ModrinthStudioActivity,14),
                ui.dp(this@ModrinthStudioActivity,15),
                ui.dp(this@ModrinthStudioActivity,13))
        }
        panel.addView(ui.text(this,
            if(warnings.isEmpty())"Release options" else "⚠ Compatibility warning",
            17f,ui.WHITE,true))
        panel.addView(ui.space(this,9))
        panel.addView(ui.text(this,hit.title+" · "+v.number,14f,ui.CYAN,true))
        panel.addView(ui.space(this,6))
        panel.addView(ui.text(this,"Minecraft: "+
            v.gameVersions.take(6).joinToString(", ").ifBlank { "unspecified" } +
            "\nLoader: "+v.loaders.joinToString(", ").ifBlank { "unspecified" },
            12f,ui.MUTED))
        panel.addView(ui.space(this,8))
        val message=buildString {
            if(warnings.isEmpty())append("✓ Matches your Fabric server.")
            else {
                warnings.forEach {append("⚠ ").append(it).append("\n")}
                append("\nIncompatible mods can prevent Minecraft from starting. Back up your server first.")
            }
            append("\n\nDownload Only saves the original SHA-512-verified file without installing it.")
        }
        val details=ScrollView(this).apply {
            isFillViewport=false
            val content=ui.text(this@ModrinthStudioActivity,message,12f,
                if(warnings.isEmpty())ui.MUTED else ui.CYAN)
            addView(content)
        }
        panel.addView(details,LinearLayout.LayoutParams(-1,
            ui.dp(this,if(warnings.isEmpty())110 else 165)))
        panel.addView(ui.space(this,12))
        val holder=arrayOfNulls<AlertDialog>(1)
        if(canInstall){
            panel.addView(ui.action(this,
                if(warnings.isEmpty())"REVIEW INSTALL" else "INSTALL ANYWAY",true){
                    holder[0]?.dismiss()
                    proceedInstall(hit,v,type,warnings.isNotEmpty())
            })
            panel.addView(ui.space(this,7))
        }
        panel.addView(ui.action(this,"DOWNLOAD ONLY"){
            holder[0]?.dismiss()
            downloadOnly(v)
        })
        panel.addView(ui.space(this,7))
        panel.addView(ui.action(this,"CANCEL"){holder[0]?.dismiss()})
        val dialog=AlertDialog.Builder(this).setView(panel).create()
        holder[0]=dialog
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels*.93f).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun proceedInstall(hit:ModrinthCatalog.Hit,v:ModrinthCatalog.Version,
                               type:String,allowMismatch:Boolean){
        if(ServerService.running || ServerService.status in setOf("STARTING","STOPPING")){
            AlertDialog.Builder(this).setTitle("Stop Minecraft first")
                .setMessage("Stop your server before installing, and keep a backup.")
                .setPositiveButton("OK",null).show()
            return
        }
        if(type=="modpack")preparePack(hit,v,allowMismatch)
        else reviewMod(hit,v,allowMismatch)
    }

    /** Save to a user-selected SAF destination, never automatically to mods/. */
    private fun downloadOnly(v:ModrinthCatalog.Version){
        pendingDownload=v
        val fileType=if(v.filename.endsWith(".mrpack",true))
            "application/zip" else "application/java-archive"
        val chooser=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type=fileType
            putExtra(Intent.EXTRA_TITLE,v.filename.take(140))
        }
        try {
            startActivityForResult(chooser,SAVE_VERSION_FILE)
        } catch(error:Exception){
            pendingDownload=null
            AlertDialog.Builder(this).setTitle("Cannot save file")
                .setMessage(error.message ?: "No compatible Android file picker available")
                .setPositiveButton("OK",null).show()
        }
    }

    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=SAVE_VERSION_FILE)return
        val selected=pendingDownload
        pendingDownload=null
        if(resultCode!=RESULT_OK || data?.data==null || selected==null)return
        val destination=data.data!!
        install("Downloading verified "+selected.filename,
            showRestartMessage=false,successTitle="✓ Download saved"){
            val cached=ModrinthCatalog.downloadVersionFile(this,selected)
            try {
                val stream=contentResolver.openOutputStream(destination,"w")
                    ?: throw java.io.IOException("Unable to open selected save location")
                stream.use {output ->
                    cached.inputStream().use {input ->input.copyTo(output)}
                }
                "Saved "+selected.filename+" to your selected Android location. " +
                    "This file was NOT installed on the server."
            } finally {cached.delete()}
        }
    }

    private fun reviewMod(hit:ModrinthCatalog.Hit,v:ModrinthCatalog.Version,
                          allowMismatch:Boolean){
        val wait=AlertDialog.Builder(this).setTitle("Checking dependencies")
            .setMessage("Reading exactly version "+v.number+"…").setCancelable(false).create()
        wait.show()
        Thread {
            val result=runCatching {
                WebsiteModInstaller.previewDependencies(this,hit.id,v.id,
                    allowVersionMismatch=allowMismatch)
            }
            runOnUiThread {
                if(isFinishing||isDestroyed)return@runOnUiThread
                wait.dismiss()
                result.onFailure {
                    AlertDialog.Builder(this).setTitle("Dependency review failed")
                        .setMessage(it.message?:"Try again").setPositiveButton("OK",null).show()
                }
                result.onSuccess {showChoices(hit,v,it,allowMismatch)}
            }
        }.start()
    }
    internal fun showChoices(hit:ModrinthCatalog.Hit,v:ModrinthCatalog.Version,
                            deps:List<WebsiteModInstaller.DependencyChoice>,allowMismatch:Boolean){
        val conflict=deps.any {it.kind=="incompatible" && it.installed}
        val selected=BooleanArray(deps.size){i ->
            deps[i].kind!="incompatible" && (deps[i].required||deps[i].installed)
        }
        val labels=deps.map {
            val label=when{
                it.kind=="incompatible" && it.installed -> "⚠ CONFLICT"
                it.kind=="incompatible" -> "INCOMPATIBLE"
                it.installed -> "✓ INSTALLED"
                it.required -> "REQUIRED"
                else -> "OPTIONAL"
            }
            label+" · "+it.title+
                (if(it.kind=="required")
                    if(it.versionId==null)" · auto-select matching Fabric release"
                    else " · pinned Modrinth release"
                 else "")
        }.toTypedArray()
        val panel=ui.column(this).apply {
            setPadding(ui.dp(this@ModrinthStudioActivity,16),ui.dp(this@ModrinthStudioActivity,10),
                ui.dp(this@ModrinthStudioActivity,16),ui.dp(this@ModrinthStudioActivity,12))
        }
        panel.addView(ui.text(this,"Required dependencies cannot be disabled. " +
            "Optional dependencies can be selected. Exact pinned version and SHA-512 verified; " +
            "existing JARs are not overwritten." +
            (if(allowMismatch)"\n\n⚠ You chose an incompatible version. " +
                "Dependencies might not resolve and Minecraft may crash on startup." else ""),
            12f,ui.MUTED))
        panel.addView(ui.space(this,10))
        if(conflict) {
            panel.addView(ui.text(this,"A conflicting mod is installed. Resolve it first.",
                12f,ui.RED,true))
        }
        deps.forEachIndexed { index, dep ->
            panel.addView(CheckBox(this).apply {
                text=labels[index]
                textSize=12f
                setTextColor(if(dep.kind=="incompatible")ui.RED else ui.WHITE)
                isChecked=selected[index]
                isEnabled=dep.kind=="optional" && !dep.installed
                setOnCheckedChangeListener { _, checked -> selected[index]=checked }
                minHeight=ui.dp(this@ModrinthStudioActivity,44)
            })
        }
        if(deps.isEmpty())panel.addView(ui.text(this,
            "This release declares no Modrinth dependencies.",12f,ui.MUTED))
        // Modrinth pins sometimes reference missing CDN assets or deleted
        // versions. The user explicitly controls whether those *required*
        // dependency pins may fall back to the same project's current
        // Minecraft + Fabric release. The chosen parent mod stays pinned.
        val pinnedDependencies=deps.filter {
            it.versionId != null && it.kind in setOf("required","optional") &&
                !it.installed
        }
        val allowRecovery=booleanArrayOf(true)
        if(pinnedDependencies.isNotEmpty()) {
            panel.addView(ui.space(this,12))
            panel.addView(CheckBox(this).apply {
                text="RECOVER UNAVAILABLE PINNED DEPENDENCIES (404/410)"
                textSize=12f
                setTextColor(ui.WHITE)
                isChecked=true
                minHeight=ui.dp(this@ModrinthStudioActivity,48)
                setOnCheckedChangeListener { _,checked ->allowRecovery[0]=checked }
            })
            panel.addView(ui.text(this,
                "If the specified dependency cannot be downloaded, try another " +
                "SHA-512-verified Fabric "+minecraft+" release of that SAME mod. " +
                "Does not change your selected "+hit.title+" version. " +
                "Uncheck to require exact dependency pins. Other failures " +
                "(including checksum mismatches) will still stop installation.",
                11f,ui.MUTED))
        }
        val builder=AlertDialog.Builder(this).setTitle("Install "+hit.title+" "+v.number+"?")
            .setView(ScrollView(this).apply {addView(panel)})
            .setNegativeButton("CANCEL",null)
        if(!conflict)builder.setPositiveButton(
            if(pinnedDependencies.isNotEmpty())"INSTALL WITH DEPENDENCIES" else "INSTALL"
        ){_,_->
            val options=deps.indices.filter {
                deps[it].kind=="optional" && selected[it] && !deps[it].installed
            }.map {deps[it].projectId}.toSet()
            install("Installing "+hit.title){
                WebsiteModInstaller.installWithDependencies(this,hit.id,v.id,options,
                    allowVersionMismatch=allowMismatch,
                    allowPinnedDependencyRecovery =
                        pinnedDependencies.isNotEmpty() && allowRecovery[0])
            }
        }
        builder.show()
    }
    private fun preparePack(hit:ModrinthCatalog.Hit,v:ModrinthCatalog.Version,
                            allowMismatch:Boolean){
        val cancel=AtomicBoolean(false)
        val wait=AlertDialog.Builder(this).setTitle("Downloading "+hit.title)
            .setMessage("Verifying selected .mrpack checksum…")
            .setNegativeButton("CANCEL"){_,_->cancel.set(true)}
            .setCancelable(false).create()
        wait.show()
        Thread {
            var artifact:Uri?=null
            val outcome=runCatching {
                InstallProgress.withSession({ update ->
                    runOnUiThread {if(wait.isShowing)wait.setMessage(update.stage)}
                },{cancel.get()}){
                    val uri=ModrinthCatalog.downloadPack(this,v)
                    artifact=uri
                    uri to ModpackImporter.optionalFiles(this,uri)
                }
            }
            runOnUiThread {
                if(isFinishing||isDestroyed||cancel.get()){
                    artifact?.path?.let {File(it).delete()}
                    return@runOnUiThread
                }
                wait.dismiss()
                outcome.onFailure {
                    artifact?.path?.let {path -> File(path).delete()}
                    AlertDialog.Builder(this).setTitle("Pack download failed")
                        .setMessage(it.message?:"Network error").setPositiveButton("OK",null).show()
                }
                outcome.onSuccess { (uri,optional) ->
                    val checked=BooleanArray(optional.size)
                    val panel=ui.column(this).apply {
                        setPadding(ui.dp(this@ModrinthStudioActivity,16),
                            ui.dp(this@ModrinthStudioActivity,10),
                            ui.dp(this@ModrinthStudioActivity,16),
                            ui.dp(this@ModrinthStudioActivity,12))
                    }
                    panel.addView(ui.text(this,"Verified .mrpack for Fabric " + minecraft +
                        ". Required files are always included. Choose optional server files below. "+
                        "Existing server files will not be overwritten.",12f,ui.MUTED))
                    panel.addView(ui.space(this,9))
                    optional.forEachIndexed {index, file ->
                        panel.addView(CheckBox(this).apply {
                            text="OPTIONAL · "+file
                            textSize=12f
                            setTextColor(ui.WHITE)
                            minHeight=ui.dp(this@ModrinthStudioActivity,44)
                            setOnCheckedChangeListener { _, value -> checked[index]=value }
                        })
                    }
                    val builder=AlertDialog.Builder(this).setTitle("Install "+hit.title+" "+v.number+"?")
                        .setView(ScrollView(this).apply {addView(panel)})
                        .setNegativeButton("CANCEL"){_,_->uri.path?.let {path->File(path).delete()}}
                    builder.setPositiveButton("INSTALL"){_,_->
                        val selected=optional.indices.filter {checked[it]}.map {optional[it]}.toSet()
                        install("Installing "+hit.title){
                            try {
                                ModpackImporter.install(this,uri,selectedOptional=selected,
                                    allowMinecraftMismatch=allowMismatch).description()
                            } finally {uri.path?.let {path->File(path).delete()}}
                        }
                    }
                    builder.setOnCancelListener {uri.path?.let {path->File(path).delete()}}
                    builder.show()
                }
            }
        }.start()
    }
    private fun install(label:String,showRestartMessage:Boolean=true,
                        successTitle:String="✓ Installation complete",
                        work:()->String){
        val cancel=AtomicBoolean(false)
        val statusView=ui.text(this,"Preparing…",13f,ui.WHITE)
        val panel=ui.column(this).apply {
            setPadding(ui.dp(this@ModrinthStudioActivity,16),ui.dp(this@ModrinthStudioActivity,12),
                ui.dp(this@ModrinthStudioActivity,16),ui.dp(this@ModrinthStudioActivity,12))
            addView(statusView)
        }
        val wait=AlertDialog.Builder(this).setTitle(label).setView(panel)
            .setNegativeButton("CANCEL"){_,_->cancel.set(true)}
            .setCancelable(false).create()
        wait.show()
        Thread {
            val result=runCatching {
                InstallProgress.withSession({ progress ->
                    runOnUiThread {
                        if(wait.isShowing)statusView.text=progress.stage+
                            (if(progress.total>0)"\n"+progress.done/1024+" / "+progress.total/1024+" KB" else "")
                    }
                },{cancel.get()}) {work()}
            }
            runOnUiThread {
                if(isFinishing||isDestroyed)return@runOnUiThread
                wait.dismiss()
                result.onSuccess {
                    AlertDialog.Builder(this).setTitle(successTitle)
                        .setMessage(it+
                            (if(showRestartMessage)"\n\nRestart Minecraft to load the changes." else ""))
                        .setPositiveButton("OK",null).show()
                }.onFailure { error ->
                    val failureMessage=error.message?:"Unknown error"
                    val depId=Regex("dependency \\(([A-Za-z0-9_-]{1,120})\\)")
                        .find(failureMessage)?.groupValues?.getOrNull(1)
                    val builder=AlertDialog.Builder(this)
                        .setTitle(if(cancel.get())"Installation cancelled" else "Installation failed")
                        .setMessage(failureMessage+
                            "\n\nNewly added JARs are rolled back. You can also install " +
                            "the required Fabric dependency manually, then retry.")
                        .setPositiveButton("OK",null)
                    if(depId!=null) {
                        builder.setNeutralButton("VIEW REQUIRED MOD"){_,_->
                            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(
                                "https://modrinth.com/project/"+depId)))
                        }
                    }
                    builder.show()
                }
            }
        }.start()
    }
}
