package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
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
    private lateinit var allowFlight: Switch
    private lateinit var hardcore: Switch
    private lateinit var generateStructures: Switch
    private lateinit var levelSeed: EditText
    private var difficulty = "normal"
    private var gamemode = "survival"
    private var selectedWorldCategory = "Gameplay"

    private fun stopped(): Boolean =
        !ServerService.running && ServerService.status !in setOf("STARTING","STOPPING")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedWorldCategory=savedInstanceState?.getString("worldCategory")
            ?.takeIf { it in listOf("Gameplay","Performance","Generation") } ?: "Gameplay"
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

    private fun help(title: String, message: String): TextView =
        ui.text(this,"ⓘ",15f,ui.MUTED,true).apply {
            gravity=android.view.Gravity.CENTER
            minWidth=ui.dp(this@WorldSettingsActivity,36)
            minHeight=ui.dp(this@WorldSettingsActivity,38)
            isClickable=true
            isFocusable=true
            contentDescription="Help: $title"
            setOnClickListener {
                AlertDialog.Builder(this@WorldSettingsActivity).setTitle(title)
                    .setMessage(message).setPositiveButton("OK",null).show()
            }
        }

    private fun addInput(parent: LinearLayout,title: String,description: String,
                         value: String,numeric: Boolean = false): EditText {
        val label=ui.row(this)
        label.addView(ui.text(this,title,13f,ui.WHITE,true),
            LinearLayout.LayoutParams(0,-2,1f))
        label.addView(help(title,description))
        parent.addView(label)
        parent.addView(ui.space(this,4))
        val field=EditText(this).apply {
            setSingleLine(true)
            textSize=14f
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            setText(value)
            if(numeric)inputType=InputType.TYPE_CLASS_NUMBER
            background=ui.shape(this@WorldSettingsActivity,ui.SURFACE,10,ui.BORDER)
            setPadding(ui.dp(this@WorldSettingsActivity,12),ui.dp(this@WorldSettingsActivity,8),
                ui.dp(this@WorldSettingsActivity,12),ui.dp(this@WorldSettingsActivity,8))
        }
        parent.addView(field,LinearLayout.LayoutParams(-1,-2))
        parent.addView(ui.space(this,12))
        return field
    }

    private fun addSelector(parent: LinearLayout,title: String,current: String,
                            choices: List<String>,onSelected: (String) -> Unit) {
        val row=ui.row(this)
        row.addView(ui.text(this,title,13f,ui.WHITE,true),
            LinearLayout.LayoutParams(0,-2,1f))
        val button=ui.text(this,current.uppercase()+"  ▾",13f,ui.GREEN,true).apply {
            gravity=android.view.Gravity.CENTER
            background=ui.pressable(this@WorldSettingsActivity,
                ui.shape(this@WorldSettingsActivity,ui.SURFACE,10,ui.BORDER))
            setPadding(ui.dp(this@WorldSettingsActivity,10),ui.dp(this@WorldSettingsActivity,10),
                ui.dp(this@WorldSettingsActivity,10),ui.dp(this@WorldSettingsActivity,10))
            isClickable=true
            isFocusable=true
            contentDescription="$title: $current"
        }
        button.setOnClickListener {
            PocketSounds.play(this,PocketSounds.Event.CLICK)
            AlertDialog.Builder(this).setTitle(title)
                .setItems(choices.toTypedArray()) { _,index ->
                    onSelected(choices[index])
                    button.text=choices[index].uppercase()+"  ▾"
                    button.contentDescription="$title: "+choices[index]
                }.setNegativeButton("CANCEL",null).show()
        }
        row.addView(button)
        parent.addView(row)
        parent.addView(ui.space(this,12))
    }

    private fun addToggle(parent: LinearLayout,title: String,description: String,
                          checked: Boolean): Switch {
        val row=ui.row(this)
        row.addView(ui.text(this,title,13f,ui.WHITE,true),
            LinearLayout.LayoutParams(0,-2,1f))
        row.addView(help(title,description))
        val control=Switch(this).apply { isChecked=checked; contentDescription=title }
        row.addView(control)
        parent.addView(row)
        parent.addView(ui.space(this,12))
        return control
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
        screen.addView(HomesteadComponents.subpageHeader(this, worldName,
            if (active) "Active world settings" else "Saved world profile") { finish() })
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = ui.column(this).apply {
            setPadding(ui.dp(this@WorldSettingsActivity,18),0,
                ui.dp(this@WorldSettingsActivity,18),ui.dp(this@WorldSettingsActivity,20))
        }
        val intro=ui.card(this)
        intro.addView(ui.pill(this,if(active)"ACTIVE WORLD" else "SAVED PROFILE",ui.GREEN))
        if(!stopped()) {
            intro.addView(ui.space(this,10))
            intro.addView(ui.text(this,
                "Stop Minecraft before changing this world's preferences.",12f,ui.RED))
        }
        body.addView(intro)
        body.addView(ui.space(this,16))

        val panels=linkedMapOf<String,LinearLayout>()
        val buttons=linkedMapOf<String,TextView>()
        val navigation=ui.row(this)
        listOf("Gameplay","Performance","Generation").forEachIndexed { i, category ->
            val button=ui.text(this,category,11f,ui.MUTED,true).apply {
                minHeight=ui.dp(this@WorldSettingsActivity,48)
                gravity=android.view.Gravity.CENTER
                isClickable=true
                isFocusable=true
                contentDescription="$category world settings"
                setOnClickListener {
                    selectedWorldCategory=category
                    PocketSounds.play(this@WorldSettingsActivity,PocketSounds.Event.SELECT)
                    panels.forEach { (name,panel) ->
                        panel.visibility=if(name==category)View.VISIBLE else View.GONE
                    }
                    buttons.forEach { (name,view) ->
                        val selected=name==category
                        view.background=ui.pressable(this@WorldSettingsActivity,
                            ui.shape(this@WorldSettingsActivity,
                                if(selected)ui.SURFACE else ui.PANEL,12,
                                if(selected)ui.BORDER else null))
                        view.setTextColor(if(selected)ui.GREEN else ui.MUTED)
                    }
                }
            }
            buttons[category]=button
            navigation.addView(button,LinearLayout.LayoutParams(0,ui.dp(this,48),1f).apply {
                if(i!=2)marginEnd=ui.dp(this@WorldSettingsActivity,5)
            })
        }
        body.addView(navigation)
        body.addView(ui.space(this,13))

        val gameplay=ui.card(this)
        gameplay.addView(HomesteadComponents.sectionHeading(this,"Gameplay",
            "Rules and player behavior"))
        gameplay.addView(ui.space(this,14))
        motd=addInput(gameplay,"Server message",
            "Message shown in the server browser for this world.",
            values.getProperty("motd","PocketCraft Server"))
        addSelector(gameplay,"Default game mode",gamemode,
            listOf("survival","creative","adventure","spectator")) { gamemode=it }
        addSelector(gameplay,"Difficulty",difficulty,
            listOf("peaceful","easy","normal","hard")) { difficulty=it }
        val pvpRow=ui.row(this)
        pvpRow.addView(ui.text(this,"PvP",13f,ui.WHITE,true),LinearLayout.LayoutParams(0,-2,1f))
        pvpRow.addView(help("PvP","Allow players to damage each other."))
        pvp=Switch(this).apply {
            isChecked=values.getProperty("pvp","true")=="true"
            contentDescription="PvP"
        }
        pvpRow.addView(pvp)
        gameplay.addView(pvpRow)
        panels["Gameplay"]=gameplay

        val performance=ui.card(this)
        performance.addView(HomesteadComponents.sectionHeading(this,"Performance",
            "Tune simulation workload for your phone"))
        performance.addView(ui.space(this,14))
        viewDistance=addInput(performance,"View distance (chunks)",
            "2–32; a smaller value can improve performance.",
            values.getProperty("view-distance","5"),numeric=true)
        simulationDistance=addInput(performance,"Simulation distance (chunks)",
            "2–32; reducing simulation can save CPU.",
            values.getProperty("simulation-distance","4"),numeric=true)
        spawnProtection=addInput(performance,"Spawn protection",
            "0 disables spawn protection.",
            values.getProperty("spawn-protection","0"),numeric=true)
        allowFlight=addToggle(performance,"Allow flight",
            "Allow flying players without being kicked.",
            values.getProperty("allow-flight","false")=="true")
        panels["Performance"]=performance

        val generation=ui.card(this)
        generation.addView(HomesteadComponents.sectionHeading(this,"World generation",
            "Settings for new worlds and chunks"))
        generation.addView(ui.space(this,14))
        hardcore=addToggle(generation,"Hardcore flag",
            "For newly generated worlds; may not change existing world metadata.",
            values.getProperty("hardcore","false")=="true")
        generateStructures=addToggle(generation,"Generate structures",
            "Changes new terrain generation; old chunks remain unchanged.",
            values.getProperty("generate-structures","true")=="true")
        levelSeed=addInput(generation,"World seed",
            "Used for initial generation. Does not change existing world seeds.",
            values.getProperty("level-seed",""))
        panels["Generation"]=generation

        panels.forEach { (name,panel) ->
            panel.visibility=if(name==selectedWorldCategory)View.VISIBLE else View.GONE
            body.addView(panel)
        }
        buttons.forEach { (name,button) ->
            val selected=name==selectedWorldCategory
            button.background=ui.pressable(this,
                ui.shape(this,if(selected)ui.SURFACE else ui.PANEL,12,
                    if(selected)ui.BORDER else null))
            button.setTextColor(if(selected)ui.GREEN else ui.MUTED)
        }
        body.addView(ui.space(this,12))
        body.addView(ui.text(this,
            if(active)"Saved changes apply when Minecraft is restarted."
            else "Changes here apply when this world is selected. Other worlds are unaffected.",
            11f,ui.MUTED))
        scroll.addView(body)
        screen.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val footer=ui.column(this).apply {
            setPadding(ui.dp(this@WorldSettingsActivity,16),ui.dp(this@WorldSettingsActivity,10),
                ui.dp(this@WorldSettingsActivity,16),ui.dp(this@WorldSettingsActivity,14))
        }
        footer.addView(ui.action(this,"SAVE CHANGES",true,stopped()) { save() })
        screen.addView(footer)
        ui.styleFormInputs(screen)
        setContentView(screen)
    }

    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("worldCategory",selectedWorldCategory)
        super.onSaveInstanceState(outState)
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
            "spawn-protection" to spawnProtection.text.toString().trim(),
            "allow-flight" to allowFlight.isChecked.toString(),
            "hardcore" to hardcore.isChecked.toString(),
            "generate-structures" to generateStructures.isChecked.toString(),
            "level-seed" to levelSeed.text.toString().trim()
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
