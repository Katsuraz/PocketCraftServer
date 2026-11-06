package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.AtomicFile
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Friendly Geyser editor. Secrets are not displayed and the server must be
 * stopped for all writes. The full config is never logged or uploaded.
 */
class GeyserSettingsActivity : Activity() {
    private val ui = PocketUi
    private val config by lazy { File(WorldStore.root(this), "config/Geyser-Fabric/config.yml") }
    private val backup by lazy { File(config.parentFile, "config.yml.pocketcraft.bak") }
    private fun serverBusy(): Boolean =
        ServerService.running || ServerService.status in setOf("STARTING", "STOPPING")


    private lateinit var address: EditText
    private lateinit var port: EditText
    private lateinit var webRtcPort: EditText
    private lateinit var advertised: EditText
    private lateinit var endpoint: EditText
    private lateinit var token: EditText
    private lateinit var stun: Switch
    private lateinit var assisted: Switch
    private var transport = "nethernet"
    private var signaling = "hybrid"
    private var selectedPane = "Connect"
    private var saveFooter: View? = null

    /** Compact labels keep help available without printing tutorials on the screen. */
    private fun fieldLabel(parent: LinearLayout, title: String, help: String) {
        val row=ui.row(this)
        row.addView(ui.text(this,title,13f,ui.WHITE,true),
            LinearLayout.LayoutParams(0,-2,1f))
        val info=ui.text(this,"ⓘ",15f,ui.MUTED,true).apply {
            gravity=android.view.Gravity.CENTER
            minWidth=ui.dp(this@GeyserSettingsActivity,36)
            minHeight=ui.dp(this@GeyserSettingsActivity,36)
            isClickable=true
            isFocusable=true
            contentDescription="Help: $title"
            setOnClickListener {
                AlertDialog.Builder(this@GeyserSettingsActivity)
                    .setTitle(title).setMessage(help)
                    .setPositiveButton("OK",null).show()
            }
        }
        row.addView(info)
        parent.addView(row)
        parent.addView(ui.space(this,4))
    }

    private fun addInput(parent: LinearLayout, label: String, help: String, value: String,
                         numeric: Boolean = false, password: Boolean = false): EditText {
        fieldLabel(parent,label,help)
        val edit=EditText(this).apply {
            setSingleLine(true)
            setText(value)
            textSize=14f
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            if(numeric) inputType=InputType.TYPE_CLASS_NUMBER
            if(password) inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background=ui.shape(this@GeyserSettingsActivity,ui.SURFACE,10,ui.BORDER)
            setPadding(ui.dp(this@GeyserSettingsActivity,12),ui.dp(this@GeyserSettingsActivity,7),
                ui.dp(this@GeyserSettingsActivity,12),ui.dp(this@GeyserSettingsActivity,7))
        }
        parent.addView(edit,LinearLayout.LayoutParams(-1,-2))
        parent.addView(ui.space(this,10))
        return edit
    }

    private fun addSwitch(parent: LinearLayout, label: String, help: String, value: Boolean): Switch {
        val row=ui.row(this)
        val labelText=ui.text(this,label,13f,ui.WHITE,true)
        row.addView(labelText,LinearLayout.LayoutParams(0,-2,1f))
        val info=ui.text(this,"ⓘ",15f,ui.MUTED,true).apply {
            gravity=android.view.Gravity.CENTER
            minWidth=ui.dp(this@GeyserSettingsActivity,36)
            minHeight=ui.dp(this@GeyserSettingsActivity,40)
            isClickable=true
            isFocusable=true
            contentDescription="Help: $label"
            setOnClickListener {
                AlertDialog.Builder(this@GeyserSettingsActivity).setTitle(label)
                    .setMessage(help).setPositiveButton("OK",null).show()
            }
        }
        row.addView(info)
        val toggle=Switch(this).apply { isChecked=value; contentDescription=label }
        row.addView(toggle)
        parent.addView(row)
        parent.addView(ui.space(this,8))
        return toggle
    }

    private fun addSelector(parent: LinearLayout, title: String, choices: List<String>,
                            initial: String, changed: (String) -> Unit) {
        val row=ui.row(this)
        row.addView(ui.text(this,title,13f,ui.WHITE,true),
            LinearLayout.LayoutParams(0,-2,1f))
        val button=ui.text(this,initial.uppercase()+"  ▾",13f,ui.GREEN,true).apply {
            gravity=android.view.Gravity.CENTER
            setPadding(ui.dp(this@GeyserSettingsActivity,12),ui.dp(this@GeyserSettingsActivity,10),
                ui.dp(this@GeyserSettingsActivity,12),ui.dp(this@GeyserSettingsActivity,10))
            background=ui.pressable(this@GeyserSettingsActivity,
                ui.shape(this@GeyserSettingsActivity,ui.SURFACE,10,ui.BORDER))
            isClickable=true
            isFocusable=true
            contentDescription="$title: $initial"
        }
        button.setOnClickListener {
            PocketSounds.play(this,PocketSounds.Event.CLICK)
            AlertDialog.Builder(this).setTitle(title)
                .setItems(choices.toTypedArray()) { _,index ->
                    button.text=choices[index].uppercase()+"  ▾"
                    button.contentDescription="$title: "+choices[index]
                    changed(choices[index])
                }.setNegativeButton("CANCEL",null).show()
        }
        row.addView(button)
        parent.addView(row)
        parent.addView(ui.space(this,10))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedPane=savedInstanceState?.getString("geyserPane")
            ?.takeIf { it in listOf("Connect","Advanced","Files") } ?: "Connect"
        ui.loadTheme(this)
        PocketSounds.prepare(this)
        window.statusBarColor = ui.BG
        window.navigationBarColor = ui.BG
        render()
    }

    private fun render() {
        val loaded = runCatching {
            if (config.isFile && config.length() in 1..128000) config.readText(Charsets.UTF_8) else ""
        }.getOrDefault("")
        val values = GeyserConfigEditor.read(loaded)
        transport = values.transport.takeIf { it in listOf("raknet", "nethernet", "both") } ?: "nethernet"
        signaling = values.signalingMode.takeIf { it in listOf("builtin", "nxs", "hybrid", "none") } ?: "hybrid"

        val screen = ui.column(this).apply { setBackgroundColor(ui.BG) }
        screen.addView(HomesteadComponents.subpageHeader(this,
            "Bedrock","Crossplay") { finish() })

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = ui.column(this).apply {
            setPadding(ui.dp(this@GeyserSettingsActivity, 18), 0,
                ui.dp(this@GeyserSettingsActivity, 18), ui.dp(this@GeyserSettingsActivity, 20))
        }
        val intro=ui.card(this)
        intro.addView(ui.text(this,"Crossplay",20f,ui.WHITE,true))
        intro.addView(ui.space(this,8))
        intro.addView(ui.pill(this,when {
            loaded.isBlank() -> "NOT SET UP"
            serverBusy() -> "SERVER ACTIVE • READ ONLY"
            else -> "READY TO EDIT"
        },when {
            loaded.isBlank() -> ui.RED
            serverBusy() -> ui.CYAN
            else -> ui.GREEN
        }))
        if(loaded.isBlank()) {
            intro.addView(ui.space(this,8))
            intro.addView(ui.text(this,"Start Geyser once to create config.yml.",12f,ui.MUTED))
        }
        val health=if(loaded.isNotBlank()) GeyserNetworkSettings.parse(loaded) else null
        if(!health?.warnings.isNullOrEmpty()) {
            intro.addView(ui.space(this,8))
            intro.addView(ui.action(this,"⚠  "+health!!.warnings.size+" CONFIG WARNINGS") {
                AlertDialog.Builder(this).setTitle("Geyser warnings")
                    .setMessage(health.warnings.joinToString("\n\n"))
                    .setPositiveButton("OK",null).show()
            })
        }
        body.addView(intro)
        body.addView(ui.space(this,12))

        val panels=linkedMapOf<String,LinearLayout>()
        val buttons=linkedMapOf<String,TextView>()
        val nav=ui.row(this)
        listOf("Connect","Advanced","Files").forEachIndexed { i,name ->
            val button=ui.text(this,name,12f,ui.MUTED,true).apply {
                minHeight=ui.dp(this@GeyserSettingsActivity,48)
                gravity=android.view.Gravity.CENTER
                isClickable=true
                isFocusable=true
                contentDescription="$name Geyser settings"
                setOnClickListener {
                    selectedPane=name
                    PocketSounds.play(this@GeyserSettingsActivity,PocketSounds.Event.SELECT)
                    panels.forEach { (section,panel) ->
                        panel.visibility=if(section==name)View.VISIBLE else View.GONE
                    }
                    buttons.forEach { (section,button) ->
                        val chosen=section==name
                        button.background=ui.pressable(this@GeyserSettingsActivity,
                            ui.shape(this@GeyserSettingsActivity,
                                if(chosen)ui.SURFACE else ui.PANEL,12,
                                if(chosen)ui.BORDER else null))
                        button.setTextColor(if(chosen)ui.GREEN else ui.MUTED)
                    }
                    saveFooter?.visibility=if(name=="Advanced")View.VISIBLE else View.GONE
                }
            }
            buttons[name]=button
            nav.addView(button,LinearLayout.LayoutParams(0,ui.dp(this,48),1f).apply {
                if(i!=2)marginEnd=ui.dp(this@GeyserSettingsActivity,5)
            })
        }
        body.addView(nav)
        body.addView(ui.space(this,13))

        val quick=ui.card(this)
        quick.addView(HomesteadComponents.sectionHeading(this,"Playit",""))
        quick.addView(ui.space(this,10))
        quick.addView(ui.pill(this,
            if(values.hasSavedNxsToken) "NXS TOKEN SAVED" else "NXS TOKEN MISSING",
            if(values.hasSavedNxsToken) ui.GREEN else ui.RED))
        quick.addView(ui.space(this,12))
        quick.addView(ui.action(this,"IMPORT PLAYIT SETTINGS",
            true,loaded.isNotBlank() && !serverBusy()) { pastePlayitSettings() })
        quick.addView(ui.space(this,8))
        quick.addView(ui.action(this,"MANUAL SETUP  ›") {
            selectedPane="Advanced"
            render()
        })
        quick.addView(ui.space(this,10))
        quick.addView(ui.text(this,"Bedrock  •  "+values.transport.uppercase()+
            "  •  "+values.port,12f,ui.MUTED))
        panels["Connect"]=quick

        val local=ui.card(this)
        local.addView(HomesteadComponents.sectionHeading(this,"Local",""))
        local.addView(ui.space(this,10))
        address=addInput(local,"Bind IP",
            "Local interface. Use 0.0.0.0 for all interfaces. Do not paste the public Playit hostname here.",
            values.address)
        port=addInput(local,"Bedrock port",
            "Port used by the Bedrock listener, normally 19132.",values.port.toString(),numeric=true)
        webRtcPort=addInput(local,"WebRTC port",
            "0 shares the Bedrock port. Both transports need a separate WebRTC UDP port.",
            values.webRtcPort.toString(),numeric=true)
        addSelector(local,"Transport",listOf("nethernet","raknet","both"),transport) {
            transport=it
        }


        val internet=ui.card(this)
        internet.addView(HomesteadComponents.sectionHeading(this,"Signaling",""))
        internet.addView(ui.space(this,10))
        addSelector(internet,"Mode",listOf("hybrid","builtin","nxs","none"),signaling) {
            signaling=it
        }
        endpoint=addInput(internet,"NXS URL",
            "External signaling endpoint. Use the URL supplied by your provider. For Playit: https://nethernet.playit.gg",
            values.nxsEndpoint)
        token=addInput(internet,"NXS token",
            if(values.hasSavedNxsToken) "Saved securely. Leave blank to keep the current token."
            else "Paste the private token from Playit's Geyser configuration help. Never share it.",
            "",password=true)
        advertised=addInput(internet,"Advertised IP",
            "Optional comma-separated public UDP endpoints. Use the exact values from Playit's Geyser instructions.",
            values.advertiseAddresses)
        stun=addSwitch(internet,"STUN discovery",
            "Find your public IP automatically when no advertised endpoint is configured.",
            values.stunDiscovery)
        assisted=addSwitch(internet,"Assisted joins",
            "Allow the signaling provider to help peers connect when direct UDP does not work.",
            values.assistedJoins)
        val advanced=ui.column(this)
        advanced.addView(local)
        advanced.addView(ui.space(this,10))
        advanced.addView(internet)
        panels["Advanced"]=advanced

        val filesPane=ui.card(this)
        filesPane.addView(HomesteadComponents.sectionHeading(this,"Config files",""))
        filesPane.addView(ui.space(this,12))
        filesPane.addView(ui.action(this,"IMPORT PLAYIT",true,
            loaded.isNotBlank() && !serverBusy()) { pastePlayitSettings() })
        filesPane.addView(ui.space(this,10))
        filesPane.addView(ui.action(this,"REPLACE CONFIG",false,!serverBusy()) { selectConfig() })
        filesPane.addView(ui.space(this,10))
        filesPane.addView(ui.action(this,"RESTORE BACKUP",false,
            backup.isFile && !serverBusy()) { restoreBackup() })
        filesPane.addView(ui.space(this,10))
        filesPane.addView(ui.text(this,"Backups are automatic.",11f,ui.MUTED))
        panels["Files"]=filesPane

        panels.forEach { (name,panel) ->
            panel.visibility=if(name==selectedPane)View.VISIBLE else View.GONE
            body.addView(panel)
        }
        buttons.forEach { (name,button) ->
            val selected=name==selectedPane
            button.background=ui.pressable(this,
                ui.shape(this,if(selected)ui.SURFACE else ui.PANEL,12,
                    if(selected)ui.BORDER else null))
            button.setTextColor(if(selected)ui.GREEN else ui.MUTED)
        }
        scroll.addView(body)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer=ui.column(this).apply {
            setPadding(ui.dp(this@GeyserSettingsActivity,16),ui.dp(this@GeyserSettingsActivity,10),
                ui.dp(this@GeyserSettingsActivity,16),ui.dp(this@GeyserSettingsActivity,14))
        }
        footer.addView(ui.action(this,"SAVE CHANGES",
            true,loaded.isNotBlank() && !serverBusy()) { save() })
        footer.visibility=if(selectedPane=="Advanced")View.VISIBLE else View.GONE
        saveFooter=footer
        screen.addView(footer)
        ui.styleFormInputs(screen)
        setContentView(screen)
    }

    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("geyserPane",selectedPane)
        super.onSaveInstanceState(outState)
    }

    private fun values(): GeyserConfigEditor.Values? {
        val p = port.text.toString().trim().toIntOrNull()
        val w = webRtcPort.text.toString().trim().toIntOrNull()
        if (p == null || w == null) {
            toast("Enter valid numeric ports")
            return null
        }
        return GeyserConfigEditor.Values(
            address = address.text.toString().trim(),
            port = p,
            webRtcPort = w,
            transport = transport,
            signalingMode = signaling,
            nxsEndpoint = endpoint.text.toString().trim(),
            advertiseAddresses = advertised.text.toString().trim(),
            stunDiscovery = stun.isChecked,
            assistedJoins = assisted.isChecked
        )
    }

    private fun save() {
        if (serverBusy()) { toast("Stop the server first"); return }
        val values = values() ?: return
        val replacement = token.text.toString().trim().takeIf { it.isNotEmpty() }
        GeyserConfigEditor.validate(values, replacement)?.let { toast(it); return }
        if (values.signalingMode == "nxs") {
            AlertDialog.Builder(this).setTitle("External-only signaling?")
                .setMessage("NXS-only signaling may prevent local Bedrock joining. Use HYBRID for both local and external signaling. Continue with NXS?")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CONTINUE") { _, _ -> saveValidated(values, replacement) }
                .show()
        } else saveValidated(values, replacement)
    }

    private fun saveValidated(values: GeyserConfigEditor.Values, replacementToken: String?) {
        if (serverBusy()) { toast("Stop the server first"); return }
        try {
            val original = config.readText(Charsets.UTF_8)
            val patched = GeyserConfigEditor.patch(original, values, replacementToken)
            writeSafely(patched, keepBackup = true)
            token.text.clear()
            toast("Geyser settings saved. Restart Minecraft.")
            render()
        } catch (e: Exception) { toast("Could not save Geyser config: " + (e.message ?: "Storage error")) }
    }

    private fun writeSafely(newText: String, keepBackup: Boolean) {
        if (serverBusy()) throw IllegalStateException("Stop the server first")
        config.parentFile?.mkdirs()
        if (keepBackup && config.isFile) config.copyTo(backup, overwrite = true)
        val file = AtomicFile(config)
        val stream = file.startWrite()
        try {
            stream.write(newText.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    /** Applies the provider's official snippet without replacing unrelated Geyser settings. */
    private fun pastePlayitSettings() {
        if (serverBusy()) { toast("Stop Minecraft before editing Geyser"); return }
        if (!config.isFile || config.length() !in 1..128000L) {
            toast("Start Geyser once to generate its config.yml first")
            return
        }
        val field = EditText(this).apply {
            hint = "Paste Playit Geyser config"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 7
            maxLines = 12
            textSize = 13f
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background = ui.shape(this@GeyserSettingsActivity,ui.SURFACE,12,ui.BORDER)
            setPadding(ui.dp(this@GeyserSettingsActivity,12),ui.dp(this@GeyserSettingsActivity,10),
                ui.dp(this@GeyserSettingsActivity,12),ui.dp(this@GeyserSettingsActivity,10))
        }
        val wrap = ui.column(this).apply {
            setPadding(ui.dp(this@GeyserSettingsActivity,18),ui.dp(this@GeyserSettingsActivity,8),
                ui.dp(this@GeyserSettingsActivity,18),ui.dp(this@GeyserSettingsActivity,8))
        }
        wrap.addView(ui.text(this,"Playit → Configuration help → Geyser",12f,ui.MUTED))
        wrap.addView(ui.space(this,10))
        wrap.addView(field)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Import Playit")
            .setView(wrap)
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("IMPORT",null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (serverBusy()) { toast("Stop Minecraft first"); return@setOnClickListener }
                runCatching {
                    val existing = config.readText(Charsets.UTF_8)
                    val updated = GeyserConfigEditor.mergePlayitSnippet(existing,
                        field.text.toString())
                    writeSafely(updated, keepBackup = true)
                }.onSuccess {
                    field.text.clear()
                    dialog.dismiss()
                    toast("Playit settings merged. Restart Minecraft to apply them.")
                    render()
                }.onFailure {
                    // Validation errors name settings, never submitted token contents.
                    toast("Not applied: " + (it.message ?: "Invalid Playit Geyser configuration"))
                }
            }
        }
        dialog.show()
    }

    private fun selectConfig() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQUEST_IMPORT)
    }

    @Deprecated("Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        if (serverBusy()) { toast("Stop the server first"); return }
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else ""
            }.orEmpty()
        }.getOrDefault("")
        if (!name.endsWith(".yml", true) && !name.endsWith(".yaml", true)) {
            toast("Choose a .yml or .yaml configuration file")
            return
        }
        try {
            val bytes = ByteArrayOutputStream()
            contentResolver.openInputStream(uri)?.use { input ->
                val chunk = ByteArray(4096)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (bytes.size() + count > 128000) throw IllegalArgumentException("Config is too large")
                    bytes.write(chunk, 0, count)
                }
            } ?: throw IllegalArgumentException("Cannot open selected file")
            val imported = bytes.toString("UTF-8")
            val check = GeyserNetworkSettings.parse(imported)
            if (!check.configured)
                throw IllegalArgumentException("Not a valid Geyser config")
            GeyserConfigEditor.validateImportedStructure(imported)?.let {
                throw IllegalArgumentException(it)
            }
            val bad = GeyserConfigEditor.validate(GeyserConfigEditor.read(imported))
            if (bad != null) throw IllegalArgumentException(bad)
            AlertDialog.Builder(this).setTitle("Replace Geyser config.yml?")
                .setMessage("This replaces ALL Geyser settings, including any NXS token, with the imported file. Your current config is backed up first. Continue?")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("REPLACE") { _, _ ->
                    runCatching { writeSafely(imported, keepBackup = true) }
                        .onSuccess { toast("Config replaced. Restart Minecraft."); render() }
                        .onFailure { toast("Import failed: " + (it.message ?: "Storage error")) }
                }.show()
        } catch (e: Exception) { toast("Import failed: " + (e.message ?: "Invalid file")) }
    }

    private fun restoreBackup() {
        if (serverBusy()) { toast("Stop the server first"); return }
        AlertDialog.Builder(this).setTitle("Restore previous Geyser settings?")
            .setMessage("The current configuration will be replaced with the last PocketCraft backup.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("RESTORE") { _, _ ->
                runCatching {
                    if (!backup.isFile || backup.length() !in 1..128000)
                        throw IllegalStateException("No usable backup found")
                    writeSafely(backup.readText(Charsets.UTF_8), keepBackup = false)
                }.onSuccess { toast("Previous settings restored. Restart Minecraft."); render() }
                    .onFailure { toast("Restore failed: " + (it.message ?: "Storage error")) }
            }.show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object { private const val REQUEST_IMPORT = 208 }
}
