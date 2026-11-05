package com.pocketcraft.server

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PocketCraft design system.
 *
 * Flat graphite surfaces, warm neutral highlights, uncluttered controls,
 * and native Android ripple/focus feedback. No decorative textures.
 */
object PocketUi {
    /**
     * Original PocketCraft themes. Homestead is an inspired woodland look,
     * drawn in code; no third-party game resources or textures are bundled.
     * Save preference under UI-only settings, never Minecraft server.properties.
     */
    private var currentTheme = "homestead"
    private var pixelFonts = true
    private var normalTypeface: Typeface? = null
    private var headingTypeface: Typeface? = null
    private var loadedFontPath: String? = null

    /** The user may import their own licensed Mojangles .ttf/.otf. */
    private fun initFonts(c: Context) {
        val custom = java.io.File(c.filesDir, "ui-fonts/mojangles.ttf")
        val path = if (custom.isFile) custom.absolutePath else "bundled-monocraft"
        if (normalTypeface != null && loadedFontPath == path) return
        normalTypeface = if (custom.isFile) runCatching {
            Typeface.createFromFile(custom)
        }.getOrNull() else null
        if (normalTypeface == null) normalTypeface = runCatching {
            Typeface.createFromAsset(c.assets,"fonts/Monocraft.ttf")
        }.getOrElse { Typeface.MONOSPACE }
        headingTypeface = normalTypeface
        loadedFontPath = path
    }
    fun hasCustomMojangles(c: Context): Boolean =
        java.io.File(c.filesDir,"ui-fonts/mojangles.ttf").isFile
    /** Validation and import are performed from Settings on a worker thread. */
    fun importMojangles(c: Context, uri: android.net.Uri) {
        val folder = java.io.File(c.filesDir,"ui-fonts").apply { mkdirs() }
        val temp = java.io.File(folder,"incoming-font.tmp")
        try {
            c.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(16384)
                    var count = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count <= 2_000_000L) { "Font file is too large (2 MB maximum)" }
                        output.write(buffer,0,n)
                    }
                }
            } ?: error("Could not read selected font")
            require(temp.length() in 1L..2_000_000L) { "Font is empty or too large" }
            // Malformed fonts must be rejected before replacing a working font.
            Typeface.createFromFile(temp)
            val target = java.io.File(folder,"mojangles.ttf")
            if (target.exists()) require(target.delete()) { "Cannot replace previous custom font" }
            require(temp.renameTo(target)) { "Could not save custom font" }
            loadedFontPath = null
            normalTypeface = null
            headingTypeface = null
        } finally { temp.delete() }
    }
    fun removeCustomMojangles(c: Context) {
        java.io.File(c.filesDir,"ui-fonts/mojangles.ttf").delete()
        loadedFontPath = null
        normalTypeface = null
        headingTypeface = null
    }
    fun pixelFont(): Boolean = pixelFonts
    fun setFontStyle(c: Context, pixel: Boolean) {
        pixelFonts = pixel
        c.getSharedPreferences("pocketcraft",0).edit().putBoolean("pixelFonts",pixel).apply()
    }
    fun styleInput(v: TextView) {
        if (pixelFonts) {
            initFonts(v.context)
            v.typeface = normalTypeface
        } else v.typeface = Typeface.DEFAULT
    }
    fun styleFormInputs(view: View) {
        if (view is EditText || view is Switch) styleInput(view as TextView)
        if (view is ViewGroup) for (index in 0 until view.childCount)
            styleFormInputs(view.getChildAt(index))
    }

    fun loadTheme(c: Context) {
        val prefs = c.getSharedPreferences("pocketcraft",0)
        currentTheme = prefs.getString("uiTheme","homestead")
            .let { if (it=="classic") "classic" else "homestead" }
        pixelFonts = prefs.getBoolean("pixelFonts",true)
        if (pixelFonts) initFonts(c)
    }
    fun setTheme(c: Context, theme: String) {
        require(theme=="homestead" || theme=="classic")
        c.getSharedPreferences("pocketcraft",0).edit().putString("uiTheme",theme).apply()
        currentTheme = theme
    }
    fun homestead(): Boolean = currentTheme=="homestead"

    // Restrained oak and parchment colors keep the Homestead identity while
    // avoiding oversized textures, gradients and neon accents.
    val BG: Int get() = if(homestead()) 0xFF1B1714.toInt() else 0xFF080E18.toInt()
    val PANEL: Int get() = if(homestead()) 0xFF2A211C.toInt() else 0xFF141E2C.toInt()
    val SURFACE: Int get() = if(homestead()) 0xFF3B2E25.toInt() else 0xFF223245.toInt()
    val MUTED: Int get() = if(homestead()) 0xFFC1B49F.toInt() else 0xFFACBED1.toInt()
    val WHITE: Int get() = if(homestead()) 0xFFF4EADB.toInt() else 0xFFF6F9FD.toInt()
    val GREEN: Int get() = if(homestead()) 0xFFD9B776.toInt() else 0xFF70E9B4.toInt()
    val CYAN: Int get() = if(homestead()) 0xFFD9B776.toInt() else 0xFF80D7FA.toInt()
    val PURPLE: Int get() = if(homestead()) 0xFFCBAA83.toInt() else 0xFFB5A5FF.toInt()
    val RED: Int get() = if(homestead()) 0xFFF0AA8A.toInt() else 0xFFFFA0AB.toInt()
    val BORDER: Int get() = if(homestead()) 0xFF594536.toInt() else 0xFF324459.toInt()
    val SUBTLE: Int get() = if(homestead()) 0xFF241D18.toInt() else 0xFF182737.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + .5f).toInt()

    fun shape(c: Context, color: Int, radius: Int = 16, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(c, radius).toFloat()
            if (stroke != null) setStroke(dp(c, 1), stroke)
        }

    // Compatibility for screens that previously requested colorful gradients.
    fun gradient(c: Context, vararg colors: Int): GradientDrawable =
        shape(c, if (homestead()) PANEL else (colors.firstOrNull() ?: PANEL), 16, BORDER)

    fun column(c: Context, gap: Int = 0): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        if (gap > 0) setPadding(dp(c, gap), dp(c, gap), dp(c, gap), dp(c, gap))
    }

    fun text(c: Context, value: String, size: Float = 14f, color: Int = WHITE, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (pixelFonts) {
                initFonts(c)
                typeface = Typeface.create(normalTypeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
                letterSpacing = if (bold) 0.012f else 0f
            } else if (bold) {
                typeface = Typeface.create("sans-serif-medium",Typeface.BOLD)
            }
            includeFontPadding = false
            setLineSpacing(dp(c, 2).toFloat(), 1.03f)
        }

    fun pad(c: Context, l: Int, t: Int, r: Int, b: Int) =
        intArrayOf(dp(c,l), dp(c,t), dp(c,r), dp(c,b))

    fun View.padding(c: Context, l: Int, t: Int, r: Int, b: Int) {
        setPadding(dp(c,l), dp(c,t), dp(c,r), dp(c,b))
    }

    fun space(c: Context, h: Int): View =
        View(c).apply { layoutParams = LinearLayout.LayoutParams(1, dp(c,h)) }

    fun card(c: Context): LinearLayout = column(c).apply {
        background = shape(c, PANEL, 14, BORDER)
        padding(c, 16, 16, 16, 16)
    }

    fun pill(c: Context, label: String, tint: Int = CYAN): TextView =
        text(c, label, 11f, tint, true).apply {
            val transparentTint = Color.argb(32,Color.red(tint),Color.green(tint),Color.blue(tint))
            val tintBorder = Color.argb(74,Color.red(tint),Color.green(tint),Color.blue(tint))
            background = shape(c, transparentTint, 10, tintBorder)
            padding(c, 10, 7, 10, 7)
            gravity = Gravity.CENTER
        }

    /** Material-like touch state, including keyboard and accessibility semantics. */
    fun pressable(c: Context, background: android.graphics.drawable.Drawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x22FFFFFF),background,null)

    fun action(c: Context, label: String, primary: Boolean = false, enabledAction: Boolean = true, click: () -> Unit): TextView =
        text(c, label, 13f, if (primary) BG else WHITE, true).apply {
            gravity = Gravity.CENTER
            background = pressable(c, if (primary) shape(c, GREEN, 12)
                else shape(c, SURFACE, 12, BORDER))
            padding(c, 16, 15, 16, 15)
            minHeight = dp(c, 52)
            isEnabled = enabledAction
            alpha = if (enabledAction) 1f else .48f
            isClickable = enabledAction
            isFocusable = enabledAction
            if (enabledAction) setOnClickListener {
                PocketSounds.play(c, PocketSounds.Event.CLICK)
                click()
            }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                }
            }
        }

    fun row(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun weight(c: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(c, 8) }
}
