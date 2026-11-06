package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** A themed and accessible exact-version selector with no network or install side effects. */
internal object ModrinthVersionPicker {
    internal fun filter(
        versions: List<ModrinthCatalog.Version>,
        channel: String,
        query: String,
        oldestFirst: Boolean = false
    ): List<ModrinthCatalog.Version> {
        val needle=query.trim()
        val visible=versions.filter { version ->
            (channel=="all" || version.releaseType.equals(channel,true)) &&
                (needle.isEmpty() || version.number.contains(needle,true) ||
                    version.title.contains(needle,true) ||
                    version.published.contains(needle,true) ||
                    version.gameVersions.any { it.contains(needle,true) } ||
                    version.loaders.any { it.contains(needle,true) })
        }
        return if(oldestFirst)visible.reversed() else visible
    }

    fun show(
        activity: Activity, projectName: String, gameVersion: String,
        versions: List<ModrinthCatalog.Version>,
        onSelect: (ModrinthCatalog.Version) -> Unit
    ): AlertDialog {
        require(versions.isNotEmpty()) { "Version picker needs releases" }
        val c=activity
        val ui=PocketUi
        val root=ui.column(c).apply {
            background=ui.shape(c,ui.PANEL,14,ui.BORDER)
            setPadding(ui.dp(c,15),ui.dp(c,15),ui.dp(c,15),ui.dp(c,12))
        }
        root.addView(ui.text(c,"Choose "+projectName+" version",17f,ui.WHITE,true))
        root.addView(ui.space(c,5))
        root.addView(ui.text(c,"YOUR SERVER  ·  FABRIC "+gameVersion,12f,ui.CYAN,true))
        root.addView(ui.space(c,7))
        root.addView(ui.text(c,"All Minecraft versions and loaders are visible. Incompatible releases require confirmation.",
            12f,ui.MUTED))
        root.addView(ui.space(c,12))

        val query=EditText(c).apply {
            hint="Find version, Minecraft, loader, date"
            setSingleLine(true)
            textSize=13f
            setTextColor(ui.WHITE)
            setHintTextColor(ui.MUTED)
            background=ui.shape(c,ui.SURFACE,10,ui.BORDER)
            setPadding(ui.dp(c,12),ui.dp(c,9),ui.dp(c,12),ui.dp(c,9))
            contentDescription="Filter Modrinth versions"
        }
        ui.styleInput(query)
        root.addView(query)
        root.addView(ui.space(c,8))
        var selectedChannel="all"
        var oldestFirst=false
        val filterRow=ui.row(c)
        val buttons=mutableListOf<Pair<String,TextView>>()
        val resultCount=ui.text(c,"",11f,ui.MUTED)
        val list=ui.column(c)
        val scroller=ScrollView(c).apply {
            isFillViewport=false
            isVerticalScrollBarEnabled=true
            addView(list)
        }
        val panel=arrayOfNulls<AlertDialog>(1)

        fun render() {
            val visible=filter(versions,selectedChannel,query.text.toString(),oldestFirst)
            resultCount.text=visible.size.toString()+" of "+versions.size+" releases"+
                if(oldestFirst)"  ·  oldest first" else "  ·  newest first"
            buttons.forEach { (id,button) ->
                val selected=id==selectedChannel
                button.setTextColor(if(selected)ui.GREEN else ui.WHITE)
                button.background=ui.pressable(c,
                    ui.shape(c,if(selected)ui.SUBTLE else ui.SURFACE,9,
                        if(selected)ui.GREEN else ui.BORDER))
            }
            list.removeAllViews()
            if(visible.isEmpty()) {
                val empty=ui.text(c,"No matching releases. Clear search or change the filter.",
                    13f,ui.MUTED)
                empty.setPadding(ui.dp(c,8),ui.dp(c,20),ui.dp(c,8),ui.dp(c,20))
                list.addView(empty)
                return
            }
            visible.forEach { version ->
                val card=ui.column(c).apply {
                    background=ui.pressable(c,ui.shape(c,ui.SURFACE,10,ui.BORDER))
                    setPadding(ui.dp(c,11),ui.dp(c,11),ui.dp(c,11),ui.dp(c,11))
                    isClickable=true
                    isFocusable=true
                    contentDescription="Select version "+version.number
                    setOnClickListener {
                        PocketSounds.play(c,PocketSounds.Event.SELECT)
                        panel[0]?.dismiss()
                        onSelect(version)
                    }
                }
                val top=ui.row(c)
                top.addView(ui.text(c,version.number,14f,ui.WHITE,true).apply {
                    maxLines=2
                    ellipsize=android.text.TextUtils.TruncateAt.END
                },LinearLayout.LayoutParams(0,-2,1f))
                val channel=version.releaseType.lowercase(Locale.ROOT)
                val tint=when(channel) {
                    "release" -> ui.GREEN
                    "beta" -> ui.CYAN
                    else -> ui.PURPLE
                }
                top.addView(ui.pill(c,channel.uppercase(Locale.ROOT),tint))
                card.addView(top)
                card.addView(ui.space(c,5))
                card.addView(ui.text(c,"Minecraft: " +
                    version.gameVersions.take(3).joinToString(", ").ifBlank { "unspecified" } +
                    (if(version.gameVersions.size>3) " +"+(version.gameVersions.size-3) else ""),
                    11f,ui.MUTED))
                card.addView(ui.space(c,3))
                card.addView(ui.text(c,"Loader: "+
                    version.loaders.joinToString(", ").ifBlank { "unspecified" } +
                    "  ·  "+version.published,11f,ui.MUTED))
                val warnings=ModrinthCatalog.compatibilityWarnings(version,gameVersion)
                card.addView(ui.space(c,5))
                if(warnings.isNotEmpty()){
                    card.addView(ui.text(c,"⚠ MAY NOT WORK WITH YOUR SERVER",11f,ui.CYAN,true))
                } else {
                    card.addView(ui.text(c,"✓ Matches your Fabric server",11f,ui.GREEN,true))
                }
                if(version.requiredCount>0 || version.optionalCount>0 ||
                    version.incompatibleCount>0) {
                    card.addView(ui.space(c,5))
                    val info=buildList {
                        if(version.requiredCount>0)add(version.requiredCount.toString()+" required")
                        if(version.optionalCount>0)add(version.optionalCount.toString()+" optional")
                        if(version.incompatibleCount>0)add(version.incompatibleCount.toString()+" conflicts")
                    }.joinToString(" · ")
                    card.addView(ui.text(c,"Dependencies: "+info,11f,ui.CYAN))
                }
                list.addView(card,LinearLayout.LayoutParams(-1,-2).apply {
                    bottomMargin=ui.dp(c,8)
                })
            }
        }
        listOf("all" to "ALL","release" to "RELEASE",
            "beta" to "BETA","alpha" to "ALPHA").forEach { (id,label) ->
            val button=ui.text(c,label,10f,ui.WHITE,true).apply {
                gravity=Gravity.CENTER
                minHeight=ui.dp(c,42)
                isClickable=true
                isFocusable=true
                contentDescription="Filter "+label+" versions"
                setOnClickListener {selectedChannel=id;render()}
            }
            buttons.add(id to button)
            filterRow.addView(button,LinearLayout.LayoutParams(0,-2,1f).apply {
                marginEnd=ui.dp(c,3)
            })
        }
        root.addView(filterRow)
        root.addView(ui.space(c,8))
        val tools=ui.row(c)
        tools.addView(resultCount,LinearLayout.LayoutParams(0,-2,1f))
        tools.addView(ui.action(c,"ORDER ⇅"){oldestFirst=!oldestFirst;render()})
        root.addView(tools)
        root.addView(ui.space(c,7))
        root.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(ui.space(c,8))
        root.addView(ui.action(c,"CLOSE"){panel[0]?.dismiss()})
        query.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {render()}
            override fun afterTextChanged(s:Editable?) {}
        })
        val dialog=AlertDialog.Builder(c).setView(root).create()
        panel[0]=dialog
        dialog.setOnShowListener {
            val metrics=c.resources.displayMetrics
            val width=(metrics.widthPixels*0.93f).toInt()
            val maxHeight=(metrics.heightPixels*0.78f).toInt()
            val desiredHeight=ui.dp(c,210+minOf(versions.size,6)*74)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(width,minOf(maxHeight,desiredHeight))
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        render()
        dialog.show()
        return dialog
    }
}
