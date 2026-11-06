package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in, server-only Vanilla+ preset. Every download is resolved by the existing
 * Modrinth installers for the EXACT Minecraft version; no guessed JAR URLs.
 *
 * A reserved (pending) world is intentionally created BEFORE installing datapacks.
 * Minecraft has not generated level.dat yet, so generation packs affect the first chunk.
 */
class WoodlandSetupActivity : Activity() {
    companion object { const val EXTRA_NEW_WORLD = "woodland_create_world" }

    private data class Choice(
        val title: String, val slug: String, val note: String,
        val kind: String, val selected: Boolean
    )

    private val modules = listOf(
        Choice("Lithium", "lithium", "General server tick performance", "mod", true),
        Choice("FerriteCore", "ferrite-core", "Reduce RAM usage", "mod", true),
        Choice("Krypton", "krypton", "Network optimizations", "mod", true),
        Choice("Alternate Current", "alternate-current", "Reduce redstone overhead", "mod", true),
        Choice("ServerCore", "servercore", "Reduce server lag spikes; vanilla defaults", "mod", true),
        Choice("spark", "spark", "Diagnose TPS, CPU and lag", "mod", true),
        Choice("Moonrise", "moonrise-opt", "Optional chunk/tick optimization; NEVER with C2ME", "mod", false),
        Choice("Terralith", "terralith", "Vanilla-block biomes; new world strongly recommended", "datapack", true),
        Choice("Structory", "structory", "Cottages, ruins, towers and settlements", "datapack", true),
        Choice("Towns and Towers", "towns-and-towers", "Improved villages and outposts", "datapack", true),
        Choice("Nullscape", "nullscape", "Optional End dimension world generation", "datapack", false)
    )

    private val ui = PocketUi
    private val cancelled = AtomicBoolean(false)
    private var installing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.loadTheme(this)
        window.statusBarColor = ui.BG
        window.navigationBarColor = ui.BG
        render()
    }

    private fun info(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun installedIds(): Set<String> {
        val directory = File(WorldStore.root(this), "mods")
        return directory.listFiles()?.filter { it.isFile && it.extension.equals("jar", true) }
            ?.flatMap { jar ->
                val data = ModInspector.inspect(jar)
                listOfNotNull(data.modId) + data.provides
            }?.toSet() ?: emptySet()
    }

    private fun render() {
        val createWorld = intent.getBooleanExtra(EXTRA_NEW_WORLD, false)
        val gameVersion = getSharedPreferences("pocketcraft", 0)
            .getString("gameVersion", null).orEmpty()
        val parent = ui.column(this).apply { setBackgroundColor(ui.BG) }
        parent.addView(HomesteadComponents.subpageHeader(
            this, "Woodland+",
            if (createWorld) "Create a world with datapacks" else "Vanilla+ server preset"
        ) { finish() })
        val body = ui.column(this).apply {
            setPadding(ui.dp(this@WoodlandSetupActivity, 18), 0,
                ui.dp(this@WoodlandSetupActivity, 18), ui.dp(this@WoodlandSetupActivity, 18))
        }
        body.addView(ui.pill(this, "FABRIC  •  " + gameVersion, ui.CYAN))
        body.addView(ui.space(this, 12))
        body.addView(ui.text(this, "Homestead-like landscapes, cozy structures, and safer server-only optimization. Bedrock players do not need this Fabric pack installed.", 13f, ui.WHITE))
        body.addView(ui.space(this, 10))
        body.addView(ui.text(this,
            "Uses your existing Geyser/Floodgate setup; it never overwrites that configuration. Exact 26.3 files and dependencies are resolved from Modrinth. Unavailable downloads are reported, not replaced with incompatible versions.",
            12f, ui.MUTED))
        body.addView(ui.space(this, 15))
        val nameInput = EditText(this).apply {
            setSingleLine(true)
            hint = "woodland-plus"
            setText("woodland-plus")
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background = ui.shape(this@WoodlandSetupActivity, ui.SURFACE, 12, ui.BORDER)
            setPadding(ui.dp(this@WoodlandSetupActivity, 14), ui.dp(this@WoodlandSetupActivity, 12),
                ui.dp(this@WoodlandSetupActivity, 14), ui.dp(this@WoodlandSetupActivity, 12))
        }
        if (createWorld) {
            body.addView(ui.text(this, "NEW WORLD NAME", 12f, ui.CYAN, true))
            body.addView(ui.space(this, 8))
            body.addView(nameInput)
            body.addView(ui.space(this, 8))
            body.addView(ui.text(this,
                "The world will stay ungenerated until you start Minecraft. Datapacks are inserted first.",
                12f, ui.MUTED))
        } else {
            body.addView(ui.text(this, "TARGET WORLD: " + WorldStore.active(this), 13f, ui.CYAN, true))
            body.addView(ui.space(this, 5))
            body.addView(ui.text(this, "For best terrain generation, create a NEW world instead of changing an existing one.", 12f, ui.MUTED))
        }
        body.addView(ui.space(this, 18))
        val checkboxes = linkedMapOf<Choice, CheckBox>()
        listOf("mod" to "SERVER PERFORMANCE", "datapack" to "WORLD DATAPACKS").forEach { (kind, heading) ->
            body.addView(ui.text(this, heading, 15f, ui.WHITE, true))
            body.addView(ui.space(this, 8))
            modules.filter { it.kind == kind }.forEach { item ->
                val card = ui.card(this)
                val check = CheckBox(this).apply {
                    text = item.title
                    isChecked = item.selected
                    textSize = 14f
                    setTextColor(ui.WHITE)
                    minHeight = ui.dp(this@WoodlandSetupActivity, 42)
                }
                checkboxes[item] = check
                card.addView(check)
                card.addView(ui.text(this, item.note, 11f, ui.MUTED))
                body.addView(card)
                body.addView(ui.space(this, 7))
            }
            body.addView(ui.space(this, 12))
        }
        body.addView(ui.text(this,
            "Safety: Stop the server first. A failed download may leave previously installed files in place; the installer reports each result. Always back up existing worlds. Don't combine Moonrise and C2ME.",
            12f, ui.MUTED))
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        parent.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = ui.column(this).apply {
            setPadding(ui.dp(this@WoodlandSetupActivity, 18), ui.dp(this@WoodlandSetupActivity, 10),
                ui.dp(this@WoodlandSetupActivity, 18), ui.dp(this@WoodlandSetupActivity, 18))
        }
        footer.addView(ui.action(this,
            if (createWorld) "CREATE WORLD + INSTALL SELECTED" else "INSTALL SELECTED TO SERVER",
            true, gameVersion == "26.3") {
            val selections = checkboxes.filterValues { it.isChecked }.keys.toList()
            if (selections.isEmpty()) { info("Select at least one mod or datapack"); return@action }
            val name = if (createWorld) nameInput.text.toString().trim() else WorldStore.active(this)
            if (createWorld && !Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,47}").matches(name)) {
                nameInput.error = "Enter a safe world name (1–48 characters)"
                return@action
            }
            if (ServerService.running || ServerService.status in setOf("STARTING", "STOPPING")) {
                info("Stop Minecraft completely before installing."); return@action
            }
            if (!createWorld && WorldStore.worlds(this).none { it.name == name }) {
                info("Create or select a managed world first."); return@action
            }
            if (selections.any { it.slug == "moonrise-opt" } && "c2me" in installedIds()) {
                info("C2ME is installed: deselect Moonrise to avoid a known conflict.")
                return@action
            }
            AlertDialog.Builder(this)
                .setTitle(if (createWorld) "Create Woodland+ world?" else "Install Woodland+?")
                .setMessage("Target: " + name + "\nMinecraft: " + gameVersion +
                    "\nSelected: " + selections.size +
                    "\n\nThe server must remain stopped. Downloads require internet access. " +
                    "Previously installed mods and existing worlds will not be erased.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("INSTALL") { _, _ -> install(name, createWorld, selections, gameVersion) }
                .show()
        })
        if (gameVersion != "26.3") {
            footer.addView(ui.space(this, 8))
            footer.addView(ui.text(this, "Install/select Minecraft Fabric 26.3 before using this preset.", 12f, ui.RED))
        }
        parent.addView(footer)
        ui.styleFormInputs(parent)
        setContentView(parent)
    }

    private fun install(world: String, createWorld: Boolean,
                        selection: List<Choice>, gameVersion: String) {
        if (installing) return
        installing = true
        cancelled.set(false)
        val status = ui.text(this, "Preparing…", 13f, ui.WHITE)
        val container = ui.column(this).apply {
            setPadding(ui.dp(this@WoodlandSetupActivity, 18), ui.dp(this@WoodlandSetupActivity, 12),
                ui.dp(this@WoodlandSetupActivity, 18), ui.dp(this@WoodlandSetupActivity, 12))
            addView(status)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Installing Woodland+")
            .setView(container)
            .setNegativeButton("CANCEL") { _, _ -> cancelled.set(true) }
            .setCancelable(false).create()
        dialog.show()
        Thread {
            val installed = mutableListOf<String>()
            val failed = mutableListOf<String>()
            var created = false
            val result = runCatching {
                InstallProgress.withSession({ progress ->
                    runOnUiThread {
                        if (dialog.isShowing) status.text = progress.stage +
                            (if (progress.total > 0) "\n" +
                                (progress.done / 1024) + " / " + (progress.total / 1024) + " KB"
                            else "")
                    }
                }, { cancelled.get() }) {
                    if (createWorld) {
                        InstallProgress.emit("Reserving new world " + world)
                        WorldStore.create(this, world)
                        created = true
                        val current = WorldHostingProfiles.readForEditor(this, world)
                        WorldHostingProfiles.save(this, world, WorldHostingProfiles.update(
                            current, mapOf("view-distance" to "6", "simulation-distance" to "4")))
                    } else {
                        if (WorldStore.active(this) != world)
                            throw java.io.IOException("Active world changed. Reopen Woodland+ before installing.")
                    }
                    for (item in selection) {
                        InstallProgress.check()
                        InstallProgress.emit("Installing " + item.title + "…")
                        try {
                            if (item.kind == "mod") {
                                WebsiteModInstaller.installWithDependencies(this, item.slug)
                            } else {
                                DatapackInstaller.installModrinth(this,
                                    DatapackInstaller.Project(item.slug, item.title, ""), gameVersion)
                            }
                            installed.add(item.title)
                        } catch (e: InterruptedIOException) {
                            throw e
                        } catch (e: Exception) {
                            InstallProgress.check()
                            failed.add(item.title + ": " + (e.message ?: "Download/compatibility error").take(200))
                        }
                    }
                }
            }
            runOnUiThread {
                installing = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                dialog.dismiss()
                val report = buildString {
                    if (created) append("Created world: ").append(world).append("\n")
                    append("Installed ").append(installed.size).append(" of ")
                        .append(selection.size).append(" selected additions.\n")
                    if (installed.isNotEmpty())
                        append("\nInstalled: ").append(installed.joinToString(", ")).append("\n")
                    if (failed.isNotEmpty())
                        append("\nUnavailable/failed:\n").append(failed.joinToString("\n")).append("\n")
                    result.exceptionOrNull()?.let {
                        append("\nStopped: ").append(it.message ?: "Installation interrupted").append("\n")
                    }
                    append("\nWorld generation was NOT started. Check failures before starting Minecraft.")
                }
                AlertDialog.Builder(this).setTitle(
                    if (failed.isEmpty() && result.isSuccess) "Woodland+ setup complete"
                    else "Woodland+ setup needs review"
                ).setMessage(report)
                    .setPositiveButton("OK") { _, _ -> finish() }.show()
            }
        }.start()
    }

    override fun onBackPressed() {
        if (installing) info("Cancel the installation first; completed files are preserved.")
        else super.onBackPressed()
    }
}
