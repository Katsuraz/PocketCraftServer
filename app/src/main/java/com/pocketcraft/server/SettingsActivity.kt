package com.pocketcraft.server

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Properties

/** A dark themed, offline editor for Java server.properties. */
class SettingsActivity : Activity() {
    private val ui=PocketUi
    private data class Field(val key:String,val title:String,val default:String,val choices:List<String>?=null,val numeric:Boolean=false)
    private val groups=linkedMapOf(
        "PLAYER EXPERIENCE" to listOf(
            Field("motd","Server name","PocketCraft Server"),
            Field("max-players","Maximum players","5",numeric=true),
            Field("gamemode","Default game mode","survival",listOf("survival","creative","adventure","spectator")),
            Field("difficulty","Difficulty","normal",listOf("peaceful","easy","normal","hard")),
            Field("pvp","Player versus player","true",listOf("true","false")),
            Field("hardcore","Hardcore mode","false",listOf("true","false"))
        ),
        "WORLD GENERATION" to listOf(
            Field("level-name","World folder","world"),
            Field("level-seed","World seed (new worlds only)",""),
            Field("view-distance","View distance (chunks)","5",numeric=true),
            Field("simulation-distance","Simulation distance","4",numeric=true),
            Field("generate-structures","Structures","true",listOf("true","false")),
            Field("spawn-protection","Spawn protection radius","0",numeric=true),
            Field("allow-flight","Allow flight","false",listOf("true","false"))
        ),
        "NETWORK & SECURITY" to listOf(
            Field("server-port","Java TCP port","25565",numeric=true),
            Field("white-list","Use whitelist","false",listOf("true","false")),
            Field("enforce-whitelist","Enforce whitelist","false",listOf("true","false")),
            Field("online-mode","Java account authentication","true",listOf("true")),
            Field("enable-rcon","Remote console disabled","false",listOf("false"))
        )
    )
    private val controls=linkedMapOf<String,EditText>()
    private val root by lazy { WorldStore.root(this) }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=ui.BG
        window.navigationBarColor=ui.BG
        val file=File(root,"server.properties")
        val properties=Properties()
        if(file.exists()) file.inputStream().use {properties.load(it)}
        val screen=ui.column(this).apply {setBackgroundColor(ui.BG)}
        val header=ui.row(this).apply {
            setPadding(ui.dp(this@SettingsActivity,18),ui.dp(this@SettingsActivity,22),ui.dp(this@SettingsActivity,18),ui.dp(this@SettingsActivity,16))
        }
        header.addView(ui.text(this,"‹",34f,ui.GREEN,true).apply {
            setOnClickListener {finish()}
            contentDescription="Back"
            setPadding(0,0,ui.dp(this@SettingsActivity,18),0)
        })
        header.addView(ui.column(this).apply {
            addView(ui.text(this@SettingsActivity,"SERVER SETTINGS",20f,ui.WHITE,true))
            addView(ui.text(this@SettingsActivity,"Customize your next world",11f,ui.MUTED))
        })
        screen.addView(header)
        val scroll=ScrollView(this)
        val body=ui.column(this).apply {
            setPadding(ui.dp(this@SettingsActivity,18),0,ui.dp(this@SettingsActivity,18),ui.dp(this@SettingsActivity,20))
        }
        val intro=ui.card(this)
        intro.addView(ui.pill(this,"OFFLINE CONFIGURATION",ui.PURPLE))
        intro.addView(ui.space(this,11))
        intro.addView(ui.text(this,"Build the world your way",19f,ui.WHITE,true))
        intro.addView(ui.space(this,7))
        intro.addView(ui.text(this,"Settings are saved into server.properties. Restart the Minecraft server to apply changes once the Android engine is available.",12f,ui.MUTED))
        body.addView(intro)
        groups.forEach { (heading,fields) ->
            body.addView(ui.space(this,21))
            body.addView(ui.text(this,heading,11f,ui.GREEN,true))
            body.addView(ui.space(this,10))
            val section=ui.card(this)
            fields.forEachIndexed {index,field->
                section.addView(ui.text(this,field.title,13f,ui.WHITE,true))
                section.addView(ui.space(this,7))
                val edit=EditText(this).apply {
                    setSingleLine(true)
                    textSize=14f
                    setTextColor(ui.WHITE);setHintTextColor(ui.MUTED)
                    setText(properties.getProperty(field.key,field.default))
                    background=ui.shape(this@SettingsActivity,ui.SURFACE,12,0xFF34455F.toInt())
                    setPadding(ui.dp(this@SettingsActivity,14),ui.dp(this@SettingsActivity,11),
                        ui.dp(this@SettingsActivity,14),ui.dp(this@SettingsActivity,11))
                    if(field.numeric) inputType=InputType.TYPE_CLASS_NUMBER
                }
                controls[field.key]=edit
                section.addView(edit,LinearLayout.LayoutParams(-1,-2))
                if(field.choices!=null) {
                    section.addView(ui.space(this,5))
                    section.addView(ui.text(this,"Options: "+field.choices.joinToString(" / "),10f,ui.MUTED))
                }
                if(index < fields.size-1) section.addView(ui.space(this,16))
            }
            body.addView(section)
        }
        scroll.addView(body)
        screen.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val footer=ui.column(this).apply {
            setPadding(ui.dp(this@SettingsActivity,18),ui.dp(this@SettingsActivity,12),
                ui.dp(this@SettingsActivity,18),ui.dp(this@SettingsActivity,18))
        }
        footer.addView(ui.action(this,"SAVE SERVER SETTINGS  ✓",true) {
            for(field in groups.values.flatten()) {
                val value=controls[field.key]!!.text.toString().trim()
                if(field.choices!=null && value !in field.choices) {
                    Toast.makeText(this,"Invalid "+field.title,Toast.LENGTH_LONG).show()
                    return@action
                }
                if(field.numeric) {
                    val n=value.toIntOrNull()
                    if(n==null || n<0 || (field.key=="server-port" && n !in 1..65535) ||
                        (field.key=="max-players" && n !in 1..100) ||
                        (field.key in listOf("view-distance","simulation-distance") && n !in 2..32)) {
                        Toast.makeText(this,"Check "+field.title,Toast.LENGTH_LONG).show()
                        return@action
                    }
                }
            }
            groups.values.flatten().forEach {field->
                properties.setProperty(field.key,controls[field.key]!!.text.toString().trim())
            }
            file.outputStream().use {properties.store(it,"PocketCraft Server configuration")}
            Toast.makeText(this,"Server settings saved",Toast.LENGTH_SHORT).show()
            finish()
        })
        screen.addView(footer)
        setContentView(screen)
    }
}
