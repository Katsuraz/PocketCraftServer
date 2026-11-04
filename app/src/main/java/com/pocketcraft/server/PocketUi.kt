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
    const val BG = 0xFF080E18.toInt()
    const val PANEL = 0xFF141E2C.toInt()
    const val SURFACE = 0xFF223245.toInt()
    const val MUTED = 0xFFACBED1.toInt()
    const val WHITE = 0xFFF6F9FD.toInt()
    const val GREEN = 0xFF70E9B4.toInt()
    const val CYAN = 0xFF80D7FA.toInt()
    const val PURPLE = 0xFFB5A5FF.toInt()
    const val RED = 0xFFFFA0AB.toInt()
    const val BORDER = 0xFF324459.toInt()
    const val SUBTLE = 0xFF182737.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + .5f).toInt()

    fun shape(c: Context, color: Int, radius: Int = 18, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(c, radius).toFloat()
            if (stroke != null) setStroke(dp(c, 1), stroke)
        }

    fun gradient(c: Context, vararg colors: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(c, 24).toFloat()
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
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
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
        background = shape(c, PANEL, 22, BORDER)
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
                else shape(c, SURFACE, 14, BORDER))
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
    override fun onDraw(canvas: Canvas) {
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
