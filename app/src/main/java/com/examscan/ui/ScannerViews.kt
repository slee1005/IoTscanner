package com.examscan.ui

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

object Palette {
    val bg = Color.rgb(11, 18, 25)
    val surface = Color.rgb(19, 30, 40)
    val line = Color.rgb(37, 53, 65)
    val text = Color.rgb(237, 243, 247)
    val muted = Color.rgb(145, 164, 177)
    val mint = Color.rgb(98, 224, 189)
    val coral = Color.rgb(255, 141, 132)
    val amber = Color.rgb(244, 199, 116)
    val blue = Color.rgb(139, 186, 235)
}

/** Decorative sweep only: neither bearings nor physical device positions are inferred. */
class RadarView(context: Context) : View(context) {
    var running = false
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var sweep: SweepGradient? = null
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sweep = SweepGradient(w / 2f, h / 2f, intArrayOf(Color.TRANSPARENT,
            Color.argb(8, 98, 224, 189), Color.argb(65, 98, 224, 189), Color.TRANSPARENT),
            floatArrayOf(0f, .68f, .995f, 1f))
    }
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val radius = min(width, height) * .44f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density
        paint.color = Color.argb(45, 98, 224, 189)
        for (i in 1..3) canvas.drawCircle(cx, cy, radius * i / 3, paint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)
        val angle = if (running) (android.os.SystemClock.uptimeMillis() % 6000) / 6000f * 360 else -42f
        paint.style = Paint.Style.FILL
        matrix.setRotate(angle, cx, cy)
        sweep?.setLocalMatrix(matrix)
        paint.shader = sweep
        canvas.drawCircle(cx, cy, radius, paint); paint.shader = null
        paint.color = Palette.mint; paint.strokeWidth = resources.displayMetrics.density * 1.2f
        val a = angle * PI.toFloat() / 180
        canvas.drawLine(cx, cy, cx + cos(a) * radius, cy + sin(a) * radius, paint)
        canvas.drawCircle(cx, cy, resources.displayMetrics.density * 3, paint)
        if (running && isAttachedToWindow) postInvalidateOnAnimation()
    }
}

class SignalChart(context: Context) : View(context) {
    var points: List<Int> = emptyList()
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        val pad = resources.displayMetrics.density * 4
        paint.strokeWidth = resources.displayMetrics.density
        paint.color = Palette.line
        for (i in 0..2) {
            val y = pad + (height - 2 * pad) * i / 2f
            canvas.drawLine(pad, y, width - pad, y, paint)
        }
        if (points.isEmpty()) return
        fun y(value: Int) = pad + (height - 2 * pad) * (1 - (value + 100).coerceIn(0, 65) / 65f)
        path.rewind()
        if (points.size == 1) { path.moveTo(pad, y(points.first())); path.lineTo(width - pad, y(points.first())) }
        else points.forEachIndexed { index, value ->
            val x = pad + (width - 2 * pad) * index / (points.size - 1)
            if (index == 0) path.moveTo(x, y(value)) else path.lineTo(x, y(value))
        }
        paint.color = Palette.mint; paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density * 2; paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
    }
}

class GlyphView @JvmOverloads constructor(context: Context, private val kind: String = "radar", private val tint: Int = Palette.mint) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPath = Path()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(c: Canvas) {
        c.save(); c.translate(width / 2f, height / 2f)
        val s = min(width, height) / 28f; c.scale(s, s)
        p.color = tint; p.strokeWidth = 1.7f; p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        fun path(vararg points: Float) {
            glyphPath.rewind(); glyphPath.moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) glyphPath.lineTo(points[i], points[i+1])
            c.drawPath(glyphPath, p)
        }
        when (kind) {
            "wifi", "hotspot", "wifi_direct", "access_point" -> {
                c.drawArc(-11f,-6f,11f,16f,225f,90f,false,p)
                c.drawArc(-7f,-2f,7f,12f,225f,90f,false,p)
                c.drawPoint(0f, 6f, p)
            }
            "earbuds" -> {
                c.drawArc(-9f,-10f,9f,8f,180f,180f,false,p)
                c.drawRoundRect(-10f,-2f,-5f,8f,2f,2f,p)
                c.drawRoundRect(5f,-2f,10f,8f,2f,2f,p)
            }
            "smart_glasses" -> {
                c.drawRoundRect(-11f,-4f,-2f,4f,2f,2f,p)
                c.drawRoundRect(2f,-4f,11f,4f,2f,2f,p)
                c.drawLine(-2f,-1f,2f,-1f,p)
            }
            "smartwatch" -> {
                c.drawRoundRect(-7f,-7f,7f,7f,3f,3f,p)
                path(-4f,-7f,-4f,-12f,4f,-12f,4f,-7f)
                path(-4f,7f,-4f,12f,4f,12f,4f,7f)
                path(0f,-3f,0f,0f,3f,2f)
            }
            "phone" -> { c.drawRoundRect(-6f,-11f,6f,11f,2f,2f,p); c.drawLine(-2f,7f,2f,7f,p) }
            "alerts" -> { path(-10f,8f,0f,-10f,10f,8f,-10f,8f); c.drawLine(0f,-3f,0f,2f,p); c.drawPoint(0f,5f,p) }
            "session" -> { c.drawRoundRect(-8f,-10f,8f,10f,2f,2f,p); c.drawLine(-4f,-4f,4f,-4f,p); c.drawLine(-4f,1f,4f,1f,p); c.drawLine(-4f,6f,1f,6f,p) }
            "radar" -> { c.drawCircle(0f,0f,10f,p); c.drawCircle(0f,0f,5f,p); path(0f,0f,8f,-8f) }
            else -> path(-5f,-6f,5f,5f,0f,10f,0f,-10f,5f,-5f,-5f,6f)
        }
        c.restore()
    }
}
