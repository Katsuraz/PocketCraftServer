package com.pocketcraft.server

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import androidx.core.graphics.ColorUtils

/** Theme previews are visual samples, never simulated server telemetry. */
class ThemeGalleryActivity : Activity() {
    private val ui = PocketUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.loadTheme(this)
        window.statusBarColor = ui.BG
        window.navigationBarColor = ui.BG
        val screen = ui.column(this).apply { setBackgroundColor(ui.BG) }
        screen.addView(HomesteadComponents.subpageHeader(this,
            "Appearance Studio", "Themes and layout density") { finish() })
        val scroll = ScrollView(this)
        val body = ui.column(this).apply {
            setPadding(ui.dp(this@ThemeGalleryActivity,16),ui.dp(this@ThemeGalleryActivity,12),
                ui.dp(this@ThemeGalleryActivity,16),ui.dp(this@ThemeGalleryActivity,28))
        }
        body.addView(ui.text(this,"THEME GALLERY",17f,ui.WHITE,true))
        body.addView(ui.space(this,10))
        PocketThemes.themes.forEach { theme ->
            val card = ui.card(this)
            val row = ui.row(this)
            val title = ui.column(this)
            title.addView(ui.text(this,theme.name,15f,ui.WHITE,true))
            title.addView(ui.space(this,4))
            title.addView(ui.text(this,theme.summary,11f,ui.MUTED))
            row.addView(title,LinearLayout.LayoutParams(0,-2,1f))
            if (theme.id == ui.themeId()) row.addView(ui.pill(this,"ACTIVE",ui.GREEN))
            card.addView(row)
            card.addView(ui.space(this,9))
            val swatches = ui.row(this)
            for (color in listOf(theme.palette.bg,theme.palette.panel,theme.palette.surface,
                theme.palette.primary,theme.palette.accent)) {
                val sample = View(this).apply { setBackgroundColor(color) }
                swatches.addView(sample,LinearLayout.LayoutParams(0,ui.dp(this,24),1f).apply {
                    marginEnd = ui.dp(this@ThemeGalleryActivity,3)
                })
            }
            card.addView(swatches)
            card.addView(ui.space(this,9))
            card.addView(ui.action(this,"PREVIEW" ) { preview(theme) })
            body.addView(card)
            body.addView(ui.space(this,10))
        }
        val options=ui.card(this)
        options.addView(ui.text(this,"PERSONALIZE",16f,ui.WHITE,true))
        options.addView(ui.space(this,10))
        options.addView(ui.action(this,"ACCENT COLOR") { accentDialog() })
        options.addView(ui.space(this,8))
        options.addView(ui.action(this,"INTERFACE DENSITY") { densityDialog() })
        options.addView(ui.space(this,8))
        options.addView(ui.action(this,"RESTORE APPEARANCE") {
            AlertDialog.Builder(this).setTitle("Restore appearance?")
                .setMessage("Reset the theme, accent and density only. Your server and worlds are untouched.")
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("RESET") { _,_ ->
                    ui.setAccent(this,null)
                    ui.setDensity(this,"comfortable")
                    ui.setTheme(this,"homestead")
                    setResult(RESULT_OK)
                    recreate()
                }.show()
        })
        body.addView(options)
        scroll.addView(body)
        screen.addView(scroll)
        setContentView(screen)
    }

    private fun preview(theme: PocketThemes.Theme) {
        val p=theme.palette
        val block=ui.column(this).apply {
            setPadding(ui.dp(this@ThemeGalleryActivity,18),ui.dp(this@ThemeGalleryActivity,14),
                ui.dp(this@ThemeGalleryActivity,18),ui.dp(this@ThemeGalleryActivity,14))
            setBackgroundColor(p.bg)
        }
        val header=TextView(this).apply {
            text="POCKETCRAFT  /  "+theme.name.uppercase()
            textSize=17f
            setTextColor(p.text)
            gravity=Gravity.CENTER_VERTICAL
        }
        block.addView(header)
        block.addView(ui.space(this,12))
        val examples=listOf("Server controls","Hosting & Tools","Console")
        examples.forEach {
            val sample=TextView(this).apply {
                text=it+"   ›"
                setTextColor(p.text)
                textSize=14f
                setPadding(ui.dp(this@ThemeGalleryActivity,14),ui.dp(this@ThemeGalleryActivity,13),
                    ui.dp(this@ThemeGalleryActivity,14),ui.dp(this@ThemeGalleryActivity,13))
                background=GradientDrawable().apply {
                    setColor(p.panel)
                    cornerRadius=ui.dp(this@ThemeGalleryActivity,10).toFloat()
                    setStroke(ui.dp(this@ThemeGalleryActivity,1),p.border)
                }
            }
            block.addView(sample)
            block.addView(ui.space(this,8))
        }
        val tip=TextView(this).apply {
            text="Design preview only • No live server data"
            textSize=11f
            setTextColor(p.muted)
        }
        block.addView(tip)
        AlertDialog.Builder(this).setTitle(theme.name).setView(block)
            .setNegativeButton("BACK",null)
            .setPositiveButton("APPLY") { _,_ ->
                ui.setTheme(this,theme.id)
                setResult(RESULT_OK)
                recreate()
            }.show()
    }

    private fun accentDialog() {
        val field=EditText(this).apply {
            hint="#B79864"
            setSingleLine(true)
            setText(getSharedPreferences("pocketcraft",0).getString("uiAccent","") ?: "")
            inputType=android.text.InputType.TYPE_CLASS_TEXT
        }
        val wrapper=ui.column(this).apply {
            setPadding(ui.dp(this@ThemeGalleryActivity,20),ui.dp(this@ThemeGalleryActivity,6),
                ui.dp(this@ThemeGalleryActivity,20),ui.dp(this@ThemeGalleryActivity,8))
            addView(field)
        }
        val dialog=AlertDialog.Builder(this).setTitle("Custom accent")
            .setView(wrapper).setNeutralButton("RESET") { _,_ ->
                ui.setAccent(this,null)
                setResult(RESULT_OK); recreate()
            }.setNegativeButton("CANCEL",null)
            .setPositiveButton("SAVE",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw=field.text.toString().trim()
                val color=PocketThemes.parseAccent(raw)
                val background=PocketThemes.palette(ui.themeId()).bg
                if(color==null || ColorUtils.calculateContrast(color,background)<4.5) {
                    field.error="Use #RRGGBB with readable contrast against the background"
                    return@setOnClickListener
                }
                ui.setAccent(this,raw)
                dialog.dismiss()
                setResult(RESULT_OK); recreate()
            }
        }
        dialog.show()
    }

    private fun densityDialog() {
        val choices=listOf("Comfortable","Compact","Minimal")
        val selected=choices.indexOfFirst { it.equals(ui.density(),true) }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Interface density")
            .setSingleChoiceItems(choices.toTypedArray(),selected) { d,index ->
                ui.setDensity(this,choices[index].lowercase())
                d.dismiss()
                setResult(RESULT_OK); recreate()
            }.setNegativeButton("CANCEL",null).show()
    }
}
