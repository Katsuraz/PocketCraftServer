package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.AtomicFile
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

    private fun addInput(parent: LinearLayout, label: String, help: String, value: String,
                         numeric: Boolean = false, password: Boolean = false): EditText {
        parent.addView(ui.text(this, label, 14f, ui.WHITE, true))
        parent.addView(ui.space(this, 4))
        parent.addView(ui.text(this, help, 11f, ui.MUTED))
        parent.addView(ui.space(this, 7))
        val edit = EditText(this).apply {
            setSingleLine(true)
            setText(value)
            textSize = 15f
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = ui.shape(this@GeyserSettingsActivity, ui.SURFACE, 12, ui.BORDER)
            setPadding(ui.dp(this@GeyserSettingsActivity, 13), ui.dp(this@GeyserSettingsActivity, 10),
                ui.dp(this@GeyserSettingsActivity, 13), ui.dp(this@GeyserSettingsActivity, 10))
        }
        parent.addView(edit, LinearLayout.LayoutParams(-1, -2))
        parent.addView(ui.space(this, 16))
        return edit
    }

    private fun addSwitch(parent: LinearLayout, label: String, help: String, value: Boolean): Switch {
        val row = ui.row(this)
        val labels = ui.column(this).apply {
            addView(ui.text(this@GeyserSettingsActivity, label, 14f, ui.WHITE, true))
            addView(ui.space(this@GeyserSettingsActivity, 4))
            addView(ui.text(this@GeyserSettingsActivity, help, 11f, ui.MUTED))
        }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this).apply { isChecked = value }
        row.addView(toggle)
        parent.addView(row)
        parent.addView(ui.space(this, 16))
        return toggle
    }

    private fun addSelector(parent: LinearLayout, title: String, choices: List<String>,
                            initial: String, changed: (String) -> Unit) {
        parent.addView(ui.text(this, title, 14f, ui.WHITE, true))
        parent.addView(ui.space(this, 8))
        val button = ui.action(this, initial.uppercase() + "  ▾") { }
        button.setOnClickListener {
            PocketSounds.play(this, PocketSounds.Event.CLICK)
            AlertDialog.Builder(this).setTitle(title)
                .setItems(choices.toTypedArray()) { _, index ->
                    button.text = choices[index].uppercase() + "  ▾"
                    changed(choices[index])
                }.setNegativeButton("CANCEL", null).show()
        }
        parent.addView(button)
        parent.addView(ui.space(this, 16))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            "Bedrock & Geyser","Network configuration") { finish() })

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = ui.column(this).apply {
            setPadding(ui.dp(this@GeyserSettingsActivity, 18), 0,
                ui.dp(this@GeyserSettingsActivity, 18), ui.dp(this@GeyserSettingsActivity, 20))
        }
        val card = ui.card(this)
        card.addView(ui.text(this, "Connect Bedrock players", 19f, ui.WHITE, true))
        card.addView(ui.space(this, 6))
        card.addView(ui.text(this,
            "Edit only the network settings you need. All other config.yml options stay unchanged.",
            12f, ui.MUTED))
        card.addView(ui.space(this, 17))

        if (loaded.isBlank()) {
            card.addView(ui.text(this,
                "No Geyser config.yml found. Start Geyser once to generate it, or import a config file below.",
                12f, ui.RED))
            card.addView(ui.space(this, 13))
        }
        val health = if (loaded.isNotBlank()) GeyserNetworkSettings.parse(loaded) else null
        health?.warnings?.forEach { warning ->
            card.addView(ui.text(this, "⚠ " + warning, 11f, ui.RED))
            card.addView(ui.space(this, 6))
        }
        if (serverBusy()) {
            card.addView(ui.text(this,
                "Server is running. Stop Minecraft before saving or importing Geyser settings.",
                12f, ui.RED))
            card.addView(ui.space(this, 12))
        }

        address = addInput(card, "Local bind IP",
            "Normally 0.0.0.0. This is not the Playit public address.", values.address)
        port = addInput(card, "Bedrock port",
            "Local signaling or RakNet port, normally 19132.", values.port.toString(), numeric = true)
        webRtcPort = addInput(card, "NetherNet UDP port",
            "0 shares the Bedrock port. Use a separate UDP port if transport is BOTH.",
            values.webRtcPort.toString(), numeric = true)
        addSelector(card, "Bedrock transport", listOf("nethernet", "raknet", "both"), transport) {
            transport = it
        }
        addSelector(card, "NetherNet signaling", listOf("hybrid", "builtin", "nxs", "none"), signaling) {
            signaling = it
        }
        card.addView(ui.text(this,
            "HYBRID runs local built-in signaling and external NXS together. NXS alone may not allow local-IP joining. Playit still requires a valid token and reachable networking.",
            11f, ui.CYAN))
        card.addView(ui.space(this, 16))
        advertised = addInput(card, "Advertised addresses (optional)",
            "Comma-separated public hosts or IPs. Leave blank for automatic discovery; never put the Java tunnel address here.",
            values.advertiseAddresses)
        stun = addSwitch(card, "STUN discovery",
            "Discover your external address when no addresses are advertised.", values.stunDiscovery)
        endpoint = addInput(card, "NXS service URL",
            "For Playit, use https://nethernet.playit.gg", values.nxsEndpoint)
        token = addInput(card, "Replace NXS token (optional)",
            if (values.hasSavedNxsToken)
                "A token is already saved. Leave blank to keep it; enter a new token only to replace."
            else "Paste the private token from Playit Configuration help. Never share or screenshot it.",
            "", password = true)
        assisted = addSwitch(card, "Assisted joins",
            "Allow NXS to assist connections where direct peer-to-peer access is unavailable.",
            values.assistedJoins)

        card.addView(ui.action(this, "SAVE GEYSER SETTINGS", true, loaded.isNotBlank() && !serverBusy()) {
            save()
        })
        card.addView(ui.space(this, 9))
        card.addView(ui.action(this, "IMPORT / REPLACE CONFIG.YML", false, !serverBusy()) {
            selectConfig()
        })
        card.addView(ui.space(this, 9))
        card.addView(ui.action(this, "RESTORE PREVIOUS CONFIG", false,
            backup.isFile && !serverBusy()) { restoreBackup() })
        card.addView(ui.space(this, 10))
        card.addView(ui.text(this,
            "Saving makes a private backup first. Restart Minecraft for changes to apply. No token is shown or copied into diagnostic logs.",
            11f, ui.MUTED))
        body.addView(card)
        scroll.addView(body)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        ui.styleFormInputs(screen)
        setContentView(screen)
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
