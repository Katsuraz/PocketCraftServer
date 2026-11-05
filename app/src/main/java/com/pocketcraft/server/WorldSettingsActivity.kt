package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Properties

/**
 * Settings belong to a particular managed world. Saving an inactive profile
 * never edits the running server.properties; selecting that world applies it.
 */
class WorldSettingsActivity : Activity() {
    private val ui = PocketUi
    private lateinit var worldName: String
    private lateinit var motd: EditText
    private lateinit var viewDistance: EditText
    private lateinit var simulationDistance: EditText
    private lateinit var spawnProtection: EditText
    private lateinit var pvp: Switch
    private var difficulty = "normal"
    private var gamemode = "survival"

    private fun stopped(): Boolean =
        !ServerService.running && ServerService.status !in setOf("STARTING","STOPPING")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.loadTheme(this)
        PocketSounds.prepare(this)
        window.statusBarColor = ui.BG
        window.navigationBarColor = ui.BG
        val requested = intent.getStringExtra(EXTRA_WORLD).orEmpty()
        val selected = WorldStore.worlds(this).firstOrNull { it.name == requested }
        if (selected == null) {
            Toast.makeText(this,"World not found",Toast.LENGTH_LONG).show()
            finish()
            return
        }
        worldName = selected.name
        render()
    }

    private fun addInput(parent: LinearLayout, title: String, description: String,
                         value: String, numeric: Boolean = false): EditText {
        parent.addView(ui.text(this,title,14f,ui.WHITE,true))
        parent.addView(ui.space(this,4))
        parent.addView(ui.text(this,description,11f,ui.MUTED))
        parent.addView(ui.space(this,7))
        val field = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            setText(value)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            background = ui.shape(this@WorldSettingsActivity,ui.SURFACE,12,ui.BORDER)
            setPadding(ui.dp(this@WorldSettingsActivity,13),ui.dp(this@WorldSettingsActivity,11),
                ui.dp(this@WorldSettingsActivity,13),ui.dp(this@WorldSettingsActivity,11))
        }
        parent.addView(field,LinearLayout.LayoutParams(-1,-2))
        parent.addView(ui.space(this,16))
        return field
    }

    private fun addSelector(parent: LinearLayout, title: String, current: String,
                            choices: List<String>, onSelected: (String) -> Unit) {
        parent.addView(ui.text(this,title,14f,ui.WHITE,true))
        parent.addView(ui.space(this,8))
        val label = ui.action(this,current.uppercase() + "  ▾") {}
        label.setOnClickListener {
            PocketSounds.play(this,PocketSounds.Event.CLICK)
            AlertDialog.Builder(this).setTitle(title)
                .setItems(choices.toTypedArray()) { _, index ->
                    onSelected(choices[index])
                    label.text = choices[index].uppercase() + "  ▾"
                }.setNegativeButton("CANCEL",null).show()
        }
        parent.addView(label)
        parent.addView(ui.space(this,17))
    }

    private fun render() {
        val values = try {
            WorldHostingProfiles.readForEditor(this,worldName)
        } catch (e: Exception) {
            toast("Could not read world settings: " + (e.message ?: "Storage error"))
            WorldHostingProfiles.defaults()
        }
        difficulty = values.getProperty("difficulty","normal")
        gamemode = values.getProperty("gamemode","survival")
        val active = WorldStore.active(this) == worldName

        val screen = ui.column(this).apply { setBackgroundColor(ui.BG) }
        val toolbar = ui.row(this).apply {
            setPadding(ui.dp(this@WorldSettingsActivity,18),ui.dp(this@WorldSettingsActivity,17),
                ui.dp(this@WorldSettingsActivity,18),ui.dp(this@WorldSettingsActivity,15))
        }
        toolbar.addView(ui.text(this,"‹",32f,ui.GREEN,true).apply {
            contentDescription = "Back to world"
            setPadding(0,0,ui.dp(this@WorldSettingsActivity,16),0)
            setOnClickListener { finish() }
        })
        toolbar.addView(ui.column(this).apply {
            addView(ui.text(this@WorldSettingsActivity,worldName,20f,ui.WHITE,true))
            addView(ui.text(this@WorldSettingsActivity,
                if (active) "ACTIVE WORLD SETTINGS" else "WORLD PROFILE • INACTIVE",
                10f,ui.MUTED,true))
        })
        screen.addView(toolbar)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = ui.column(this).apply {
            setPadding(ui.dp(this@WorldSettingsActivity,18),0,
                ui.dp(this@WorldSettingsActivity,18),ui.dp(this@WorldSettingsActivity,20))
        }
        val card = ui.card(this)
        card.addView(ui.text(this,"World hosting preferences",19f,ui.WHITE,true))
        card.addView(ui.space(this,8))
        card.addView(ui.text(this,
            "These options are remembered separately for this world and applied to server.properties when the world is selected. They do not rewrite Minecraft's level.dat.",
            12f,ui.MUTED))
        card.addView(ui.space(this,12))
        if (!stopped()) {
            card.addView(ui.text(this,
                "Stop the server before changing world preferences.",12f,ui.RED))
            card.addView(ui.space(this,10))
        }
        motd = addInput(card,"Server message",
            "Message shown in the server browser when hosting this world.",
            values.getProperty("motd","PocketCraft Server"))
        addSelector(card,"Default game mode",gamemode,
            listOf("survival","creative","adventure","spectator")) { gamemode = it }
        addSelector(card,"Difficulty",difficulty,
            listOf("peaceful","easy","normal","hard")) { difficulty = it }
        val pvpRow=ui.row(this)
        pvpRow.addView(ui.column(this).apply {
            addView(ui.text(this@WorldSettingsActivity,"Player versus player",14f,ui.WHITE,true))
            addView(ui.space(this@WorldSettingsActivity,4))
            addView(ui.text(this@WorldSettingsActivity,"Allow players to damage each other",11f,ui.MUTED))
        },LinearLayout.LayoutParams(0,-2,1f))
        pvp=Switch(this).apply { isChecked=values.getProperty("pvp","true")=="true" }
        pvpRow.addView(pvp)
        card.addView(pvpRow)
        card.addView(ui.space(this,18))
        viewDistance = addInput(card,"View distance (chunks)","2–32; use 4–6 for slower phones.",
            values.getProperty("view-distance","5"),numeric=true)
        simulationDistance = addInput(card,"Simulation distance (chunks)","2–32; reducing this saves CPU.",
            values.getProperty("simulation-distance","4"),numeric=true)
        spawnProtection = addInput(card,"Spawn protection","0 disables spawn protection.",
            values.getProperty("spawn-protection","0"),numeric=true)
        card.addView(ui.action(this,"SAVE THIS WORLD'S SETTINGS",true,stopped()) { save() })
        card.addView(ui.space(this,10))
        card.addView(ui.text(this,
            if (active) "Changes apply on the next Minecraft start."
            else "This profile will be applied when you assign this world. Other worlds keep their own preferences.",
            11f,ui.MUTED))
        body.addView(card)
        scroll.addView(body)
        screen.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        ui.styleFormInputs(screen)
        setContentView(screen)
    }

    private fun save() {
        if (!stopped()) { toast("Stop Minecraft before changing world settings");return }
        val fields = mapOf(
            "motd" to motd.text.toString(),
            "gamemode" to gamemode,
            "difficulty" to difficulty,
            "pvp" to pvp.isChecked.toString(),
            "view-distance" to viewDistance.text.toString().trim(),
            "simulation-distance" to simulationDistance.text.toString().trim(),
            "spawn-protection" to spawnProtection.text.toString().trim()
        )
        try {
            val current = WorldHostingProfiles.readForEditor(this,worldName)
            val updated = WorldHostingProfiles.update(current,fields)
            WorldHostingProfiles.save(this,worldName,updated)
            toast(if (worldName == WorldStore.active(this))
                "Saved. Restart the server to apply this world's settings."
            else "Saved for $worldName. Select this world to apply its settings.")
            finish()
        } catch (e: Exception) { toast("Could not save world settings: " + (e.message ?: "Storage error")) }
    }

    private fun toast(message:String) = Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    companion object { const val EXTRA_WORLD = "world_name" }
}
