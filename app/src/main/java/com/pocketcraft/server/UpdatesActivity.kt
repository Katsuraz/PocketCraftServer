package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import java.util.Locale

/** An accessible, in-app update screen. Never downloads an APK without a tap. */
class UpdatesActivity : Activity() {
    private val ui = PocketUi
    private var release: PocketUpdates.Release? = null
    private var verifiedApk: File? = null
    private lateinit var status: TextView
    private lateinit var latest: TextView
    private lateinit var changelog: TextView
    private lateinit var progressText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var checkButton: TextView
    private lateinit var downloadButton: TextView
    private lateinit var installButton: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.loadTheme(this)
        window.statusBarColor = ui.BG
        window.navigationBarColor = ui.BG
        val screen = ui.column(this).apply { setBackgroundColor(ui.BG) }
        screen.addView(HomesteadComponents.subpageHeader(this,
            "App updates","Verified GitHub releases") { finish() })
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = ui.column(this).apply {
            setPadding(ui.dp(this@UpdatesActivity,18),ui.dp(this@UpdatesActivity,24),
                ui.dp(this@UpdatesActivity,18),ui.dp(this@UpdatesActivity,28))
        }
        body.addView(HomesteadComponents.pageIntro(this,
            "RELEASES","Keep PocketCraft updated",
            "Verified updates, changelogs and installation controls."))
        body.addView(ui.space(this,12))
        val channelCard=ui.card(this)
        channelCard.addView(ui.text(this,"RELEASE CHANNEL",14f,ui.WHITE,true))
        channelCard.addView(ui.space(this,9))
        val choices=ui.row(this)
        val selectedChannel=PocketUpdates.channel(this)
        choices.addView(ui.action(this,
            if(selectedChannel==UpdateChannels.STABLE) "✓  STABLE" else "STABLE",
            selectedChannel==UpdateChannels.STABLE) {
            chooseChannel(UpdateChannels.STABLE)
        },LinearLayout.LayoutParams(0,-2,1f))
        choices.addView(ui.space(this,8))
        choices.addView(ui.action(this,
            if(selectedChannel==UpdateChannels.BETA) "✓  BETA" else "BETA",
            selectedChannel==UpdateChannels.BETA) {
            chooseChannel(UpdateChannels.BETA)
        },LinearLayout.LayoutParams(0,-2,1f))
        channelCard.addView(choices)
        channelCard.addView(ui.space(this,8))
        channelCard.addView(ui.text(this,
            if(selectedChannel==UpdateChannels.BETA)
                "Early features • Some issues may remain"
            else "Recommended • Verified stable releases",
            11f,ui.MUTED))
        body.addView(channelCard)
        body.addView(ui.space(this,14))
        val card = ui.card(this)
        card.addView(ui.pill(this,"APP VERSION",ui.GREEN))
        card.addView(ui.space(this,12))
        card.addView(ui.text(this,"Installed: v" + PocketUpdates.installedName(this) +
            " (code " + PocketUpdates.installedVersion(this) + ")",14f,ui.WHITE,true))
        card.addView(ui.space(this,8))
        latest = ui.text(this,"Latest release: not checked",13f,ui.WHITE)
        card.addView(latest)
        card.addView(ui.space(this,8))
        status = ui.text(this,"Check for a published GitHub Release.",12f,ui.MUTED)
        card.addView(status)
        card.addView(ui.space(this,16))
        checkButton = ui.action(this,"CHECK FOR UPDATES",true) { check() }
        card.addView(checkButton)
        card.addView(ui.space(this,10))
        downloadButton = ui.action(this,"DOWNLOAD VERIFIED APK") { download() }
        card.addView(downloadButton)
        card.addView(ui.space(this,10))
        installButton = ui.action(this,"INSTALL VERIFIED APK") { install() }
        card.addView(installButton)
        card.addView(ui.space(this,13))
        progress = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(ui.GREEN)
            progress = 0
        }
        card.addView(progress,LinearLayout.LayoutParams(-1,ui.dp(this,10)))
        card.addView(ui.space(this,6))
        progressText = ui.text(this,"Download starts only when you tap Download.",11f,ui.MUTED)
        card.addView(progressText)
        body.addView(card)

        body.addView(ui.space(this,15))
        val changes = ui.card(this)
        changes.addView(ui.text(this,"WHAT'S NEW",16f,ui.WHITE,true))
        changes.addView(ui.space(this,9))
        changelog = ui.text(this,"Release notes appear here after checking GitHub.",12f,ui.MUTED)
        changes.addView(changelog)
        body.addView(changes)

        body.addView(ui.space(this,15))
        val options = ui.card(this)
        val optionRow = ui.row(this)
        val caption = ui.column(this)
        caption.addView(ui.text(this,"Automatic update checks",14f,ui.WHITE,true))
        caption.addView(ui.text(this,"Check at most once each day. Never automatically install APKs.",11f,ui.MUTED))
        optionRow.addView(caption,LinearLayout.LayoutParams(0,-2,1f))
        optionRow.addView(Switch(this).apply {
            isChecked = PocketUpdates.autoCheckEnabled(this@UpdatesActivity)
            thumbTintList = android.content.res.ColorStateList.valueOf(ui.GREEN)
            setOnCheckedChangeListener { _,isChecked ->
                PocketUpdates.setAutoCheck(this@UpdatesActivity,isChecked)
            }
        })
        options.addView(optionRow)
        options.addView(ui.space(this,12))
        options.addView(ui.text(this,
            "Updates require a GitHub Release with a verified SHA-256 and the same Android signing key. A temporary debug-signed APK cannot replace an installation signed with a different key. Export your worlds before any reinstall.",
            11f,ui.MUTED))
        body.addView(options)
        scroll.addView(body)
        screen.addView(scroll)
        setContentView(screen)
        refreshButtons()
    }

    private fun chooseChannel(channel: String) {
        if(busy) return
        if(PocketUpdates.channel(this)==channel) return
        val apply= {
            PocketUpdates.setChannel(this,channel)
            release=null
            verifiedApk=null
            recreate()
        }
        if(channel==UpdateChannels.BETA) {
            AlertDialog.Builder(this).setTitle("Join the Beta channel?")
                .setMessage("Beta APKs are official but experimental. You can return to Stable " +
                    "for future updates; Android will not downgrade to an older version. " +
                    "Back up worlds before installing a beta.")
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("JOIN BETA") { _,_ -> apply() }.show()
        } else apply()
    }

    private fun refreshButtons() {
        checkButton.isEnabled = !busy
        downloadButton.isEnabled = !busy && release != null &&
            release!!.versionCode > PocketUpdates.installedVersion(this)
        installButton.isEnabled = !busy && verifiedApk?.isFile == true
        for (v in listOf(checkButton,downloadButton,installButton))
            v.alpha = if (v.isEnabled) 1f else .42f
    }

    private fun check() {
        if (busy) return
        busy = true
        status.text = "Checking GitHub Releases…"
        refreshButtons()
        Thread {
            val answer = runCatching { PocketUpdates.fetchLatest(PocketUpdates.channel(this@UpdatesActivity)) }
            runOnUiThread {
                busy = false
                answer.onSuccess { item ->
                    release = item
                    verifiedApk = null
                    latest.text = if (item == null) "No eligible releases found"
                        else "Latest release: v" + item.versionName + " • " + item.published.take(10)
                    changelog.text = item?.notes?.takeIf { it.isNotBlank() }
                        ?: "No release notes available."
                    status.text = when {
                        item == null -> "No published PocketCraft releases yet. You can still use Actions artifacts manually."
                        item.versionCode > PocketUpdates.installedVersion(this) ->
                            "A newer version is available for download."
                        else -> "You are using the latest published release or newer."
                    }
                }.onFailure {
                    status.text = "Cannot verify updates: " + (it.message ?: "Network error")
                }
                refreshButtons()
            }
        }.start()
    }

    private fun download() {
        val item = release ?: return
        if (busy) return
        busy = true
        verifiedApk = null
        status.text = "Downloading and verifying…"
        progress.progress = 0
        refreshButtons()
        Thread {
            val result = runCatching {
                PocketUpdates.download(this,item) { done,total ->
                    runOnUiThread {
                        progress.progress = ((done.toDouble() / total.coerceAtLeast(1)).coerceIn(0.0,1.0)*1000).toInt()
                        progressText.text = String.format(Locale.US,"%.1f / %.1f MB",
                            done/1048576.0,total/1048576.0)
                    }
                }
            }
            runOnUiThread {
                busy = false
                result.onSuccess {
                    verifiedApk = it.file
                    status.text = "APK verified: checksum, package, version and signing certificate match."
                    progressText.text = "Verified APK ready to install."
                }.onFailure {
                    status.text = "Update blocked: " + (it.message ?: "Download failed")
                    progressText.text = "No APK will be installed."
                }
                refreshButtons()
            }
        }.start()
    }

    private fun install() {
        val file = verifiedApk ?: return
        try {
            PocketUpdates.install(this,file)
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Installation needs attention")
                .setMessage(e.message ?: "Unable to open Android installer")
                .setPositiveButton("OK",null).show()
        }
    }
}
