package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.widget.ImageView
import android.widget.FrameLayout
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
    // Folder-style Worlds navigation; the root list is kept distinct from the selected world.
    private var openedWorldFolder: String? = null
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var readyText: TextView? = null
    private var heroState: TextView? = null
    private var primaryServerAction: TextView? = null
    private var vitalsText: TextView? = null
    private var lastVitalsUpdate = 0L
    private var onlineHost: FrameLayout? = null
    private var lastOnlineSignature = ""
    private var phoneHost: FrameLayout? = null
    private var lastPhoneSignature = ""
    private var connectionHost: FrameLayout? = null
    private var crossplaySummaryHost: FrameLayout? = null
    private var lastCrossplaySummarySignature = ""
    private var lastConnectionSignature = ""
    private var consoleOutput: TextView? = null
    private var consoleLogScroll: ScrollView? = null
    private var consoleCommandEntry: android.widget.EditText? = null
    private var consoleCommandFeedbackView: TextView? = null
    private var consoleActivityView: TextView? = null
    private var lastActivityRevision = -1
    private var consoleAvailabilityView: TextView? = null
    private var consoleLogExpanded = false
    private var lastCommandFeedbackRevision = -1
    private var consoleSuggestionHost: LinearLayout? = null
    private var lastConsoleCommandRevision = -1
    private var lastConsoleSize = -1L
    private var lastConsoleModified = -1L
    private var consoleViewVersion = 0
    private var consoleCachedTail = "[PocketCraft] Loading console…"
    private var consoleLogFilter = "CLEAN"
    private var consoleLogSearch = ""
    private val consoleReadPending = java.util.concurrent.atomic.AtomicBoolean(false)
    // Log I/O and decoding must never compete with the Android main thread.
    private val consoleReader = java.util.concurrent.Executors.newSingleThreadExecutor { job ->
        Thread(job, "PocketCraft-console-reader").apply { isDaemon = true }
    }
    private val updateStatus = object: Runnable {
        override fun run() {
            val state = ServerService.status
            readyText?.text = when {
                !ServerService.running -> if(state=="ERROR") "Server error • open Console" else "Server offline"
                ServerService.javaReady && ServerService.bedrockJoinVerified -> "Java + Bedrock player joined"
                ServerService.javaReady && ServerService.bedrockReady -> "Java ready • Geyser started (not tested)"
                ServerService.javaReady -> "Java server ready • Geyser unverified"
                else -> "Starting Minecraft • not joinable yet"
            }
            heroState?.text = when {
                state == "STARTING" -> "●  STARTING"
                state == "STOPPING" -> "●  STOPPING"
                !ServerService.running -> if(state == "ERROR") "●  NEEDS ATTENTION" else "●  OFFLINE"
                ServerService.javaReady && ServerService.bedrockJoinVerified -> "●  BEDROCK JOIN VERIFIED"
                ServerService.javaReady && ServerService.bedrockReady -> "●  GEYSER STARTED"
                ServerService.javaReady -> "●  JAVA READY"
                else -> "●  STARTING"
            }
            primaryServerAction?.let { button ->
                val busy = state in setOf("STARTING","STOPPING")
                button.text = when {
                    state == "STARTING" -> "STARTING MINECRAFT…"
                    state == "STOPPING" -> "STOPPING MINECRAFT…"
                    ServerService.running -> "■  STOP SERVER"
                    HostingHealth.startBlockers(healthSnapshot()) > 0 -> "✦  FINISH SETUP"
                    else -> "▶  START SERVER"
                }
                button.isEnabled = !busy
                button.alpha = if(busy) .52f else 1f
            }
            val now=android.os.SystemClock.elapsedRealtime()
            if(tab==0 && now-lastVitalsUpdate>=5000) {
                lastVitalsUpdate=now
                vitalsText?.text=runCatching {ServerVitals.device(this@MainActivity)}
                    .getOrElse {"Device metrics unavailable"}
            }
            // Keep live hosting state up to date without redrawing the entire
            // page or unexpectedly resetting the user's scroll position.
            if (tab == 0) {
                val crossplaySignature = listOf(ServerService.running, ServerService.javaReady,
                    ServerService.bedrockReady, ServerService.bedrockEvidence,
                    ServerService.nethernetNativeFailure).joinToString("|")
                if (crossplaySignature != lastCrossplaySummarySignature) {
                    lastCrossplaySummarySignature = crossplaySignature
                    crossplaySummaryHost?.let { host ->
                        host.removeAllViews()
                        host.addView(crossplaySummaryPanel())
                    }
                }
            }
            if (tab == 0) {
                val signature = listOf(ServerService.running, ServerService.status,
                    ServerService.javaReady, ServerService.bedrockReady,
                    ServerService.nethernetReady, ServerService.nethernetMode,
                    ServerService.bedrockEvidence, ServerService.nethernetNativeFailure,
                    NetworkInfo.lanAddress().orEmpty(),
                    File(base,"config/Geyser-Fabric/config.yml").lastModified()
                ).joinToString("|")
                if (signature != lastPhoneSignature) {
                    lastPhoneSignature = signature
                    phoneHost?.let { host ->
                        host.removeAllViews()
                        host.addView(phoneOnlyPanel())
                    }
                }
            } else if (tab == 3) {
                // The Console can be opened *before* Minecraft starts. Never
                // leave controls permanently disabled from the initial render.
                val availability = if (ServerService.running)
                    "Minecraft process active • type a command and tap SEND or keyboard Send."
                else "Minecraft is offline • start the server before sending."
                consoleAvailabilityView?.let { if(it.text.toString()!=availability)it.text=availability }
                if (lastCommandFeedbackRevision != ServerService.commandRevision) {
                    lastCommandFeedbackRevision=ServerService.commandRevision
                    consoleCommandFeedbackView?.text=ServerService.commandFeedback
                }
                if (lastActivityRevision != ConsoleCommandActivity.revision) {
                    lastActivityRevision=ConsoleCommandActivity.revision
                    consoleActivityView?.text=ConsoleCommandActivity.preview()
                }
                // Keep full-log decoding dormant while using the compact
                // command panel. Opening Full Logs starts the async reader.
                if (consoleLogExpanded) {
                    val file = File(base, "console.log")
                    if (file.length() != lastConsoleSize ||
                        file.lastModified() != lastConsoleModified) {
                        lastConsoleSize = file.length()
                        lastConsoleModified = file.lastModified()
                        requestConsoleLogRefresh()
                    }
                }
                if (lastConsoleCommandRevision != ServerCommandHints.revision) {
                    lastConsoleCommandRevision = ServerCommandHints.revision
                    updateConsoleSuggestions()
                }
            }
            if (tab == 0) {
                val signature = listOf(
                    ServerService.running, ServerService.javaReady,
                    ServerService.bedrockReady, ServerService.nethernetNativeFailure,
                    ServerService.bedrockEvidence, ServerService.nethernetMode,
                    File(base,"config/Geyser-Fabric/config.yml").lastModified()
                ).joinToString("|")
                if (signature != lastConnectionSignature) {
                    lastConnectionSignature = signature
                    connectionHost?.let { host ->
                        host.removeAllViews()
                        host.addView(connectionJourneyPanel())
                    }
                }
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
    private var currentScroller: ScrollView? = null
    private var renderedTab = -1
    private var resetWorldScroll = false
    private var scrollOffsets = IntArray(4)
    private val serverJar get() = File(base, "fabric-server-launch.jar")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.loadTheme(this)
        PocketSounds.prepare(this)
        window.statusBarColor = ui.BG
        window.navigationBarColor = ui.BG
        window.decorView.systemUiVisibility = 0
        tab = (savedInstanceState?.getInt("tab") ?: 0).coerceIn(0,3)
        openedWorldFolder = savedInstanceState?.getString("openedWorldFolder")
        scrollOffsets = savedInstanceState?.getIntArray("tabScroll")?.takeIf { it.size==4 } ?: IntArray(4)
        render()
        // Show a real installation guide only on a new app-data installation.
        val prefs = getSharedPreferences("pocketcraft",0)
        if (savedInstanceState == null && !serverJar.isFile &&
            !prefs.getBoolean("firstRunGuideSeenV0833",false)) {
            uiHandler.post { if (!isFinishing && !isDestroyed) showFirstRunGuide() }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (renderedTab in 0..3) scrollOffsets[renderedTab] = currentScroller?.scrollY ?: 0
        outState.putInt("tab", tab)
        outState.putString("openedWorldFolder", openedWorldFolder)
        outState.putIntArray("tabScroll", scrollOffsets)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); ui.loadTheme(this); PocketUpdates.maybeCheck(this); render(); uiHandler.removeCallbacks(updateStatus); uiHandler.post(updateStatus) }
    @Deprecated("Back navigation")
    override fun onBackPressed() {
        if(tab == 2 && openedWorldFolder != null) {
            openedWorldFolder = null
            resetWorldScroll=true
            render()
        } else super.onBackPressed()
    }
    override fun onPause() { uiHandler.removeCallbacks(updateStatus); super.onPause() }
    override fun onDestroy() {
        uiHandler.removeCallbacks(updateStatus)
        consoleReader.shutdownNow()
        super.onDestroy()
    }

    /** Fetch the logfile tail off the main thread to prevent Console navigation freezes. */
    private fun requestConsoleLogRefresh() {
        if (tab != 3 || !consoleLogExpanded || consoleReader.isShutdown) return
        val viewer = consoleOutput ?: return
        if (!consoleReadPending.compareAndSet(false,true)) return
        val generation = consoleViewVersion
        try {
            consoleReader.execute {
                val tail = getLog()
                uiHandler.post {
                    consoleReadPending.set(false)
                    if (!isFinishing && !isDestroyed && tab == 3) {
                        if (generation == consoleViewVersion && consoleOutput === viewer) {
                            val scroller = consoleLogScroll
                            val nearBottom = scroller?.let {
                                it.scrollY + it.height >=
                                    (it.getChildAt(0)?.height ?: 0) - ui.dp(this, 52)
                            } ?: true
                            consoleCachedTail = tail
                            val visible=filterConsoleLog(tail)
                            if (viewer.text.toString() != visible) viewer.text = visible
                            // Scroll position, not keyboard/text-selection focus.
                            if (nearBottom && scroller != null) {
                                scroller.post {
                                    if (consoleLogScroll === scroller && consoleOutput === viewer)
                                        scroller.scrollTo(0, ((scroller.getChildAt(0)?.height ?: 0) -
                                            scroller.height).coerceAtLeast(0))
                                }
                            }
                        } else {
                            // A tab re-rendered while the previous read was in flight.
                            requestConsoleLogRefresh()
                        }
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            consoleReadPending.set(false)
        }
    }

    private fun filterConsoleLog(raw: String): String {
        val query=consoleLogSearch.trim().lowercase(Locale.ROOT)
        val lines=raw.lineSequence().filter { line ->
            val lower=line.lowercase(Locale.ROOT)
            val typeMatch=when(consoleLogFilter) {
                "ERRORS" -> listOf("error","warn","exception","failed","fatal").any { it in lower }
                "PLAYERS" -> listOf("joined the game","left the game","logged in","lost connection","players online").any { it in lower }
                "CLEAN" -> !ConsoleCommandActivity.isHelpListing(line)
                else -> true
            }
            typeMatch && (query.isEmpty() || query in lower)
        }.toList().takeLast(110).joinToString("\n")
        return if(lines.isBlank()) "No recent log lines match the current filter." else lines
    }

    private fun showCrashReport() {
        val report = PocketCraftCrashReports.read(this)
        val panel = ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity, 16),ui.dp(this@MainActivity, 8),
                ui.dp(this@MainActivity, 16),ui.dp(this@MainActivity, 12))
        }
        panel.addView(ui.text(this,
            "Private report saved on this phone. Review before sharing: crash stack traces may include paths or other diagnostic details. Nothing is uploaded automatically.",
            12f, ui.MUTED))
        panel.addView(ui.space(this,10))
        val text = ui.text(this,report,11f,ui.WHITE).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            // Copy uses the explicit button; avoid focus-heavy text selection.
            setTextIsSelectable(false)
        }
        val scroll = ScrollView(this).apply {addView(text)}
        panel.addView(scroll, LinearLayout.LayoutParams(-1,ui.dp(this,300)))
        AlertDialog.Builder(this)
            .setTitle("PocketCraft crash diagnostics")
            .setView(panel)
            .setNegativeButton("CLOSE",null)
            .setNeutralButton("CLEAR REPORT") {_,_->
                PocketCraftCrashReports.clear(this)
                toast("Saved crash report deleted")
            }
            .setPositiveButton("COPY REPORT") {_,_->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("PocketCraft crash report",report))
                toast("Crash report copied. Review before sending.")
            }.show()
    }

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
        if (tab != 0) {
            heroState = null
            primaryServerAction = null
            readyText = null
            vitalsText = null
            crossplaySummaryHost = null
            connectionHost = null
            phoneHost = null
            onlineHost = null
        }
        if (tab != 3) {
            consoleOutput = null
            consoleLogScroll = null
            consoleCommandEntry = null
            consoleCommandFeedbackView = null
            consoleActivityView = null
            consoleAvailabilityView = null
            consoleSuggestionHost = null
        }
        if (renderedTab in 0..3 && !(renderedTab == 2 && resetWorldScroll)) {
            scrollOffsets[renderedTab] = currentScroller?.scrollY ?: scrollOffsets[renderedTab]
        }
        if (resetWorldScroll) scrollOffsets[2] = 0
        val screen = ui.column(this).apply { setBackgroundColor(ui.BG) }
        screen.addView(HomesteadComponents.masthead(this,
            if (tab == 2 && openedWorldFolder != null) "World folder"
            else HomesteadNavigation.label(tab)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        val scroller = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val body = ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,18),ui.dp(this@MainActivity,7),
                ui.dp(this@MainActivity,18),ui.dp(this@MainActivity,26))
        }
        when(tab) {
            0 -> dashboard(body)
            1 -> modsPage(body)
            2 -> worldsPage(body)
            else -> try {
                consolePage(body)
            } catch (e: Exception) {
                // A broken Console layout must not take down the whole server manager.
                PocketCraftCrashReports.record(this, "Console screen initialization", e)
                consoleOutput = null
                consoleLogScroll = null
                consoleCommandEntry = null
                consoleSuggestionHost = null
                body.removeAllViews()
                body.addView(h("Console error", "The console could not be displayed. Server hosting remains separate."))
                body.addView(ui.space(this, 12))
                body.addView(ui.action(this, "VIEW CRASH REPORT", true) { showCrashReport() })
                body.addView(ui.space(this, 10))
                body.addView(ui.action(this, "RETURN HOME") { tab = 0; render() })
            }
        }
        scroller.addView(body)
        screen.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))

        screen.addView(HomesteadComponents.bottomDock(this, tab) { selectedPage ->
            if (tab != selectedPage) {
                if (selectedPage != 2) openedWorldFolder = null
                tab = selectedPage
                render()
            }
        })
        ui.styleFormInputs(screen)
        setContentView(screen)
        if (tab == 3 && consoleLogExpanded) {
            // Only decode the full log when the user expands its tools.
            lastConsoleSize = -1L
            lastConsoleModified = -1L
            requestConsoleLogRefresh()
        }
        currentScroller = scroller
        renderedTab = tab
        val restoreY = if (tab == 3 && !consoleLogExpanded) 0 else scrollOffsets[tab]
        resetWorldScroll = false
        scroller.post { scroller.scrollTo(0,restoreY) }
    }
    private fun healthSnapshot(): HostingHealth.Snapshot = HostingHealth.Snapshot(
        fabricInstalled = serverJar.isFile,
        javaInstalled = RuntimeStore.isInstalled(this),
        eulaAccepted = ServerDownloads.eulaAccepted(this),
        geyserInstalled = modFiles().any { it.name.startsWith("Geyser",true) && it.name.endsWith(".jar") },
        floodgateInstalled = modFiles().any { it.name.startsWith("floodgate",true) && it.name.endsWith(".jar") },
        processRunning = ServerService.running,
        javaReady = ServerService.javaReady,
        bedrockListening = ServerService.bedrockReady,
        nethernetSignalingReady = ServerService.nethernetReady,
        playitActive = PlayitManager.isActive(),
        javaTunnelAddress = PlayitManager.javaAddress,
        bedrockTunnelAddress = PlayitManager.bedrockAddress,
        localIp = NetworkInfo.lanAddress(),
        lastError = ServerService.failure.takeIf { it.isNotBlank() },
        bedrockEvidence = ServerService.bedrockEvidence,
        bedrockJoinVerified = ServerService.bedrockJoinVerified,
        geyserWarnings = geyserNetworkSettings().warnings,
        compatibilityWarnings = ServerService.compatibilityWarnings
    )

    /** All diagnostics are local, with no remote probes or secret disclosure. */
    private fun showHealthCenter() {
        val state = healthSnapshot()
        val checks = HostingHealth.evaluate(state)
        val content = ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,18),ui.dp(this@MainActivity,6),
                ui.dp(this@MainActivity,18),ui.dp(this@MainActivity,22))
        }
        val core = 3 - HostingHealth.startBlockers(state)
        content.addView(ui.pill(this,"$core / 3 CORE PREREQUISITES",
            if(core==3)ui.GREEN else ui.CYAN))
        content.addView(ui.space(this,10))
        content.addView(ui.text(this,
            "An installed mod or assigned tunnel address isn't proof of a successful connection. This check uses only actual files and local server signals.",
            12f,ui.MUTED))
        content.addView(ui.space(this,14))
        checks.forEachIndexed { index, check ->
            val card = ui.card(this)
            val tint = when(check.level) {
                HostingHealth.Level.GOOD -> ui.GREEN
                HostingHealth.Level.ACTION -> ui.RED
                HostingHealth.Level.INFO -> ui.CYAN
            }
            val row = ui.row(this)
            row.addView(ui.text(this,
                when(check.level) {
                    HostingHealth.Level.GOOD -> "✓"
                    HostingHealth.Level.ACTION -> "!"
                    HostingHealth.Level.INFO -> "i"
                },16f,tint,true).apply {
                gravity=Gravity.CENTER
                background = ui.shape(this@MainActivity,ui.SURFACE,11,ui.BORDER)
            },LinearLayout.LayoutParams(ui.dp(this,35),ui.dp(this,35)))
            row.addView(ui.text(this,check.title,14f,ui.WHITE,true).apply {
                setPadding(ui.dp(this@MainActivity,12),0,0,0)
            },LinearLayout.LayoutParams(0,-2,1f))
            card.addView(row)
            card.addView(ui.space(this,9))
            card.addView(ui.text(this,check.explanation,12f,ui.MUTED))
            content.addView(card)
            if (index != checks.lastIndex) content.addView(ui.space(this,9))
        }
        lateinit var dialog: AlertDialog
        content.addView(ui.space(this,14))
        content.addView(ui.action(this,"OPEN CONSOLE") {
            dialog.dismiss()
            tab=3
            render()
        })
        val scroll = ScrollView(this).apply { addView(content) }
        dialog = AlertDialog.Builder(this)
            .setTitle("Server health")
            .setView(scroll)
            .setNegativeButton("CLOSE",null)
            .setNeutralButton("COPY SAFE REPORT") {_,_->
                // Intentionally omit agent secrets, console output, user paths,
                // public tunnel names, IP addresses and raw exception messages.
                val version = runCatching {
                    packageManager.getPackageInfo(packageName,0).versionName ?: "unknown"
                }.getOrDefault("unknown")
                val report = listOf(
                    "PocketCraft diagnostics • v$version",
                    "Android API: " + android.os.Build.VERSION.SDK_INT,
                    "Fabric installed: " + state.fabricInstalled,
                    "Java files installed: " + state.javaInstalled,
                    "EULA accepted: " + state.eulaAccepted,
                    "Minecraft process: " + state.processRunning,
                    "Java ready: " + state.javaReady,
                    "Geyser mod: " + state.geyserInstalled,
                    "Floodgate mod: " + state.floodgateInstalled,
                    "Bedrock bridge started: " + state.bedrockListening,
                    "Bedrock join verified: " + state.bedrockJoinVerified,
                    "Geyser config warnings: " + state.geyserWarnings.size,
                    "Android compatibility warnings: " + state.compatibilityWarnings.size,
                    "NetherNet signaling reported: " + state.nethernetSignalingReady,
                    "Playit running: " + state.playitActive,
                    "Java public address assigned: " + !state.javaTunnelAddress.isNullOrBlank(),
                    "Bedrock public address assigned: " + !state.bedrockTunnelAddress.isNullOrBlank()
                ).joinToString("\n")
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("PocketCraft safe report",report))
                toast("Private tokens omitted. Diagnostic summary copied.")
            }
            .setPositiveButton("SERVER FILES") {_,_->openServerFiles()}
            .create()
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels*.95f).toInt(),
            (resources.displayMetrics.heightPixels*.82f).toInt())
    }

    /**
     * Homestead 2 home is a launchpad, not a second copy of every settings page.
     * All actions keep their existing tested handlers and engine behavior.
     */
    private fun dashboard(body: LinearLayout) {
        val busy = ServerService.status in setOf("STARTING","STOPPING")
        val blockers = HostingHealth.startBlockers(healthSnapshot())
        body.addView(HomesteadComponents.pageIntro(this,
            "YOUR SERVER", "Home",
            "One place to start Minecraft and check how it is running."))
        body.addView(ui.space(this,17))

        val hero = ui.card(this)
        hero.addView(WorldArt(this).apply {
            contentDescription = "Pixel art of the Homestead woodland and cabin"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = ui.shape(this@MainActivity,ui.SUBTLE,10)
        })
        hero.addView(ui.space(this,14))
        val stateRow = ui.row(this)
        stateRow.addView(ui.pill(this,when {
            ServerService.status == "STARTING" -> "●  STARTING"
            ServerService.status == "STOPPING" -> "●  STOPPING"
            ServerService.javaReady -> "●  ONLINE"
            else -> "●  OFFLINE"
        },if(ServerService.javaReady)ui.GREEN else ui.MUTED).also { heroState = it })
        stateRow.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        stateRow.addView(ui.text(this,"FABRIC SERVER",10f,ui.CYAN,true))
        hero.addView(stateRow)
        hero.addView(ui.space(this,14))
        hero.addView(ui.text(this,WorldStore.active(this),22f,ui.WHITE,true))
        hero.addView(ui.space(this,6))
        hero.addView(ui.text(this,
            if(ServerService.running) "Minecraft is running on this phone"
            else "Your world is ready when you are",
            12f,ui.MUTED))
        hero.addView(ui.space(this,9))
        hero.addView(ui.text(this,"Checking server state…",11f,ui.CYAN)
            .also { readyText=it })
        hero.addView(ui.space(this,17))
        hero.addView(ui.action(this,when {
            ServerService.status == "STARTING" -> "STARTING MINECRAFT…"
            ServerService.status == "STOPPING" -> "STOPPING MINECRAFT…"
            ServerService.running -> "■  STOP SERVER"
            blockers > 0 -> "✦  FINISH SETUP"
            else -> "▶  START SERVER"
        },true,!busy) {
            when {
                ServerService.status in setOf("STARTING","STOPPING") -> toast("Minecraft is busy")
                ServerService.running -> confirmStopMinecraft()
                HostingHealth.startBlockers(healthSnapshot()) == 0 -> startMinecraft()
                !serverJar.isFile -> installFabricPrompt()
                !ServerDownloads.eulaAccepted(this) -> offerEula()
                !RuntimeStore.isInstalled(this) -> installJavaPrompt()
                else -> showHealthCenter()
            }
        }.also { primaryServerAction=it })
        body.addView(hero)
        body.addView(ui.space(this,17))

        body.addView(HomesteadComponents.sectionHeading(this,"Quick access",
            "Jump straight to the part you want to manage"))
        body.addView(ui.space(this,11))
        body.addView(HomesteadComponents.tileRow(this,
            HomesteadComponents.shortcut(this,"▧","Worlds",
                WorldStore.worlds(this).size.toString()+" saved") {
                openedWorldFolder=null
                tab=2
                render()
            },
            HomesteadComponents.shortcut(this,"⬡","Mods",
                modFiles().count { it.name.endsWith(".jar") }.toString()+" active") {
                tab=1
                render()
            }))
        body.addView(ui.space(this,10))
        body.addView(HomesteadComponents.tileRow(this,
            HomesteadComponents.shortcut(this,"♟","Players","Whitelist & access") {
                showPlayerManager()
            },
            HomesteadComponents.shortcut(this,"▤","Server files","Browse storage") {
                openServerFiles()
            }))
        body.addView(ui.space(this,17))

        val summary=FrameLayout(this)
        summary.addView(crossplaySummaryPanel())
        crossplaySummaryHost=summary
        lastCrossplaySummarySignature=""
        body.addView(summary)
        body.addView(ui.space(this,16))

        val vitals=ui.card(this)
        vitals.addView(HomesteadComponents.sectionHeading(this,"Phone & performance",
            "A lightweight status snapshot"))
        vitals.addView(ui.space(this,10))
        vitals.addView(ui.text(this,runCatching { ServerVitals.device(this) }
            .getOrDefault("Device metrics unavailable"),12f,ui.WHITE).also { vitalsText=it })
        vitals.addView(ui.space(this,8))
        vitals.addView(ui.text(this,"Observed players: "+PlayerActivity.count()+
            "  •  TPS and JVM heap need a server metrics mod",10f,ui.MUTED))
        body.addView(vitals)
        body.addView(ui.space(this,14))

        val prefs=getSharedPreferences("pocketcraft",0)
        var expanded=prefs.getBoolean("hostingExpanded",false)
        val detailGroup=ui.column(this).apply {
            visibility=if(expanded)View.VISIBLE else View.GONE
        }
        val toggle=ui.action(this,if(expanded)
            "−  HIDE HOSTING & TOOLS" else "＋  HOSTING & TOOLS") {}
        toggle.setOnClickListener {
            PocketSounds.play(this,PocketSounds.Event.CLICK)
            expanded=!expanded
            prefs.edit().putBoolean("hostingExpanded",expanded).apply()
            detailGroup.visibility=if(expanded)View.VISIBLE else View.GONE
            toggle.text=if(expanded)"−  HIDE HOSTING & TOOLS" else "＋  HOSTING & TOOLS"
        }
        body.addView(toggle)
        body.addView(ui.space(this,12))
        if(blockers>0) {
            detailGroup.addView(setupJourney())
            detailGroup.addView(ui.space(this,12))
        }
        val join=FrameLayout(this)
        join.addView(connectionJourneyPanel())
        connectionHost=join
        lastConnectionSignature=""
        detailGroup.addView(join)
        detailGroup.addView(ui.space(this,12))
        val local=FrameLayout(this)
        local.addView(phoneOnlyPanel())
        phoneHost=local
        lastPhoneSignature=""
        detailGroup.addView(local)
        detailGroup.addView(ui.space(this,12))
        val online=FrameLayout(this)
        online.addView(internetPanel())
        onlineHost=online
        lastOnlineSignature=""
        detailGroup.addView(online)
        detailGroup.addView(ui.space(this,12))
        detailGroup.addView(enginePanel())
        detailGroup.addView(ui.space(this,13))

        val toolbox=ui.card(this)
        toolbox.addView(HomesteadComponents.sectionHeading(this,"Tools & diagnostics",
            "Advanced controls live here, away from the Home shortcuts"))
        toolbox.addView(ui.space(this,12))
        val advancedActions=listOf(
            Triple("◎","First-time setup","Check the installation guide"),
            Triple("✦","Fabric installer","Install or repair Fabric"),
            Triple("☕","Java runtime","Manage the packaged Java engine"),
            Triple("↓","Import server","Restore a complete server"),
            Triple("✓","Health checks","Inspect setup and readiness"),
            Triple("!","Crash report","Read private crash diagnostics"),
            Triple("ⓘ","About","App details and credits")
        )
        advancedActions.forEachIndexed { i, item ->
            toolbox.addView(HomesteadComponents.menuRow(this,item.first,item.second,item.third) {
                when(i) {
                    0 -> showFirstRunGuide()
                    1 -> installFabricPrompt()
                    2 -> installJavaPrompt()
                    3 -> picker(1)
                    4 -> showHealthCenter()
                    5 -> showCrashReport()
                    else -> about()
                }
            })
            if(i!=advancedActions.lastIndex) toolbox.addView(ui.space(this,8))
        }
        detailGroup.addView(toolbox)
        body.addView(detailGroup)
    }
    /**
     * Simplified Anvil-inspired hosting workflow, using PocketCraft's real
     * readiness signals rather than promising one-tap internet access.
     */
    private fun connectionJourneyPanel(): LinearLayout {
        val card = ui.card(this)
        card.addView(ui.text(this, "PLAY TOGETHER", 11f, ui.CYAN, true))
        card.addView(ui.space(this, 8))
        card.addView(ui.text(this, "Get your friends connected", 18f, ui.WHITE, true))
        card.addView(ui.space(this, 8))
        card.addView(ui.text(this,
            "Choose Java or Bedrock, then LAN or internet. PocketCraft shows the next step and only shares an address when the required listener is detected.",
            12f, ui.MUTED))
        card.addView(ui.space(this, 11))
        val indicators = ui.row(this)
        indicators.addView(ui.pill(this,
            if (ServerService.javaReady) "✓ JAVA READY" else "○ JAVA WAITING",
            if (ServerService.javaReady) ui.GREEN else ui.MUTED))
        indicators.addView(ui.space(this, 7))
        indicators.addView(ui.pill(this,
            when {
                ServerService.bedrockJoinVerified -> "✓ BEDROCK JOIN VERIFIED"
                ServerService.bedrockReady -> "○ GEYSER STARTED"
                else -> "○ GEYSER WAITING"
            },
            if (ServerService.bedrockJoinVerified) ui.GREEN else ui.MUTED))
        card.addView(indicators)
        if (ServerService.nethernetNativeFailure) {
            card.addView(ui.space(this, 10))
            card.addView(ui.text(this,
                "Bedrock blocker detected: Android WebRTC native library failed to load. Open the guide for diagnostics.",
                12f, ui.RED))
        }
        card.addView(ui.space(this, 13))
        card.addView(ui.action(this, "PLAY WITH FRIENDS — GET ADDRESS", true) {
            showConnectionChooser()
        })
        card.addView(ui.space(this, 8))
        card.addView(ui.text(this,
            "Public addresses and listener messages are not proof that a friend can join. Always test from a second device.",
            11f, ui.MUTED))
        return card
    }

    private fun showConnectionChooser() {
        val targets = ConnectionJourney.Target.values()
        AlertDialog.Builder(this)
            .setTitle("Who is joining your world?")
            .setItems(targets.map { it.label }.toTypedArray()) { _, which ->
                showConnectionGuide(targets[which])
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showConnectionGuide(target: ConnectionJourney.Target) {
        val state = ConnectionJourney.State(
            javaReady = ServerService.javaReady,
            serverRunning = ServerService.running,
            geyserInstalled = modFiles().any {
                it.name.startsWith("Geyser", true) && it.name.endsWith(".jar")
            },
            bedrockListener = ServerService.bedrockReady,
            nethernetNativeFailure = ServerService.nethernetNativeFailure,
            nethernetSignalingReady = ServerService.nethernetReady,
            lanIp = NetworkInfo.lanAddress(),
            javaPort = serverProperty("server-port", "25565"),
            playitRunning = PlayitManager.isActive(),
            playitAccountConnected = PlayitManager.accountConnected,
            playitClaimAvailable = !PlayitManager.claimUrl.isNullOrBlank(),
            javaPublicAddress = PlayitManager.javaAddress,
            bedrockPublicAddress = PlayitManager.bedrockAddress,
            bedrockPort = geyserNetworkSettings().bedrockPort.toString(),
            bedrockTransport = geyserNetworkSettings().transport,
            signalingMode = geyserNetworkSettings().signalingMode
        )
        val result = ConnectionJourney.assess(target, state)
        val content = ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,20), ui.dp(this@MainActivity,12),
                ui.dp(this@MainActivity,20), ui.dp(this@MainActivity,14))
        }
        content.addView(ui.pill(this, target.label.uppercase(Locale.ROOT), ui.CYAN))
        content.addView(ui.space(this, 12))
        content.addView(ui.text(this, result.title, 19f, ui.WHITE, true))
        content.addView(ui.space(this, 9))
        content.addView(ui.text(this, result.detail, 13f, ui.MUTED))
        result.address?.let { address ->
            content.addView(ui.space(this, 14))
            val addressCard = ui.card(this)
            addressCard.addView(ui.text(this,
                if (target.online) "ASSIGNED • NOT VERIFIED" else "LOCAL • TEST JOIN",
                11f, ui.CYAN, true))
            addressCard.addView(ui.space(this, 5))
            addressCard.addView(ui.text(this, address, 17f, ui.WHITE, true))
            content.addView(addressCard)
        }
        AlertDialog.Builder(this)
            .setTitle("Connect friends")
            .setView(content)
            .setNegativeButton("CLOSE", null)
            .setNeutralButton("SERVER HEALTH") { _, _ -> showHealthCenter() }
            .setPositiveButton(result.actionLabel) { _, _ ->
                when (result.action) {
                    ConnectionJourney.Action.START_SERVER -> startMinecraft()
                    ConnectionJourney.Action.OPEN_CONSOLE -> { tab = 3; render() }
                    ConnectionJourney.Action.OPEN_MODS -> { tab = 1; render() }
                    ConnectionJourney.Action.OPEN_NETWORK_SETTINGS -> {
                        startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                    }
                    ConnectionJourney.Action.START_PLAYIT -> {
                        startForegroundService(Intent(this, ServerService::class.java)
                            .setAction(ServerService.ACTION_TUNNEL_START))
                        toast("Starting Playit; check the Internet Hosting panel for status.")
                    }
                    ConnectionJourney.Action.CONNECT_PLAYIT -> {
                        PlayitManager.claimUrl?.let { url ->
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        } ?: toast("Playit approval link is not ready. Check Internet Hosting.")
                    }
                    ConnectionJourney.Action.VIEW_PLAYIT -> {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://playit.gg/account/tunnels")))
                    }
                    ConnectionJourney.Action.COPY_ADDRESS -> {
                        result.address?.let { address ->
                            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("Minecraft server address", address))
                            toast("Address copied. Ask a friend to test joining.")
                        }
                    }
                }
            }
            .show()
    }

    /** Guided, reusable onboarding; displays automatically after clearing app data. */
    private fun showFirstRunGuide() {
        getSharedPreferences("pocketcraft",0).edit()
            .putBoolean("firstRunGuideSeenV0833",true).apply()
        val body = ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,18),ui.dp(this@MainActivity,12),
                ui.dp(this@MainActivity,18),ui.dp(this@MainActivity,18))
        }
        body.addView(ui.text(this,"Set up a server on your phone",19f,ui.WHITE,true))
        body.addView(ui.space(this,8))
        body.addView(ui.text(this,
            "Follow these steps in order. Java runs locally first; Geyser and Playit are optional. All installations require internet for downloads. Keep your phone charged and allow PocketCraft to run while hosting.",
            12f,ui.MUTED))
        body.addView(ui.space(this,12))
        body.addView(ui.text(this,
            "Clearing PocketCraft app data deletes private worlds, mods, server settings, downloaded runtimes and Playit agent credentials. Export backups from Worlds before clearing data.",
            12f,ui.RED))
        lateinit var dialog: AlertDialog
        fun step(title: String, detail: String, action: () -> Unit) {
            body.addView(ui.space(this,12))
            body.addView(ui.text(this,detail,12f,ui.MUTED))
            body.addView(ui.space(this,5))
            body.addView(ui.action(this,title,false) { dialog.dismiss(); action() })
        }
        step("1  INSTALL FABRIC SERVER",
            "Choose the Minecraft version you want to host. This installs the launcher, not a working server until Java is installed.") { installFabricPrompt() }
        step("2  ACCEPT MINECRAFT EULA",
            "Review the official agreement yourself before continuing.") { offerEula() }
        step("3  INSTALL ANDROID JAVA RUNTIME",
            "Download the compatible ARM64 Java runtime. The engine test is available in the setup tools.") { installJavaPrompt() }
        step("4  CHECK SERVER HEALTH",
            "Check prerequisites and start Minecraft. The Console shows actual startup errors.") { showHealthCenter() }
        step("5  ADD BEDROCK CROSSPLAY (OPTIONAL)",
            "Open Mods to install matching Fabric API, Geyser and Floodgate. First verify Java works over LAN.") {
            tab=1; render()
        }
        step("6  CONFIGURE PLAYIT (OPTIONAL)",
            "Create Java and Minecraft Bedrock (NetherNet) tunnels, copy Geyser's NXS settings from Playit's Configuration help, then test an external Bedrock join. For local NetherNet joining, review hybrid signaling.") {
            getSharedPreferences("pocketcraft",0).edit().putBoolean("hostingExpanded",true).apply()
            tab=0; render()
        }
        val scroll = ScrollView(this).apply { addView(body) }
        dialog = AlertDialog.Builder(this)
            .setTitle("Welcome to PocketCraft")
            .setView(scroll)
            .setNegativeButton("CLOSE",null)
            .create()
        dialog.show()
    }

    /** Setup checks separate core prerequisites from optional crossplay. */
    private fun setupJourney(): LinearLayout {
        val card = ui.card(this)
        val version = getSharedPreferences("pocketcraft",0).getString("gameVersion",null)
        val steps = listOf(
            Triple("01","Fabric server launcher",serverJar.isFile),
            Triple("02","Minecraft EULA accepted",ServerDownloads.eulaAccepted(this)),
            Triple("03","Android Java runtime files",RuntimeStore.isInstalled(this))
        )
        val completed = steps.count { it.third }
        val crossplay = listOf("fabric-api.jar","Geyser-Fabric.jar","floodgate-fabric.jar")
            .all { File(mods,it).isFile }
        val heading = ui.row(this)
        heading.addView(ui.column(this).apply {
            addView(ui.text(this@MainActivity,"GET READY TO PLAY",11f,ui.GREEN,true))
            addView(ui.space(this@MainActivity,5))
            addView(ui.text(this@MainActivity,"Server setup",19f,ui.WHITE,true))
        },LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(ui.pill(this,"$completed / 3 CORE",if(completed==3)ui.GREEN else ui.CYAN))
        card.addView(heading)
        card.addView(ui.space(this,8))
        card.addView(ui.text(this,
            if(completed==3)"Required files detected. Check Test Java Engine and the Console to verify your server runs."
            else "Complete these three steps before hosting your world.",
            12f,ui.MUTED))
        card.addView(ui.space(this,13))
        val track = FrameLayout(this).apply {background=ui.shape(this@MainActivity,ui.SURFACE,8)}
        val fill = View(this).apply {background=ui.gradient(this@MainActivity,ui.GREEN,ui.CYAN)}
        track.addView(fill,FrameLayout.LayoutParams(0,ui.dp(this,6)))
        track.post {
            fill.layoutParams=FrameLayout.LayoutParams(track.width*completed/3,ui.dp(this,6))
        }
        card.addView(track,LinearLayout.LayoutParams(-1,ui.dp(this,6)))
        card.addView(ui.space(this,17))
        steps.forEachIndexed { index, step ->
            val row=ui.row(this)
            row.addView(ui.text(this,if(step.third)"✓" else step.first,12f,
                if(step.third)ui.GREEN else ui.MUTED,true).apply {
                gravity=Gravity.CENTER
                background=ui.shape(this@MainActivity,ui.SURFACE,10)
                layoutParams=LinearLayout.LayoutParams(ui.dp(this@MainActivity,35),
                    ui.dp(this@MainActivity,35))
            })
            val details=ui.column(this).apply {setPadding(ui.dp(this@MainActivity,11),0,0,0)}
            details.addView(ui.text(this,step.second,13f,ui.WHITE,true))
            val subtitle=when(index) {
                0 -> if(step.third)"Installed • ${version ?: "version not recorded"}"
                    else "Choose a Minecraft version"
                1 -> if(step.third)"Agreement recorded"
                    else "Review and accept the EULA"
                else -> if(step.third)"Files detected • test before launch"
                    else "Install Android Java 25"
            }
            details.addView(ui.space(this,4))
            details.addView(ui.text(this,subtitle,11f,ui.MUTED))
            row.addView(details,LinearLayout.LayoutParams(0,-2,1f))
            card.addView(row)
            if(index<steps.lastIndex)card.addView(ui.space(this,13))
        }
        card.addView(ui.space(this,16))
        val optional=ui.row(this)
        optional.addView(ui.pill(this,"OPTIONAL",ui.PURPLE))
        optional.addView(ui.text(this,
            if(crossplay)"  Bedrock bridge files present" else "  Bedrock crossplay not installed",
            12f,if(crossplay)ui.GREEN else ui.MUTED))
        card.addView(optional)
        card.addView(ui.space(this,15))
        val label = when {
            !serverJar.isFile -> "INSTALL FABRIC SERVER"
            !ServerDownloads.eulaAccepted(this) -> "REVIEW MINECRAFT EULA"
            !RuntimeStore.isInstalled(this) -> "INSTALL JAVA ENGINE"
            !crossplay -> "ADD BEDROCK CROSSPLAY (OPTIONAL)"
            else -> "OPEN SERVER CONSOLE"
        }
        card.addView(ui.action(this,label,true) {
            when {
                !serverJar.isFile -> installFabricPrompt()
                !ServerDownloads.eulaAccepted(this) -> offerEula()
                !RuntimeStore.isInstalled(this) -> installJavaPrompt()
                !crossplay -> {tab=1;render()}
                else -> {tab=3;render()}
            }
        })
        return card
    }


    /** Live local process controls. A JVM process is not proof that the game server is ready. */
    private fun geyserNetworkSettings(): GeyserNetworkSettings.Settings =
        GeyserNetworkSettings.read(File(base,"config/Geyser-Fabric/config.yml"))

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
        box.addView(ui.text(this,"Java port: $port   •   Bedrock port: " +
            geyserNetworkSettings().bedrockPort + " (from Geyser config; default 19132)",11f,ui.MUTED))
        return box
    }

    /** Home shows observed Bedrock evidence and lets the owner confirm a real join. */
    private fun crossplaySummaryPanel(): LinearLayout {
        val card = ui.card(this)
        val geyserInstalled = modFiles().any { it.name.startsWith("Geyser", true) &&
            it.name.endsWith(".jar", true) }
        card.addView(ui.text(this, "BEDROCK / GEYSER", 11f, ui.CYAN, true))
        card.addView(ui.space(this, 7))
        val bridgeStarted = ServerService.running && ServerService.bedrockReady
        val connected = ServerService.running && ServerService.bedrockJoinVerified
        card.addView(ui.pill(this, when {
            connected -> "✓  BEDROCK JOIN VERIFIED"
            bridgeStarted -> "○  GEYSER STARTED • JOIN NOT VERIFIED"
            !geyserInstalled -> "○  GEYSER NOT INSTALLED"
            !ServerService.running -> "○  SERVER OFFLINE"
            !ServerService.javaReady -> "○  JAVA SERVER STARTING"
            else -> "○  BEDROCK JOIN NOT YET OBSERVED"
        }, if (connected) ui.GREEN else ui.MUTED))
        card.addView(ui.space(this, 7))
        card.addView(ui.text(this, when {
            connected -> "Evidence: " + ServerService.bedrockEvidence
            bridgeStarted -> "Geyser reported startup/signaling: " + ServerService.bedrockEvidence +
                ". A successful Bedrock player login has not been observed. Test with a second device."
            geyserInstalled && ServerService.javaReady ->
                "Try joining from Bedrock. If you are already playing, confirm the connection below; a missing Geyser startup line does not mean the bridge is offline."
            geyserInstalled -> "Start the Java server, then test connecting from Bedrock."
            else -> "Install Geyser-Fabric to enable Bedrock players."
        }, 11f, ui.MUTED))
        if (geyserInstalled && ServerService.javaReady && !connected) {
            card.addView(ui.space(this, 9))
            card.addView(ui.action(this, "✓  I JOINED FROM BEDROCK", false) {
                AlertDialog.Builder(this)
                    .setTitle("Confirm a real Bedrock join?")
                    .setMessage("Only confirm after successfully joining this running Minecraft server from Bedrock Edition. This confirmation lasts for the current server session.")
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("CONFIRM") { _, _ ->
                        startService(Intent(this, ServerService::class.java)
                            .setAction(ServerService.ACTION_CONFIRM_BEDROCK))
                        toast("Bedrock join confirmed for this session")
                    }.show()
            })
        }
        return card
    }

    /**
     * A real local-first path: Android itself hosts both Minecraft listeners.
     * No Playit, cloud account, reverse proxy or external registration needed.
     */
    private fun phoneOnlyPanel(): LinearLayout {
        val card = ui.card(this)
        val local = NetworkInfo.lanAddress()
        val port = serverProperty("server-port","25565")
        val geyserSettings = geyserNetworkSettings()
        val bedrockPort = geyserSettings.bedrockPort
        val geyserInstalled = modFiles().any { it.name.startsWith("Geyser",true) && it.name.endsWith(".jar") }
        card.addView(ui.text(this,"PHONE-ONLY HOSTING",11f,ui.GREEN,true))
        card.addView(ui.space(this,8))
        card.addView(ui.text(this,"Host straight from this phone",18f,ui.WHITE,true))
        card.addView(ui.space(this,8))
        val authenticated = serverProperty("online-mode","true").equals("true",true)
        card.addView(ui.pill(this,
            if(authenticated)"✓  JAVA AUTHENTICATION ON" else "⚠  JAVA AUTHENTICATION OFF",
            if(authenticated)ui.GREEN else ui.RED))
        if(!authenticated) {
            card.addView(ui.space(this,8))
            card.addView(ui.text(this,
                "Java account verification is disabled; player names can be impersonated. Use trusted players and avoid handing out operator access.",
                11f,ui.RED))
        }
        card.addView(ui.space(this,8))
        card.addView(ui.text(this,"No Playit account or tunnel. Start your Fabric server and share these local addresses with players on the same Wi-Fi or phone hotspot.",12f,ui.MUTED))
        card.addView(ui.space(this,11))
        val ready = ServerService.javaReady
        val geyser = ServerService.bedrockReady
        val localBedrockSupported = geyserSettings.localJoinSupported()
        card.addView(ui.pill(this,
            when {
                geyser && localBedrockSupported -> "●  JAVA + GEYSER STARTED"
                geyser -> "○  EXTERNAL SIGNALING ONLY"
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
        val bedrockLanText = if (!localBedrockSupported)
            "Local-IP joins unavailable with external-only NXS signaling"
        else (local?.let { "$it:$bedrockPort" } ?: "Local network required") +
            if (geyser) " • Geyser started, join unverified" else " • Startup not captured"
        card.addView(ui.text(this,"BEDROCK: $bedrockLanText",12f,
            if(geyser && localBedrockSupported)ui.GREEN else ui.MUTED))
        if (!localBedrockSupported) {
            card.addView(ui.space(this,7))
            card.addView(ui.text(this,
                "For local NetherNet joins, enable built-in signaling alongside Playit's NXS (hybrid mode), then restart. Do not remove your private NXS token.",
                11f,ui.RED))
        }
        geyserSettings.warnings.take(3).forEach { warning ->
            card.addView(ui.space(this,5))
            card.addView(ui.text(this,"Geyser: " + warning,11f,ui.RED))
        }
        if (geyser) {
            card.addView(ui.space(this,5))
            card.addView(ui.text(this,"Startup evidence: " + ServerService.bedrockEvidence,11f,ui.MUTED))
        } else if (geyserInstalled && ServerService.javaReady) {
            card.addView(ui.space(this,7))
            card.addView(ui.text(this,
                "No connection log captured yet. If you already joined from Bedrock, confirm it below or check Home for the connection status.",
                11f,ui.MUTED))
            card.addView(ui.space(this,8))
            card.addView(ui.action(this,"✓  I JOINED SUCCESSFULLY FROM BEDROCK",false) {
                AlertDialog.Builder(this).setTitle("Confirm Bedrock connection?")
                    .setMessage("Only confirm after you have joined this running server from Minecraft Bedrock. The status applies only to this server session, and is not proof of external tunnel reachability.")
                    .setNegativeButton("CANCEL",null)
                    .setPositiveButton("YES, I JOINED") {_,_->
                        startService(Intent(this, ServerService::class.java)
                            .setAction(ServerService.ACTION_CONFIRM_BEDROCK))
                        toast("Your Bedrock join confirmation was sent to the server")
                    }.show()
            })
        }
        if (ServerService.nethernetNativeFailure) {
            card.addView(ui.space(this,8))
            card.addView(ui.text(this,
                "NetherNet WebRTC reported an error. RakNet may still work; check which Bedrock transport is configured.",
                12f,ui.RED))
        }
        if (ServerService.nethernetReady) {
            card.addView(ui.space(this,5))
            card.addView(ui.text(this,"✓ " + ServerService.nethernetMode,12f,ui.GREEN))
        }
        card.addView(ui.space(this,14))
        card.addView(ui.action(this,
            if(ServerService.running) "✓  SERVER PROCESS ACTIVE" else "▶  HOST ON THIS PHONE — NO PLAYIT",
            true,!ServerService.running) {
            startMinecraft()
        })
        if(local != null) {
            card.addView(ui.space(this,9))
            card.addView(ui.action(this,"COPY JAVA LAN ADDRESS") {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Java LAN","$local:$port"))
                toast("Copied local Java address")
            })
            if (geyserInstalled && ServerService.javaReady) {
                card.addView(ui.space(this,9))
                card.addView(ui.action(this,
                    if(geyser)"COPY BEDROCK LAN ADDRESS" else "COPY BEDROCK TEST ADDRESS") {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Bedrock LAN","$local:$bedrockPort"))
                    toast(if(geyser)"Copied local Bedrock address"
                        else "Copied test address. Try joining from Minecraft Bedrock.")
                })
            }
        }
        val customAddress = getSharedPreferences("pocketcraft",0)
            .getString("shareAddress","").orEmpty().trim()
        if(customAddress.isNotBlank()) {
            card.addView(ui.space(this,11))
            card.addView(ui.text(this,"CUSTOM SHARE ADDRESS  •  NOT VERIFIED",11f,ui.MUTED,true))
            card.addView(ui.space(this,6))
            card.addView(ui.text(this,customAddress,13f,ui.WHITE,true))
            card.addView(ui.space(this,8))
            card.addView(ui.action(this,"COPY CUSTOM ADDRESS") {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Custom server address",customAddress))
                toast("Custom address copied; verify its DNS/tunnel before sharing.")
            })
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
                    "2. Forward TCP $port (Java) and " + geyserSettings.directRouterAdvice() +
                     " (Bedrock/Geyser) on your router to $localIp.\n" +
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

    /**
     * Playit agents can be reused through their private secret.
     * Never display, log, export or send that secret outside app storage.
     */
    private fun existingPlayitAgentPrompt() {
        if (PlayitManager.isActive()) {
            AlertDialog.Builder(this)
                .setTitle("Go Offline before switching agents")
                .setMessage("Stop Playit in PocketCraft first, and stop the same agent on its old device. Two copies running with the same identity may conflict.")
                .setPositiveButton("OK",null).show()
            return
        }
        val shell = ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,20),ui.dp(this@MainActivity,12),
                ui.dp(this@MainActivity,20),ui.dp(this@MainActivity,12))
        }
        shell.addView(ui.text(this,
            "Have an existing Playit agent? Import the secret from its local playit.toml file. This reuses the agent's identity and can preserve its assigned tunnels. Stop the old agent first. If you don't have its secret, you can instead transfer existing tunnels to the PocketCraft agent in Playit's dashboard.",
            12f,ui.MUTED))
        shell.addView(ui.space(this,13))
        val field = android.widget.EditText(this).apply {
            hint = "Paste existing agent secret (kept private)"
            setSingleLine(true)
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background = ui.shape(this@MainActivity,ui.SURFACE,12,ui.BORDER)
            setPadding(ui.dp(this@MainActivity,14),ui.dp(this@MainActivity,14),
                ui.dp(this@MainActivity,14),ui.dp(this@MainActivity,14))
        }
        shell.addView(field)
        shell.addView(ui.space(this,12))
        shell.addView(ui.text(this,
            "Security: never send the agent secret in chat, screenshots or GitHub issues. PocketCraft saves it only to its private files.",
            11f,ui.MUTED))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Use an existing Playit agent")
            .setView(shell)
            .setNeutralButton("TRANSFER TUNNELS IN PLAYIT") {_,_->
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://playit.gg/account/tunnels")))
            }
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("USE THIS AGENT",null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    PlayitManager.importExistingAgent(this,field.text.toString())
                    field.text.clear()
                    dialog.dismiss()
                    toast("Agent imported locally. Tap Use Playit to connect.")
                    render()
                } catch (e:Exception) {
                    field.error = e.message ?: "Invalid agent secret"
                }
            }
        }
        dialog.show()
    }

    /** One-tap Playit setup: first use requires a one-time external account approval. */
    private fun internetPanel(): LinearLayout {
        val card = ui.card(this)
        val active = PlayitManager.isActive()
        val javaAddress = PlayitManager.javaAddress
        val bedrockAddress = PlayitManager.bedrockAddress
        val bedrockPort = geyserNetworkSettings().bedrockPort
        val javaPort = serverProperty("server-port","25565")
        val approval = PlayitManager.claimUrl
        card.addView(ui.text(this,"OPTIONAL INTERNET TUNNEL",11f,ui.CYAN,true))
        card.addView(ui.space(this,8))
        card.addView(ui.text(this,"Playit for remote connections",18f,ui.WHITE,true))
        card.addView(ui.space(this,8))
        card.addView(ui.text(this,
            if(ServerService.nethernetReady) "✓ " + ServerService.nethernetMode
            else "NetherNet startup message not captured. RakNet may still be working.",
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
                        "Use the Playit dashboard to assign tunnels to THIS phone's running agent. Java: 127.0.0.1:$javaPort. Bedrock: 127.0.0.1:$bedrockPort where supported.",
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
        card.addView(ui.action(this,"USE AN EXISTING PLAYIT AGENT") {
            existingPlayitAgentPrompt()
        })
        card.addView(ui.space(this,9))
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

    /** Centralized guarded start for the hero, local-host and control actions. */
    private fun startMinecraft() {
        if (ServerService.running || ServerService.status == "STARTING") {
            toast("Minecraft is already starting or running")
            return
        }
        when {
            !serverJar.isFile -> {
                AlertDialog.Builder(this).setTitle("Fabric server needed")
                    .setMessage("Install a Fabric server before starting your world.")
                    .setNegativeButton("NOT NOW",null)
                    .setPositiveButton("CHOOSE VERSION") {_,_->installFabricPrompt()}
                    .show()
                return
            }
            !RuntimeStore.isInstalled(this) -> {
                installJavaPrompt()
                return
            }
            !ServerDownloads.eulaAccepted(this) -> {
                offerEula()
                return
            }
        }
        try {
            startForegroundService(Intent(this,ServerService::class.java)
                .setAction(ServerService.ACTION_START))
            toast("Starting Minecraft. Server status is on Home; Console can be opened separately.")
            // Do not automatically open Console: it must not gate server startup.
            render()
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Could not start Minecraft")
                .setMessage(e.message ?: "Android prevented the server service from starting.")
                .setPositiveButton("OK",null).show()
        }
    }

    private fun confirmStopMinecraft() {
        if (!ServerService.running) return
        AlertDialog.Builder(this).setTitle("Stop your Minecraft server?")
            .setMessage("Connected players will be disconnected. PocketCraft will request a graceful world save before stopping.")
            .setNegativeButton("KEEP RUNNING",null)
            .setPositiveButton("STOP SERVER") {_,_->
                startService(Intent(this,ServerService::class.java).setAction(ServerService.ACTION_STOP))
                toast("Graceful server shutdown requested")
                render()
            }.show()
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
        buttons.addView(ui.action(this,"▶  START SERVER",true,
            !ServerService.running && ServerService.status != "STARTING") {
            startMinecraft()
        },ui.weight(this))
        buttons.addView(ui.action(this,"■  STOP",false,ServerService.running) {
            confirmStopMinecraft()
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
        box.addView(ui.space(this,12))
        box.addView(ui.text(this,
            "Manage local and remote addresses in the hosting panels below. Stop the server before changing mods, worlds, or JVM settings.",
            11f,ui.MUTED))
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
        setBackgroundColor(ui.BORDER)
        layoutParams=LinearLayout.LayoutParams(-1,ui.dp(this@MainActivity,1)).apply {
            topMargin=ui.dp(this@MainActivity,5)
            bottomMargin=ui.dp(this@MainActivity,5)
        }
    }
    private fun actionRow(icon:String,title:String,click:()->Unit):LinearLayout {
        val row=ui.row(this)
        row.addView(ui.text(this,icon,19f,ui.CYAN,true).apply {
            gravity=Gravity.CENTER
        },LinearLayout.LayoutParams(ui.dp(this,34),ui.dp(this,44)))
        row.addView(ui.text(this,title,15f,ui.WHITE,true).apply {
            setPadding(ui.dp(this@MainActivity,12),0,0,0)
        },LinearLayout.LayoutParams(0,-2,1f))
        row.addView(ui.text(this,"›",22f,ui.MUTED))
        row.background=ui.pressable(this,ui.shape(this,ui.PANEL,10))
        row.isClickable=true
        row.isFocusable=true
        row.minimumHeight=ui.dp(this,54)
        row.contentDescription=title
        row.setOnClickListener {
            PocketSounds.play(this,PocketSounds.Event.SELECT)
            click()
        }
        return row
    }
    private fun modFiles():List<File> =
        mods.listFiles()?.filter {it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".jar.disabled"))}
            ?.sortedBy { it.name.lowercase(Locale.ROOT) } ?: emptyList()

    /**
     * Geyser runs inside app-private Android storage. Let users apply NetherNet
     * settings without a file manager, root, or sharing private tunnel tokens.
     */

    /** Paste Playit's Geyser snippet while keeping unrelated settings. */
    private fun pastePlayitGeyserSettings() {
        if (ServerService.running) {
            toast("Stop Minecraft before editing Geyser. Restart afterward.")
            return
        }
        val config = File(base, "config/Geyser-Fabric/config.yml")
        if (!config.isFile || config.length() > 256000L) {
            AlertDialog.Builder(this).setTitle("Geyser config unavailable")
                .setMessage("Install Geyser, run the server once to generate config.yml, then stop it.")
                .setPositiveButton("OK",null).show()
            return
        }
        val entry = android.widget.EditText(this).apply {
            hint = "Paste Playit Configuration help → Geyser → Copy"
            setSingleLine(false)
            minLines = 6
            maxLines = 12
            textSize = 13f
        }
        val dialog = AlertDialog.Builder(this).setTitle("Connect Playit to Geyser")
            .setMessage("Copy the FULL Geyser settings from your Minecraft Bedrock (NetherNet) tunnel in Playit. Paste here. A Playit agent secret alone is not a Geyser config.")
            .setView(entry).setNegativeButton("CANCEL",null)
            .setPositiveButton("APPLY",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val updated = mergePlayitGeyserSettings(config.readText(Charsets.UTF_8), entry.text.toString())
                    val backup = File(config.parentFile,"config.yml.pocketcraft.bak")
                    if (!backup.exists()) config.copyTo(backup)
                    val tmp = File(config.parentFile,"config.yml.pocketcraft.part")
                    tmp.writeText(updated,Charsets.UTF_8)
                    if (!tmp.renameTo(config)) {
                        tmp.delete()
                        error("Unable to activate updated config")
                    }
                    dialog.dismiss()
                    toast("Playit settings saved! Restart Minecraft.")
                } catch (e: Exception) {
                    AlertDialog.Builder(this).setTitle("Not applied")
                        .setMessage(e.message ?: "Invalid settings")
                        .setPositiveButton("OK",null).show()
                }
            }
        }
        dialog.show()
    }

    private fun mergePlayitGeyserSettings(original: String, paste: String): String {
        require(paste.length in 20..32768) { "Paste the full Geyser settings snippet, not only a token." }
        val topLevel = Regex("(?m)^([a-zA-Z][a-zA-Z0-9_-]*):\\s*(?:#.*)?$")
        fun collect(text: String): Map<String,String> {
            val matches = topLevel.findAll(text).toList()
            return matches.mapIndexed { index, match ->
                val end = matches.getOrNull(index+1)?.range?.first ?: text.length
                match.groupValues[1] to text.substring(match.range.last+1,end)
            }.toMap()
        }
        val snippets = collect(paste.replace("\r\n","\n"))
        require("bedrock" in snippets) { "Expected bedrock: settings from Playit's Geyser tab." }
        require(snippets.keys.all { it == "bedrock" || it == "java" }) {
            "Only the Geyser bedrock: and java: sections can be imported."
        }
        var result = original.replace("\r\n","\n")
        for (section in listOf("bedrock","java")) {
            val source = snippets[section] ?: continue
            val fields = Regex("(?m)^  ([a-zA-Z][a-zA-Z0-9_-]*):[^\\n]*(?:\\n(?: {4,}[^\\n]*|\\s*)\\n?)*")
                .findAll(source).toList()
            require(fields.isNotEmpty()) { "No settings found in " + section + ":. Check YAML indentation." }
            for (field in fields) {
                val key = field.groupValues[1]
                val sectionMatch = Regex("(?m)^" + section + ":\\s*(?:#.*)?$")
                    .find(result) ?: error("Existing Geyser config lacks " + section + ":")
                val nextSection = topLevel.find(result,sectionMatch.range.last+1)
                val end = nextSection?.range?.first ?: result.length
                val start = sectionMatch.range.last+1
                val body = result.substring(start,end)
                val matching = Regex("(?m)^  " + Regex.escape(key) + ":[^\\n]*(?:\\n(?: {4,}[^\\n]*|\\s*)\\n?)*")
                    .find(body)
                val replacement = "\n" + field.value.trimEnd('\n') + "\n"
                val newBody = if (matching == null)
                    body.trimEnd('\n') + replacement
                else body.replaceRange(matching.range, replacement)
                result = result.replaceRange(start,end,newBody)
            }
        }
        require(result.length <= 256000) { "Updated config too large" }
        return result
    }

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
        val version=getSharedPreferences("pocketcraft",0).getString("gameVersion",null)
        val files=modFiles()
        val installed=files.count { it.name.endsWith(".jar") }
        val disabled=files.count { it.name.endsWith(".disabled") }

        body.addView(HomesteadComponents.pageIntro(this,
            "THE WORKSHOP","Mods & modpacks",
            "Discover, install and manage your Fabric additions."))
        body.addView(ui.space(this,16))
        val discovery=ui.card(this)
        discovery.addView(ui.pill(this,
            "FABRIC  •  "+(version ?: "VERSION NOT SELECTED"),ui.CYAN))
        discovery.addView(ui.space(this,13))
        discovery.addView(ui.text(this,"Find something new",18f,ui.WHITE,true))
        discovery.addView(ui.space(this,6))
        discovery.addView(ui.text(this,
            "Browse Modrinth without needing to manage folders manually.",12f,ui.MUTED))
        discovery.addView(ui.space(this,13))
        discovery.addView(ui.action(this,"⌕  BROWSE MODS",true) { browseMods() })
        discovery.addView(ui.space(this,8))
        discovery.addView(ui.action(this,"▦  BROWSE MODPACKS") { browseModrinth() })
        body.addView(discovery)
        body.addView(ui.space(this,16))

        body.addView(HomesteadComponents.sectionHeading(this,"My library",
            "$installed enabled  •  $disabled disabled"))
        body.addView(ui.space(this,11))
        body.addView(HomesteadComponents.tileRow(this,
            HomesteadComponents.shortcut(this,"＋","Import JAR","From your phone") { picker(2) },
            HomesteadComponents.shortcut(this,"▤","Mods folder","Browse all files") {
                openServerFiles("mods")
            }))
        body.addView(ui.space(this,9))
        body.addView(HomesteadComponents.menuRow(this,"▦","Import modpack",
            "Load an existing .mrpack archive",enabled=!ServerService.running) { picker(5) })
        body.addView(ui.space(this,16))

        val crossplay=ui.card(this)
        crossplay.addView(HomesteadComponents.sectionHeading(this,"Bedrock compatibility",
            "Manage Geyser and Floodgate separately from your mod library"))
        crossplay.addView(ui.space(this,11))
        crossplay.addView(HomesteadComponents.menuRow(this,"⚙","Geyser & network settings",
            "Ports, signaling, NXS and safe config.yml replacement") {
            startActivity(Intent(this,GeyserSettingsActivity::class.java))
        })
        crossplay.addView(ui.space(this,8))
        crossplay.addView(HomesteadComponents.menuRow(this,"↔","Install crossplay files",
            "Check Geyser, Floodgate and Fabric API dependencies") {
            AlertDialog.Builder(this).setTitle("Install crossplay?")
                .setMessage("Install matching Geyser, Floodgate and Fabric API files where supported.")
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("INSTALL") { _,_ ->
                    downloadAsync("Crossplay files") { ServerDownloads.installCrossplay(this) }
                }.show()
        })
        body.addView(crossplay)
        body.addView(ui.space(this,17))

        body.addView(HomesteadComponents.sectionHeading(this,"Installed mods",
            "Disable or delete mods when the server is stopped"))
        body.addView(ui.space(this,10))
        if(files.isEmpty()) {
            body.addView(empty("⬡","No mods installed",
                "Browse Modrinth or import a Fabric JAR to get started."))
        }
        files.forEach { file ->
            val enabled=file.name.endsWith(".jar")
            val meta=ModInspector.inspect(file)
            val card=ui.card(this)
            val heading=ui.row(this)
            val titles=ui.column(this)
            titles.addView(ui.text(this,meta.title,14f,ui.WHITE,true))
            titles.addView(ui.space(this,5))
            titles.addView(ui.text(this,
                meta.version+"  •  "+(if(meta.fabric)"Fabric" else "Check compatibility"),
                11f,ui.MUTED))
            heading.addView(titles,LinearLayout.LayoutParams(0,-2,1f))
            heading.addView(ui.pill(this,if(enabled)"ON" else "OFF",
                if(enabled)ui.GREEN else ui.MUTED))
            card.addView(heading)
            card.addView(ui.space(this,12))
            val actions=ui.row(this)
            val change=ui.action(this,if(enabled)"DISABLE" else "ENABLE",
                false,!ServerService.running) {
                val target=File(mods,if(enabled)file.name+".disabled"
                    else file.name.removeSuffix(".disabled"))
                if(target.exists() || !file.renameTo(target))toast("Could not change mod")
                else render()
            }
            actions.addView(change,ui.weight(this))
            actions.addView(ui.action(this,"REMOVE",false,!ServerService.running) {
                AlertDialog.Builder(this).setTitle("Remove mod?")
                    .setMessage(meta.title)
                    .setNegativeButton("CANCEL",null)
                    .setPositiveButton("REMOVE"){_,_->
                        if(file.delete())render() else toast("Delete failed")
                    }.show()
            },LinearLayout.LayoutParams(0,-2,1f))
            card.addView(actions)
            body.addView(card)
            body.addView(ui.space(this,10))
        }
        body.addView(HomesteadComponents.menuRow(this,"⋯","Install from URL",
            "Advanced download and import options") { installFromWebsite() })
    }

    private fun browseMods(category:String = "all") {
        val version=getSharedPreferences("pocketcraft",0).getString("gameVersion",null)
        if (version.isNullOrBlank()) {
            AlertDialog.Builder(this).setTitle("Choose Minecraft version")
                .setMessage("Install your Fabric server first. The mod browser will show matching releases.")
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("SELECT VERSION"){_,_->installFabricPrompt()}
                .show()
            return
        }
        val query=android.widget.EditText(this).apply {
            hint="Search Fabric mods"
            setSingleLine(true)
            textSize=16f
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background=ui.shape(this@MainActivity,ui.SURFACE,12)
            setPadding(ui.dp(this@MainActivity,12),ui.dp(this@MainActivity,12),
                ui.dp(this@MainActivity,12),ui.dp(this@MainActivity,12))
        }
        val content=ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,16),ui.dp(this@MainActivity,10),
                ui.dp(this@MainActivity,16),ui.dp(this@MainActivity,12))
            addView(ui.pill(this@MainActivity,"FABRIC  •  "+version,ui.CYAN))
            addView(ui.space(this@MainActivity,12))
            addView(query)
            addView(ui.space(this@MainActivity,12))
        }
        val groups=listOf("all" to "All","performance" to "Performance",
            "worldgen" to "Worldgen","utility" to "Utility","adventure" to "Adventure")
        groups.chunked(2).forEach { pair ->
            val row=ui.row(this)
            pair.forEach { (key,title) ->
                row.addView(ui.action(this,if(category==key)"✓ "+title else title) {
                    browseMods(key)
                },ui.weight(this))
            }
            content.addView(row)
            content.addView(ui.space(this,7))
        }
        AlertDialog.Builder(this).setTitle("Find mods")
            .setView(content)
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("SEARCH"){_,_->
                val search=query.text.toString().trim()
                val waiting=AlertDialog.Builder(this).setTitle("Finding mods")
                    .setMessage("Checking Fabric "+version+"…").setCancelable(false).create()
                waiting.show()
                Thread {
                    val results=runCatching {ModrinthDiscovery.findMods(search,version,category)}
                    runOnUiThread {
                        waiting.dismiss()
                        results.onSuccess {showModResults(it,version)}
                            .onFailure {toast("Search failed: "+it.message)}
                    }
                }.start()
            }.show()
    }

    private fun showModResults(list:List<ModrinthDiscovery.Mod>,version:String) {
        if(list.isEmpty()) {toast("No matching Fabric mods for "+version);return}
        val column=ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,13),ui.dp(this@MainActivity,9),
                ui.dp(this@MainActivity,13),ui.dp(this@MainActivity,13))
        }
        list.forEach { item ->
            val card=ui.card(this)
            card.addView(ui.text(this,item.title,15f,ui.WHITE,true))
            if(item.description.isNotBlank()){
                card.addView(ui.space(this,5))
                card.addView(ui.text(this,item.description,11f,ui.MUTED))
            }
            card.addView(ui.space(this,8))
            val hintColor = if (item.serverHint == "Client only") ui.RED else ui.CYAN
            card.addView(ui.pill(this,"FABRIC " + version + "  •  " +
                item.serverHint, hintColor))
            card.addView(ui.space(this,10))
            card.addView(ui.action(this,"CHECK COMPATIBILITY",true) {
                val wait = AlertDialog.Builder(this)
                    .setTitle("Checking "+item.title)
                    .setMessage("Verifying its exact Fabric release and server support…")
                    .setCancelable(false).create()
                wait.show()
                Thread {
                    val result = runCatching {
                        ModrinthDiscovery.checkCompatibility(item.id,version)
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        wait.dismiss()
                        result.onSuccess { compatibility ->
                            val supported = compatibility.verdict !=
                                ModrinthDiscovery.Verdict.UNSUPPORTED
                            val name = when(compatibility.verdict) {
                                ModrinthDiscovery.Verdict.COMPATIBLE -> "✓ Compatible"
                                ModrinthDiscovery.Verdict.UNSUPPORTED -> "✕ Not compatible"
                                ModrinthDiscovery.Verdict.UNCERTAIN -> "? Needs verification"
                            }
                            val confirmation = AlertDialog.Builder(this)
                                .setTitle(name + " • " + compatibility.title)
                                .setMessage(compatibility.details)
                                .setNegativeButton("CLOSE",null)
                            if (supported && !ServerService.running) {
                                confirmation.setPositiveButton(
                                    if(compatibility.verdict ==
                                        ModrinthDiscovery.Verdict.UNCERTAIN)
                                        "INSTALL ANYWAY" else "INSTALL"
                                ) { _, _ ->
                                    chooseModDependencies(item.title, item.slug)
                                }
                            }
                            confirmation.show()
                        }.onFailure { toast("Compatibility check failed: " +
                            (it.message ?: "Network unavailable")) }
                    }
                }.start()
            })
            column.addView(card)
            column.addView(ui.space(this,10))
        }
        val dlg=AlertDialog.Builder(this).setTitle("Mods for "+version)
            .setView(ScrollView(this).apply {addView(column)})
            .setNegativeButton("CLOSE",null).create()
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels*.95).toInt(),
            (resources.displayMetrics.heightPixels*.82).toInt())
    }

    /** All destructive world operations are stopped-server-only and guarded by WorldStore. */
    private fun worldsPage(body:LinearLayout) {
        val selected = openedWorldFolder?.let { name ->
            WorldStore.worlds(this).firstOrNull { it.name == name }
        }
        if (selected != null) {
            worldFolderPage(body,selected)
            return
        }
        openedWorldFolder = null
        body.addView(HomesteadComponents.pageIntro(this,
            "WORLD LIBRARY","Your worlds",
            "Open a world to manage its own settings, files and backups."))
        body.addView(ui.space(this,13))
        val active=WorldStore.active(this)
        val activeCard=ui.card(this)
        activeCard.addView(ui.text(this,"ASSIGNED SERVER WORLD",11f,ui.CYAN,true))
        activeCard.addView(ui.space(this,6))
        activeCard.addView(ui.text(this,active,19f,ui.WHITE,true))
        activeCard.addView(ui.space(this,5))
        activeCard.addView(ui.text(this,
            if(ServerService.running || ServerService.status=="STARTING")
                "Stop Minecraft before switching or editing worlds."
            else "This is the world Minecraft will open on its next start.",
            12f,ui.MUTED))
        body.addView(activeCard)
        body.addView(ui.space(this,11))
        val actions=ui.row(this)
        actions.addView(ui.action(this,"＋ NEW WORLD",true,!ServerService.running &&
            ServerService.status !in listOf("STARTING","STOPPING")) {newWorldPrompt()},ui.weight(this))
        actions.addView(ui.action(this,"IMPORT ZIP") {picker(3)},
            LinearLayout.LayoutParams(0,-2,1f))
        body.addView(actions)
        body.addView(ui.space(this,8))
        body.addView(ui.space(this,13))
        val worlds=WorldStore.worlds(this)
        if(worlds.isEmpty())body.addView(empty("▧","No generated worlds yet",
            "Create a new world, then start Minecraft to generate it, or import an existing Java world ZIP."))
        worlds.forEach { file ->
            body.addView(HomesteadComponents.menuRow(this,
                "▧",file.name,
                if(WorldStore.isPending(file)) "Generate when the server starts"
                else "Settings  •  Files  •  Backups",
                if(file.name==active)"ACTIVE" else "") {
                openedWorldFolder=file.name
                resetWorldScroll=true
                render()
            })
            body.addView(ui.space(this,10))
        }
        body.addView(ui.text(this,"Backups stay on this phone until exported. Always keep an off-device ZIP copy before uninstalling PocketCraft or resetting Android.",
            11f,ui.MUTED))
    }

    /** Opens a single world like a folder; server-level and world-level controls stay separate. */
    private fun worldFolderPage(body: LinearLayout, world: File) {
        val active = WorldStore.active(this) == world.name
        val offline = !ServerService.running &&
            ServerService.status !in listOf("STARTING","STOPPING")
        body.addView(ui.action(this,"‹  ALL WORLDS") {
            openedWorldFolder = null
            resetWorldScroll=true
            render()
        })
        body.addView(ui.space(this,13))
        val heading = ui.card(this)
        heading.addView(WorldArt(this).apply {
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        heading.addView(ui.space(this,13))
        heading.addView(ui.text(this,"▧  " + world.name,22f,ui.WHITE,true))
        heading.addView(ui.space(this,7))
        heading.addView(ui.pill(this,if(active)"ACTIVE WORLD" else "SAVED WORLD",
            if(active)ui.GREEN else ui.CYAN))
        heading.addView(ui.space(this,9))
        heading.addView(ui.text(this,
            if(WorldStore.isPending(world)) "Minecraft will generate this world on the next start."
            else "Choose what to manage inside this world.",
            12f,ui.MUTED))
        if(!active) {
            heading.addView(ui.space(this,13))
            heading.addView(ui.action(this,"✓  USE THIS WORLD",true,offline) {
                downloadAsync("Switch world") {
                    WorldStore.activate(this,world)
                    "Assigned " + world.name + ". Its saved profile is active on the next server start."
                }
            })
        }
        body.addView(heading)
        body.addView(ui.space(this,13))

        body.addView(HomesteadComponents.sectionHeading(this,"In this world",
            "Each setting belongs to this world folder"))
        body.addView(ui.space(this,11))
        body.addView(HomesteadComponents.menuRow(this,"⚙","World settings",
            "Game mode, difficulty, view distance, seed and PvP") {
            startActivity(Intent(this,WorldSettingsActivity::class.java).apply {
                putExtra(WorldSettingsActivity.EXTRA_WORLD,world.name)
            })
        })
        body.addView(ui.space(this,8))
        body.addView(HomesteadComponents.menuRow(this,"▤","World files",
            "Explore region files, datapacks and level data") {
            openServerFiles(world.name)
        })
        if(!WorldStore.isPending(world)) {
            body.addView(ui.space(this,8))
            body.addView(HomesteadComponents.menuRow(this,"◈","Datapacks",
                "Manage datapacks for this world") {
                if(active)showDatapacks() else openServerFiles(world.name+"/datapacks")
            })
        }
        body.addView(ui.space(this,8))
        body.addView(HomesteadComponents.menuRow(this,"▧","Snapshots & backups",
            "Review saved snapshots of this world") { showSnapshotManager(world) })
        if(!WorldStore.isPending(world)) {
            body.addView(ui.space(this,8))
            body.addView(HomesteadComponents.menuRow(this,"＋","Create snapshot",
                "Save a local recovery point",enabled=offline) {
                downloadAsync("Backup world") {
                    val saved=WorldSnapshots.create(this,world)
                    "Snapshot saved: "+saved.name
                }
            })
        }
        body.addView(ui.space(this,8))
        body.addView(HomesteadComponents.menuRow(this,"⋯","More world actions",
            "Rename, duplicate, export or delete") { showWorldActions(world) })
        body.addView(ui.space(this,12))
        body.addView(ui.text(this,
            "World settings are saved separately. Server-wide connections, accounts and Geyser are managed from the top-right Settings icon.",
            11f,ui.MUTED))
    }

    private fun askWorldName(title:String,hint:String,default:String="",onDone:(String)->Unit) {
        val input=android.widget.EditText(this).apply {
            setSingleLine(true)
            this.hint=hint
            setText(default)
            setSelectAllOnFocus(true)
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background=ui.shape(this@MainActivity,ui.SURFACE,12,ui.BORDER)
            setPadding(ui.dp(this@MainActivity,14),ui.dp(this@MainActivity,13),
                ui.dp(this@MainActivity,14),ui.dp(this@MainActivity,13))
        }
        val panel=ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,20),ui.dp(this@MainActivity,10),
                ui.dp(this@MainActivity,20),ui.dp(this@MainActivity,14))
            addView(ui.text(this@MainActivity,
                "World names: 1–48 letters, numbers, dots, underscores or dashes, starting with a letter or number. Existing worlds are never overwritten.",
                12f,ui.MUTED))
            addView(ui.space(this@MainActivity,12))
            addView(input)
        }
        val dialog=AlertDialog.Builder(this).setTitle(title)
            .setView(panel).setNegativeButton("CANCEL",null)
            .setPositiveButton("CONTINUE",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name=input.text.toString().trim()
                if(!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,47}").matches(name)) {
                    input.error="Enter a safe world name"
                    return@setOnClickListener
                }
                dialog.dismiss()
                onDone(name)
            }
        }
        dialog.show()
    }

    private fun newWorldPrompt() {
        if(ServerService.running || ServerService.status in listOf("STARTING","STOPPING")) {
            toast("Stop the server before creating or switching worlds")
            return
        }
        askWorldName("Create & assign new world","New world name","new-world") { name ->
            AlertDialog.Builder(this).setTitle("Create world: "+name+"?")
                .setMessage("Creates an empty world slot and assigns it for Minecraft to generate on the next server start. Your existing worlds and mods remain unchanged.")
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("CREATE & ASSIGN") { _,_ ->
                    downloadAsync("Create world") {
                        WorldStore.create(this,name)
                        "Created and assigned world: "+name+". Start Minecraft to generate it."
                    }
                }.show()
        }
    }

    private fun showWorldActions(file:File) {
        val offline=!ServerService.running && ServerService.status !in listOf("STARTING","STOPPING")
        val panel=ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,16),ui.dp(this@MainActivity,10),
                ui.dp(this@MainActivity,16),ui.dp(this@MainActivity,12))
        }
        panel.addView(ui.text(this,if(file.name==WorldStore.active(this))
            "Currently assigned to Minecraft" else "Available server world",
            12f,ui.MUTED))
        panel.addView(ui.space(this,9))
        lateinit var dialog:AlertDialog
        fun item(label:String,enabled:Boolean=true,action:()->Unit) {
            panel.addView(ui.action(this,label,false,enabled) {
                dialog.dismiss()
                action()
            })
            panel.addView(ui.space(this,8))
        }
        item("DUPLICATE WORLD",offline) {
            askWorldName("Duplicate "+file.name,"New copy name",file.name.take(32)+"-copy") { name ->
                downloadAsync("Duplicate world") {
                    WorldStore.duplicate(this,file,name)
                    "Created an independent copy named "+name
                }
            }
        }
        item("RENAME WORLD",offline) {
            askWorldName("Rename "+file.name,"New world name",file.name) { name ->
                downloadAsync("Rename world") {
                    WorldStore.rename(this,file,name)
                    "World renamed to "+name
                }
            }
        }
        if(!WorldStore.isPending(file))item("EXPORT WORLD ZIP",offline) {
            pendingBackup=file
            createBackupDocument(file)
        }
        item("DELETE WORLD",offline) {
            AlertDialog.Builder(this).setTitle("Permanently delete "+file.name+"?")
                .setMessage("This permanently deletes this world. Make an off-device backup first. Active worlds cannot be deleted; assign another world instead.")
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("DELETE") { _,_ ->
                    downloadAsync("Delete world"){
                        WorldStore.delete(this,file)
                        "Deleted "+file.name
                    }
                }.show()
        }
        dialog=AlertDialog.Builder(this).setTitle("World • "+file.name)
            .setView(ScrollView(this).apply{addView(panel)})
            .setNegativeButton("CLOSE",null).create()
        dialog.show()
    }

    private fun showSnapshotManager(world: File? = null) {
        val panel=ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,16),ui.dp(this@MainActivity,10),
                ui.dp(this@MainActivity,16),ui.dp(this@MainActivity,15))
        }
        panel.addView(ui.text(this,
            "Local snapshots keep the five newest copies of each world. They require the server to be stopped and use phone storage. Export ZIPs off-device for disaster recovery.",
            12f,ui.MUTED))
        panel.addView(ui.space(this,13))
        val all=WorldSnapshots.list(this,world)
        val offline=!ServerService.running && ServerService.status !in listOf("STARTING","STOPPING")
        if(all.isEmpty())panel.addView(ui.text(this,"No snapshots for this world yet. Use Create local snapshot in its folder.",12f,ui.MUTED))
        lateinit var dialog:AlertDialog
        all.take(60).forEach { snapshot ->
            val card=ui.card(this)
            card.addView(ui.text(this,snapshot.name,12f,ui.WHITE,true))
            card.addView(ui.space(this,6))
            card.addView(ui.text(this,ServerFileOps.humanSize(snapshot.length())+
                "  •  "+java.text.DateFormat.getDateTimeInstance().format(java.util.Date(snapshot.lastModified())),
                11f,ui.MUTED))
            card.addView(ui.space(this,9))
            val actions=ui.row(this)
            actions.addView(ui.action(this,"RESTORE AS NEW",false,offline) {
                dialog.dismiss()
                askWorldName("Restore snapshot as new world","New world name","restored-world") { name ->
                    downloadAsync("Restore world snapshot") {
                        WorldSnapshots.restore(this,snapshot,name)
                        "Restored a separate world named "+name+". Select it from Worlds to play."
                    }
                }
            },ui.weight(this))
            actions.addView(ui.action(this,"DELETE",false,offline) {
                dialog.dismiss()
                AlertDialog.Builder(this).setTitle("Delete saved snapshot?")
                    .setMessage(snapshot.name)
                    .setNegativeButton("CANCEL",null)
                    .setPositiveButton("DELETE"){_,_->
                        downloadAsync("Delete snapshot") {
                            WorldSnapshots.delete(this,snapshot)
                            "Removed local snapshot"
                        }
                    }.show()
            },LinearLayout.LayoutParams(0,-2,1f))
            card.addView(actions)
            panel.addView(card)
            panel.addView(ui.space(this,8))
        }
        dialog=AlertDialog.Builder(this).setTitle(if(world==null) "World backups" else "Backups • " + world.name)
            .setView(ScrollView(this).apply{addView(panel)})
            .setNegativeButton("CLOSE",null).create()
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels*.94).toInt(),
            (resources.displayMetrics.heightPixels*.75).toInt())
    }

    /** Common player moderation commands are sent to the real Java server stdin. */
    private fun showPlayerManager() {
        val online=PlayerActivity.names()
        val shell=ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,16),ui.dp(this@MainActivity,9),
                ui.dp(this@MainActivity,16),ui.dp(this@MainActivity,13))
        }
        shell.addView(ui.text(this,
            "Players detected from Minecraft join/leave messages. For the newest full list, tap Refresh; results appear in the Console. Admin commands require a running server.",
            12f,ui.MUTED))
        shell.addView(ui.space(this,11))
        shell.addView(ui.pill(this,
            online.size.toString()+" ONLINE OBSERVED",if(online.isNotEmpty())ui.GREEN else ui.MUTED))
        shell.addView(ui.space(this,9))
        if(online.isEmpty())shell.addView(ui.text(this,
            if(ServerService.running)"No join messages captured yet." else "Start Minecraft to manage online players.",
            12f,ui.MUTED))
        lateinit var dialog:AlertDialog
        fun confirmCommand(label:String,command:String,description:String) {
            AlertDialog.Builder(this)
                .setTitle(label).setMessage(description)
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("CONFIRM") { _,_ ->
                    if(issueConsoleCommand(command))toast("Requested "+command.substringBefore(" "))
                }.show()
        }
        online.forEach { name ->
            val card=ui.card(this)
            card.addView(ui.text(this,name,15f,ui.WHITE,true))
            card.addView(ui.space(this,9))
            val actions=ui.row(this)
            actions.addView(ui.action(this,"KICK",false,ServerService.running) {
                dialog.dismiss()
                confirmCommand("Kick "+name+"?","kick "+name,"Disconnect this player from the current server?")
            },ui.weight(this))
            actions.addView(ui.action(this,"PERMISSIONS",false,ServerService.running) {
                dialog.dismiss()
                val controls=ui.column(this).apply {
                    setPadding(ui.dp(this@MainActivity,17),ui.dp(this@MainActivity,12),
                        ui.dp(this@MainActivity,17),ui.dp(this@MainActivity,14))
                }
                listOf(
                    Triple("ADD TO WHITELIST","whitelist add "+name,"Allow "+name+" to join when the whitelist is enabled?"),
                    Triple("REMOVE FROM WHITELIST","whitelist remove "+name,"Remove "+name+" from the whitelist?"),
                    Triple("GRANT OPERATOR","op "+name,"Operator permissions allow full server administration, including changing the world. Only grant to trusted players."),
                    Triple("REMOVE OPERATOR","deop "+name,"Remove operator access from "+name+"?")
                ).forEach { item ->
                    controls.addView(ui.action(this,item.first) {
                        confirmCommand(item.first+"?",item.second,item.third)
                    })
                    controls.addView(ui.space(this,7))
                }
                AlertDialog.Builder(this).setTitle(name+" • permissions")
                    .setView(ScrollView(this).apply {addView(controls)})
                    .setNegativeButton("CLOSE",null).show()
            },LinearLayout.LayoutParams(0,-2,1f))
            card.addView(actions)
            shell.addView(card)
            shell.addView(ui.space(this,8))
        }
        shell.addView(ui.space(this,8))
        shell.addView(ui.action(this,"↻ REFRESH PLAYER LIST",false,ServerService.running) {
            dialog.dismiss()
            if(issueConsoleCommand("list"))toast("Player list requested. Check Console.")
        })
        shell.addView(ui.space(this,8))
        shell.addView(ui.action(this,"VIEW WHITELIST",false,ServerService.running) {
            dialog.dismiss()
            if(issueConsoleCommand("whitelist list"))toast("Whitelist requested. Check Console.")
        })
        dialog=AlertDialog.Builder(this).setTitle("Players & access")
            .setView(ScrollView(this).apply{addView(shell)})
            .setNegativeButton("CLOSE",null).create()
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels*.94).toInt(),
            (resources.displayMetrics.heightPixels*.78).toInt())
    }

    /** Datapacks belong to the active world, never to Fabric's /mods folder. */
    private fun showDatapacks() {
        val active = runCatching { DatapackInstaller.activeDirectory(this) }
        if (active.isFailure) {
            AlertDialog.Builder(this).setTitle("World required")
                .setMessage(active.exceptionOrNull()?.message ?:
                    "Start Minecraft once or select a world before adding datapacks.")
                .setPositiveButton("OK",null).show()
            return
        }
        val world = WorldStore.active(this)
        val panel = ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,16),ui.dp(this@MainActivity,10),
                ui.dp(this@MainActivity,16),ui.dp(this@MainActivity,14))
        }
        panel.addView(ui.pill(this,"WORLD  •  " + world,ui.GREEN))
        panel.addView(ui.space(this,9))
        panel.addView(ui.text(this,"Datapacks change this world for Java and Geyser players.",
            12f,ui.MUTED))
        panel.addView(ui.space(this,12))
        lateinit var dialog: AlertDialog
        panel.addView(ui.action(this,"＋  IMPORT DATAPACK ZIP",true,!ServerService.running) {
            dialog.dismiss()
            picker(6)
        })
        panel.addView(ui.space(this,8))
        panel.addView(ui.action(this,"⌕  FIND ON MODRINTH",false,!ServerService.running) {
            dialog.dismiss()
            searchDatapacks()
        })
        panel.addView(ui.space(this,8))
        panel.addView(ui.action(this,"▤  VIEW DATAPACKS FOLDER") {
            dialog.dismiss()
            openServerFiles(world + "/datapacks")
        })
        panel.addView(ui.space(this,16))
        panel.addView(ui.text(this,"Installed ZIPs",17f,ui.WHITE,true))
        panel.addView(ui.space(this,8))
        val installed = DatapackInstaller.installed(this)
        if (installed.isEmpty()) {
            panel.addView(ui.text(this,"No ZIP datapacks installed in this world.",
                12f,ui.MUTED))
        } else {
            installed.forEach { file ->
                val row = ui.row(this)
                row.addView(ui.text(this,file.name,12f,ui.WHITE),ui.weight(this))
                row.addView(ui.action(this,"REMOVE",false,!ServerService.running) {
                    dialog.dismiss()
                    AlertDialog.Builder(this).setTitle("Remove " + file.name + "?")
                        .setMessage("Only this datapack ZIP will be deleted. Back up your world first; removing datapacks can affect world features.")
                        .setNegativeButton("CANCEL") {_,_->showDatapacks()}
                        .setPositiveButton("REMOVE") {_,_->
                            val result=runCatching {
                                DatapackInstaller.remove(this,file.name)
                            }
                            result.onFailure {toast("Could not remove datapack: "+it.message)}
                            result.onSuccess {toast("Removed "+file.name+". Restart the server.");}
                            showDatapacks()
                        }.show()
                },LinearLayout.LayoutParams(ui.dp(this,96),-2))
                panel.addView(row)
                panel.addView(ui.space(this,8))
            }
        }
        val scroll=ScrollView(this).apply {addView(panel)}
        dialog=AlertDialog.Builder(this).setTitle("Datapacks")
            .setView(scroll).setNegativeButton("CLOSE",null).create()
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels*.94).toInt(),
            (resources.displayMetrics.heightPixels*.78).toInt())
    }

    private fun searchDatapacks() {
        if (ServerService.running) {toast("Stop Minecraft first");return}
        val version = getSharedPreferences("pocketcraft",0)
            .getString("gameVersion",null)
        if (version.isNullOrBlank()) {
            AlertDialog.Builder(this).setTitle("Select a Minecraft version")
                .setMessage("Install your Fabric server to browse matching datapacks.")
                .setPositiveButton("OK",null).show()
            return
        }
        val query=android.widget.EditText(this).apply {
            setSingleLine(true)
            hint="Search datapacks (e.g. Vanilla Refresh)"
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background=ui.shape(this@MainActivity,ui.SURFACE,12,ui.BORDER)
            setPadding(ui.dp(this@MainActivity,14),ui.dp(this@MainActivity,14),
                ui.dp(this@MainActivity,14),ui.dp(this@MainActivity,14))
        }
        val content=ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,20),ui.dp(this@MainActivity,12),
                ui.dp(this@MainActivity,20),ui.dp(this@MainActivity,12))
            addView(ui.pill(this@MainActivity,"MINECRAFT  •  "+version,ui.CYAN))
            addView(ui.space(this@MainActivity,12))
            addView(query)
        }
        AlertDialog.Builder(this).setTitle("Find datapacks")
            .setView(content).setNegativeButton("CANCEL",null)
            .setPositiveButton("SEARCH") {_,_->
                val wait=AlertDialog.Builder(this)
                    .setTitle("Searching Modrinth")
                    .setMessage("Finding datapacks for Minecraft "+version+"…")
                    .setCancelable(false).create()
                wait.show()
                Thread {
                    val matches=runCatching {
                        DatapackInstaller.search(query.text.toString().trim(),version)
                    }
                    runOnUiThread {
                        if(isFinishing || isDestroyed)return@runOnUiThread
                        wait.dismiss()
                        matches.onSuccess {showDatapackResults(it,version)}
                            .onFailure {toast("Datapack search failed: "+it.message)}
                    }
                }.start()
            }.show()
    }

    private fun showDatapackResults(list:List<DatapackInstaller.Project>,version:String) {
        if(list.isEmpty()){toast("No datapacks found for Minecraft "+version);return}
        val body=ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,14),ui.dp(this@MainActivity,8),
                ui.dp(this@MainActivity,14),ui.dp(this@MainActivity,16))
        }
        lateinit var dialog:AlertDialog
        list.forEach { project ->
            val card=ui.card(this)
            card.addView(ui.text(this,project.title,15f,ui.WHITE,true))
            if(project.description.isNotBlank()) {
                card.addView(ui.space(this,6))
                card.addView(ui.text(this,project.description,11f,ui.MUTED))
            }
            card.addView(ui.space(this,8))
            card.addView(ui.action(this,"INSTALL DATAPACK",true,!ServerService.running) {
                dialog.dismiss()
                AlertDialog.Builder(this).setTitle("Install "+project.title+"?")
                    .setMessage("Install a Modrinth datapack ZIP for Minecraft "+version+
                        " into the active world. Existing datapacks will not be overwritten. Java and Bedrock visuals may differ; back up your world first.")
                    .setNegativeButton("CANCEL",null)
                    .setPositiveButton("INSTALL"){_,_->
                        downloadAsync("Datapack: "+project.title) {
                            DatapackInstaller.installModrinth(this,project,version)
                        }
                    }.show()
            })
            body.addView(card)
            body.addView(ui.space(this,10))
        }
        dialog=AlertDialog.Builder(this).setTitle("Datapacks • "+version)
            .setView(ScrollView(this).apply{addView(body)})
            .setNegativeButton("CLOSE",null).create()
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels*.94).toInt(),
            (resources.displayMetrics.heightPixels*.8).toInt())
    }

    /** Only the real Minecraft stdin process receives a submitted command. */
    private fun issueConsoleCommand(raw: String): Boolean {
        val command = runCatching { ConsoleCommands.normalize(raw) }.getOrElse { error ->
            val reason = error.message ?: "Invalid command"
            consoleCommandFeedbackView?.text = "Not sent: " + reason
            toast(reason)
            return false
        }
        if (!ServerService.running) {
            consoleCommandFeedbackView?.text="Not sent: Minecraft isn't running. Start it from Home."
            toast("Start Minecraft before sending commands")
            return false
        }
        return try {
            // The service reports whether its serial command worker actually
            // delivered this to the running JVM's stdin or failed to write it.
            startService(Intent(this, ServerService::class.java)
                .setAction(ServerService.ACTION_COMMAND)
                .putExtra(ServerService.EXTRA_COMMAND, command))
            consoleCommandFeedbackView?.text="Command queued. Checking server stdin…"
            true
        } catch (e:Exception) {
            consoleCommandFeedbackView?.text="Not sent: " + (e.message ?: "Android service error")
            toast("Could not send command: " + (e.message ?: "service unavailable"))
            false
        }
    }

    private fun updateConsoleSuggestions() {
        val host = consoleSuggestionHost ?: return
        val entry = consoleCommandEntry ?: return
        host.removeAllViews()
        if (!ServerService.running || entry.text.isBlank()) return
        val proposed = ServerCommandHints.suggestions(entry.text.toString(), 6)
        if (proposed.isEmpty()) return
        host.addView(ui.text(this,
            if (ServerCommandHints.registeredCount() > 0)
                "From server help • tap to insert (includes available mod commands)"
            else "Common examples • live command discovery starts when server is ready",
            11f, ui.MUTED))
        host.addView(ui.space(this, 5))
        proposed.forEach { item ->
            val suggestion = ui.text(this, "  /" + item, 13f, ui.WHITE)
            suggestion.background = ui.shape(this, ui.SURFACE, 9)
            suggestion.setPadding(ui.dp(this, 12), ui.dp(this, 10),
                ui.dp(this, 12), ui.dp(this, 10))
            suggestion.setOnClickListener {
                entry.setText(item)
                entry.setSelection(entry.text.length)
                entry.requestFocus()
            }
            host.addView(suggestion)
            host.addView(ui.space(this, 5))
        }
    }

    /** Searchable directory populated from the Minecraft server's own /help output. */
    private fun showConsoleCommands() {
        val roots = ServerCommandHints.registered()
        val usages = ServerCommandHints.usages()
        val all = (roots + usages + listOf("list","seed","save-all","time set day",
            "weather clear","chunky progress","geyser list","whitelist list"))
            .distinct().sorted()
        val panel = ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity, 16), ui.dp(this@MainActivity, 10),
                ui.dp(this@MainActivity, 16), ui.dp(this@MainActivity, 12))
        }
        panel.addView(ui.text(this,
            if (all.isEmpty())
                "Common command examples are shown. To discover additional mod commands, request /help manually. The resulting long output is hidden from the simple command panel."
            else roots.size.toString() + " command roots discovered. " +
                "Fabric mod commands appear when mods register them in /help. " +
                "Some mods hide subcommands or require in-game permissions.",
            12f, ui.MUTED))
        panel.addView(ui.space(this, 9))
        val search = android.widget.EditText(this).apply {
            hint = "Search, e.g. chunky"
            setSingleLine(true)
            textSize = 14f
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background = ui.shape(this@MainActivity, ui.SURFACE, 12)
            setPadding(ui.dp(this@MainActivity, 12), ui.dp(this@MainActivity, 10),
                ui.dp(this@MainActivity, 12), ui.dp(this@MainActivity, 10))
        }
        panel.addView(search)
        panel.addView(ui.space(this, 10))
        val list = ui.column(this)
        val scroll = ScrollView(this).apply { addView(list) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, ui.dp(this, 340)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Server commands")
            .setView(panel)
            .setNegativeButton("CLOSE", null)
            .setNeutralButton("REFRESH HELP") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Scan /help commands?")
                    .setMessage("Minecraft can print a very large list of commands. This scan runs only when requested; the command panel will hide the dump. Full Logs retains it.")
                    .setNegativeButton("CANCEL",null)
                    .setPositiveButton("SCAN") {_,_->
                        if(issueConsoleCommand("help"))
                            toast("Requested help; available commands will appear in the catalog.")
                    }.show()
            }.create()
        var commandDisplayLimit = 100
        fun populate(term: String) {
            list.removeAllViews()
            val needle = term.trim().removePrefix("/")
            val matches = all.filter { it.contains(needle, true) }
            if (matches.isEmpty()) {
                list.addView(ui.text(this, "No matching registered commands.", 12f, ui.MUTED))
                return
            }
            matches.take(commandDisplayLimit).forEach { item ->
                val line = ui.text(this, "/" + item, 13f, ui.WHITE)
                line.typeface = android.graphics.Typeface.MONOSPACE
                line.background = ui.shape(this, ui.SURFACE, 8)
                line.setPadding(ui.dp(this, 10), ui.dp(this, 9),
                    ui.dp(this, 10), ui.dp(this, 9))
                line.setOnClickListener {
                    consoleCommandEntry?.let { entry ->
                        entry.setText(item)
                        entry.setSelection(entry.text.length)
                    }
                    dialog.dismiss()
                }
                list.addView(line)
                list.addView(ui.space(this, 5))
            }
            if (matches.size > commandDisplayLimit) {
                list.addView(ui.action(this,
                    "SHOW MORE (" + commandDisplayLimit + " / " + matches.size + ")") {
                    commandDisplayLimit += 100
                    populate(term)
                })
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                commandDisplayLimit = 100
                populate(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        populate("")
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .94).toInt(), -2)
    }

    private fun consolePage(body:LinearLayout) {
        consoleViewVersion++
        body.addView(HomesteadComponents.pageIntro(this,
            "COMMAND CENTER","Console",
            "Send server commands and inspect activity without losing your place."))
        body.addView(ui.space(this,12))

        val composer=ui.card(this)
        composer.addView(ui.text(this,"SEND A COMMAND",12f,ui.CYAN,true))
        composer.addView(ui.space(this,8))
        val entry=android.widget.EditText(this).apply {
            hint="e.g. list, time set day, /chunky progress"
            setSingleLine(true)
            textSize=14f
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background=ui.shape(this@MainActivity,ui.SURFACE,12,ui.BORDER)
            setPadding(ui.dp(this@MainActivity,13),ui.dp(this@MainActivity,13),
                ui.dp(this@MainActivity,13),ui.dp(this@MainActivity,13))
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            isEnabled=true
        }
        consoleCommandEntry=entry
        composer.addView(entry)
        composer.addView(ui.space(this,8))
        val suggestions=ui.column(this)
        consoleSuggestionHost=suggestions
        composer.addView(suggestions)
        entry.addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                updateConsoleSuggestions()
            }
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        updateConsoleSuggestions()
        fun sendTypedCommand() {
            if(issueConsoleCommand(entry.text.toString())) {
                entry.text.clear()
                consoleActivityView?.text="Sending command to Minecraft…"
            }
        }
        entry.setOnEditorActionListener { _,actionId,event ->
            if(actionId==android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
               (event?.keyCode==android.view.KeyEvent.KEYCODE_ENTER &&
                event.action==android.view.KeyEvent.ACTION_DOWN)) {
                sendTypedCommand()
                true
            } else false
        }
        composer.addView(ui.action(this,"SEND COMMAND →",true) {sendTypedCommand()})
        composer.addView(ui.space(this,7))
        composer.addView(ui.text(this,ServerService.commandFeedback,12f,ui.CYAN).also {
            consoleCommandFeedbackView=it
            lastCommandFeedbackRevision=ServerService.commandRevision
        })
        composer.addView(ui.space(this,5))
        composer.addView(ui.text(this,
            if(ServerService.running)"Server process active · commands can be sent"
            else "Start the Minecraft server first",
            11f,ui.MUTED).also {consoleAvailabilityView=it})
        body.addView(composer)

        body.addView(ui.space(this,11))
        val shortcuts=ui.card(this)
        shortcuts.addView(ui.text(this,"QUICK COMMANDS",11f,ui.CYAN,true))
        shortcuts.addView(ui.space(this,8))
        fun shortcut(label:String,command:String):TextView =
            ui.action(this,label,false) {
                if(issueConsoleCommand(command))
                    consoleActivityView?.text="Sending "+command+"…"
            }
        val quick1=ui.row(this)
        quick1.addView(shortcut("PLAYERS","list"),ui.weight(this))
        quick1.addView(shortcut("SAVE WORLD","save-all"),LinearLayout.LayoutParams(0,-2,1f))
        shortcuts.addView(quick1)
        shortcuts.addView(ui.space(this,7))
        val quick2=ui.row(this)
        quick2.addView(shortcut("WORLD SEED","seed"),ui.weight(this))
        quick2.addView(shortcut("CHUNKY STATUS","chunky progress"),LinearLayout.LayoutParams(0,-2,1f))
        shortcuts.addView(quick2)
        body.addView(shortcuts)

        body.addView(ui.space(this,11))
        val activity=ui.card(this)
        activity.addView(ui.text(this,"COMMAND ACTIVITY",12f,ui.CYAN,true))
        activity.addView(ui.space(this,6))
        activity.addView(ui.text(this,
            "Your sent commands and nearby Minecraft replies. Unrelated server events can also appear; the full log is available below.",
            11f,ui.MUTED))
        activity.addView(ui.space(this,9))
        val commandText=ui.text(this,ConsoleCommandActivity.preview(),12f,ui.WHITE).apply {
            typeface=android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(false)
            maxLines=24
            consoleActivityView=this
            lastActivityRevision=ConsoleCommandActivity.revision
        }
        activity.addView(commandText)
        activity.addView(ui.space(this,9))
        activity.addView(ui.action(this,"COPY COMMAND ACTIVITY") {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(
                    "PocketCraft command activity",ConsoleCommandActivity.snapshot()))
            toast("Copied recent commands and responses. Review before sharing.")
        })
        body.addView(activity)
        body.addView(ui.space(this,12))

        val advanced=ui.column(this)
        advanced.visibility=if(consoleLogExpanded) View.VISIBLE else View.GONE
        lateinit var logToggle:TextView
        logToggle=ui.action(this,if(consoleLogExpanded)
            "− HIDE FULL LOGS & TOOLS" else "＋ SHOW FULL LOGS & TOOLS") {
            consoleLogExpanded=!consoleLogExpanded
            advanced.visibility=if(consoleLogExpanded) View.VISIBLE else View.GONE
            logToggle.text=if(consoleLogExpanded)
                "− HIDE FULL LOGS & TOOLS" else "＋ SHOW FULL LOGS & TOOLS"
            if(consoleLogExpanded) {
                lastConsoleSize=-1
                lastConsoleModified=-1
                requestConsoleLogRefresh()
            }
        }
        body.addView(logToggle)
        body.addView(ui.space(this,9))
        advanced.addView(ui.text(this,
            "Full Logs contains startup, mod and network messages. CLEAN hides the long /help list; ALL reveals everything.",
            11f,ui.MUTED))
        advanced.addView(ui.space(this,8))
        val tools=ui.row(this)
        tools.addView(ui.action(this,"COMMAND CATALOG") {showConsoleCommands()},ui.weight(this))
        tools.addView(ui.action(this,"COPY RAW LOG") {
            AlertDialog.Builder(this)
                .setTitle("Copy Minecraft server log?")
                .setMessage("Full logs can contain player names, IP addresses and private details. Review before sharing.")
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("COPY") {_,_->
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("PocketCraft raw log tail",consoleCachedTail))
                    toast("Recent raw log copied")
                }.show()
        },LinearLayout.LayoutParams(0,-2,1f))
        advanced.addView(tools)
        advanced.addView(ui.space(this,8))
        val filters=ui.row(this)
        listOf("CLEAN","ERRORS","PLAYERS","ALL").forEach { choice ->
            filters.addView(ui.action(this,choice,consoleLogFilter==choice) {
                consoleLogFilter=choice
                consoleOutput?.text=filterConsoleLog(consoleCachedTail)
            },LinearLayout.LayoutParams(0,-2,1f))
        }
        advanced.addView(filters)
        advanced.addView(ui.space(this,7))
        val searchLog=android.widget.EditText(this).apply {
            hint="Find text in recent raw log"
            setSingleLine(true)
            textSize=13f
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background=ui.shape(this@MainActivity,ui.SURFACE,12,ui.BORDER)
            setPadding(ui.dp(this@MainActivity,12),ui.dp(this@MainActivity,10),
                ui.dp(this@MainActivity,12),ui.dp(this@MainActivity,10))
            setText(consoleLogSearch)
        }
        searchLog.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                consoleLogSearch=s?.toString().orEmpty().take(60)
                consoleOutput?.text=filterConsoleLog(consoleCachedTail)
            }
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        advanced.addView(searchLog)
        advanced.addView(ui.space(this,9))
        val log=ui.column(this).apply {
            background=ui.shape(this@MainActivity,
                if(ui.homestead())0xFF211A16.toInt() else 0xFF0F1728.toInt(),
                18,if(ui.homestead())0xFF745035.toInt() else 0xFF26354D.toInt())
            ui.run {padding(this@MainActivity,14,14,14,14)}
        }
        log.addView(ui.pill(this,"●  MINECRAFT LOG",ui.CYAN))
        log.addView(ui.space(this,10))
        val logText=ui.text(this,filterConsoleLog(consoleCachedTail),11f,0xFFC0D7D7.toInt()).apply {
            typeface=android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(false)
            maxLines=110
            isFocusable=false
            consoleOutput=this
        }
        consoleLogScroll=null
        log.addView(logText)
        advanced.addView(log)
        advanced.addView(ui.space(this,9))
        advanced.addView(ui.action(this,"↻ REFRESH LOG") {
            lastConsoleSize=-1
            lastConsoleModified=-1
            requestConsoleLogRefresh()
        })
        body.addView(advanced)
    }

    private fun getLog():String {
        val file = File(base,"console.log")
        if(!file.isFile) return "[PocketCraft] No server output yet.\nStart the server to see live status here."
        return try {
            // A bounded tail prevents unbounded text layout and memory churn.
            // This function is used by the console's background worker.
            java.io.RandomAccessFile(file,"r").use { reader ->
                val count = minOf(reader.length(), 12_288L).toInt()
                reader.seek(reader.length()-count)
                val buffer = ByteArray(count)
                reader.readFully(buffer)
                String(buffer,Charsets.UTF_8)
            }
        } catch(e:Exception) {
            "[PocketCraft] Could not read console: " + (e.message ?: "storage error")
        }
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
        popup.addView(ui.action(this,"OTHER MINECRAFT VERSION") {
            dialog.dismiss()
            val input=android.widget.EditText(this).apply {
                hint="e.g. 1.21.4"
                setSingleLine(true)
                setTextColor(ui.WHITE)
                setHintTextColor(ui.MUTED)
                inputType=android.text.InputType.TYPE_CLASS_TEXT
                setPadding(ui.dp(this@MainActivity,18),ui.dp(this@MainActivity,14),
                    ui.dp(this@MainActivity,18),ui.dp(this@MainActivity,14))
            }
            AlertDialog.Builder(this).setTitle("Minecraft version")
                .setMessage("Enter an official Minecraft version supported by Fabric. The Android Java runtime must also support this version.")
                .setView(input)
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("CONTINUE"){_,_->
                    val selected=input.text.toString().trim()
                    if(selected.matches(Regex("[0-9][A-Za-z0-9._+-]{0,35}")))
                        confirmFabricInstall(selected)
                    else toast("Enter a valid Minecraft version")
                }.show()
        })
        popup.addView(ui.space(this,9))
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
                    val wasCancelled = cancelled.get()
                    val reason = error.message?.take(1300)
                        ?: "Check your internet connection, storage, and installed Minecraft version."
                    val builder = AlertDialog.Builder(this)
                        .setTitle(if(wasCancelled)"Installation cancelled" else "Installation failed")
                        .setMessage(if(wasCancelled)"The installer was stopped. Your existing world and server settings were not intentionally changed."
                            else "$label could not finish.\n\n$reason")
                        .setNegativeButton("CLOSE",null)
                    if (!wasCancelled) {
                        builder.setNeutralButton("COPY ERROR") {_,_->
                            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("PocketCraft installer failure",
                                    "$label: $reason"))
                            toast("Error copied for troubleshooting")
                        }
                        if (label.startsWith("Fabric ") || label == "crossplay" ||
                            label == "Android Java 25") {
                            builder.setPositiveButton("TRY AGAIN") {_,_->downloadAsync(label,action)}
                        }
                    }
                    builder.show()
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
    private fun openServerFiles(path: String = "") {
        startActivity(Intent(this,ServerFilesActivity::class.java).apply {
            if(path.isNotBlank()) putExtra(ServerFilesActivity.EXTRA_PATH,path)
        })
    }

    private fun updateHelp() {
        AlertDialog.Builder(this)
            .setTitle("Why won't the APK update?")
            .setMessage("Android requires the new APK to use the same signing certificate as the installed app. Previous GitHub debug builds used a fresh certificate each time, so Android refuses to update them.\n\n" +
                "1. Export a complete server ZIP from Server Files before uninstalling.\n" +
                "2. Add your private POCKETCRAFT_KEYSTORE_BASE64 and POCKETCRAFT_SIGNING_PASSWORD secrets in GitHub Actions settings.\n" +
                "3. Build a new permanently signed APK. Back up and uninstall the old temporary-key APK once, install the new one, then restore your ZIP.\n" +
                "4. Future builds using that same permanent key can update without uninstalling.\n\n" +
                "The application cannot bypass Android's certificate checks. Never upload your signing keystore to a public repository.")
            .setNeutralButton("BACK UP SERVER") {_,_->openServerFiles()}
            .setPositiveButton("GOT IT",null).show()
    }

    private fun setup() {
        val crossplay = listOf("fabric-api.jar","Geyser-Fabric.jar","floodgate-fabric.jar")
            .all { File(mods,it).isFile }
        val version = getSharedPreferences("pocketcraft",0).getString("gameVersion","unknown")
        val checks = listOf(
            "Fabric server: " + (if(serverJar.isFile)"Installed ($version)" else "Not installed"),
            "Android Java files: " + (if(RuntimeStore.isInstalled(this))"Installed" else "Not installed"),
            "Minecraft EULA: " + (if(ServerDownloads.eulaAccepted(this))"Accepted" else "Needs acceptance"),
            "Java multiplayer: " + (if(ServerService.javaReady)"Ready (reported by Minecraft)" else "Not verified"),
            "Bedrock bridge mods: " + (if(crossplay)"Files present" else "Not all installed"),
            "Geyser connection: " + (if(ServerService.bedrockReady)ServerService.bedrockEvidence else "No startup evidence captured"),
            "NetherNet signaling: " + ServerService.nethernetMode,
            "Playit agent: " + PlayitManager.status
        )
        AlertDialog.Builder(this).setTitle("Setup & connection diagnostics")
            .setMessage(checks.joinToString("\n")+
                "\n\nInstalled files do not guarantee that the JVM or a public tunnel works. Confirm by joining from the desired network.")
            .setNeutralButton("OPEN CONSOLE") {_,_->tab=3;render()}
            .setPositiveButton("OK",null).show()
    }

    private fun about() {
        val v=runCatching{packageManager.getPackageInfo(packageName,0).versionName}
            .getOrNull() ?: "unknown"
        AlertDialog.Builder(this).setTitle("PocketCraft Server · v$v")
            .setMessage("A phone-first Minecraft Fabric server studio. LAN hosting runs on your device; internet tunnels are optional. Java, Bedrock and NetherNet readiness are checked separately.\n\nMinecraft, Fabric, Geyser and Playit are independent third-party projects. On-device and remote gameplay still depends on your device, server and network.")
            .setPositiveButton("GOT IT",null).show()
    }
    private fun picker(code:Int) {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type=if(code==3 || code==5)"*/*" else "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent,code)
    }
    private fun installFromWebsite() {
        val input=android.widget.EditText(this).apply {
            hint="https://modrinth.com/mod/..."
            setSingleLine(false)
            minLines=2
            maxLines=4
            inputType=android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background=ui.shape(this@MainActivity,ui.SURFACE,12)
            setPadding(ui.dp(this@MainActivity,13),ui.dp(this@MainActivity,12),
                ui.dp(this@MainActivity,13),ui.dp(this@MainActivity,12))
        }
        val panel=ui.column(this).apply {
            setPadding(ui.dp(this@MainActivity,20),ui.dp(this@MainActivity,10),
                ui.dp(this@MainActivity,20),ui.dp(this@MainActivity,12))
            addView(ui.text(this@MainActivity,
                "Modrinth project/version pages, direct HTTPS Fabric .jar links, and .mrpack downloads are supported. Minecraft must be stopped.",
                12f,ui.MUTED))
            addView(ui.space(this@MainActivity,12))
            addView(input)
        }
        val dialog=AlertDialog.Builder(this)
            .setTitle("Add from website")
            .setView(panel)
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("INSTALL",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val link=input.text.toString().trim()
                if (!link.startsWith("https://")) {
                    toast("Paste a complete HTTPS download or Modrinth link")
                    return@setOnClickListener
                }
                if(ServerService.running) {
                    toast("Stop Minecraft before installing mods")
                    return@setOnClickListener
                }
                dialog.dismiss()
                downloadAsync("Website mod installation") {
                    WebsiteModInstaller.install(this,link)
                }
            }
        }
        dialog.show()
    }

    /** Let players select optional dependencies before downloading mods. */
    private fun chooseModDependencies(title: String, slug: String) {
        val waiting = AlertDialog.Builder(this).setTitle("Checking dependencies")
            .setMessage("Reading required and optional mods…")
            .setCancelable(false).create()
        waiting.show()
        Thread {
            val result = runCatching { WebsiteModInstaller.previewDependencies(this, slug) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                waiting.dismiss()
                result.onFailure {
                    AlertDialog.Builder(this).setTitle("Dependency lookup failed")
                        .setMessage((it.message ?: "Network error") +
                            "\n\nYou can still try the standard installer.")
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("INSTALL") { _, _ ->
                            downloadAsync("Installing " + title) {
                                WebsiteModInstaller.installWithDependencies(this, slug)
                            }
                        }.show()
                }
                result.onSuccess { choices ->
                    val selected = BooleanArray(choices.size) { i ->
                        choices[i].required || choices[i].installed
                    }
                    val labels = choices.map {
                        (if (it.installed) "✓ INSTALLED · " else if (it.required)
                            "REQUIRED · " else "OPTIONAL · ") + it.title
                    }.toTypedArray()
                    val builder = AlertDialog.Builder(this)
                        .setTitle("Dependencies · " + title)
                        .setMessage(if (choices.isEmpty()) "No dependencies listed for this release."
                            else "Required mods stay selected. Already installed mods are skipped. " +
                                "Tap optional mods to include them.")
                        .setNegativeButton("CANCEL", null)
                    if (choices.isNotEmpty()) builder.setMultiChoiceItems(
                        labels, selected
                    ) { dialog, index, checked ->
                        if (choices[index].required || choices[index].installed) {
                            selected[index] = true
                            (dialog as AlertDialog).listView.setItemChecked(index, true)
                        } else selected[index] = checked
                    }
                    builder.setPositiveButton("INSTALL") { _, _ ->
                        val optional = choices.indices.filter {
                            !choices[it].required && !choices[it].installed && selected[it]
                        }.map { choices[it].projectId }.toSet()
                        downloadAsync("Installing " + title) {
                            WebsiteModInstaller.installWithDependencies(
                                this, slug, selectedOptional = optional)
                        }
                    }.show()
                }
            }
        }.start()
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
    private fun displayName(uri:android.net.Uri, fallbackExtension:String=".jar"):String {
        var value:String?=null
        contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
            if(cursor.moveToFirst()) value=cursor.getString(0)
        }
        return value ?: ("imported-" + System.currentTimeMillis() + fallbackExtension)
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
            6 -> {
                val name=displayName(uri,".zip")
                if(!name.endsWith(".zip",true)) {
                    toast("Choose a .zip datapack with pack.mcmeta at its root.")
                    return
                }
                downloadAsync("Import datapack ZIP") {
                    DatapackInstaller.importZip(this,uri,name)
                }
            }
            5 -> {
                val name=displayName(uri).lowercase(Locale.ROOT)
                if(!name.endsWith(".mrpack") && !name.endsWith(".zip")) {
                    toast("Choose a .mrpack or CurseForge .zip file");return
                }
                // Both .mrpack and .zip are ZIP archives. Inspect the manifest, not only the extension.
                if(name.endsWith(".zip")) askCurseForgeKey(uri) else installModpack(uri,null)
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
