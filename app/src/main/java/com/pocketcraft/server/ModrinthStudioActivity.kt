package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
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
    companion object { const val EXTRA_TYPE = "modrinth_type" }
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
        frame.addView(ui.pill(this,"FABRIC  •  MINECRAFT " + minecraft,ui.CYAN))
        frame.addView(ui.space(this,7))
        frame.addView(ui.text(this,"Discover mods and modpacks. Choose exact releases and their dependencies.",12f,ui.MUTED))
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
        status.text="Searching "+type+" projects…"
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
            " projects • Fabric "+minecraft
        page.text="PAGE "+(offset/ModrinthCatalog.PAGE_SIZE+1)
        prev.isEnabled=offset>0
        next.isEnabled=offset+ModrinthCatalog.PAGE_SIZE<count && offset<2000
        prev.alpha=if(prev.isEnabled)1f else .4f
        next.alpha=if(next.isEnabled)1f else .4f
        listing.removeAllViews()
        if(results.hits.isEmpty())listing.addView(ui.text(this,
            "No matching versions. Try another category, sort or shorter search.",13f,ui.MUTED))
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
                if(hit.serverHint=="Client-only project")ui.RED else ui.CYAN))
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
            .setMessage("Loading Fabric "+minecraft+" versions…").setCancelable(false).create()
        dialog.show()
        Thread {
            val outcome=runCatching {ModrinthCatalog.versions(hit.id,type,minecraft)}
            runOnUiThread {
                if(isFinishing||isDestroyed)return@runOnUiThread
                dialog.dismiss()
                outcome.onFailure {
                    AlertDialog.Builder(this).setTitle("Could not load versions")
                        .setMessage(it.message?:"Network error").setPositiveButton("OK",null).show()
                }
                outcome.onSuccess { versions ->
                    if(versions.isEmpty()){
                        AlertDialog.Builder(this).setTitle("No compatible release")
                            .setMessage("This project has no server-compatible Fabric "+
                                minecraft+" download.").setPositiveButton("OK",null).show()
                    }else{
                        AlertDialog.Builder(this).setTitle("Choose "+hit.title+" version")
                            .setMessage("Exact releases. Existing mod files will not be overwritten.")
                            .setItems(versions.map { v ->
                                v.number+" ["+v.releaseType.uppercase(Locale.ROOT)+"]  •  "+v.published+
                                "\n"+v.requiredCount+" required / "+v.optionalCount+" optional dependencies"
                            }.toTypedArray()){_,i ->selectVersion(hit,versions[i],type)}
                            .setNegativeButton("CLOSE",null).show()
                    }
                }
            }
        }.start()
    }
    private fun selectVersion(hit:ModrinthCatalog.Hit,v:ModrinthCatalog.Version,type:String){
        if(ServerService.running || ServerService.status in setOf("STARTING","STOPPING")){
            AlertDialog.Builder(this).setTitle("Stop Minecraft first")
                .setMessage("Mods and packs can only be installed when the server is stopped.")
                .setPositiveButton("OK",null).show()
            return
        }
        if(type=="modpack")preparePack(hit,v) else reviewMod(hit,v)
    }
    private fun reviewMod(hit:ModrinthCatalog.Hit,v:ModrinthCatalog.Version){
        val wait=AlertDialog.Builder(this).setTitle("Checking dependencies")
            .setMessage("Reading exactly version "+v.number+"…").setCancelable(false).create()
        wait.show()
        Thread {
            val result=runCatching {WebsiteModInstaller.previewDependencies(this,hit.id,v.id)}
            runOnUiThread {
                if(isFinishing||isDestroyed)return@runOnUiThread
                wait.dismiss()
                result.onFailure {
                    AlertDialog.Builder(this).setTitle("Dependency review failed")
                        .setMessage(it.message?:"Try again").setPositiveButton("OK",null).show()
                }
                result.onSuccess {showChoices(hit,v,it)}
            }
        }.start()
    }
    private fun showChoices(hit:ModrinthCatalog.Hit,v:ModrinthCatalog.Version,
                            deps:List<WebsiteModInstaller.DependencyChoice>){
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
            label+" · "+it.title
        }.toTypedArray()
        val builder=AlertDialog.Builder(this).setTitle("Install "+hit.title+" "+v.number+"?")
            .setMessage("Required dependencies stay selected. Optional dependencies are your choice. "+
                "Version is pinned; SHA-512 verified. Existing JARs won't be replaced."+
                if(conflict)"\n\nRemove conflicting mods before continuing." else "")
            .setNegativeButton("CANCEL",null)
        if(labels.isNotEmpty())builder.setMultiChoiceItems(labels,selected){dialog,index,checked ->
            val dep=deps[index]
            if(dep.kind=="incompatible"||dep.required||dep.installed){
                selected[index]=dep.kind!="incompatible"
                (dialog as AlertDialog).listView.setItemChecked(index,selected[index])
            }else selected[index]=checked
        }
        if(!conflict)builder.setPositiveButton("INSTALL"){_,_->
            val options=deps.indices.filter {
                deps[it].kind=="optional" && selected[it] && !deps[it].installed
            }.map {deps[it].projectId}.toSet()
            install("Installing "+hit.title){
                WebsiteModInstaller.installWithDependencies(this,hit.id,v.id,options)
            }
        }
        builder.show()
    }
    private fun preparePack(hit:ModrinthCatalog.Hit,v:ModrinthCatalog.Version){
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
                    val builder=AlertDialog.Builder(this).setTitle("Install "+hit.title+" "+v.number+"?")
                        .setMessage("Verified Fabric "+minecraft+" pack. Required server files are "+
                            "always included. Choose additional optional server files.")
                        .setNegativeButton("CANCEL"){_,_->uri.path?.let {path->File(path).delete()}}
                    if(optional.isNotEmpty())builder.setMultiChoiceItems(
                        optional.map {"OPTIONAL · "+it}.toTypedArray(),checked
                    ){_,i,value ->checked[i]=value}
                    builder.setPositiveButton("INSTALL"){_,_->
                        val selected=optional.indices.filter {checked[it]}.map {optional[it]}.toSet()
                        install("Installing "+hit.title){
                            try {
                                ModpackImporter.install(this,uri,selectedOptional=selected).description()
                            } finally {uri.path?.let {path->File(path).delete()}}
                        }
                    }
                    builder.setOnCancelListener {uri.path?.let {path->File(path).delete()}}
                    builder.show()
                }
            }
        }.start()
    }
    private fun install(label:String,work:()->String){
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
                    AlertDialog.Builder(this).setTitle("✓ Installation complete")
                        .setMessage(it+"\n\nRestart Minecraft to load the changes.")
                        .setPositiveButton("OK",null).show()
                }.onFailure {
                    AlertDialog.Builder(this)
                        .setTitle(if(cancel.get())"Installation cancelled" else "Installation failed")
                        .setMessage((it.message?:"Unknown error")+
                            "\nExisting files should remain unchanged. Review compatibility before retrying.")
                        .setPositiveButton("OK",null).show()
                }
            }
        }.start()
    }
}
