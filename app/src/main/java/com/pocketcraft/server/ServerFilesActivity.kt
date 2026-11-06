package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.File
import java.io.IOException

/** Browse app-private server files with editing and SAF export, no root required. */
class ServerFilesActivity : Activity() {
    private val ui = PocketUi
    private val serverRoot by lazy { WorldStore.root(this).canonicalFile }
    private var directory: File? = null
    private var pendingExport: File? = null
    private var filter = ""

    companion object {
        const val EXTRA_PATH = "server_path"
        private const val EXPORT_FILE = 81
        private const val EXPORT_ALL = 82
        private const val IMPORT_ALL = 83
        private const val VERIFY_BACKUP = 84
    }

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        ui.loadTheme(this)
        window.statusBarColor=ui.BG
        window.navigationBarColor=ui.BG
        directory=serverRoot
        showFolder()
        intent.getStringExtra(EXTRA_PATH)?.let { path ->
            window.decorView.post { openPath(File(serverRoot,path)) }
        }
    }

    @Deprecated("Compatible with older Android versions")
    override fun onBackPressed() {
        val current=directory ?: serverRoot
        if(current!=serverRoot) {
            directory=current.parentFile ?: serverRoot
            filter=""
            showFolder()
        } else super.onBackPressed()
    }

    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun safe(file:File)=ServerFileOps.checked(serverRoot,file)

    private fun openPath(file:File) {
        val item=try{safe(file)}catch(_:Exception){toast("Unsafe path");return}
        when {
            item.isDirectory -> {directory=item;filter="";showFolder()}
            item.isFile -> {directory=item.parentFile;showFolder();showFile(item)}
            else -> AlertDialog.Builder(this).setTitle("Not generated yet")
                .setMessage("Minecraft or Geyser must create this file first:\n"+
                    item.relativeTo(serverRoot).invariantSeparatorsPath)
                .setPositiveButton("OK",null).show()
        }
    }

    /**
     * Launch the real Android Files browser, NOT ACTION_OPEN_DOCUMENT_TREE.
     * DocumentsUI intentionally hides a caller's own DocumentsProvider in
     * self-initiated picker dialogs, so PocketCraft's provider is absent there.
     */
    private fun openInAndroidFiles() {
        val rootUri=android.provider.DocumentsContract.buildRootUri(
            PocketCraftDocumentsProvider.AUTHORITY,
            PocketCraftDocumentsProvider.ROOT_ID)
        val openRoot=Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(rootUri,android.provider.DocumentsContract.Root.MIME_TYPE_ITEM)
        }
        try {
            startActivity(openRoot)
            return
        } catch (_:android.content.ActivityNotFoundException) {
            // Older OEM file managers may not implement the root VIEW action.
        } catch (_:SecurityException) {
            // Fall back to the Files launcher when direct root navigation is unavailable.
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                startActivity(Intent.makeMainSelectorActivity(
                    Intent.ACTION_MAIN,Intent.CATEGORY_APP_FILES))
                toast("In Files, open the menu and choose PocketCraft Server.")
                return
            } catch (_:Exception) { }
        }
        AlertDialog.Builder(this).setTitle("Android Files unavailable")
            .setMessage("Open your phone's Files app directly and look for PocketCraft Server in storage locations. If it isn't supported by your file manager, use PocketCraft's built-in file editor or Export Complete Server ZIP.")
            .setPositiveButton("OK",null).show()
    }

    private fun showFolder() {
        val current=runCatching{safe(directory ?: serverRoot)}.getOrDefault(serverRoot)
        if(!current.isDirectory){directory=serverRoot;return showFolder()}
        directory=current
        val screen=ui.column(this).apply{setBackgroundColor(ui.BG)}
        screen.addView(HomesteadComponents.subpageHeader(this,
            "Server files",if(current==serverRoot)"On-device storage"
            else current.name) { onBackPressed() })
        val scroll=ScrollView(this)
        val body=ui.column(this)
        body.setPadding(ui.dp(this,17),ui.dp(this,7),ui.dp(this,17),ui.dp(this,24))
        val breadcrumb=if(current==serverRoot)"Minecraft /" else
            "Minecraft / "+current.relativeTo(serverRoot).invariantSeparatorsPath
        body.addView(ui.text(this,breadcrumb,12f,ui.GREEN,true))
        body.addView(ui.space(this,9))
        body.addView(ui.space(this,10))
        val controls=ui.row(this)
        controls.addView(ui.action(this,"SEARCH"){
            val field=EditText(this).apply {
                setText(filter);hint="Filter names in this folder";setSingleLine(true)
            }
            AlertDialog.Builder(this).setTitle("Search files").setView(field)
                .setNegativeButton("CANCEL",null)
                .setNeutralButton("CLEAR"){_,_->filter="";showFolder()}
                .setPositiveButton("SEARCH"){_,_->filter=field.text.toString().trim();showFolder()}
                .show()
        },ui.weight(this))
        val sortPrefs=getSharedPreferences("pocketcraft",0)
        val sortMode=ServerFileSort.parse(sortPrefs.getString("fileSortMode",null))
        controls.addView(ui.action(this,"SORT") {
            val modes=ServerFileSort.Mode.entries
            AlertDialog.Builder(this).setTitle("Sort server files")
                .setSingleChoiceItems(modes.map {it.label}.toTypedArray(),modes.indexOf(sortMode)) {
                    dialog,index ->
                    sortPrefs.edit().putString("fileSortMode",modes[index].name).apply()
                    dialog.dismiss()
                    showFolder()
                }.setNegativeButton("CANCEL",null).show()
        },ui.weight(this))
        controls.addView(ui.action(this,"REFRESH"){showFolder()},
            LinearLayout.LayoutParams(0,-2,1f))
        body.addView(controls)
        body.addView(ui.space(this,8))
        body.addView(ui.text(this,"Sorted: "+sortMode.label+
            if(filter.isBlank())"" else "  ·  Search: "+filter.take(34),
            11f,ui.MUTED))
        body.addView(ui.space(this,11))
        if(current==serverRoot){
            val shortcuts=ui.card(this)
            shortcuts.addView(ui.text(this,"QUICK ACCESS",11f,ui.CYAN,true))
            shortcuts.addView(ui.space(this,11))
            for((title,path) in listOf(
                "Geyser config" to "config/Geyser-Fabric/config.yml",
                "Server properties" to "server.properties",
                "Mods folder" to "mods",
                "Datapacks" to (WorldStore.active(this@ServerFilesActivity)+"/datapacks"),
                "Console log" to "console.log"
            )) {
                shortcuts.addView(HomesteadComponents.menuRow(this,"▤",title,
                    if(path.endsWith(".yml") || path.endsWith(".properties"))
                        "View or edit this configuration"
                    else if(path.endsWith(".log")) "Read diagnostic output"
                    else "Browse the folder") { openPath(File(serverRoot,path)) })
                shortcuts.addView(ui.space(this,7))
            }
            shortcuts.addView(HomesteadComponents.menuRow(this,"✓",
                "Diagnose Bedrock","Review crossplay logs and connection details") {
                diagnoseBedrock()
            })
            body.addView(shortcuts)
            body.addView(ui.space(this,13))
            val access=ui.card(this)
            access.addView(ui.text(this,"ANDROID FILES",16f,ui.WHITE,true))
            access.addView(ui.space(this,8))
            access.addView(ui.space(this,9))
            access.addView(ui.action(this,"OPEN FILES",true) {
                openInAndroidFiles()
            })
            body.addView(access)
            body.addView(ui.space(this,13))
            val backupCard=ui.card(this)
            backupCard.addView(ui.text(this,"BACKUP & RESTORE",11f,ui.GREEN,true))
            backupCard.addView(ui.space(this,8))
            backupCard.addView(ui.text(this,"Full server backup",16f,ui.WHITE,true))
            backupCard.addView(ui.space(this,8))
            backupCard.addView(ui.action(this,"ⓘ  ABOUT BACKUPS") {
                AlertDialog.Builder(this).setTitle("Server backups")
                    .setMessage("Exports worlds, mods and settings. Stop Minecraft first. " +
                        "Java runtime and private Playit credentials are not included.")
                    .setPositiveButton("OK",null).show()
            })
            backupCard.addView(ui.space(this,9))
            backupCard.addView(ui.action(this,"EXPORT ZIP",true){startExportAll()})
            backupCard.addView(ui.space(this,8))
            backupCard.addView(ui.action(this,"RESTORE ZIP"){startImportAll()})
        backupCard.addView(ui.space(this,8))
        backupCard.addView(ui.action(this,"VERIFY ZIP"){startVerifyBackup()})
            body.addView(backupCard)
            body.addView(ui.space(this,13))
        }
        val files=current.listFiles()?.filter {
            runCatching{safe(it);true}.getOrDefault(false) &&
                (filter.isBlank()||it.name.contains(filter,true))
        }?.sortedWith(ServerFileSort.comparator(sortMode)) ?: emptyList()
        if(files.isEmpty()){
            body.addView(ui.text(this,"No files",12f,ui.MUTED))
        }
        files.take(200).forEach { file ->
            val folder=file.isDirectory
            val button=ui.action(this,(if(folder)"▣  " else "▤  ")+file.name) {openPath(file)}
            button.contentDescription=if(folder)"Open folder "+file.name else "Open file "+file.name
            body.addView(button)
            body.addView(ui.text(this,if(folder)"Folder" else ServerFileOps.humanSize(file.length()),11f,ui.MUTED))
            body.addView(ui.space(this,9))
        }
        if(files.size>200)body.addView(ui.text(this,"200 items shown • Use Search",12f,ui.MUTED))
        scroll.addView(body)
        screen.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(screen)
    }

    private fun showFile(file:File) {
        val checked=try{safe(file)}catch(_:Exception){toast("Cannot access file");return}
        if(!checked.isFile){toast("File missing");return}
        val editable=ServerFileOps.isEditable(checked)
        val panel=ui.column(this)
        panel.setPadding(ui.dp(this,15),ui.dp(this,12),ui.dp(this,15),ui.dp(this,14))
        panel.addView(ui.text(this,checked.relativeTo(serverRoot).invariantSeparatorsPath,14f,ui.WHITE,true))
        panel.addView(ui.space(this,8))
        panel.addView(ui.text(this,ServerFileOps.humanSize(checked.length()),12f,ui.MUTED))
        if(editable){
            val preview=ScrollView(this)
            preview.addView(ui.text(this,
                runCatching{ServerFileOps.readText(serverRoot,checked)}.getOrDefault("Unable to preview"),
                12f,ui.WHITE).apply {
                typeface=Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(ui.dp(this@ServerFilesActivity,10),ui.dp(this@ServerFilesActivity,10),
                    ui.dp(this@ServerFilesActivity,10),ui.dp(this@ServerFilesActivity,10))
                background=ui.shape(this@ServerFilesActivity,ui.SUBTLE,12)
            })
            panel.addView(preview,LinearLayout.LayoutParams(-1,ui.dp(this,200)))
            panel.addView(ui.space(this,9))
            panel.addView(ui.action(this,"EDIT (BACKUP FIRST)",true,!ServerService.running) {editFile(checked)})
            panel.addView(ui.space(this,8))
            panel.addView(ui.action(this,"RESTORE PREVIOUS VERSION",false,!ServerService.running){restoreFile(checked)})
        }
        panel.addView(ui.space(this,9))
        panel.addView(ui.action(this,"EXPORT FILE") {
            if(checked.path.contains("geyser",true)||checked.path.contains("floodgate",true)) {
                AlertDialog.Builder(this).setTitle("Private file warning")
                    .setMessage("This file could contain your NetherNet token or other private credentials. Keep exports private.")
                    .setNegativeButton("CANCEL",null)
                    .setPositiveButton("EXPORT"){_,_->exportFile(checked)}.show()
            }else exportFile(checked)
        })
        panel.addView(ui.space(this,9))
        panel.addView(ui.action(this,"COPY FILE PATH"){
            val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PocketCraft file path",
                checked.relativeTo(serverRoot).invariantSeparatorsPath))
            toast("File path copied")
        })
        val scroll=ScrollView(this).apply{addView(panel)}
        AlertDialog.Builder(this).setTitle(checked.name).setView(scroll)
            .setPositiveButton("CLOSE",null).show()
    }

    private fun editFile(file:File){
        if(ServerService.running){toast("Stop Minecraft before editing");return}
        val old=try{ServerFileOps.readText(serverRoot,file)}
            catch(e:Exception){toast(e.message ?: "Cannot read file");return}
        val box=ui.column(this)
        box.setPadding(ui.dp(this,14),ui.dp(this,10),ui.dp(this,14),ui.dp(this,12))
        box.addView(ui.text(this,"A dated backup is created before saving. YAML indentation is significant.",12f,ui.MUTED))
        val field=EditText(this).apply{
            setText(old);setSingleLine(false);typeface=Typeface.MONOSPACE
            textSize=12f;setTextColor(ui.WHITE)
            gravity=Gravity.TOP
            background=ui.shape(this@ServerFilesActivity,ui.SUBTLE,12)
        }
        box.addView(field,LinearLayout.LayoutParams(-1,ui.dp(this,320)))
        val dialog=AlertDialog.Builder(this).setTitle("Edit "+file.name)
            .setView(ScrollView(this).apply{addView(box)})
            .setNegativeButton("CANCEL",null).setPositiveButton("SAVE",null).create()
        dialog.setOnShowListener{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                if(ServerService.running){toast("Stop Minecraft before saving");return@setOnClickListener}
                try{
                    if(field.text.toString()!=old) {
                        ServerFileOps.saveText(serverRoot,file,field.text.toString())
                    }
                    dialog.dismiss()
                    toast("Saved. Restart Minecraft for changes to apply.")
                }catch(e:Exception){field.error=e.message ?: "Save failed"}
            }
        }
        dialog.show()
    }

    private fun restoreFile(file:File){
        if(ServerService.running){toast("Stop Minecraft first");return}
        val backups=file.parentFile?.listFiles()?.filter{
            it.isFile && it.name.startsWith(file.name+".pocketcraft-") && it.name.endsWith(".bak")
        }?.sortedByDescending{it.lastModified()} ?: emptyList()
        if(backups.isEmpty()){toast("No backup saved for this file yet");return}
        val latest=backups.first()
        AlertDialog.Builder(this).setTitle("Restore "+file.name+"?")
            .setMessage("Restore backup "+latest.name+"? The current file will be replaced.")
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("RESTORE"){_,_->
                try{ServerFileOps.restore(serverRoot,file,latest);toast("Restored. Restart Minecraft.")}
                catch(e:Exception){toast("Restore failed: "+e.message)}
            }.show()
    }

    private fun exportFile(file:File){
        pendingExport=file
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type="application/octet-stream"
            putExtra(Intent.EXTRA_TITLE,file.name)
        }
        startActivityForResult(intent,EXPORT_FILE)
    }

    private fun startExportAll(){
        if(ServerService.running){toast("Stop Minecraft before exporting");return}
        AlertDialog.Builder(this).setTitle("Backup entire server?")
            .setMessage("Export worlds, mods and server configs to a ZIP in your phone's Documents or Downloads folder. Protect your backup: Geyser configs may contain private tunnel keys.")
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("CREATE ZIP"){_,_->
                val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type="application/zip"
                    putExtra(Intent.EXTRA_TITLE,"PocketCraft-server-backup.zip")
                }
                startActivityForResult(intent,EXPORT_ALL)
            }.show()
    }

    private fun startVerifyBackup(){
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type="application/zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },VERIFY_BACKUP)
    }

    private fun startImportAll(){
        if(ServerService.running){toast("Stop Minecraft before restoring");return}
        AlertDialog.Builder(this).setTitle("Restore server backup?")
            .setMessage("Only use a PocketCraft ZIP you created. Restore is allowed only to an empty Minecraft server folder, so your current worlds and mods cannot be overwritten.")
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("CHOOSE ZIP"){_,_->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type="application/zip"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },IMPORT_ALL)
            }.show()
    }

    @Deprecated("Uses Android system document picker")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK)return
        val uri=data?.data ?: return
        if(requestCode !in listOf(EXPORT_FILE,EXPORT_ALL,IMPORT_ALL,VERIFY_BACKUP))return
        val file=pendingExport
        pendingExport=null
        Thread{
            val result=runCatching{
                when(requestCode) {
                    EXPORT_FILE -> {
                        val src=file ?: throw IOException("No export file selected")
                        val safe=ServerFileOps.checked(serverRoot,src)
                        val output=contentResolver.openOutputStream(uri,"w")
                            ?: throw IOException("Cannot open output document")
                        output.use{out->safe.inputStream().use{it.copyTo(out)}}
                        "File exported"
                    }
                    EXPORT_ALL -> "Saved "+ServerDataTransfer.backup(this,uri)+" server files to ZIP"
                    VERIFY_BACKUP -> {
                        val verified=contentResolver.openInputStream(uri)?.use {
                            BackupArchiveVerifier.verify(it)
                        } ?: throw IOException("Cannot read selected backup")
                        "ZIP verified: "+verified.files+" files · "+
                            (verified.expandedBytes/1048576L)+" MB unpacked"
                    }
                    else -> "Restored "+ServerDataTransfer.restore(this,uri)+" ZIP entries"
                }
            }
            runOnUiThread{
                result.onSuccess{toast(it);showFolder()}
                    .onFailure{
                        AlertDialog.Builder(this).setTitle("File operation failed")
                            .setMessage(it.message ?: "Unknown error")
                            .setPositiveButton("OK",null).show()
                    }
            }
        }.start()
    }

    private fun diagnoseBedrock(){
        val mods=File(serverRoot,"mods").listFiles()?.map{it.name.lowercase()} ?: emptyList()
        val conf=File(serverRoot,"config/Geyser-Fabric/config.yml")
        val geyser=mods.any{it.contains("geyser")&&it.endsWith(".jar")}
        val floodgate=mods.any{it.contains("floodgate")&&it.endsWith(".jar")}
        val fabric=mods.any{it.contains("fabric-api")&&it.endsWith(".jar")}
        val geyserYml=if(conf.isFile && conf.length()<ServerFileOps.MAX_EDIT_BYTES)
            runCatching{conf.readText(Charsets.UTF_8)}.getOrDefault("") else ""
        val bedrockPort=Regex("""(?m)^ *port: *([0-9]{1,5}) *$""")
            .find(geyserYml)?.groupValues?.get(1) ?: "not detected"
        val authType=Regex("""(?m)^ *auth-type: *([a-zA-Z_-]+)""")
            .find(geyserYml)?.groupValues?.get(1) ?: "not detected"
        val body=listOf(
            "Minecraft Java: "+if(ServerService.javaReady)"Ready" else "Not ready",
            "Geyser-Fabric JAR: "+if(geyser)"Present" else "Missing",
            "Floodgate JAR: "+if(floodgate)"Present" else "Missing",
            "Fabric API JAR: "+if(fabric)"Present" else "Missing",
            "Geyser config: "+if(conf.isFile)"Found" else "Missing",
            "Bedrock port in config: "+bedrockPort,
            "Geyser auth mode: "+authType,
            "Bedrock listener: "+if(ServerService.bedrockReady)"Reported" else "Not confirmed",
            "NetherNet signaling: "+if(ServerService.nethernetReady)"Registered" else "Not confirmed",
            "Playit agent: "+PlayitManager.status,
            "",
            "NEXT STEPS",
            "1. Test Bedrock on the same Wi-Fi using the phone IP and Geyser's Bedrock port.",
            "2. For Playit Bedrock (NetherNet), apply your tunnel's exact Configuration help → Geyser settings.",
            "3. Restart the server and check the Geyser log for connection or authentication errors.",
            "4. Never share a Geyser config containing your private NetherNet token."
        ).joinToString("\n")
        AlertDialog.Builder(this).setTitle("Bedrock connection diagnostics")
            .setMessage(body)
            .setNeutralButton("COPY SAFE REPORT"){_,_->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("PocketCraft Bedrock checks",body))
                toast("Copied diagnostic summary without secrets. You can share it for help.")
            }
            .setNegativeButton("GEYSER CONFIG"){_,_->openPath(conf)}
            .setPositiveButton("DONE",null).show()
    }
}
