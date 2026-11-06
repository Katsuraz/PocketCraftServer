package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Native, theme-aware Modrinth release picker. Only versions supplied by the
 * already validated catalog are shown. No installation starts without a tap.
 */
internal object ModrinthVersionDialog {
    fun show(activity: Activity, project: String, minecraft: String,
             versions: List<ModrinthCatalog.Version>,
             onSelect: (ModrinthCatalog.Version) -> Unit): AlertDialog {
        val ui = PocketUi
        val panel = ui.column(activity).apply {
            setPadding(ui.dp(activity,16),ui.dp(activity,12),
                ui.dp(activity,16),ui.dp(activity,4))
        }
        panel.addView(ui.text(activity,"Choose " + project + " version",
            18f,ui.WHITE,true))
        panel.addView(ui.space(activity,6))
        panel.addView(ui.text(activity,
            "Fabric " + minecraft + " · Choose a release to review its dependencies.",
            12f,ui.MUTED))
        panel.addView(ui.space(activity,12))
        val search = EditText(activity).apply {
            hint = "Search version numbers"
            contentDescription = "Search available versions"
            setSingleLine(true)
            textSize = 13f
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background = ui.shape(activity,ui.SURFACE,10,ui.BORDER)
            setPadding(ui.dp(activity,11),ui.dp(activity,9),
                ui.dp(activity,11),ui.dp(activity,9))
        }
        panel.addView(search)
        panel.addView(ui.space(activity,10))
        val buttons = linkedMapOf<String,TextView>()
        val filters = ui.row(activity)
        panel.addView(filters)
        panel.addView(ui.space(activity,9))
        val count = ui.text(activity,"",11f,ui.CYAN,true)
        panel.addView(count)
        panel.addView(ui.space(activity,8))
        val entries = ui.column(activity)
        val scrolling = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            addView(entries)
        }
        val listHeight = minOf(ui.dp(activity,385),
            (activity.resources.displayMetrics.heightPixels * 0.45f).toInt())
            .coerceAtLeast(ui.dp(activity,125))
        panel.addView(scrolling,LinearLayout.LayoutParams(-1,listHeight))
        val dialog = AlertDialog.Builder(activity).setView(panel)
            .setNegativeButton("CLOSE",null).create()
        var selected = "ALL"
        fun refresh() {
            val query = search.text.toString().trim()
            val visible = versions.filter {
                (selected == "ALL" || it.releaseType.equals(selected,true)) &&
                    (query.isEmpty() || it.number.contains(query,true) ||
                        it.title.contains(query,true))
            }
            count.text = "SHOWING " + visible.size + " OF " + versions.size + " VERSIONS"
            buttons.forEach { (channel,button) ->
                button.setTextColor(if (selected == channel) ui.GREEN else ui.MUTED)
                button.isSelected = selected == channel
                button.contentDescription = channel + " versions" +
                    if (selected == channel) ", selected" else ""
            }
            entries.removeAllViews()
            if (visible.isEmpty()) {
                entries.addView(ui.text(activity,
                    "No matching releases. Try a different version or release type.",
                    13f,ui.MUTED))
            }
            visible.forEach { version ->
                val card = ui.card(activity)
                val top = ui.row(activity)
                top.addView(ui.text(activity,version.number,15f,ui.WHITE,true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },LinearLayout.LayoutParams(0,-2,1f))
                val tint = when (version.releaseType.lowercase(Locale.ROOT)) {
                    "release" -> ui.GREEN
                    "beta" -> ui.CYAN
                    else -> ui.PURPLE
                }
                top.addView(ui.pill(activity,version.releaseType.uppercase(Locale.ROOT),tint))
                card.addView(top)
                card.addView(ui.space(activity,7))
                card.addView(ui.text(activity,
                    "Published " + version.published + " · " +
                        version.downloads + " downloads",11f,ui.MUTED))
                val declared = version.requiredCount + version.optionalCount +
                    version.incompatibleCount
                if (declared > 0) {
                    card.addView(ui.space(activity,5))
                    val dependencies = version.requiredCount.toString() + " required · " +
                        version.optionalCount + " optional" +
                        (if (version.incompatibleCount > 0)
                            " · " + version.incompatibleCount + " incompatible"
                        else "")
                    card.addView(ui.text(activity,dependencies,11f,ui.CYAN))
                }
                card.addView(ui.space(activity,9))
                card.addView(ui.action(activity,"SELECT VERSION",true) {
                    dialog.dismiss()
                    onSelect(version)
                }.apply {
                    contentDescription = "Select " + project + " version " + version.number
                })
                entries.addView(card)
                entries.addView(ui.space(activity,9))
            }
            scrolling.scrollTo(0,0)
        }
        listOf("ALL","RELEASE","BETA","ALPHA").forEach { channel ->
            val button = ui.action(activity,channel) {
                selected = channel
                refresh()
            }
            buttons[channel] = button
            filters.addView(button,LinearLayout.LayoutParams(0,-2,1f))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                refresh()
            }
            override fun afterTextChanged(s:Editable?) {}
        })
        ui.styleFormInputs(panel)
        refresh()
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels*0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT)
        return dialog
    }
}
