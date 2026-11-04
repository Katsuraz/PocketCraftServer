package com.pocketcraft.server

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.*
import java.io.File
import java.util.Properties

/** Offline editor for Minecraft Java server.properties. Does not start the server. */
class SettingsActivity : Activity() {
    private data class Field(val key: String, val label: String, val default: String, val choices: List<String>? = null)
    private val fields = listOf(
        Field("motd", "Server description", "PocketCraft Server"),
        Field("server-port", "Java TCP port", "25565"),
        Field("max-players", "Maximum players", "5"),
        Field("gamemode", "Default game mode", "survival", listOf("survival","creative","adventure","spectator")),
        Field("difficulty", "Difficulty", "normal", listOf("peaceful","easy","normal","hard")),
        Field("hardcore", "Hardcore", "false", listOf("false","true")),
        Field("force-gamemode", "Force default game mode", "false", listOf("false","true")),
        Field("white-list", "Whitelist", "false", listOf("false","true")),
        Field("enforce-whitelist", "Enforce whitelist", "false", listOf("false","true")),
        Field("online-mode", "Online authentication", "true", listOf("true")),
        Field("view-distance", "View distance (chunks)", "5"),
        Field("simulation-distance", "Simulation distance (chunks)", "4"),
        Field("spawn-protection", "Spawn protection radius", "0"),
        Field("level-name", "World folder name", "world"),
        Field("level-seed", "World seed (new worlds only)", ""),
        Field("generate-structures", "Generate structures", "true", listOf("true","false")),
        Field("allow-flight", "Allow flight", "false", listOf("false","true")),
        Field("enable-status", "Show server status", "true", listOf("true","false")),
        Field("player-idle-timeout", "Idle timeout (minutes)", "0"),
        Field("max-world-size", "Max world radius", "29999984"),
        Field("enable-rcon", "Remote console (disabled for safety)", "false", listOf("false"))
    )
    private val controls = mutableMapOf<String, EditText>()
    private val root by lazy { File(filesDir, "minecraft").apply { mkdirs() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(root, "server.properties")
        val props = Properties()
        if (file.exists()) file.inputStream().use { props.load(it) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        body.addView(TextView(this).apply { text = "Server settings"; textSize = 26f })
        body.addView(TextView(this).apply { text = "Edit Java server.properties. Restart required. Bedrock/Geyser settings are separate. Some keys change between Minecraft versions." })
        fields.forEach { f ->
            body.addView(TextView(this).apply { text = f.label })
            val editor = EditText(this).apply {
                setSingleLine(true)
                setText(props.getProperty(f.key, f.default))
                if (f.key in listOf("max-players","view-distance","simulation-distance","server-port","spawn-protection","player-idle-timeout","max-world-size"))
                    inputType = InputType.TYPE_CLASS_NUMBER
            }
            controls[f.key] = editor
            body.addView(editor)
            if (f.choices != null) {
                body.addView(TextView(this).apply { text = "Allowed: " + f.choices.joinToString(", "); textSize = 12f })
            }
        }
        body.addView(Button(this).apply {
            text = "Save settings"
            setOnClickListener {
                for (f in fields) {
                    val value = controls[f.key]!!.text.toString().trim()
                    if (f.choices != null && value !in f.choices) {
                        Toast.makeText(this@SettingsActivity, "Invalid ${f.key}", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    if (f.key in listOf("max-players","view-distance","simulation-distance","server-port","spawn-protection","player-idle-timeout","max-world-size")) {
                        val n = value.toIntOrNull()
                        if (n == null || n < 0 || (f.key == "server-port" && n !in 1..65535) || (f.key == "max-players" && n < 1)) {
                            Toast.makeText(this@SettingsActivity, "Invalid number for ${f.key}", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                    }
                }
                fields.forEach { f -> props.setProperty(f.key, controls[f.key]!!.text.toString().trim()) }
                file.outputStream().use { props.store(it, "PocketCraft Java server settings") }
                Toast.makeText(this@SettingsActivity, "Saved server.properties", Toast.LENGTH_LONG).show()
                finish()
            }
        })
        setContentView(ScrollView(this).apply { addView(body) })
    }
}
