package com.pocketcraft.server

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Lightweight theme built using Android views, no external UI dependency. */
object PocketUi {
    const val BG = 0xFF0A1020.toInt()
    const val PANEL = 0xFF151E32.toInt()
    const val SURFACE = 0xFF1E2B43.toInt()
    const val MUTED = 0xFF91A3BE.toInt()
    const val WHITE = 0xFFF5F8FF.toInt()
    const val GREEN = 0xFF67E8AC.toInt()
    const val CYAN = 0xFF70D8FF.toInt()
    const val PURPLE = 0xFF9B85FF.toInt()
    const val RED = 0xFFFF8B9A.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + .5f).toInt()
    fun shape(c: Context, color: Int, radius: Int = 18, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(c, radius).toFloat()
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
            text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
        }
    fun pad(c: Context, l: Int, t: Int, r: Int, b: Int) = intArrayOf(dp(c,l), dp(c,t), dp(c,r), dp(c,b))
    fun View.padding(c: Context, l: Int, t: Int, r: Int, b: Int) { setPadding(dp(c,l),dp(c,t),dp(c,r),dp(c,b)) }
    fun space(c: Context, h: Int): View = View(c).apply { layoutParams = LinearLayout.LayoutParams(1, dp(c,h)) }
    fun card(c: Context): LinearLayout = column(c).apply {
        background = shape(c, PANEL, 22, 0xFF26354D.toInt())
        padding(c, 18, 17, 18, 17)
    }
    fun pill(c: Context, label: String, tint: Int = CYAN): TextView = text(c, label, 11f, tint, true).apply {
        background = shape(c, tint and 0x00FFFFFF or 0x22000000, 10, tint and 0x00FFFFFF or 0x55000000)
        padding(c, 10, 7, 10, 7)
        gravity = Gravity.CENTER
    }
    fun action(c: Context, label: String, primary: Boolean = false, enabledAction: Boolean = true, click: () -> Unit): TextView =
        text(c, label, 14f, if (primary) BG else WHITE, true).apply {
            gravity = Gravity.CENTER
            background = if (primary) gradient(c, GREEN, CYAN) else shape(c, SURFACE, 14, 0xFF31415D.toInt())
            padding(c, 16, 15, 16, 15)
            isEnabled = enabledAction
            alpha = if (enabledAction) 1f else .45f
            if (enabledAction) setOnClickListener { click() }
        }
    fun row(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    fun weight(c: Context): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
        marginEnd = dp(c, 8)
    }
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
