package com.pocketcraft.server

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Homestead 2: a single mobile-first UI kit for every management screen.
 *
 * Intentionally contains no server, storage, credential or networking logic.
 * Screens use their existing handlers; the hosting engine remains untouched.
 */
internal object HomesteadNavigation {
    val labels = listOf("Home", "Worlds", "Mods", "Console")
    val subtitles = listOf(
        "Your server at a glance",
        "Your worlds and their settings",
        "Explore and manage additions",
        "Commands and live diagnostics"
    )
    val icons = listOf(
        R.drawable.pc_home, R.drawable.pc_worlds,
        R.drawable.pc_mods, R.drawable.pc_console
    )
    // MainActivity keeps its existing page IDs (0=Home, 1=Mods, 2=Worlds,
    // 3=Console), so the redesign never breaks deep links or existing handlers.
    private val pagesBySlot = listOf(0, 2, 1, 3)
    fun pageForSlot(slot: Int): Int = pagesBySlot.getOrElse(slot) { 0 }
    fun slotForPage(page: Int): Int = pagesBySlot.indexOf(page).takeIf { it >= 0 } ?: 0
    fun label(page: Int): String = labels[slotForPage(page)]
    fun subtitle(page: Int): String = subtitles[slotForPage(page)]
    fun validTab(page: Int): Int = page.coerceIn(0, labels.lastIndex)
}

internal object HomesteadComponents {
    private val ui = PocketUi
    private fun d(c: Context, value: Int) = ui.dp(c, value)

    fun masthead(c: Context, destination: String, onSettings: () -> Unit): LinearLayout {
        val bar = ui.row(c).apply {
            background = ui.shape(c, ui.SUBTLE, 0)
            setPadding(d(c, 18), d(c, 13), d(c, 14), d(c, 13))
            minimumHeight = d(c, 70)
        }
        val logo = ImageView(c).apply {
            setImageResource(R.drawable.pocket_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ui.shape(c, ui.SURFACE, 12, ui.BORDER)
            setPadding(d(c, 4), d(c, 4), d(c, 4), d(c, 4))
            contentDescription = "PocketCraft logo"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        bar.addView(logo, LinearLayout.LayoutParams(d(c, 43), d(c, 43)))
        val heading = ui.column(c).apply {
            setPadding(d(c, 12), 0, d(c, 6), 0)
            addView(ui.text(c, "POCKETCRAFT", 17f, ui.WHITE, true))
            addView(ui.space(c, 4))
            addView(ui.text(c, "HOMESTEAD  /  ${destination.uppercase()}", 9.5f, ui.CYAN, true))
        }
        bar.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = ImageView(c).apply {
            setImageResource(R.drawable.pc_settings)
            setColorFilter(ui.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(d(c, 12), d(c, 12), d(c, 12), d(c, 12))
            background = ui.pressable(c, ui.shape(c, ui.SURFACE, 13, ui.BORDER))
            contentDescription = "Open global server settings"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                PocketSounds.play(c, PocketSounds.Event.CLICK)
                onSettings()
            }
        }
        bar.addView(settings, LinearLayout.LayoutParams(d(c, 46), d(c, 46)))
        return bar
    }

    fun subpageHeader(
        c: Context, title: String, subtitle: String, onBack: () -> Unit
    ): LinearLayout {
        val row=ui.row(c).apply {
            background=ui.shape(c,ui.SUBTLE,0)
            setPadding(d(c,15),d(c,13),d(c,17),d(c,13))
            minimumHeight=d(c,68)
        }
        val back=ui.text(c,"‹",31f,ui.GREEN,true).apply {
            gravity=Gravity.CENTER
            background=ui.pressable(c,ui.shape(c,ui.SURFACE,12,ui.BORDER))
            contentDescription="Back"
            isFocusable=true
            isClickable=true
            setOnClickListener {
                PocketSounds.play(c,PocketSounds.Event.CLICK)
                onBack()
            }
        }
        row.addView(back,LinearLayout.LayoutParams(d(c,46),d(c,46)))
        row.addView(ui.column(c).apply {
            setPadding(d(c,13),0,0,0)
            addView(ui.text(c,title,18f,ui.WHITE,true))
            addView(ui.space(c,5))
            addView(ui.text(c,subtitle.uppercase(),10f,ui.CYAN,true))
        },LinearLayout.LayoutParams(0,-2,1f))
        return row
    }

    fun bottomDock(c: Context, selectedTab: Int, onNavigate: (Int) -> Unit): LinearLayout {
        val dock = ui.row(c).apply {
            background = ui.shape(c, ui.PANEL, 0, ui.BORDER)
            setPadding(d(c, 8), d(c, 9), d(c, 8), d(c, 10))
            minimumHeight = d(c, 75)
        }
        HomesteadNavigation.labels.forEachIndexed { index, name ->
            val active = HomesteadNavigation.slotForPage(selectedTab) == index
            val tint = if (active) ui.GREEN else ui.MUTED
            val button = ui.column(c).apply {
                gravity = Gravity.CENTER
                minimumHeight = d(c, 57)
                background = ui.pressable(c, ui.shape(c,
                    if (active) ui.SURFACE else ui.PANEL, 12, if (active) ui.BORDER else null))
                isClickable = true
                isFocusable = true
                isSelected = active
                contentDescription = "$name tab" + if(active) ", selected" else ""
                setOnClickListener {
                    if (!active) {
                        PocketSounds.play(c, PocketSounds.Event.SELECT)
                        onNavigate(HomesteadNavigation.pageForSlot(index))
                    }
                }
            }
            val indicator = View(c).apply {
                background = ui.shape(c, if (active) ui.GREEN else ui.PANEL, 3)
            }
            button.addView(indicator, LinearLayout.LayoutParams(d(c, 25), d(c, 3)))
            button.addView(ui.space(c, 6))
            val image = ImageView(c).apply {
                setImageResource(HomesteadNavigation.icons[index])
                setColorFilter(tint)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            button.addView(image, LinearLayout.LayoutParams(d(c, 22), d(c, 22)))
            button.addView(ui.space(c, 5))
            button.addView(ui.text(c, name, 10f, tint, active).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                maxLines = 1
            })
            dock.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return dock
    }

    fun pageIntro(c: Context, eyebrow: String, heading: String, sub: String): LinearLayout =
        ui.column(c).apply {
            addView(ui.text(c, eyebrow.uppercase(), 10f, ui.CYAN, true))
            addView(ui.space(c, 8))
            addView(ui.text(c, heading, 23f, ui.WHITE, true))
            addView(ui.space(c, 6))
            addView(ui.text(c, sub, 12f, ui.MUTED))
        }

    fun sectionHeading(c: Context, title: String, subtitle: String = ""): LinearLayout =
        ui.column(c).apply {
            addView(ui.text(c, title, 17f, ui.WHITE, true))
            if (subtitle.isNotBlank()) {
                addView(ui.space(c, 5))
                addView(ui.text(c, subtitle, 11f, ui.MUTED))
            }
        }

    /** A keyboard/touch accessible row; the chevron is intentionally not a second button. */
    fun menuRow(
        c: Context, symbol: String, title: String, detail: String,
        caption: String = "", enabled: Boolean = true, click: () -> Unit
    ): LinearLayout {
        val row = ui.row(c).apply {
            background = ui.pressable(c, ui.shape(c, ui.PANEL, 13, ui.BORDER))
            setPadding(d(c, 13), d(c, 14), d(c, 13), d(c, 14))
            minimumHeight = d(c, 66)
            isClickable = enabled
            isFocusable = enabled
            alpha = if(enabled) 1f else .48f
            contentDescription = listOf(title,detail,caption).filter { it.isNotBlank() }.joinToString(", ")
            if(enabled) setOnClickListener {
                PocketSounds.play(c, PocketSounds.Event.CLICK)
                click()
            }
        }
        val symbolView = ui.text(c, symbol, 22f, ui.CYAN, true).apply {
            gravity = Gravity.CENTER
            background = ui.shape(c, ui.SUBTLE, 10, ui.BORDER)
        }
        row.addView(symbolView, LinearLayout.LayoutParams(d(c, 43), d(c, 45)))
        val body = ui.column(c).apply {
            setPadding(d(c, 12), 0, d(c, 5), 0)
            addView(ui.text(c,title,14f,ui.WHITE,true))
            addView(ui.space(c,5))
            addView(ui.text(c,detail,11f,ui.MUTED))
        }
        row.addView(body,LinearLayout.LayoutParams(0,-2,1f))
        if(caption.isNotEmpty()) {
            row.addView(ui.text(c,caption,10f,ui.GREEN,true))
        } else {
            row.addView(ui.text(c,"›",23f,ui.CYAN,true).apply {
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        return row
    }

    fun shortcut(
        c: Context, symbol: String, title: String, detail: String, click: () -> Unit
    ): LinearLayout {
        val tile = ui.column(c).apply {
            background = ui.pressable(c, ui.shape(c, ui.PANEL, 14, ui.BORDER))
            setPadding(d(c, 13), d(c, 15), d(c, 12), d(c, 14))
            minimumHeight = d(c, 118)
            isFocusable = true
            isClickable = true
            contentDescription = "$title, $detail"
            setOnClickListener {
                PocketSounds.play(c,PocketSounds.Event.CLICK)
                click()
            }
            addView(ui.text(c,symbol,22f,ui.CYAN,true))
            addView(ui.space(c,11))
            addView(ui.text(c,title,13f,ui.WHITE,true))
            addView(ui.space(c,5))
            addView(ui.text(c,detail,10f,ui.MUTED))
        }
        return tile
    }

    fun tileRow(
        c: Context,
        a: LinearLayout,
        b: LinearLayout
    ): LinearLayout = ui.row(c).apply {
        addView(a,LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=d(c,5) })
        addView(b,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=d(c,5) })
    }

    fun statusLabel(c: Context, title: String, value: String, tint: Int): LinearLayout =
        ui.column(c).apply {
            background = ui.shape(c, ui.SUBTLE, 11, ui.BORDER)
            setPadding(d(c,12),d(c,12),d(c,12),d(c,12))
            addView(ui.text(c,title.uppercase(),10f,ui.MUTED,true))
            addView(ui.space(c,7))
            addView(ui.text(c,value,13f,tint,true))
        }
}
