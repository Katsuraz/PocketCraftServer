package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.util.Properties

/**
 * PocketCraft server settings. Clear labels and touch-friendly choices replace raw string inputs.
 * All Minecraft values persist to server.properties. Share address is a UI hint ONLY.
 */
class SettingsActivity : Activity() {
    private val ui = PocketUi
    private val root by lazy { WorldStore.root(this) }
    private val file by lazy { File(root, "server.properties") }
    private val props = Properties()
    private val editors = linkedMapOf<String, EditText>()
    private val toggles = linkedMapOf<String, Switch>()
    private val selectors = linkedMapOf<String, TextView>()
    private val selected = linkedMapOf<String, String>()
    private lateinit var advertised: EditText
    private var selectedHeapMB: Int = 2048
    private var selectedCategory = "Appearance"
    private val settingsGroups = mutableListOf<Pair<String, View>>()
    private val categoryButtons = linkedMapOf<String, TextView>()
    private var saveFooter: View? = null

    private fun categoryFor(title: String): String =
        HomesteadNavigation.settingsCategory(title)

    private fun showCategory(category: String) {
        selectedCategory = category
        settingsGroups.forEach { (key, view) ->
            view.visibility = if (key == category) View.VISIBLE else View.GONE
        }
        categoryButtons.forEach { (key, button) ->
            val active=key==category
            button.background=ui.pressable(this,ui.shape(this,
                if(active)ui.SURFACE else ui.PANEL,12,if(active)ui.BORDER else null))
            button.setTextColor(if(active)ui.GREEN else ui.MUTED)
        }
        saveFooter?.visibility = if(category == "Appearance") View.GONE else View.VISIBLE
    }


    private fun infoButton(title: String, help: String): TextView =
        ui.text(this,"ⓘ",15f,ui.MUTED,true).apply {
            gravity=Gravity.CENTER
            minWidth=ui.dp(this@SettingsActivity,36)
            minHeight=ui.dp(this@SettingsActivity,40)
            isClickable=true
            isFocusable=true
            contentDescription="Help: $title"
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity).setTitle(title)
                    .setMessage(help).setPositiveButton("OK",null).show()
            }
        }

    private fun heading(label: String, description: String): LinearLayout =
        HomesteadComponents.sectionHeading(this,label,description)

    private fun input(
        parent: LinearLayout, key: String, title: String, default: String,
        help: String = "", numeric: Boolean = false
    ) {
        val container=ui.column(this)
        val row=ui.row(this)
        row.addView(ui.text(this,title,13f,ui.WHITE,true),
            LinearLayout.LayoutParams(0,-2,1f))
        if(help.isNotBlank()) row.addView(infoButton(title,help))
        container.addView(row)
        container.addView(ui.space(this,4))
        val edit=EditText(this).apply {
            setSingleLine(true)
            textSize=14f
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            setText(props.getProperty(key,default))
            if(numeric)inputType=InputType.TYPE_CLASS_NUMBER
            if(key=="level-name"){isEnabled=false;alpha=.72f}
            background=ui.shape(this@SettingsActivity,ui.SURFACE,10,ui.BORDER)
            setPadding(ui.dp(this@SettingsActivity,12),ui.dp(this@SettingsActivity,8),
                ui.dp(this@SettingsActivity,12),ui.dp(this@SettingsActivity,8))
        }
        editors[key]=edit
        container.addView(edit,LinearLayout.LayoutParams(-1,-2))
        parent.addView(container)
        parent.addView(ui.space(this,12))
    }

    private fun toggle(parent: LinearLayout,key: String,title: String,help: String,default: Boolean) {
        val row=ui.row(this)
        row.addView(ui.text(this,title,13f,ui.WHITE,true),
            LinearLayout.LayoutParams(0,-2,1f))
        if(help.isNotBlank())row.addView(infoButton(title,help))
        val control=Switch(this).apply {
            isChecked=props.getProperty(key,default.toString()).equals("true",true)
            thumbTintList=android.content.res.ColorStateList.valueOf(ui.GREEN)
            contentDescription=title
        }
        toggles[key]=control
        row.addView(control)
        parent.addView(row)
        parent.addView(ui.space(this,12))
    }

    private fun select(parent:LinearLayout,key:String,title:String,default:String,choices:List<String>) {
        val row=ui.row(this)
        row.addView(ui.text(this,title,13f,ui.WHITE,true),
            LinearLayout.LayoutParams(0,-2,1f))
        val current=props.getProperty(key,default).takeIf { it in choices } ?: default
        selected[key]=current
        val button=ui.text(this,current.uppercase()+"  ▾",13f,ui.GREEN,true).apply {
            gravity=Gravity.CENTER
            setPadding(ui.dp(this@SettingsActivity,11),ui.dp(this@SettingsActivity,10),
                ui.dp(this@SettingsActivity,11),ui.dp(this@SettingsActivity,10))
            background=ui.pressable(this@SettingsActivity,
                ui.shape(this@SettingsActivity,ui.SURFACE,10,ui.BORDER))
            isClickable=true
            isFocusable=true
            contentDescription="$title: $current"
        }
        button.setOnClickListener {
            AlertDialog.Builder(this).setTitle(title).setItems(choices.toTypedArray()) { _,index ->
                selected[key]=choices[index]
                selectors[key]?.text=choices[index].uppercase()+"  ▾"
                selectors[key]?.contentDescription="$title: "+choices[index]
            }.setNegativeButton("CANCEL",null).show()
        }
        selectors[key]=button
        row.addView(button)
        parent.addView(row)
        parent.addView(ui.space(this,12))
    }

    private fun section(body:LinearLayout,title:String,subtitle:String,fill:(LinearLayout)->Unit) {
        val group=ui.column(this)
        group.addView(ui.space(this,17))
        group.addView(HomesteadComponents.sectionHeading(this,title,subtitle))
        group.addView(ui.space(this,10))
        val card=ui.card(this)
        fill(card)
        group.addView(card)
        settingsGroups.add(categoryFor(title) to group)
        group.visibility=if(categoryFor(title)==selectedCategory)View.VISIBLE else View.GONE
        body.addView(group)
    }
    private fun value(key:String) = editors[key]?.text?.toString()?.trim() ?: ""

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        selectedCategory=savedInstanceState?.getString("selectedCategory")
            ?.takeIf { it in listOf("Appearance","Connections","Access","System") }
            ?: "Appearance"
        ui.loadTheme(this)
        PocketSounds.prepare(this)
        window.statusBarColor=ui.BG
        window.navigationBarColor=ui.BG
        if (file.exists()) file.inputStream().use { props.load(it) }

        val screen=ui.column(this).apply { setBackgroundColor(ui.BG) }
        screen.addView(HomesteadComponents.subpageHeader(this,
            "Settings","Server controls") { finish() })
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; isFillViewport=true }
        val body=ui.column(this).apply {
            setPadding(ui.dp(this@SettingsActivity,18),0,ui.dp(this@SettingsActivity,18),ui.dp(this@SettingsActivity,30))
        }
        body.addView(ui.space(this,8))
        val categories=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false
        }
        val categoryRow=ui.row(this)
        listOf("Appearance","Connections","Access","System").forEach { key ->
            val option=ui.action(this,key,false) {
                showCategory(key)
                scroll.smoothScrollTo(0,0)
            }
            categoryButtons[key]=option
            categoryRow.addView(option,LinearLayout.LayoutParams(-2,ui.dp(this,46)).apply {
                marginEnd=ui.dp(this@SettingsActivity,8)
            })
        }
        categories.addView(categoryRow)
        body.addView(categories)
        showCategory(selectedCategory)
        section(body,"Appearance","Themes & personalization") { card ->
            card.addView(ui.text(this,ui.themeName(),16f,ui.WHITE,true))
            card.addView(ui.space(this,7))
            card.addView(ui.text(this,
                "10 themes  •  "+ui.density().replaceFirstChar { it.uppercase() }+" layout",
                11f,ui.MUTED))
            card.addView(ui.space(this,11))
            card.addView(ui.action(this,"OPEN APPEARANCE STUDIO",true) {
                startActivityForResult(Intent(this,ThemeGalleryActivity::class.java),72)
            })
        }
        section(body,"Mojangles font","Real Minecraft-style lettering across the interface") { card ->
            card.addView(ui.text(this,
                if (ui.hasCustomMojangles(this)) "✓ CUSTOM MOJANGLES IMPORTED" else "MONOCRAFT • MOJANGLES-STYLE FALLBACK",
                13f,ui.CYAN,true))
            card.addView(ui.space(this,7))
            card.addView(ui.text(this,
                "PocketCraft uses the licensed Monocraft font by default. To use the exact Mojangles font, import a font file you are allowed to use from your phone. PocketCraft never redistributes it.",
                12f,ui.MUTED))
            card.addView(ui.space(this,12))
            card.addView(ui.action(this,"IMPORT MOJANGLES .TTF / .OTF",true) {
                val chooser = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(chooser,71)
            })
            if (ui.hasCustomMojangles(this)) {
                card.addView(ui.space(this,8))
                card.addView(ui.action(this,"REMOVE IMPORTED FONT") {
                    ui.removeCustomMojangles(this)
                    recreate()
                })
            }
            card.addView(ui.space(this,9))
            card.addView(ui.action(this,
                if (ui.pixelFont()) "✓ PIXEL FONT • ACTIVE" else "USE MOJANGLES-STYLE FONT",true) {
                if (!ui.pixelFont()) { ui.setFontStyle(this,true); recreate() }
            })
            card.addView(ui.space(this,8))
            card.addView(ui.action(this,
                if (!ui.pixelFont()) "✓ CLASSIC ANDROID • ACTIVE" else "USE CLASSIC ANDROID") {
                if (ui.pixelFont()) { ui.setFontStyle(this,false); recreate() }
            })
        }
        section(body,"Sound effects","") { card ->
            card.addView(ui.text(this,"PocketCraft soundscape",16f,ui.WHITE,true))
            card.addView(ui.space(this,7))
            card.addView(ui.text(this,
                "Menu and server sounds.",
                12f,ui.MUTED))
            card.addView(ui.space(this,14))
            val soundRow = ui.row(this)
            soundRow.addView(ui.text(this,"Enable sound effects",14f,ui.WHITE,true),
                LinearLayout.LayoutParams(0,-2,1f))
            soundRow.addView(Switch(this).apply {
                isChecked = PocketSounds.isEnabled(this@SettingsActivity)
                thumbTintList = android.content.res.ColorStateList.valueOf(ui.CYAN)
                setOnCheckedChangeListener { _,checked ->
                    PocketSounds.setEnabled(this@SettingsActivity,checked)
                    if (checked) PocketSounds.play(this@SettingsActivity,PocketSounds.Event.SELECT)
                }
            })
            card.addView(soundRow)
            card.addView(ui.space(this,12))
            val volumeLabel = ui.text(this,"Sound volume: " + PocketSounds.volume(this) + "%",12f,ui.MUTED)
            card.addView(volumeLabel)
            card.addView(SeekBar(this).apply {
                max = 100
                progress = PocketSounds.volume(this@SettingsActivity)
                progressTintList = android.content.res.ColorStateList.valueOf(ui.CYAN)
                thumbTintList = android.content.res.ColorStateList.valueOf(ui.CYAN)
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                        volumeLabel.text = "Sound volume: " + p + "%"
                        if (fromUser) PocketSounds.setVolume(this@SettingsActivity,p)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        PocketSounds.play(this@SettingsActivity,PocketSounds.Event.SELECT)
                    }
                })
            })
            card.addView(ui.space(this,8))
            card.addView(ui.action(this,"▶ PREVIEW MENU CLICK") {
                PocketSounds.play(this,PocketSounds.Event.SELECT)
            })
            card.addView(ui.space(this,7))
            card.addView(ui.action(this,"▶ PREVIEW SERVER READY") {
                PocketSounds.play(this,PocketSounds.Event.SERVER_READY)
            })
            card.addView(ui.space(this,6))
            val diagnostics = ui.text(this,PocketSounds.diagnostic(this),11f,ui.CYAN)
            card.addView(diagnostics)
            card.addView(ui.space(this,8))
            card.addView(ui.action(this,"TEST SOUND + CHECK MEDIA VOLUME") {
                PocketSounds.play(this,PocketSounds.Event.SERVER_READY)
                diagnostics.postDelayed({
                    diagnostics.text = PocketSounds.diagnostic(this)
                }, 350L)
            })
            card.addView(ui.space(this,8))
            card.addView(ui.action(this,"OPEN PHONE SOUND SETTINGS") {
                startActivity(Intent(android.provider.Settings.ACTION_SOUND_SETTINGS))
            })
            card.addView(ui.space(this,8))
            card.addView(ui.text(this,
                "Sounds use MEDIA volume. If you hear nothing, raise media volume and check 'Effects loaded 6/6'. The menu sounds are original Minecraft-inspired effects.",
                11f,ui.MUTED))
        }
        section(body,"App updates","Check GitHub Releases and install verified APKs") { card ->
            val installed = PocketUpdates.installedName(this)
            val available = getSharedPreferences("pocketcraft",0)
                .getString("availableUpdate","") ?: ""
            card.addView(ui.text(this,"Installed v" + installed,15f,ui.WHITE,true))
            card.addView(ui.space(this,5))
            card.addView(ui.text(this,
                if (available.isNotBlank()) "New release found: v" + available
                else "No verified new release recorded. Check online for details.",
                12f,if (available.isNotBlank()) ui.GREEN else ui.MUTED))
            card.addView(ui.space(this,12))
            card.addView(ui.action(this,"CHECK FOR UPDATES",true) {
                startActivity(android.content.Intent(this,UpdatesActivity::class.java))
            })
            card.addView(ui.space(this,8))
            card.addView(ui.text(this,
                "GitHub Actions artifacts are not update releases. A compatible signing certificate is required to preserve installed data.",
                11f,ui.MUTED))
        }
        if(ServerService.running) {
            body.addView(ui.space(this,12))
            val notice=ui.card(this)
            notice.addView(ui.text(this,"SERVER CURRENTLY RUNNING",12f,ui.RED,true))
            notice.addView(ui.space(this,6))
            notice.addView(ui.text(this,
                "You can review settings, but stop the Minecraft server before saving changes.",
                12f,ui.MUTED))
            body.addView(notice)
        }

        section(body,"Server connections","Share address and Java capacity") { card ->
            card.addView(ui.text(this,"World options are under Worlds.",12f,ui.MUTED))
            card.addView(ui.space(this,13))
            card.addView(ui.text(this,"SHARE ADDRESS",14f,ui.WHITE,true))
            card.addView(ui.space(this,5))
            card.addView(ui.text(this,"Display name only. Does not configure DNS.",11f,ui.MUTED))
            card.addView(ui.space(this,10))
            advertised=EditText(this).apply {
                setSingleLine(true);textSize=15f
                setTextColor(ui.WHITE);setHintTextColor(ui.MUTED)
                hint="Example: myworld.example.net"
                setText(getSharedPreferences("pocketcraft",0).getString("shareAddress","") ?: "")
                background=ui.shape(this@SettingsActivity,ui.SURFACE,12,ui.BORDER)
                setPadding(ui.dp(this@SettingsActivity,14),ui.dp(this@SettingsActivity,13),
                    ui.dp(this@SettingsActivity,14),ui.dp(this@SettingsActivity,13))
            }
            card.addView(advertised)
            card.addView(ui.space(this,10))
            card.addView(ui.action(this,"COPY SHARE ADDRESS") {
                val address=advertised.text.toString().trim()
                if(address.isBlank()) { toast("Enter an address first"); return@action }
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("PocketCraft address",address))
                toast("Address copied. Check that DNS/tunnel is actually configured.")
            })
            card.addView(ui.space(this,16))
            input(card,"server-port","Java TCP port","25565","Local listener port — not your external tunnel port",true)
            input(card,"max-players","Maximum players","5","Start with 2–5 players on a phone",true)
        }

        section(body,"Bedrock & Geyser","Simple local and Playit networking configuration") { card ->
            card.addView(ui.text(this,"Crossplay settings",12f,ui.MUTED))
            card.addView(ui.space(this,12))
            card.addView(ui.action(this,"OPEN GEYSER SETTINGS",true) {
                startActivity(Intent(this,GeyserSettingsActivity::class.java))
            })
            card.addView(ui.space(this,7))
            card.addView(ui.text(this,"Stop server to edit.",11f,ui.MUTED))
        }

        section(body,"Who can join?","Accounts, whitelist and access safety") { card ->
            toggle(card,"online-mode","Verify Java accounts","ON = official Mojang/Microsoft authentication",true)
            card.addView(ui.pill(this,"OFFLINE MODE WARNING",ui.RED))
            card.addView(ui.space(this,7))
            card.addView(ui.text(this,"Turning verification OFF allows unofficial/non-premium Java clients, but names can be impersonated. Use a whitelist and a trusted network; avoid granting operator access until you've secured authentication. This does not bypass the Minecraft EULA.",12f,ui.MUTED))
            card.addView(ui.space(this,15))
            toggle(card,"white-list","Whitelist players","Only approved usernames may enter (manage names through console)",false)
            toggle(card,"enforce-whitelist","Enforce whitelist","Remove players not on the allowed list",false)
            card.addView(ui.pill(this,"RCON REMAINS DISABLED",ui.CYAN))
            card.addView(ui.space(this,7))
            card.addView(ui.text(this,"No external remote console is exposed by PocketCraft.",11f,ui.MUTED))
        }

        section(body,"Server resources","Java memory; gameplay and world generation settings are inside each world folder") { card ->
            // JNI launcher uses the saved heapMB setting; no Android device RAM is
            // reserved until the server is started. 21 safe steps from 512–3072 MB.
            selectedHeapMB = getSharedPreferences("pocketcraft",0)
                .getInt("heapMB",2048).coerceIn(512,3072)
                .let { 512 + (((it - 512 + 64) / 128).coerceIn(0,20) * 128) }
            val ramLabel = ui.text(this,"Java RAM  •  ${selectedHeapMB} MB",15f,ui.WHITE,true)
            card.addView(ramLabel)
            card.addView(ui.space(this,9))
            val slider = SeekBar(this).apply {
                max = 20   // 0=512 MB, 20=3072 MB, each step=128 MB
                progress = (selectedHeapMB - 512) / 128
                progressTintList = android.content.res.ColorStateList.valueOf(ui.GREEN)
                thumbTintList = android.content.res.ColorStateList.valueOf(ui.GREEN)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(ui.BORDER)
                isEnabled = !ServerService.running
                contentDescription = "Minecraft server Java RAM"
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        if (fromUser) {
                            selectedHeapMB = 512 + value.coerceIn(0,20) * 128
                            ramLabel.text = "Java RAM  •  ${selectedHeapMB} MB"
                        }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {
                        PocketSounds.play(this@SettingsActivity,PocketSounds.Event.SELECT)
                    }
                })
            }
            card.addView(slider)
            val limits = ui.row(this)
            limits.addView(ui.text(this,"512 MB",11f,ui.MUTED),
                LinearLayout.LayoutParams(0,-2,1f))
            limits.addView(ui.text(this,"3 GB",11f,ui.MUTED).apply {
                gravity=Gravity.END
            })
            card.addView(limits)
            card.addView(ui.space(this,6))
            card.addView(ui.text(this,"Changes apply after restart.",11f,ui.MUTED))
        }

        scroll.addView(body)
        screen.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val footer=ui.column(this).apply {
            setPadding(ui.dp(this@SettingsActivity,18),ui.dp(this@SettingsActivity,12),
                ui.dp(this@SettingsActivity,18),ui.dp(this@SettingsActivity,17))
        }
        footer.addView(ui.action(this,
            if(ServerService.running || ServerService.status in setOf("STARTING","STOPPING"))
                "STOP SERVER BEFORE SAVING" else "✓  SAVE SERVER SETTINGS",
            true,!ServerService.running &&
                ServerService.status !in setOf("STARTING","STOPPING")) { save() })
        screen.addView(footer)
        saveFooter=footer
        showCategory(selectedCategory)
        ui.styleFormInputs(screen)
        setContentView(screen)
    }

    private fun save() {
        if(ServerService.running || ServerService.status in setOf("STARTING","STOPPING")) {
            toast("Stop the server before editing its settings")
            return
        }
        val ranges=mapOf(
            "server-port" to 1..65535,
            "max-players" to 1..100
        )
        for((key,range) in ranges) {
            val n=value(key).toIntOrNull()
            if(n==null || n !in range) {
                editors[key]?.error="Enter a number from ${range.first} to ${range.last}"
                editors[key]?.requestFocus()
                toast("Please check ${key.replace('-', ' ')}")
                return
            }
        }
        val ram = selectedHeapMB
        if (ram !in 512..3072) {
            toast("Java memory must be between 512 and 3072 MB")
            return
        }
        val share=advertised.text.toString().trim()
        if(share.length>253 || share.contains(Regex("[\\s/\\\\]")) || share.contains("@")) {
            toast("Enter a hostname or address, without spaces or URLs");return
        }
        val online=toggles.getValue("online-mode").isChecked
        val whitelist=toggles.getValue("white-list").isChecked
        if(!online) {
            AlertDialog.Builder(this).setTitle("Enable non-premium Java clients?")
                .setMessage("Account verification will be disabled (online-mode=false). Players may impersonate names, and whitelist alone is not strong identity verification. Never expose this publicly without an authentication solution. Continue?")
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("I UNDERSTAND") {_,_-> commit(share,ram) }
                .show()
        } else commit(share,ram)
    }

    private fun commit(share:String,ram:Int) {
        for((key,input) in editors) props.setProperty(key,input.text.toString().trim())
        for((key,control) in toggles) props.setProperty(key,control.isChecked.toString())
        for((key,value) in selected) props.setProperty(key,value)
        // RCON is deliberately disabled regardless of old settings.
        props.setProperty("enable-rcon","false")
        props.setProperty("server-ip","")
        file.parentFile?.mkdirs()
        try {
            // Android's AtomicFile restores the previous server.properties if a
            // write fails or the app loses power during the save.
            val atomic=AtomicFile(file)
            val stream=atomic.startWrite()
            try {
                props.store(stream,"PocketCraft Server settings")
                atomic.finishWrite(stream)
            } catch(e:Exception) {
                atomic.failWrite(stream)
                throw e
            }
            getSharedPreferences("pocketcraft",0).edit()
                .putInt("heapMB",ram).putString("shareAddress",share).apply()
            toast("Saved. Restart the server to apply.")
            finish()
        } catch(e:Exception) {toast("Could not save: "+(e.message ?: "storage error"))}
    }

    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("selectedCategory",selectedCategory)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Activity result callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == 72 && resultCode == RESULT_OK) {
            ui.loadTheme(this)
            recreate()
            return
        }
        if (requestCode != 71 || resultCode != RESULT_OK) return
        val fontUri = data?.data ?: return
        val filename = runCatching {
            contentResolver.query(fontUri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,null,null)?.use {
                if (it.moveToFirst()) it.getString(0) else ""
            } ?: ""
        }.getOrDefault("")
        if (!(filename.endsWith(".ttf",true) || filename.endsWith(".otf",true))) {
            toast("Select a .ttf or .otf font file.")
            return
        }
        Thread {
            val result = runCatching { ui.importMojangles(this,fontUri) }
            runOnUiThread {
                result.onSuccess {
                    ui.setFontStyle(this,true)
                    toast("Custom Mojangles font imported.")
                    recreate()
                }.onFailure { toast("Cannot import font: " + it.message) }
            }
        }.start()
    }

    private fun toast(message:String) = Toast.makeText(this,message,Toast.LENGTH_LONG).show()
}
