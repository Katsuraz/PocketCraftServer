package com.pocketcraft.server

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    private var currentTheme = "classic"
    fun loadTheme(c: Context) {
        currentTheme = c.getSharedPreferences("pocketcraft",0)
            .getString("uiTheme","classic").let { if(it=="homestead") "homestead" else "classic" }
    }
    fun setTheme(c: Context, theme: String) {
        require(theme=="homestead" || theme=="classic")
        c.getSharedPreferences("pocketcraft",0).edit().putString("uiTheme",theme).apply()
        currentTheme = theme
    }
    fun homestead(): Boolean = currentTheme=="homestead"

    val BG: Int get() = if(homestead()) 0xFF161A13.toInt() else 0xFF080E18.toInt()
    val PANEL: Int get() = if(homestead()) 0xFF2C3023.toInt() else 0xFF141E2C.toInt()
    val SURFACE: Int get() = if(homestead()) 0xFF444634.toInt() else 0xFF223245.toInt()
    val MUTED: Int get() = if(homestead()) 0xFFC6C1AA.toInt() else 0xFFACBED1.toInt()
    val WHITE: Int get() = if(homestead()) 0xFFF6ECD7.toInt() else 0xFFF6F9FD.toInt()
    val GREEN: Int get() = if(homestead()) 0xFFB4CE86.toInt() else 0xFF70E9B4.toInt()
    val CYAN: Int get() = if(homestead()) 0xFFE1BC83.toInt() else 0xFF80D7FA.toInt()
    val PURPLE: Int get() = if(homestead()) 0xFFD6B894.toInt() else 0xFFB5A5FF.toInt()
    val RED: Int get() = if(homestead()) 0xFFF5AA93.toInt() else 0xFFFFA0AB.toInt()
    val BORDER: Int get() = if(homestead()) 0xFF67684C.toInt() else 0xFF324459.toInt()
    val SUBTLE: Int get() = if(homestead()) 0xFF252D20.toInt() else 0xFF182737.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + .5f).toInt()

    fun shape(c: Context, color: Int, radius: Int = 18, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            if (homestead() && (color==PANEL || color==SURFACE)) {
                setColors(if (color==PANEL)
                    intArrayOf(0xFF36382A.toInt(), 0xFF27291F.toInt())
                else intArrayOf(0xFF514D38.toInt(), 0xFF3D402F.toInt()))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            } else setColor(color)
            cornerRadius = dp(c, if(homestead()) radius.coerceAtMost(15) else radius).toFloat()
            if (stroke != null) setStroke(dp(c, 1), stroke)
        }

    fun gradient(c: Context, vararg colors: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (homestead()) intArrayOf(0xFFB3C886.toInt(),0xFFE0BB87.toInt())
            else colors).apply {
            cornerRadius = dp(c, if(homestead()) 14 else 24).toFloat()
            if (homestead()) setStroke(dp(c,1),0xFFE4D5A3.toInt())
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
            if (bold) typeface = Typeface.create(if (homestead() && size >= 17f) "serif" else "sans-serif-medium", Typeface.BOLD)
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
        background = shape(c, PANEL, if(homestead()) 14 else 22, BORDER)
        padding(c, 18, 18, 18, 18)
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
        text(c, label, 13f, if (primary) BG else WHITE, true).apply {
            gravity = Gravity.CENTER
            background = pressable(c,if (primary) gradient(c, GREEN, CYAN)
                else shape(c, SURFACE, if(homestead()) 9 else 14, BORDER))
            padding(c, 16, 15, 16, 15)
            minHeight = dp(c, 52)
            isEnabled = enabledAction
            alpha = if (enabledAction) 1f else .48f
            isClickable = enabledAction
            isFocusable = enabledAction
            if (enabledAction) setOnClickListener { click() }
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
        paint.color = 0x3357DCF5
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
        paint.color = 0xFFAB95FF.toInt()
        canvas.drawRect(unit*19, ground-unit*5, unit*20.8f, ground-unit*3, paint)
        paint.color = 0xFF8C77E9.toInt()
        canvas.drawRect(unit*19.5f, ground-unit*6.3f, unit*20.3f, ground-unit*4, paint)
        paint.color = 0xFFDBE5FF.toInt()
        canvas.drawRect(unit*19.7f, ground-unit*5.6f, unit*20.1f, ground-unit*5.2f, paint)
    }
}
