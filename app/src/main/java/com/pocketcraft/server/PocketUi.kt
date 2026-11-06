package com.pocketcraft.server

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.ColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
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
 * Restrained graphite surfaces, generous spacing, prominent single-call-to-action
 * controls and true Android ripple/focus feedback. No network or theme dependency.
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

    private var activePalette = PocketThemes.palette("homestead")
    private var densityMode = "comfortable"

    fun themeId(): String = currentTheme
    fun themeName(): String = PocketThemes.selected(currentTheme).name
    fun density(): String = densityMode

    fun loadTheme(c: Context) {
        val prefs = c.getSharedPreferences("pocketcraft",0)
        currentTheme = PocketThemes.selected(prefs.getString("uiTheme","homestead")).id
        activePalette = PocketThemes.palette(currentTheme)
        if (currentTheme == "material" && android.os.Build.VERSION.SDK_INT >= 31) {
            // Android system accents; fallback remains available on older phones.
            val accent = runCatching { c.getColor(android.R.color.system_accent1_600) }.getOrNull()
            val primary = runCatching { c.getColor(android.R.color.system_accent2_700) }.getOrNull()
            if (accent != null && primary != null)
                activePalette = activePalette.copy(primary=primary,accent=accent)
        }
        PocketThemes.parseAccent(prefs.getString("uiAccent",null))?.let {
            activePalette = activePalette.copy(primary=it,accent=it)
        }
        densityMode = prefs.getString("uiDensity","comfortable")
            .takeIf { it in setOf("comfortable","compact","minimal") } ?: "comfortable"
        pixelFonts = prefs.getBoolean("pixelFonts",true)
        if (pixelFonts) initFonts(c)
    }

    fun setTheme(c: Context, theme: String) {
        require(PocketThemes.valid(theme)) { "Unknown PocketCraft theme" }
        c.getSharedPreferences("pocketcraft",0).edit().putString("uiTheme",theme).apply()
        loadTheme(c)
    }

    fun setAccent(c: Context, value: String?) {
        require(value.isNullOrBlank() || PocketThemes.parseAccent(value) != null) {
            "Enter an RGB hex color like #406080"
        }
        c.getSharedPreferences("pocketcraft",0).edit()
            .putString("uiAccent",value?.takeIf { it.isNotBlank() }).apply()
        loadTheme(c)
    }

    fun setDensity(c: Context, density: String) {
        require(density in setOf("comfortable","compact","minimal"))
        c.getSharedPreferences("pocketcraft",0).edit().putString("uiDensity",density).apply()
        loadTheme(c)
    }

    fun homestead(): Boolean = currentTheme == "homestead"
    fun pixelStyle(): Boolean = currentTheme in setOf("homestead","minecraft")
    val BG: Int get() = activePalette.bg
    val PANEL: Int get() = activePalette.panel
    val SURFACE: Int get() = activePalette.surface
    val MUTED: Int get() = activePalette.muted
    val WHITE: Int get() = activePalette.text
    val GREEN: Int get() = activePalette.primary
    val CYAN: Int get() = activePalette.accent
    val PURPLE: Int get() = activePalette.purple
    val RED: Int get() = activePalette.error
    val BORDER: Int get() = activePalette.border
    val SUBTLE: Int get() = activePalette.subtle

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + .5f).toInt()

    fun shape(c: Context, color: Int, radius: Int = 18, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            if (homestead() && (color==PANEL || color==SURFACE)) {
                setColors(if (color==PANEL)
                    intArrayOf(0xFF3D2D24.toInt(), 0xFF28211C.toInt())
                else intArrayOf(0xFF75583D.toInt(), 0xFF553E2B.toInt()))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            } else setColor(color)
            cornerRadius = dp(c, when (currentTheme) {\n                "minecraft" -> 4\n                "obsidian", "minimal" -> radius.coerceAtMost(8)\n                "homestead" -> radius.coerceAtMost(15)\n                else -> radius\n            }).toFloat()
            if (stroke != null) setStroke(dp(c, 1), stroke)
        }

    fun gradient(c: Context, vararg colors: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (homestead()) intArrayOf(0xFF846347.toInt(),0xFF4D3428.toInt())
            else colors).apply {
            cornerRadius = dp(c, if(homestead()) 14 else 24).toFloat()
            if (homestead()) setStroke(dp(c,2),0xFFA07D50.toInt())
        }

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
        background = if (homestead()) TimberDrawable(c, false, true)
            else shape(c, PANEL, 22, BORDER)
        padding(c, 14, 14, 14, 14)
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
        RippleDrawable(ColorStateList.valueOf(0x40FFFFFF),background,null)

    fun action(c: Context, label: String, primary: Boolean = false, enabledAction: Boolean = true, click: () -> Unit): TextView =
        text(c, label, 12f, if (primary) BG else WHITE, true).apply {
            gravity = Gravity.CENTER
            background = pressable(c,if (homestead()) TimberDrawable(c, primary, false)
                else if (primary) gradient(c, GREEN, CYAN)
                else shape(c, SURFACE, 14, BORDER))
            padding(c, 12, 11, 12, 11)
            minHeight = dp(c, 48)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
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

/** A tiny, original block-world landscape drawn with rectangles (no game assets). */
class WorldArt(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), PocketUi.dp(context, 116))
    }
    private fun paintHomestead(canvas: Canvas) {
        val u = width / 28f
        val h = height.toFloat()
        val grass = h * 0.74f
        paint.color = 0xFF3A4534.toInt()
        canvas.drawRect(0f,0f,width.toFloat(),h,paint)
        paint.color = 0xFF7E8B5D.toInt()
        canvas.drawCircle(width * 0.79f,h * .23f, u * 1.7f,paint)
        // Layered timber silhouettes create a calm handmade woodland scene.
        for(i in 0..7) {
            val x = (i * 4.1f - 1f) * u
            paint.color = 0xFF303D2D.toInt()
            canvas.drawRect(x+1.4f*u,grass-5.9f*u,x+2f*u,grass+0.9f*u,paint)
            paint.color = if(i%2==0) 0xFF5F7950.toInt() else 0xFF516B49.toInt()
            canvas.drawCircle(x+1.8f*u,grass-6.7f*u,2.9f*u,paint)
            paint.color = 0xFF789267.toInt()
            canvas.drawCircle(x+0.6f*u,grass-6.2f*u,1.5f*u,paint)
        }
        paint.color = 0xFF678157.toInt()
        canvas.drawRect(0f,grass,width.toFloat(),h,paint)
        // Small original homestead cabin — timber roof, stone base, lit window.
        val x = 11f*u
        paint.color = 0xFF66513B.toInt()
        canvas.drawRect(x,grass-5.2f*u,x+7.5f*u,grass+2f*u,paint)
        paint.color = 0xFF9A7954.toInt()
        canvas.drawRect(x+0.4f*u,grass-4.6f*u,x+7.1f*u,grass+1.7f*u,paint)
        paint.color = 0xFF453C32.toInt()
        canvas.drawRect(x-0.8f*u,grass-6.0f*u,x+8.3f*u,grass-4.9f*u,paint)
        paint.color = 0xFFB89A70.toInt()
        canvas.drawRect(x+1.6f*u,grass-3.8f*u,x+3.2f*u,grass-2.1f*u,paint)
        paint.color = 0xFFFFD98F.toInt()
        canvas.drawRect(x+1.82f*u,grass-3.55f*u,x+2.94f*u,grass-2.31f*u,paint)
        paint.color = 0xFF3B3929.toInt()
        canvas.drawRect(x+4.8f*u,grass-3.6f*u,x+6.3f*u,grass+2f*u,paint)
        paint.color = 0xFFA2A17D.toInt()
        for(i in 0..27) {
            val xx = i * u
            val lift = if(i%4==0) 1.1f*u else 0.5f*u
            canvas.drawRect(xx,grass+2.3f*u-lift,xx+0.45f*u,grass+2.4f*u,paint)
        }
    }
    override fun onDraw(canvas: Canvas) {
        if (PocketUi.homestead()) {
            paintHomestead(canvas)
            return
        }
        val unit = width / 25f
        val ground = height * .72f
        paint.color = PocketUi.CYAN and 0x33FFFFFF
        for (i in 0..4) {
            val x = width * (.08f + i * .22f)
            canvas.drawCircle(x, height * .25f, unit * (if (i % 2 == 0) 1.3f else .7f), paint)
        }
        val grass = 0xFF68E2AA.toInt()
        val dirt = 0xFF316A67.toInt()
        val shadow = 0xFF234F66.toInt()
        for (i in 0..24) {
            val terrain = if (i in 5..9) 3 else if (i in 15..17) 2 else 1
            paint.color = dirt
            canvas.drawRect(i*unit, ground - terrain*unit, (i+1)*unit+1, height.toFloat(), paint)
            paint.color = grass
            canvas.drawRect(i*unit, ground - terrain*unit, (i+1)*unit+1, ground - (terrain-0.45f)*unit, paint)
            if (i % 4 == 0) {
                paint.color = shadow
                canvas.drawRect(i*unit, ground + .5f*unit, (i+1)*unit, ground + 1.1f*unit, paint)
            }
        }
        paint.color = PocketUi.PURPLE
        canvas.drawRect(unit*19, ground-unit*5, unit*20.8f, ground-unit*3, paint)
        paint.color = PocketUi.CYAN
        canvas.drawRect(unit*19.5f, ground-unit*6.3f, unit*20.3f, ground-unit*4, paint)
        paint.color = PocketUi.WHITE
        canvas.drawRect(unit*19.7f, ground-unit*5.6f, unit*20.1f, ground-unit*5.2f, paint)
    }
}


/**
 * Original timber-panel styling: carved double rims, plank seams and subtle
 * grain. Pure Android drawing; no Minecraft/Homestead copyrighted assets.
 */
class TimberDrawable(private val context: Context, private val primary: Boolean,
                     private val panel: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val scale = context.resources.displayMetrics.density
        val rim = 2f * scale
        val radius = (if (panel) 13f else 8f) * scale
        rect.set(b.left.toFloat(),b.top.toFloat(),b.right.toFloat(),b.bottom.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = 0xFF211A16.toInt()
        canvas.drawRoundRect(rect,radius,radius,paint)
        rect.inset(rim,rim)
        paint.color = if (panel) 0xFF3A2C24.toInt() else if (primary)
            0xFF856446.toInt() else 0xFF634830.toInt()
        canvas.drawRoundRect(rect,radius-rim,radius-rim,paint)
        paint.color = 0x335B3722
        val seamY = if (panel) 24f*scale else 16f*scale
        var y = rect.top + seamY
        while (y < rect.bottom-3*scale) {
            canvas.drawRect(rect.left+4*scale,y,rect.right-4*scale,y+1f*scale,paint)
            y += seamY
        }
        paint.color = 0x33DCC3A0
        var grain = rect.top+7*scale
        var i = 0
        while (grain < rect.bottom-5*scale) {
            val left = rect.left + (8 + ((i*13)%37))*scale
            val right = (left + (22 + (i%5)*9)*scale).coerceAtMost(rect.right-8*scale)
            canvas.drawRect(left,grain,right,grain+0.65f*scale,paint)
            grain += 15f*scale
            i++
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = rim
        paint.color = if (primary) 0xFFB69467.toInt() else 0xFF92714F.toInt()
        canvas.drawRoundRect(rect,radius-rim,radius-rim,paint)
        rect.inset(rim*1.5f,rim*1.5f)
        paint.strokeWidth = 0.75f*scale
        paint.color = 0x88412C20.toInt()
        canvas.drawRoundRect(rect,radius-2*rim,radius-2*rim,paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFBD9569.toInt()
        for (x in listOf(rect.left+6*scale,rect.right-6*scale)) {
            canvas.drawRect(x,rect.top+6*scale,x+1.8f*scale,rect.top+7.8f*scale,paint)
            canvas.drawRect(x,rect.bottom-7.8f*scale,x+1.8f*scale,rect.bottom-6*scale,paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Android API")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
