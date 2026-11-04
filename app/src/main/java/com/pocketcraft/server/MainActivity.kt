package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/** PocketCraft's offline-first dashboard. Does not pretend that a JVM is available on Android. */
class MainActivity : Activity() {
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var readyText: TextView? = null
    private var heroState: TextView? = null
    private var onlineHost: android.widget.FrameLayout? = null
    private var lastOnlineSignature = ""
    private val updateStatus = object: Runnable {
        override fun run() {
            val state = ServerService.status
            readyText?.text = when {
                !ServerService.running -> if(state=="ERROR") "Server error • open Console" else "Server offline"
                ServerService.javaReady && ServerService.bedrockReady -> "Java + Bedrock listener detected"
                ServerService.javaReady -> "Java server ready • Geyser unverified"
                else -> "Starting Minecraft • not joinable yet"
            }
            heroState?.text = when {
                !ServerService.running -> "●  OFFLINE"
                ServerService.javaReady && ServerService.bedrockReady -> "●  JAVA + BEDROCK"
                ServerService.javaReady -> "●  JAVA READY"
                else -> "●  STARTING"
            }
            // Refresh just the online hosting panel when the Playit worker changes
            // state, including account claim URL and process-launch errors.
            if (tab == 0) {
                val signature = listOf(
                    PlayitManager.status, PlayitManager.detail, PlayitManager.claimUrl.orEmpty(),
                    PlayitManager.javaAddress.orEmpty(), PlayitManager.bedrockAddress.orEmpty(),
                    PlayitManager.accountConnected.toString(), PlayitManager.isActive().toString()
                ).joinToString("|")
                if (signature != lastOnlineSignature) {
                    lastOnlineSignature = signature
                    onlineHost?.let { host ->
                        host.removeAllViews()
                        host.addView(internetPanel())
                    }
                }
            }
            uiHandler.postDelayed(this, 2000)
        }
    }
    private val ui = PocketUi
    private val base by lazy { WorldStore.root(this) }
    private val mods by lazy { File(base,"mods").apply { mkdirs() } }
    private var tab = 0
    private var pendingBackup: File? = null
    private val serverJar get() = File(base, "fabric-server-launch.jar")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ui.BG
        window.navigationBarColor = ui.BG
        window.decorView.systemUiVisibility = 0
        tab = savedInstanceState?.getInt("tab") ?: 0
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab); super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); render(); uiHandler.removeCallbacks(updateStatus); uiHandler.post(updateStatus) }
    override fun onPause() { uiHandler.removeCallbacks(updateStatus); super.onPause() }

    private fun h(label: String, sub: String? = null): LinearLayout {
        val item = ui.column(this)
        item.addView(ui.text(this, label, 21f, ui.WHITE, true))
        if (sub != null) {
            item.addView(ui.space(this, 5))
            item.addView(ui.text(this, sub, 12f, ui.MUTED))
        }
        return item
    }
    private fun render() {
        val screen = ui.column(this).apply { setBackgroundColor(ui.BG) }
        val top = ui.row(this).apply { ui.run { padding(this@MainActivity, 20, 19, 20, 13) } }
        top.addView(ui.text(this,"▦", 28f, ui.GREEN,true).apply {
            background = ui.shape(this@MainActivity, ui.SURFACE, 12)
            ui.run { padding(this@MainActivity, 9, 7, 9, 7) }
        })
        top.addView(ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,12),0,0,0)
            addView(ui.text(this@MainActivity,"POCKETCRAFT",17f,ui.WHITE,true))
            addView(ui.text(this@MainActivity,"HOST • CUSTOMIZE • PLAY",9f,ui.MUTED,true))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(ui.text(this,"⚙",24f,ui.GREEN,true).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity,SettingsActivity::class.java)) }; contentDescription="Server settings"
            ui.run { padding(this@MainActivity, 10, 3, 10, 3) }
        })
        screen.addView(top)
        val scroller = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val body = ui.column(this).apply { setPadding(ui.dp(this@MainActivity,18), ui.dp(this@MainActivity,7), ui.dp(this@MainActivity,18), ui.dp(this@MainActivity,18)) }
        when(tab) {
            0 -> dashboard(body)
            1 -> modsPage(body)
            2 -> worldsPage(body)
            else -> consolePage(body)
        }
        scroller.addView(body)
        screen.addView(scroller, LinearLayout.LayoutParams(-1,0,1f))
        val navbar = ui.row(this).apply {
            background = ui.shape(this@MainActivity, 0xFF101A2D.toInt(), 0)
            setPadding(ui.dp(this@MainActivity,6),ui.dp(this@MainActivity,10),ui.dp(this@MainActivity,6),ui.dp(this@MainActivity,12))
        }
        val icons = listOf("◈", "⬡", "▧", "⌘")
        val labels = listOf("HOME", "MODS", "WORLDS", "CONSOLE")
        repeat(4) { i ->
            val item = ui.column(this)
            item.gravity = Gravity.CENTER
            item.addView(ui.text(this,icons[i],24f,if(i==tab) ui.GREEN else ui.MUTED,true).apply { gravity=Gravity.CENTER })
            item.addView(ui.space(this,5))
            item.addView(ui.text(this,labels[i],10f,if(i==tab) ui.GREEN else ui.MUTED,true).apply { gravity=Gravity.CENTER })
            item.isClickable = true
            item.contentDescription = "Open "+labels[i].lowercase(Locale.ROOT)
            item.background=if(i==tab) ui.shape(this,ui.SURFACE,14) else ui.shape(this,0xFF101A2D.toInt(),14)
            item.setOnClickListener { tab=i; render() }
            navbar.addView(item,LinearLayout.LayoutParams(0,ui.dp(this,52),1f))
        }
        screen.addView(navbar)
        setContentView(screen)
    }
    private fun dashboard(body: LinearLayout) {
        body.addView(h("Pocket server","Your Minecraft world, your rules"))
        body.addView(ui.space(this,16))
        val hero = ui.column(this).apply {
            background = ui.gradient(this@MainActivity,0xFF29366A.toInt(),0xFF17354D.toInt(),0xFF145044.toInt())
            ui.run { padding(this@MainActivity,20,18,20,18) }
        }
        val header = ui.row(this)
        header.addView(ui.pill(this,if(ServerService.javaReady)"●  JAVA READY" else if(ServerService.running)"●  STARTING" else "●  OFFLINE",if(ServerService.javaReady)ui.GREEN else if(ServerService.running)ui.CYAN else ui.RED).also { heroState = it })
        header.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        header.addView(ui.pill(this,"FABRIC HOST",ui.CYAN))
        hero.addView(header)
        hero.addView(ui.space(this,10))
        hero.addView(ui.text(this,"Build a world.\nBring your friends.",25f,ui.WHITE,true))
        hero.addView(WorldArt(this))
        hero.addView(ui.text(this,if(ServerService.running)"Minecraft process launched" else "Server engine setup",13f,ui.WHITE,true))
        hero.addView(ui.space(this,6))
        hero.addView(ui.text(this,if(ServerService.running)"Java process is active. Check the Console tab to confirm Minecraft has fully started." else "Install the ARM64 Java runtime, Fabric, and crossplay files before starting. Startup on Android is experimental.",12f,0xFFB7CFE0.toInt()))
        hero.addView(ui.space(this,16))
        hero.addView(ui.action(this,"VIEW SETUP STATUS  →",true) { setup() })
        body.addView(hero)
        body.addView(ui.space(this,16))
        body.addView(setupJourney())
        body.addView(ui.space(this,14))
        body.addView(enginePanel())
        body.addView(ui.space(this,14))
        body.addView(connectionPanel())
        body.addView(ui.space(this,14))
        body.addView(phoneOnlyPanel())
        body.addView(ui.space(this,14))
        val host = android.widget.FrameLayout(this)
        host.addView(internetPanel())
        onlineHost = host
        lastOnlineSignature = ""
        body.addView(host)
        body.addView(ui.space(this,19))
        body.addView(h("Overview"))
        body.addView(ui.space(this,11))
        val row = ui.row(this)
        row.addView(stat("— / "+serverProperty("max-players","5"), "PLAYERS", "♟"), ui.weight(this))
        row.addView(stat(modFiles().count { it.name.endsWith(".jar") }.toString(), "MODS", "⬡"), LinearLayout.LayoutParams(0,-2,1f))
        body.addView(row)
        body.addView(ui.space(this,10))
        val row2 = ui.row(this)
        row2.addView(stat(WorldStore.worlds(this).size.toString(), "WORLDS", "▧"),ui.weight(this))
        row2.addView(stat((getSharedPreferences("pocketcraft",0).getInt("heapMB",2048)/1024f).let { String.format(Locale.ROOT,"%.1f GB",it) }, "RAM TARGET", "◉"),LinearLayout.LayoutParams(0,-2,1f))
        body.addView(row2)
        body.addView(ui.space(this,17))
        body.addView(h("Quick actions"))
        body.addView(ui.space(this,11))
        val actions = ui.card(this)
        actions.addView(actionRow("⚙","Server settings","Addresses, non-premium access, gameplay & performance") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        actions.addView(line())
        actions.addView(actionRow("✦","Download Fabric server","Official Fabric download • select Minecraft version") { installFabricPrompt() })
        actions.addView(line())
        actions.addView(actionRow("☕","Install Android Java 25","36 MB verified ARM64 runtime download") { installJavaPrompt() })
        actions.addView(line())
        actions.addView(actionRow("↓","Import Fabric server","Choose an existing Fabric server launcher JAR") { picker(1) })
        actions.addView(line())
        actions.addView(actionRow("◈","Manage mod library","Import, disable, and remove JAR files") {tab=1;render()})
        actions.addView(line())
        actions.addView(actionRow("▧","World vault","Backups, ZIP imports, and active world") {tab=2;render()})
        body.addView(actions)
        body.addView(ui.space(this,14))
        val note = ui.card(this)
        note.addView(ui.pill(this,"COMPATIBILITY CHECK",ui.PURPLE))
        note.addView(ui.space(this,9))
        note.addView(ui.text(this,"Crossplay compatibility",15f,ui.WHITE,true))
        note.addView(ui.space(this,7))
        note.addView(ui.text(this,"Geyser-Fabric + Floodgate must match your Fabric version. Non-premium Java access and Bedrock authentication are separate settings; neither proves that public multiplayer works.",12f,ui.MUTED))
        body.addView(note)
    }
    /** Honest, actionable setup journey: never equates imported files with a running server. */
    private fun setupJourney(): LinearLayout {
        val card = ui.card(this)
        val version = getSharedPreferences("pocketcraft",0).getString("gameVersion",null)
        val steps = listOf(
            Triple("01", "Fabric server launcher", serverJar.isFile),
            Triple("02", "Minecraft EULA accepted", ServerDownloads.eulaAccepted(this)),
            Triple("03", "Android Java runtime", RuntimeStore.isInstalled(this)),
            Triple("04", "Crossplay and internet access", false)
        )
        val ready = steps.count { it.third }
        val heading = ui.row(this)
        heading.addView(ui.column(this).apply {
            addView(ui.text(this@MainActivity,"SETUP JOURNEY",11f,ui.GREEN,true))
            addView(ui.space(this@MainActivity,5))
            addView(ui.text(this@MainActivity,"Make your world playable",18f,ui.WHITE,true))
        }, LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(ui.pill(this,"$ready / 4",if(ready==4)ui.GREEN else ui.CYAN))
        card.addView(heading)
        card.addView(ui.space(this,14))
        val track = android.widget.FrameLayout(this).apply {
            background=ui.shape(this@MainActivity,ui.SURFACE,8)
        }
        val fill=View(this).apply { background=ui.gradient(this@MainActivity,ui.GREEN,ui.CYAN) }
        track.addView(fill,android.widget.FrameLayout.LayoutParams(0,ui.dp(this,7)))
        track.post {
            fill.layoutParams=android.widget.FrameLayout.LayoutParams(track.width*ready/4,ui.dp(this,7))
        }
        card.addView(track,LinearLayout.LayoutParams(-1,ui.dp(this,7)))
        card.addView(ui.space(this,15))
        steps.forEachIndexed { index, step ->
            val row=ui.row(this)
            row.addView(ui.text(this,if(step.third)"✓" else step.first,12f,
                if(step.third)ui.GREEN else ui.MUTED,true).apply {
                gravity=Gravity.CENTER
                background=ui.shape(this@MainActivity,ui.SURFACE,10)
                layoutParams=LinearLayout.LayoutParams(ui.dp(this@MainActivity,35),ui.dp(this@MainActivity,35))
            })
            val details=ui.column(this).apply { setPadding(ui.dp(this@MainActivity,11),0,0,0) }
            details.addView(ui.text(this,step.second,13f,ui.WHITE,true))
            val subtitle=when(index) {
                0 -> if(step.third) "Installed • ${version ?: "version not recorded"}" else "Choose a Minecraft version"
                1 -> if(step.third) "Agreement recorded" else "Review and accept before hosting"
                2 -> if(step.third) "ARM64 Java 25 files detected • launch experimental" else "Tap Install Java 25 on the dashboard"
                else -> "Geyser, Floodgate and internet tunnel not verified"
            }
            details.addView(ui.space(this,4))
            details.addView(ui.text(this,subtitle,11f,ui.MUTED))
            row.addView(details,LinearLayout.LayoutParams(0,-2,1f))
            card.addView(row)
            if(index<steps.lastIndex) card.addView(ui.space(this,13))
        }
        card.addView(ui.space(this,16))
        card.addView(ui.action(this,if(!serverJar.isFile)"GET FABRIC SERVER  →" else
            if(!ServerDownloads.eulaAccepted(this))"REVIEW MINECRAFT EULA  →" else "VIEW ENGINE LIMITATIONS  →",true) {
            when {
                !serverJar.isFile -> installFabricPrompt()
                !ServerDownloads.eulaAccepted(this) -> offerEula()
                else -> setup()
            }
        })
        return card
    }


    /** Live local process controls. A JVM process is not proof that the game server is ready. */
    private fun serverProperty(key:String,default:String):String {
        val properties=java.util.Properties()
        try {
            File(base,"server.properties").takeIf { it.isFile }?.inputStream()?.use { properties.load(it) }
        } catch (_:Exception) {}
        return properties.getProperty(key,default)
    }

    private fun connectionPanel():LinearLayout {
        val box=ui.card(this)
        val authorized=serverProperty("online-mode","true")!="false"
        val port=serverProperty("server-port","25565")
        val share=getSharedPreferences("pocketcraft",0).getString("shareAddress","").orEmpty().trim()
        val lan=NetworkInfo.lanAddress()
        box.addView(ui.text(this,"CONNECT & SHARE",11f,ui.CYAN,true))
        box.addView(ui.space(this,9))
        box.addView(ui.text(this,"Invite your friends",18f,ui.WHITE,true))
        box.addView(ui.space(this,6))
        box.addView(ui.pill(this,if(authorized)"✓  VERIFIED JAVA ACCOUNTS" else "⚠  NON-PREMIUM MODE",if(authorized) ui.GREEN else ui.RED))
        box.addView(ui.space(this,10))
        if(!authorized) {
            box.addView(ui.text(this,"Account verification is OFF. Usernames can be impersonated. Prefer trusted, private connections and avoid OP permissions.",12f,ui.RED))
            box.addView(ui.space(this,12))
        }
        val address=if(share.isNotBlank())share else if(lan!=null)"$lan:$port" else ""
        box.addView(ui.text(this,if(share.isNotBlank())"CUSTOM SHARE ADDRESS" else "LOCAL NETWORK ADDRESS",11f,ui.MUTED,true))
        box.addView(ui.space(this,7))
        box.addView(ui.text(this,address.ifEmpty { "Unavailable — connect to Wi-Fi" },16f,ui.WHITE,true))
        box.addView(ui.space(this,8))
        box.addView(ui.text(this,if(share.isNotBlank())"Saved for sharing only. DNS, port forwarding or a tunnel must actually point to your phone." else "Java only, same LAN. Your Bedrock address depends on an active Geyser server.",11f,ui.MUTED))
        box.addView(ui.space(this,12))
        val buttons=ui.row(this)
        buttons.addView(ui.action(this,"COPY ADDRESS",true,address.isNotBlank()) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Minecraft server address",address))
            toast("Copied. Verify connectivity before sharing.")
        },ui.weight(this))
        buttons.addView(ui.action(this,"EDIT NETWORK") {
            startActivity(Intent(this,SettingsActivity::class.java))
        },LinearLayout.LayoutParams(0,-2,1f))
        box.addView(buttons)
        box.addView(ui.space(this,12))
        box.addView(ui.text(this,"Java port: $port   •   Bedrock usually: 19132 (Geyser required)",11f,ui.MUTED))
        return box
    }

    /**
     * A real local-first path: Android itself hosts both Minecraft listeners.
     * No Playit, cloud account, reverse proxy or external registration needed.
     */
    private fun phoneOnlyPanel(): LinearLayout {
        val card = ui.card(this)
        val local = NetworkInfo.lanAddress()
        val port = serverProperty("server-port","25565")
        card.addView(ui.text(this,"PHONE-ONLY HOSTING",11f,ui.GREEN,true))
        card.addView(ui.space(this,8))
        card.addView(ui.text(this,"Host straight from this phone",18f,ui.WHITE,true))
        card.addView(ui.space(this,8))
        card.addView(ui.text(this,"No Playit account or tunnel. Start your Fabric server and share these local addresses with players on the same Wi-Fi or phone hotspot.",12f,ui.MUTED))
        card.addView(ui.space(this,11))
        val ready = ServerService.javaReady
        val geyser = ServerService.bedrockReady
        card.addView(ui.pill(this,
            when {
                geyser -> "●  JAVA + BEDROCK LISTENERS"
                ready -> "●  JAVA LISTENER READY"
                ServerService.running -> "●  MINECRAFT STARTING"
                else -> "●  OFFLINE"
            },
            if (ready) ui.GREEN else if (ServerService.running) ui.CYAN else ui.MUTED))
        card.addView(ui.space(this,12))
        card.addView(ui.text(this,
            "JAVA: " + (local?.let { "$it:$port" } ?: "Connect to Wi-Fi / hotspot"),
            13f,ui.WHITE,true))
        card.addView(ui.space(this,5))
        card.addView(ui.text(this,
            "BEDROCK: " + (local?.let { "$it:19132" } ?: "Local network required") +
                if (geyser) " • Geyser listener detected" else " • Geyser listener not confirmed",
            12f,ui.MUTED))
        if (ServerService.nethernetReady) {
            card.addView(ui.space(this,5))
            card.addView(ui.text(this,"✓ External NetherNet signaling registered",12f,ui.GREEN))
        }
        card.addView(ui.space(this,14))
        card.addView(ui.action(this,
            if(ServerService.running) "✓  SERVER PROCESS ACTIVE" else "▶  HOST ON THIS PHONE — NO PLAYIT",
            true,!ServerService.running) {
            startForegroundService(Intent(this,ServerService::class.java)
                .setAction(ServerService.ACTION_START))
            toast("Starting local Minecraft server. No external service used.")
            tab=3
            render()
        })
        if(local != null) {
            card.addView(ui.space(this,9))
            card.addView(ui.action(this,"COPY JAVA LAN ADDRESS") {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Java LAN","$local:$port"))
                toast("Copied local Java address")
            })
            if (geyser) {
                card.addView(ui.space(this,9))
                card.addView(ui.action(this,"COPY BEDROCK LAN ADDRESS") {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Bedrock LAN","$local:19132"))
                    toast("Copied local Bedrock address")
                })
            }
        }
        card.addView(ui.space(this,10))
        card.addView(ui.action(this,"PLAY FROM ANOTHER NETWORK — NO TUNNEL?") {
            val localIp = local ?: "your phone's Wi-Fi address"
            AlertDialog.Builder(this)
                .setTitle("Direct internet hosting (no Playit)")
                .setMessage(
                    "Your phone can host on its own. For remote friends without a tunnel, " +
                    "your home internet/router must allow incoming connections.\n\n" +
                    "1. Connect the phone to your home Wi-Fi and keep PocketCraft running.\n" +
                    "2. Forward TCP $port (Java) and UDP 19132 (Bedrock/Geyser) on your router to $localIp.\n" +
                    "3. Share your router's reachable public IP or hostname, NOT the private LAN IP shown here.\n\n" +
                    "Mobile data and many ISPs use CGNAT, which prevents inbound port forwarding. " +
                    "A tunnel, VPN mesh, or publicly reachable connection is then needed. " +
                    "Only open ports for a server you trust; use allowlists and secure accounts."
                )
                .setPositiveButton("OK",null)
                .show()
        })
        return card
    }

    /** One-tap Playit setup: first use requires a one-time external account approval. */
    private fun internetPanel(): LinearLayout {
        val card = ui.card(this)
        val active = PlayitManager.isActive()
        val javaAddress = PlayitManager.javaAddress
        val bedrockAddress = PlayitManager.bedrockAddress
        val approval = PlayitManager.claimUrl
        card.addView(ui.text(this,"OPTIONAL INTERNET TUNNEL",11f,ui.CYAN,true))
        card.addView(ui.space(this,8))
        card.addView(ui.text(this,"Playit for remote connections",18f,ui.WHITE,true))
        card.addView(ui.space(this,8))
        card.addView(ui.text(this,
            if(ServerService.nethernetReady) "✓ Geyser registered with external NetherNet signaling"
            else "NetherNet signaling not confirmed yet. Check Geyser's configuration and Console.",
            12f,if(ServerService.nethernetReady)ui.GREEN else ui.MUTED))
        card.addView(ui.space(this,8))
        val blocked = PlayitManager.status == "ACCOUNT LIMIT" || PlayitManager.status == "ACCOUNT ACTION"
        card.addView(ui.pill(this,
            if(blocked) "●  PLAYIT ACCOUNT ISSUE" else if(PlayitManager.status=="ERROR") "●  ERROR"
            else if(!active) "●  OFFLINE"
            else if(javaAddress!=null || bedrockAddress!=null) "●  ADDRESS ASSIGNED" else "●  SETTING UP",
            if(blocked || PlayitManager.status=="ERROR")ui.RED
            else if(javaAddress!=null || bedrockAddress!=null)ui.GREEN
            else if(active)ui.CYAN else ui.MUTED))
        card.addView(ui.space(this,9))
        card.addView(ui.text(this,PlayitManager.detail,12f,if(blocked)ui.RED else ui.MUTED))
        card.addView(ui.space(this,12))
        if (blocked) {
            card.addView(ui.action(this,"OPEN PLAYIT AGENT MANAGEMENT",true) {
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://playit.gg/account/agents")))
            })
            card.addView(ui.space(this,9))
            card.addView(ui.action(this,"RETRY AFTER FIXING PLAYIT ACCOUNT") {
                if (active) PlayitManager.retryTunnel(this)
                else startForegroundService(Intent(this,ServerService::class.java)
                    .setAction(ServerService.ACTION_TUNNEL_START))
                toast("Retrying Playit connection")
            })
            card.addView(ui.space(this,11))
        }
        if(!active) {
            card.addView(ui.action(this,"▶  USE PLAYIT FOR REMOTE FRIENDS",true) {
                startForegroundService(Intent(this,ServerService::class.java)
                    .setAction(ServerService.ACTION_TUNNEL_START))
                toast("Starting Playit and checking your Minecraft server. See status below.")
            })
            card.addView(ui.space(this,8))
            card.addView(ui.text(this,"Approve PocketCraft in Playit once. For remote access, assign Minecraft Java (and Bedrock if supported) tunnels in your Playit dashboard. This avoids rejected automatic API requests.",11f,ui.MUTED))
        } else {
            if(!PlayitManager.accountConnected) {
                card.addView(ui.action(this,
                    if(approval!=null)"①  CONNECT PLAYIT ACCOUNT" else "①  PREPARING ACCOUNT LINK...",true,approval!=null) {
                    startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(approval)))
                })
                card.addView(ui.space(this,8))
                card.addView(ui.text(this,"Approve the one-time connection in your browser; then come back here. You do not need to share a password with PocketCraft.",11f,ui.MUTED))
            } else {
                card.addView(ui.text(this,"✓  PLAYIT ACCOUNT CONNECTED",12f,ui.GREEN,true))
                card.addView(ui.space(this,10))
                card.addView(ui.text(this,"JAVA EDITION",11f,ui.MUTED,true))
                card.addView(ui.text(this,javaAddress ?: "Requesting Java address...",15f,ui.WHITE,true))
                if(javaAddress!=null) {
                    card.addView(ui.space(this,6))
                    card.addView(ui.action(this,"COPY JAVA ADDRESS",true) {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Java server",javaAddress))
                        toast("Java address copied")
                    })
                }
                card.addView(ui.space(this,12))
                card.addView(ui.text(this,"BEDROCK EDITION",11f,ui.MUTED,true))
                card.addView(ui.text(this,bedrockAddress ?: "Requesting Bedrock address...",15f,ui.WHITE,true))
                if(bedrockAddress!=null) {
                    card.addView(ui.space(this,6))
                    card.addView(ui.action(this,"COPY BEDROCK ADDRESS",true) {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Bedrock server",bedrockAddress))
                        toast("Bedrock address copied")
                    })
                }
                if(javaAddress==null || bedrockAddress==null) {
                    card.addView(ui.space(this,9))
                    card.addView(ui.text(this,
                        "Use the Playit dashboard to assign tunnels to THIS phone's running agent. Java: 127.0.0.1:25565. Bedrock: 127.0.0.1:19132 where supported.",
                        11f,ui.MUTED))
                    card.addView(ui.space(this,8))
                    card.addView(ui.action(this,"OPEN PLAYIT TUNNELS",true) {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://playit.gg/account/tunnels")))
                    })
                }
            }
            card.addView(ui.space(this,9))
            card.addView(ui.action(this,"↻  REFRESH STATUS") {
                PlayitManager.refreshAsync(this)
                render()
            })
            card.addView(ui.space(this,8))
            card.addView(ui.action(this,"■  GO OFFLINE") {
                startService(Intent(this,ServerService::class.java)
                    .setAction(ServerService.ACTION_TUNNEL_STOP))
                toast("Stopping internet tunnel")
                render()
            })
        }
        card.addView(ui.space(this,10))
        card.addView(ui.action(this,"ADVANCED SETUP / DIAGNOSTICS") {
            AlertDialog.Builder(this)
                .setTitle("Internet hosting diagnostics")
                .setMessage(PlayitManager.recentLog(this).takeLast(4500))
                .setNeutralButton("Playit dashboard") {_,_->
                    startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://playit.gg/")))
                }
                .setPositiveButton("CLOSE",null)
                .show()
        })
        card.addView(ui.space(this,8))
        card.addView(ui.text(this,"Internet access is confirmed only by a successful remote connection. Playit may require additional account approval or manual setup, especially for Bedrock.",11f,ui.MUTED))
        return card
    }

    private fun enginePanel(): LinearLayout {
        val box = ui.card(this)
        val state = ServerService.status
        box.addView(ui.text(this,"HOST CONTROLS",11f,ui.GREEN,true))
        box.addView(ui.space(this,9))
        box.addView(ui.text(this,if(ServerService.javaReady)"Minecraft server ready" else if(ServerService.running)"Minecraft is starting" else "Your server is offline",18f,ui.WHITE,true).also { readyText = it })
        box.addView(ui.space(this,5))
        box.addView(ui.text(this,"Process status: $state. Java readiness requires the Minecraft Done message; Bedrock needs Geyser listener confirmation.",12f,ui.MUTED))
        if(ServerService.failure.isNotBlank()) {
            box.addView(ui.space(this,7))
            box.addView(ui.text(this,"Last error: "+ServerService.failure,12f,ui.RED))
        }
        box.addView(ui.space(this,15))
        val buttons = ui.row(this)
        buttons.addView(ui.action(this,"▶  START",true,!ServerService.running) {
            val intent = Intent(this,ServerService::class.java).setAction(ServerService.ACTION_START)
            startForegroundService(intent)
            toast("Starting process; watch the Console for Minecraft startup")
            tab=3; render()
        },ui.weight(this))
        buttons.addView(ui.action(this,"■  STOP",false,ServerService.running) {
            startService(Intent(this,ServerService::class.java).setAction(ServerService.ACTION_STOP))
            toast("Requesting graceful server stop")
            render()
        },LinearLayout.LayoutParams(0,-2,1f))
        box.addView(buttons)
        box.addView(ui.space(this,9))
        box.addView(ui.action(this,if(RuntimeStore.isInstalled(this))"☕  REINSTALL JAVA 25" else "☕  INSTALL JAVA 25") {
            installJavaPrompt()
        })
        if(RuntimeStore.isInstalled(this)) {
            box.addView(ui.space(this,8))
            box.addView(ui.action(this,"✓  TEST JAVA ENGINE",false) {
                downloadAsync("Java self-test") { RuntimeStore.probe(this) }
            })
        }
        box.addView(ui.space(this,8))
        val lan = NetworkInfo.lanAddress()
        val serverConfig = java.util.Properties()
        File(base, "server.properties").takeIf {it.isFile}?.inputStream()?.use {serverConfig.load(it)}
        val javaPort = serverConfig.getProperty("server-port", "25565")
        if(lan != null) {
            box.addView(ui.space(this,15))
            box.addView(ui.text(this,"LAN CONNECTION",11f,ui.CYAN,true))
            box.addView(ui.space(this,7))
            box.addView(ui.text(this,"Java: $lan:$javaPort",13f,ui.WHITE,true))
            box.addView(ui.text(this,"Bedrock: $lan:19132 (only when Geyser is active)",12f,ui.MUTED))
            box.addView(ui.space(this,9))
            box.addView(ui.action(this,"COPY JAVA LAN ADDRESS") {
                val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("Minecraft Java LAN", "$lan:$javaPort"))
                toast("Local address copied")
            })
        }
        box.addView(ui.space(this,12))
        box.addView(ui.text(this,"LAN addresses work only on the same network. Remote friends need a compatible TCP and UDP tunnel or router ports. Do not share this LAN IP as a public address.",11f,ui.MUTED))
        box.addView(ui.space(this,8))
        box.addView(ui.text(this,"Experimental: on-device execution and Java compatibility require phone testing. Stop the server before changing worlds or mods.",11f,ui.MUTED))
        return box
    }

    private fun installJavaPrompt() {
        if(!RuntimeStore.availableForDevice()) { toast("Java 25 installer supports ARM64 devices only"); return }
        if(ServerService.running) { toast("Stop the server before installing Java"); return }
        AlertDialog.Builder(this)
            .setTitle("Install Android OpenJDK 25?")
            .setMessage("Downloads roughly 36 MB from AngelAuraMC's Android Java release and checks the published SHA-256 before unpacking. Java files require extra storage. You are choosing to install and run third-party Java code; functionality is experimental and your Minecraft world should be backed up first.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Install"){_,_ ->
                downloadAsync("Android Java 25") { RuntimeStore.install(this) { } }
            }.show()
    }

    private fun stat(value:String, label:String, symbol:String):LinearLayout {
        val card=ui.card(this)
        val row=ui.row(this)
        row.addView(ui.text(this,symbol,17f,ui.GREEN,true))
        row.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        card.addView(row);card.addView(ui.space(this,8))
        card.addView(ui.text(this,value,23f,ui.WHITE,true))
        card.addView(ui.space(this,5))
        card.addView(ui.text(this,label,10f,ui.MUTED,true))
        return card
    }
    private fun line():View = View(this).apply {
        setBackgroundColor(0xFF26354C.toInt())
        layoutParams=LinearLayout.LayoutParams(-1,ui.dp(this@MainActivity,1)).apply {
            topMargin=ui.dp(this@MainActivity,12);bottomMargin=ui.dp(this@MainActivity,12)
        }
    }
    private fun actionRow(icon:String,title:String,desc:String,click:()->Unit):LinearLayout {
        val row=ui.row(this)
        row.addView(ui.text(this,icon,22f,ui.CYAN,true).apply {
            gravity=Gravity.CENTER
            background=ui.shape(this@MainActivity,ui.SURFACE,12)
            layoutParams=LinearLayout.LayoutParams(ui.dp(this@MainActivity,46),ui.dp(this@MainActivity,46))
        })
        val labels=ui.column(this).apply { setPadding(ui.dp(this@MainActivity,12),0,0,0) }
        labels.addView(ui.text(this,title,14f,ui.WHITE,true))
        labels.addView(ui.space(this,4))
        labels.addView(ui.text(this,desc,11f,ui.MUTED))
        row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(ui.text(this,"›",24f,ui.MUTED))
        row.setOnClickListener {click()}
        row.contentDescription=title
        return row
    }
    private fun modFiles():List<File> =
        mods.listFiles()?.filter {it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".jar.disabled"))}
            ?.sortedBy { it.name.lowercase(Locale.ROOT) } ?: emptyList()

    /**
     * Geyser runs inside app-private Android storage. Let users apply NetherNet
     * settings without a file manager, root, or sharing private tunnel tokens.
     */
    private fun editGeyserConfiguration() {
        if (ServerService.running) {
            AlertDialog.Builder(this).setTitle("Stop Minecraft first")
                .setMessage("Stop the Minecraft server before changing Geyser settings. Restart afterward to apply the changes.")
                .setPositiveButton("OK",null).show()
            return
        }
        val config = File(base,"config/Geyser-Fabric/config.yml")
        if (!config.isFile) {
            AlertDialog.Builder(this).setTitle("Geyser configuration not created yet")
                .setMessage("Install Geyser-Fabric under Mods, start Minecraft once so Geyser creates config/Geyser-Fabric/config.yml, then stop the server and come back here. Check the Console if Geyser failed to start.")
                .setPositiveButton("OK",null).show()
            return
        }
        if(config.length() > 256_000L) {
            toast("Geyser configuration is unexpectedly large. Cannot edit safely.")
            return
        }
        val original = try { config.readText(Charsets.UTF_8) } catch(e:Exception) {
            toast("Cannot read Geyser config: " + e.message)
            return
        }
        val body = ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,18),ui.dp(this@MainActivity,12),
                ui.dp(this@MainActivity,18),ui.dp(this@MainActivity,12))
        }
        body.addView(ui.text(this,
            "Playit dashboard → your Bedrock (NetherNet) tunnel → Configuration help → Geyser → Copy. Edit the matching bedrock: and java: sections below, keeping the rest of the config intact. Set java.auth-type to floodgate if Floodgate is installed. Never share the private token.",
            12f,ui.MUTED))
        body.addView(ui.space(this,10))
        val editor = android.widget.EditText(this).apply {
            setSingleLine(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(original)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ui.WHITE)
            setBackgroundColor(ui.SURFACE)
            setPadding(ui.dp(this@MainActivity,12),ui.dp(this@MainActivity,12),
                ui.dp(this@MainActivity,12),ui.dp(this@MainActivity,12))
            minLines = 12
            maxLines = 20
            gravity = Gravity.TOP or Gravity.START
        }
        body.addView(editor,LinearLayout.LayoutParams(-1,ui.dp(this,320)))
        body.addView(ui.space(this,10))
        body.addView(ui.text(this,
            "An original-file backup is saved alongside config.yml. Restart the server after saving. This editor never uploads config contents or the NetherNet token.",
            11f,ui.MUTED))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Geyser / NetherNet settings")
            .setView(body)
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("SAVE",null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val next = editor.text.toString()
                if (next.length !in 50..256000 ||
                    !Regex("(?m)^bedrock\\s*:").containsMatchIn(next) ||
                    !Regex("(?m)^java\\s*:").containsMatchIn(next)) {
                    toast("Keep the full Geyser YAML, including bedrock: and java: sections.")
                    return@setOnClickListener
                }
                try {
                    val backup = File(config.parentFile,"config.yml.pocketcraft.bak")
                    val temp = File(config.parentFile,"config.yml.pocketcraft.part")
                    if (!backup.isFile) config.copyTo(backup,overwrite=false)
                    temp.writeText(next,Charsets.UTF_8)
                    if (!temp.renameTo(config)) {
                        temp.delete()
                        throw java.io.IOException("Cannot activate edited config")
                    }
                    dialog.dismiss()
                    toast("Geyser settings saved. Restart Minecraft to use NetherNet.")
                } catch(e:Exception) {
                    toast("Could not save Geyser config: " + e.message)
                }
            }
        }
        dialog.show()
    }

    private fun restoreGeyserBackup() {
        if (ServerService.running) {
            toast("Stop Minecraft before restoring the Geyser configuration.")
            return
        }
        val config = File(base,"config/Geyser-Fabric/config.yml")
        val backup = File(config.parentFile,"config.yml.pocketcraft.bak")
        if (!backup.isFile) {
            toast("No Geyser configuration backup found yet.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Restore Geyser settings?")
            .setMessage("This replaces your current Geyser configuration with the version PocketCraft backed up before your first edit. The current configuration will be saved separately.")
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("RESTORE") {_,_->
                try {
                    val previous = File(config.parentFile,"config.yml.before-restore.bak")
                    if(config.isFile) config.copyTo(previous,overwrite=true)
                    backup.copyTo(config,overwrite=true)
                    toast("Original Geyser configuration restored. Restart Minecraft.")
                } catch(e:Exception) {
                    toast("Could not restore Geyser backup: "+e.message)
                }
            }
            .show()
    }

    private fun modsPage(body:LinearLayout) {
        body.addView(h("Mod library","Manage your Fabric server add-ons"))
        body.addView(ui.space(this,15))
        body.addView(ui.action(this,"+  IMPORT MOD JAR",true) {picker(2)})
        body.addView(ui.space(this,10))
        val packCard=ui.card(this)
        packCard.addView(ui.pill(this,"NEW  •  MODPACKS",ui.GREEN))
        packCard.addView(ui.space(this,9))
        packCard.addView(ui.text(this,"Import a complete modpack",18f,ui.WHITE,true))
        packCard.addView(ui.space(this,7))
        packCard.addView(ui.text(this,"Modrinth .mrpack or CurseForge manifest ZIP. Installs server-compatible files only; no world or launcher replacement.",12f,ui.MUTED))
        packCard.addView(ui.space(this,13))
        packCard.addView(ui.action(this,"⌕  BROWSE MODRINTH PACKS",true) {
            browseModrinth()
        })
        packCard.addView(ui.space(this,8))
        packCard.addView(ui.action(this,"＋  IMPORT MODPACK",true) {
            if(ServerService.running){toast("Stop the server before installing a modpack")}else picker(5)
        })
        body.addView(packCard)
        body.addView(ui.space(this,14))
        body.addView(ui.action(this,"↓  INSTALL CROSSPLAY FILES") {
            AlertDialog.Builder(this).setTitle("Install crossplay bridge?")
                .setMessage("Downloads matching Fabric API from Modrinth, plus the latest Geyser-Fabric and Floodgate from GeyserMC for Minecraft Java 26.3. These server files alone cannot host Minecraft: an Android Java runtime is still required.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Download") {_,_-> downloadAsync("crossplay") {ServerDownloads.installCrossplay(this)} }
                .show()
        })
        body.addView(ui.space(this,10))
        body.addView(ui.action(this,"⚙  EDIT GEYSER / NETHERNET CONFIG",true) {
            editGeyserConfiguration()
        })
        body.addView(ui.space(this,8))
        body.addView(ui.action(this,"↶  RESTORE GEYSER CONFIG BACKUP") {
            restoreGeyserBackup()
        })
        body.addView(ui.space(this,12))
        val list=modFiles()
        body.addView(ui.text(this,list.size.toString() + " total  •  " + list.count {it.name.endsWith(".jar")} + " enabled",12f,ui.MUTED))
        body.addView(ui.space(this,12))
        if (list.isEmpty()) {
            body.addView(empty("⬡","A blank slate","Import .jar mods that support your server's Fabric and Minecraft versions."))
        }
        list.forEach { file ->
            val enabled=file.name.endsWith(".jar")
            val meta=ModInspector.inspect(file)
            val card=ui.card(this)
            val top=ui.row(this)
            top.addView(ui.text(this,"⬡",22f,ui.PURPLE,true))
            top.addView(ui.text(this,meta.title,13f,ui.WHITE,true).apply {
                setPadding(ui.dp(this@MainActivity,10),0,0,0); maxLines=2
            },LinearLayout.LayoutParams(0,-2,1f))
            top.addView(ui.pill(this,if(enabled)"ON" else "OFF",if(enabled)ui.GREEN else ui.MUTED))
            card.addView(top)
            card.addView(ui.space(this,6))
            card.addView(ui.text(this,meta.version+"  •  "+(if(meta.fabric)"Fabric mod" else "Check format"),11f,ui.MUTED))
            card.addView(ui.space(this,10))
            val lower=ui.row(this)
            lower.addView(ui.action(this,if(enabled)"Disable" else "Enable") {
                val target=File(mods,if(enabled) file.name+".disabled" else file.name.removeSuffix(".disabled"))
                if(target.exists() || !file.renameTo(target)) toast("Could not change mod status") else render()
            },ui.weight(this))
            lower.addView(ui.action(this,"Delete") {
                AlertDialog.Builder(this).setTitle("Delete mod?")
                    .setMessage(file.name).setNegativeButton("Cancel",null)
                    .setPositiveButton("Delete"){_,_->if(file.delete())render()else toast("Unable to delete")}.show()
            },LinearLayout.LayoutParams(0,-2,1f))
            card.addView(lower)
            body.addView(card); body.addView(ui.space(this,10))
        }
        val help=ui.card(this)
        help.addView(ui.text(this,"Crossplay checklist",16f,ui.WHITE,true))
        help.addView(ui.space(this,8))
        listOf("Fabric API","Geyser-Fabric","Floodgate-Fabric").forEach { name ->
            val installed=list.any { it.name.replace(" ","-").contains(name.replace(" ","-").substringBefore("-"),true) && it.name.endsWith(".jar") }
            help.addView(ui.text(this,(if(installed)"✓  " else "○  ") + name,
                13f,if(installed)ui.GREEN else ui.MUTED))
            help.addView(ui.space(this,7))
        }
        help.addView(ui.text(this,"File presence does not confirm version compatibility or that the server can start.",11f,ui.MUTED))
        body.addView(help)
    }
    private fun worldsPage(body:LinearLayout) {
        body.addView(h("World vault","Protect every block you've placed"))
        body.addView(ui.space(this,15))
        body.addView(ui.action(this,"+  IMPORT WORLD ZIP",true) {picker(3)})
        body.addView(ui.space(this,16))
        val list=WorldStore.worlds(this)
        val current=WorldStore.active(this)
        if (list.isEmpty()) body.addView(empty("▧","No worlds yet","Import a Java Edition world ZIP containing level.dat."))
        list.forEach { file ->
            val card=ui.card(this)
            val heading=ui.row(this)
            heading.addView(ui.text(this,"▧",23f,ui.GREEN,true))
            heading.addView(ui.text(this,file.name,15f,ui.WHITE,true).apply {
                setPadding(ui.dp(this@MainActivity,9),0,0,0)
            },LinearLayout.LayoutParams(0,-2,1f))
            if(file.name==current) heading.addView(ui.pill(this,"ACTIVE",ui.GREEN))
            card.addView(heading)
            card.addView(ui.space(this,11))
            val controls=ui.row(this)
            controls.addView(ui.action(this,"Use world") {
                try { WorldStore.activate(this,file); render();toast("Active world changed") }
                catch(e:Exception) { toast(e.message ?: "Unable to activate") }
            },ui.weight(this))
            controls.addView(ui.action(this,"Export ZIP") { pendingBackup=file;createBackupDocument(file) },
                LinearLayout.LayoutParams(0,-2,1f))
            card.addView(controls)
            card.addView(ui.space(this,7))
            card.addView(ui.text(this,"Delete world",12f,ui.RED).apply {
                gravity=Gravity.END
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("Delete this world?")
                        .setMessage("This permanently deletes "+file.name+". Export a backup first.")
                        .setNegativeButton("Cancel",null)
                        .setPositiveButton("Delete") {_,_->if(file.deleteRecursively()) render() else toast("Could not delete world")}
                        .show()
                }
            })
            body.addView(card);body.addView(ui.space(this,10))
        }
        val note=ui.card(this)
        note.addView(ui.text(this,"World safety",15f,ui.WHITE,true))
        note.addView(ui.space(this,8))
        note.addView(ui.text(this,"Exports are saved to a location you choose. Imports use path checks and a 1 GB extracted-size limit. Always back up a world before replacing it.",12f,ui.MUTED))
        body.addView(note)
    }
    private fun consolePage(body:LinearLayout) {
        body.addView(h("Server console","Real logs, no pretend online status"))
        body.addView(ui.space(this,16))
        val actions=ui.row(this)
        actions.addView(ui.action(this,"↻  Refresh",true){render()},ui.weight(this))
        actions.addView(ui.action(this,"Copy log"){
            val manager=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("PocketCraft logs",getLog()))
            toast("Log copied")
        },LinearLayout.LayoutParams(0,-2,1f))
        body.addView(actions)
        body.addView(ui.space(this,13))
        val log=ui.column(this).apply {
            background=ui.shape(this@MainActivity,0xFF0F1728.toInt(),18,0xFF26354D.toInt())
            ui.run {padding(this@MainActivity,16,16,16,16)}
        }
        log.addView(ui.pill(this,"●  LOCAL LOG",ui.CYAN))
        log.addView(ui.space(this,12))
        log.addView(ui.text(this,getLog(),12f,0xFFC0D7D7.toInt()))
        body.addView(log)
        body.addView(ui.space(this,15))
        val entry=android.widget.EditText(this).apply {
            hint = "Type server command, e.g. list"
            setSingleLine(true)
            textSize = 14f
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background = ui.shape(this@MainActivity,ui.SURFACE,12,0xFF31415D.toInt())
            setPadding(ui.dp(this@MainActivity,14),ui.dp(this@MainActivity,13),
                ui.dp(this@MainActivity,14),ui.dp(this@MainActivity,13))
            isEnabled = ServerService.running
        }
        body.addView(entry)
        body.addView(ui.space(this,8))
        body.addView(ui.action(this,"SEND COMMAND →",true,ServerService.running) {
            val command=entry.text.toString().trim().removePrefix("/")
            if(command.isBlank()) {toast("Enter a command");return@action}
            startService(Intent(this,ServerService::class.java)
                .setAction(ServerService.ACTION_COMMAND)
                .putExtra(ServerService.EXTRA_COMMAND,command))
            entry.text.clear()
            toast("Command submitted; refresh the console to see results")
        })
        body.addView(ui.space(this,8))
        body.addView(ui.text(this,if(ServerService.running)"Commands are sent to the running Java process." else "Start the Java server first to enable commands.",12f,ui.MUTED))
    }
    private fun getLog():String {
        val file=File(base,"console.log")
        return if(file.exists()) file.readText().takeLast(12000) else
            "[PocketCraft] No server process has been started.\n[Setup] Android Java runtime is not bundled in this build.\n[Tip] Imported JARs are stored safely for later use."
    }
    private fun empty(symbol:String,title:String,detail:String):LinearLayout=ui.card(this).apply {
        gravity=Gravity.CENTER_HORIZONTAL
        addView(ui.text(this@MainActivity,symbol,36f,ui.PURPLE,true))
        addView(ui.space(this@MainActivity,11))
        addView(ui.text(this@MainActivity,title,17f,ui.WHITE,true))
        addView(ui.space(this@MainActivity,7))
        addView(ui.text(this@MainActivity,detail,12f,ui.MUTED).apply {gravity=Gravity.CENTER})
    }
    /**
     * Android AlertDialog's message panel hides setItems on some Android builds.
     * Use explicit themed buttons instead, so selection stays visible on realme UI.
     */
    private fun installFabricPrompt() {
        val popup = ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,18),ui.dp(this@MainActivity,10),
                ui.dp(this@MainActivity,18),ui.dp(this@MainActivity,14))
        }
        popup.addView(ui.text(this,"Select a Minecraft version",17f,ui.WHITE,true))
        popup.addView(ui.space(this,8))
        popup.addView(ui.text(this,
            "Choose the Fabric server version to download. The separate Java runtime must also be installed.",
            12f,ui.MUTED))
        popup.addView(ui.space(this,16))
        val dialog=AlertDialog.Builder(this)
            .setTitle("Install Fabric server")
            .setView(popup)
            .setNegativeButton("CANCEL",null)
            .create()
        val versions=listOf(
            Triple("26.3", "Minecraft 26.3", "Recommended for Geyser crossplay • Java 25"),
            Triple("1.21.11", "Minecraft 1.21.11", "Requires Java 21 • check older crossplay support"),
            Triple("1.20.1", "Minecraft 1.20.1", "Requires Java 17 • older modpacks")
        )
        versions.forEach { (version,title,subtitle) ->
            val item=ui.column(this).apply {
                background=ui.shape(this@MainActivity,ui.SURFACE,14,0xFF344A61.toInt())
                setPadding(ui.dp(this@MainActivity,15),ui.dp(this@MainActivity,14),
                    ui.dp(this@MainActivity,15),ui.dp(this@MainActivity,14))
                isClickable=true
                isFocusable=true
                contentDescription="$title. $subtitle"
                setOnClickListener {
                    dialog.dismiss()
                    confirmFabricInstall(version)
                }
            }
            val heading=ui.row(this)
            heading.addView(ui.text(this,title,15f,ui.WHITE,true),LinearLayout.LayoutParams(0,-2,1f))
            heading.addView(ui.text(this,"›",22f,ui.GREEN,true))
            item.addView(heading)
            item.addView(ui.space(this,5))
            item.addView(ui.text(this,subtitle,11f,ui.MUTED))
            popup.addView(item)
            popup.addView(ui.space(this,9))
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setBackgroundDrawable(ui.shape(this,ui.PANEL,22))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ui.CYAN)
        }
        dialog.show()
    }

    private fun confirmFabricInstall(version:String) {
        AlertDialog.Builder(this)
            .setTitle("Install Fabric $version?")
            .setMessage("Downloads the official Fabric server launcher from meta.fabricmc.net. This replaces only the server JAR, keeping your worlds and mods. A compatible Java runtime is also required.")
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("INSTALL") {_,_->
                downloadAsync("Fabric $version") {ServerDownloads.installFabric(this,version)}
            }
            .show()
    }

    private fun downloadAsync(label:String, action:()->String) {
        val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
        val panel=ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,22),ui.dp(this@MainActivity,16),
                ui.dp(this@MainActivity,22),ui.dp(this@MainActivity,20))
        }
        panel.addView(ui.pill(this,"✦  POCKETCRAFT INSTALLER",ui.CYAN))
        panel.addView(ui.space(this,15))
        val title=ui.text(this,label,19f,ui.WHITE,true)
        panel.addView(title)
        panel.addView(ui.space(this,8))
        val status=ui.text(this,"Preparing download…",13f,ui.MUTED)
        panel.addView(status)
        panel.addView(ui.space(this,17))
        val bar=android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate=true
            max=1000
            progressTintList=android.content.res.ColorStateList.valueOf(ui.GREEN)
            indeterminateTintList=android.content.res.ColorStateList.valueOf(ui.CYAN)
        }
        panel.addView(bar,LinearLayout.LayoutParams(-1,ui.dp(this,10)))
        panel.addView(ui.space(this,9))
        val details=ui.text(this,"Connecting securely…",12f,ui.MUTED)
        panel.addView(details)
        panel.addView(ui.space(this,10))
        panel.addView(ui.text(this,"Keep this screen open. Cancelling stops the current transfer; partially installed files may need cleanup.",11f,ui.MUTED))
        val waiting=AlertDialog.Builder(this)
            .setView(panel).setNegativeButton("CANCEL") {_,_->cancelled.set(true)}
            .create()
        waiting.setCancelable(false)
        waiting.show()
        Thread {
            val outcome=try {
                Result.success(InstallProgress.withSession({update ->
                    runOnUiThread {
                        if(!isFinishing && waiting.isShowing) {
                            status.text=update.stage
                            val total=update.total
                            if(total>0) {
                                bar.isIndeterminate=false
                                bar.progress=((update.done.toDouble()/total).coerceIn(0.0,1.0)*1000).toInt()
                                val doneMb=update.done/1048576.0
                                val totalMb=total/1048576.0
                                details.text=String.format(Locale.ROOT,"%.1f / %.1f MB",doneMb,totalMb)+
                                    (if(update.files>0)"  •  File ${update.file}/${update.files}" else "")
                            }else{
                                bar.isIndeterminate=true
                                details.text=if(update.files>0)"File ${update.file} of ${update.files}" else "Preparing installation"
                            }
                        }
                    }
                }, {cancelled.get()}) { action() })
            } catch(e:Exception){ Result.failure<String>(e) }
            runOnUiThread {
                if(isFinishing)return@runOnUiThread
                waiting.dismiss()
                outcome.onSuccess {message->
                    AlertDialog.Builder(this).setTitle("✓  Installation complete")
                        .setMessage(message).setPositiveButton("OK",null).show()
                    render()
                    if(label.startsWith("Fabric") && !ServerDownloads.eulaAccepted(this)) offerEula()
                }.onFailure {error->
                    AlertDialog.Builder(this).setTitle(if(cancelled.get())"Installation cancelled" else "Installation failed")
                        .setMessage(error.message ?: "Check your connection and available storage.")
                        .setPositiveButton("OK",null).show()
                }
            }
        }.start()
    }
    private fun offerEula() {
        AlertDialog.Builder(this).setTitle("Minecraft EULA")
            .setMessage("Before starting a Minecraft server, review and agree to Mojang's EULA at minecraft.net/en-us/eula. The app will only write eula=true if you explicitly agree.")
            .setNeutralButton("Read EULA") {_,_->
                startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.minecraft.net/en-us/eula")))
            }
            .setNegativeButton("Not now",null)
            .setPositiveButton("I have read and agree") {_,_->
                ServerDownloads.acceptEula(this);toast("EULA acceptance saved")
            }.show()
    }
    private fun setup() {
        AlertDialog.Builder(this).setTitle("Server engine status")
            .setMessage("Fabric JAR: "+(if(serverJar.isFile)"Imported" else "Missing")+
                "\nAndroid Java runtime: "+(if(RuntimeStore.isInstalled(this))"Java binary detected (experimental)" else "Not installed")+
                "\nEULA acceptance: "+(if(ServerDownloads.eulaAccepted(this))"Recorded" else "Required before launch")+
                "\nGeyser/Floodgate: Requires compatible Fabric versions"+
                "\nInternet tunnel: Not configured"+
                "\n\nServer launch is experimental. A Java binary can be started if Android permits it, but startup and multiplayer have not been verified on your phone.")
            .setPositiveButton("OK",null).show()
    }
    private fun about() {
        AlertDialog.Builder(this).setTitle("PocketCraft Server 0.3")
            .setMessage("A dark-mode Minecraft manager with experimental Java 25 runtime installation and real process controls. This build is not tested on your phone; internet tunneling and verified multiplayer are still under development.")
            .setPositiveButton("Got it",null).show()
    }
    private fun picker(code:Int) {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type=if(code==3 || code==5)"*/*" else "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent,code)
    }
    private fun browseModrinth() {
        val input=android.widget.EditText(this).apply {
            hint="Search Fabric modpacks"
            setSingleLine(true)
            textSize=16f
            setTextColor(ui.WHITE);setHintTextColor(ui.MUTED)
            background=ui.shape(this@MainActivity,ui.SURFACE,12)
            setPadding(ui.dp(this@MainActivity,14),ui.dp(this@MainActivity,14),
                ui.dp(this@MainActivity,14),ui.dp(this@MainActivity,14))
        }
        val shell=ui.column(this).apply{
            setPadding(ui.dp(this@MainActivity,20),ui.dp(this@MainActivity,12),
                ui.dp(this@MainActivity,20),ui.dp(this@MainActivity,15))
            addView(input)
        }
        AlertDialog.Builder(this).setTitle("Explore Modrinth")
            .setMessage("Discover Fabric modpacks. Only releases matching your installed Minecraft server can be installed.")
            .setView(shell).setNegativeButton("CANCEL",null)
            .setPositiveButton("SEARCH") {_,_->
                val query=input.text.toString().trim()
                if(query.isBlank())toast("Enter a search term") else {
                    val wait=AlertDialog.Builder(this).setTitle("Searching Modrinth")
                        .setMessage("Finding compatible Fabric modpacks…").setCancelable(false).create()
                    wait.show()
                    Thread{
                        val result=runCatching{ModrinthDiscovery.find(query)}
                        runOnUiThread {
                            wait.dismiss()
                            result.onSuccess {showModrinthPacks(it)}
                                .onFailure {toast("Search failed: "+it.message)}
                        }
                    }.start()
                }
            }.show()
    }

    private fun showModrinthPacks(packs:List<ModrinthDiscovery.Pack>) {
        if(packs.isEmpty()){toast("No Fabric modpacks found");return}
        val screen=ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,15),ui.dp(this@MainActivity,8),
                ui.dp(this@MainActivity,15),ui.dp(this@MainActivity,16))
        }
        lateinit var dialog:AlertDialog
        packs.forEach { pack ->
            val item=ui.column(this).apply{
                background=ui.shape(this@MainActivity,ui.SURFACE,13)
                setPadding(ui.dp(this@MainActivity,15),ui.dp(this@MainActivity,13),
                    ui.dp(this@MainActivity,15),ui.dp(this@MainActivity,13))
                isClickable=true
                addView(ui.text(this@MainActivity,pack.title,15f,ui.WHITE,true))
                addView(ui.space(this@MainActivity,5))
                addView(ui.text(this@MainActivity,pack.description,11f,ui.MUTED))
                setOnClickListener {
                    dialog.dismiss()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Install "+pack.title+"?")
                        .setMessage("The app downloads the matching .mrpack, checks its server dependencies and installs allowed server files. The server must be stopped. Existing files won't be overwritten.")
                        .setNegativeButton("CANCEL",null)
                        .setPositiveButton("INSTALL"){_,_->
                            if(ServerService.running){toast("Stop the server first");return@setPositiveButton}
                            val version=getSharedPreferences("pocketcraft",0)
                                .getString("gameVersion",null)
                            if(version.isNullOrBlank()){toast("Install a Fabric server first");return@setPositiveButton}
                            downloadAsync("Modrinth: "+pack.title){
                                val uri=ModrinthDiscovery.downloadLatest(this@MainActivity,pack,version)
                                try {ModpackImporter.install(this@MainActivity,uri).description()}
                                finally{File(uri.path ?: "").delete()}
                            }
                        }.show()
                }
            }
            screen.addView(item)
            screen.addView(ui.space(this,9))
        }
        val scroll=ScrollView(this).apply {addView(screen)}
        dialog=AlertDialog.Builder(this).setTitle("Modrinth modpacks")
            .setView(scroll).setNegativeButton("CLOSE",null).create()
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels*0.94).toInt(),
            (resources.displayMetrics.heightPixels*0.75).toInt())
    }

    private fun askCurseForgeKey(uri:android.net.Uri) {
        val keyBox=android.widget.EditText(this).apply {
            hint="Optional CurseForge developer API key"
            setSingleLine(true)
            inputType=android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val wrapper=ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,20),ui.dp(this@MainActivity,12),
                ui.dp(this@MainActivity,20),ui.dp(this@MainActivity,8))
            addView(ui.text(this@MainActivity,"CurseForge downloads generally require an approved API key. It is used for this import only and is not saved.",12f,ui.MUTED))
            addView(ui.space(this@MainActivity,13))
            addView(keyBox)
        }
        AlertDialog.Builder(this).setTitle("Import CurseForge modpack")
            .setView(wrapper).setNegativeButton("CANCEL",null)
            .setPositiveButton("IMPORT"){_,_->
                installModpack(uri,keyBox.text.toString().trim().ifEmpty {null})
            }.show()
    }

    private fun installModpack(uri:android.net.Uri,key:String?) {
        downloadAsync("Modpack installation") {
            ModpackImporter.install(this,uri,key).description()
        }
    }

    private fun createBackupDocument(world:File) {
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type="application/zip"
            putExtra(Intent.EXTRA_TITLE,world.name+"-backup.zip")
        }
        startActivityForResult(intent,4)
    }
    private fun displayName(uri:android.net.Uri):String {
        var value:String?=null
        contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
            if(cursor.moveToFirst()) value=cursor.getString(0)
        }
        return value ?: "imported-"+System.currentTimeMillis()+".jar"
    }
    private fun safeName(original:String):String=original.replace(Regex("[^A-Za-z0-9._-]"),"_")
        .take(120).trimStart('.')
    private fun importJar(uri:android.net.Uri,kind:Int) {
        val filename=safeName(displayName(uri))
        if(!filename.endsWith(".jar",true)) {toast("Please select a .jar file");return}
        val target=if(kind==1) serverJar else File(mods,filename)
        if(kind==2 && target.exists()) {toast("Mod already imported");return}
        Thread {
            val temp=File(base,"import-"+System.currentTimeMillis()+".tmp")
            try {
                var length=0L
                val input=contentResolver.openInputStream(uri) ?: error("Cannot open file")
                input.use { source ->
                    temp.outputStream().use { output ->
                        val data=ByteArray(32768)
                        while(true) {
                            val n=source.read(data)
                            if(n<0) break
                            length+=n
                            if(length>250L*1024L*1024L) error("JAR exceeds 250 MB import limit")
                            output.write(data,0,n)
                        }
                    }
                }
                temp.inputStream().use { stream ->
                    val magic=ByteArray(2)
                    if(stream.read(magic)!=2 || magic[0]!=0x50.toByte() || magic[1]!=0x4B.toByte())
                        error("Not a valid JAR/ZIP file")
                }
                val oldFile=File(base,"previous-fabric-server.jar")
                if(kind==1 && target.exists()) {
                    oldFile.delete()
                    if(!target.renameTo(oldFile)) error("Could not preserve previous server JAR")
                }
                if(!temp.renameTo(target)) {
                    if(kind==1 && oldFile.exists()) oldFile.renameTo(target)
                    error("Unable to install imported JAR")
                }
                runOnUiThread {toast("Imported "+filename);render()}
            } catch(e:Exception) {runOnUiThread {toast("Import failed: "+e.message)}}
            finally {temp.delete()}
        }.start()
    }
    @Deprecated("Activity result callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK) return
        val uri=data?.data ?: return
        when(requestCode) {
            1,2 -> importJar(uri,requestCode)
            3 -> {
                Thread {
                    try {
                        val imported=WorldStore.importZip(this,uri)
                        runOnUiThread {toast("World imported: "+imported.name);tab=2;render()}
                    } catch(e:Exception) {runOnUiThread {toast("World import failed: "+e.message)}}
                }.start()
            }
            5 -> {
                val name=displayName(uri).lowercase(Locale.ROOT)
                if(!name.endsWith(".mrpack") && !name.endsWith(".zip")) {
                    toast("Choose a .mrpack or CurseForge .zip file");return
                }
                val isCurse=name.endsWith(".zip")
                if(isCurse) askCurseForgeKey(uri) else installModpack(uri,null)
            }
            4 -> {
                val world=pendingBackup ?: return
                pendingBackup=null
                Thread {
                    try {
                        WorldStore.backup(this,world,uri)
                        runOnUiThread {toast("Backup exported successfully")}
                    } catch(e:Exception) {runOnUiThread {toast("Backup error: "+e.message)}}
                }.start()
            }
        }
    }
    private fun toast(value:String) = Toast.makeText(this,value,Toast.LENGTH_LONG).show()
}
