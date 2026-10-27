package com.drasticra

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Colours for the online / offline notices. */
object NetNotice {
    val YELLOW = intArrayOf(0xFFFFD43B.toInt(), 0xFFFFA62B.toInt(), 0xFFFFE680.toInt(), 0xFFFFC21A.toInt())
}

/** Edge glow look (Appearance -> Edge glow): same as accent, rainbow, or a custom gradient with free stops. */
object EdgeStyle {
    val RAINBOW = intArrayOf(
        0xFFFF3B3B.toInt(), 0xFFFF9F1C.toInt(), 0xFFFFE53B.toInt(), 0xFF3BE07A.toInt(),
        0xFF2EC5FF.toInt(), 0xFF5B5BFF.toInt(), 0xFFC23BFF.toInt()
    )

    class Stop(var pos: Float, var color: Int)

    fun mode(ctx: Context): String = ctx.prefs().getString("edge_mode", "accent") ?: "accent"

    fun stops(ctx: Context): MutableList<Stop> {
        val raw = ctx.prefs().getString("edge_stops", "") ?: ""
        val out = ArrayList<Stop>()
        for (part in raw.split(',')) {
            val kv = part.split(':')
            if (kv.size != 2) continue
            val p = kv[0].toFloatOrNull() ?: continue
            val c = try { Color.parseColor(kv[1]) } catch (_: Exception) { continue }
            out.add(Stop(p.coerceIn(0f, 1f), c))
        }
        if (out.size < 2) {
            out.clear()
            val g = Pal.grad()
            for (i in g.indices) out.add(Stop(i / (g.size - 1f), g[i]))
        }
        out.sortBy { it.pos }
        return out
    }

    fun saveStops(ctx: Context, list: List<Stop>) {
        val s = list.sortedBy { it.pos }.joinToString(",") { String.format("%.3f:#%08X", it.pos, it.color) }
        ctx.prefs().edit().putString("edge_stops", s).apply()
    }

    /** Colours + positions of a closed loop for the travelling sweep glow. */
    fun resolve(ctx: Context): Pair<IntArray, FloatArray?> {
        return when (mode(ctx)) {
            "rainbow" -> Pair(intArrayOf(*RAINBOW, RAINBOW[0]), null)
            "custom" -> {
                val st = stops(ctx)
                // squeeze the stops into 0..0.9 and close back to the first colour so the seam never shows
                val cols = IntArray(st.size + 1) { if (it < st.size) st[it].color else st[0].color }
                val pos = FloatArray(st.size + 1) { if (it < st.size) st[it].pos * 0.9f else 1f }
                pos[0] = 0f
                Pair(cols, pos)
            }
            else -> Pair(Pal.loop(), null)
        }
    }

    /** Flat list of the current glow colours (for small previews). */
    fun preview(ctx: Context): IntArray = resolve(ctx).first
}

/**
 * Photoshop-style gradient bar: tap the bar to add a colour stop, drag a stop's handle to move it, tap a handle to
 * select it (then pick its colour on the wheel). [onSelect] reports the selected stop; [onChange] fires when the
 * gradient changed (true = finished, worth saving).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class GradientEditorView(ctx: Context, val stops: MutableList<EdgeStyle.Stop>) : View(ctx) {
    var selected = 0
        private set
    var onSelect: ((EdgeStyle.Stop) -> Unit)? = null
    var onChange: ((Boolean) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()
    private val d = ctx.resources.displayMetrics.density
    private var dragging = -1

    init {
        ring.style = Paint.Style.STROKE
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (70 * d).toInt())
    }

    private fun left() = 14 * d
    private fun right() = width - 14 * d
    private fun xOf(pos: Float) = left() + (right() - left()) * pos
    private fun posOf(x: Float) = ((x - left()) / (right() - left())).coerceIn(0f, 1f)

    fun colorAt(pos: Float): Int {
        val s = stops.sortedBy { it.pos }
        if (pos <= s.first().pos) return s.first().color
        for (i in 0 until s.size - 1) {
            val a = s[i]
            val b = s[i + 1]
            if (pos <= b.pos) {
                val t = if (b.pos - a.pos < 1e-4f) 0f else (pos - a.pos) / (b.pos - a.pos)
                return Pal.mix(a.color, b.color, t)
            }
        }
        return s.last().color
    }

    fun setSelectedColor(c: Int) {
        stops.getOrNull(selected)?.color = c
        invalidate()
        onChange?.invoke(false)
    }

    fun removeSelected(): Boolean {
        if (stops.size <= 2) return false
        stops.removeAt(selected)
        selected = 0
        invalidate()
        onSelect?.invoke(stops[selected])
        onChange?.invoke(true)
        return true
    }

    override fun onDraw(c: Canvas) {
        bar.set(left(), 6 * d, right(), 36 * d)
        val s = stops.sortedBy { it.pos }
        val cols = s.map { it.color }.toIntArray()
        val pos = s.map { it.pos }.toFloatArray()
        p.shader = LinearGradient(bar.left, 0f, bar.right, 0f, cols, pos, Shader.TileMode.CLAMP)
        c.drawRoundRect(bar, 10 * d, 10 * d, p)
        p.shader = null
        ring.strokeWidth = 1.5f * d
        ring.color = Pal.TEXT.withAlpha(0x40)
        c.drawRoundRect(bar, 10 * d, 10 * d, ring)
        for ((i, st) in stops.withIndex()) {
            val x = xOf(st.pos)
            val y = 52 * d
            // little stem from the bar to the handle
            p.color = Pal.TEXT.withAlpha(0x66)
            c.drawRect(x - 1 * d, bar.bottom, x + 1 * d, y - 9 * d, p)
            p.color = st.color
            c.drawCircle(x, y, 10 * d, p)
            ring.strokeWidth = (if (i == selected) 3.5f else 2f) * d
            ring.color = if (i == selected) Pal.ACC1 else Pal.TEXT
            c.drawCircle(x, y, (if (i == selected) 12.5f else 10.5f) * d, ring)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var best = -1
                var bestD = 22 * d
                for ((i, st) in stops.withIndex()) {
                    val dx = kotlin.math.abs(xOf(st.pos) - e.x)
                    if (dx < bestD && e.y > 30 * d) {
                        bestD = dx
                        best = i
                    }
                }
                if (best < 0) {
                    // tap on the bar: new stop with the colour already there
                    val pos = posOf(e.x)
                    stops.add(EdgeStyle.Stop(pos, colorAt(pos)))
                    best = stops.size - 1
                    onChange?.invoke(false)
                }
                selected = best
                dragging = best
                onSelect?.invoke(stops[best])
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                stops[dragging].pos = posOf(e.x)
                invalidate()
                onChange?.invoke(false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                onChange?.invoke(true)
            }
        }
        return true
    }
}

/** Hue / saturation wheel. Brightness comes from outside ([value]). */
@SuppressLint("ClickableViewAccessibility")
class ColorWheelView(ctx: Context) : View(ctx) {
    var onPick: ((Int, Boolean) -> Unit)? = null
    private var h = 0f
    private var s = 0f
    var value = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density

    init {
        ring.style = Paint.Style.STROKE
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val side = min(w, (220 * d).toInt())
        setMeasuredDimension(w, side)
    }

    fun color(): Int = Color.HSVToColor(floatArrayOf(h, s, value))

    fun setColor(c: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        h = hsv[0]
        s = hsv[1]
        value = hsv[2]
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 8 * d
        p.shader = SweepGradient(cx, cy, intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
        c.drawCircle(cx, cy, r, p)
        p.shader = RadialGradient(cx, cy, r, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        p.color = Color.BLACK
        p.alpha = ((1f - value) * 255).toInt()
        c.drawCircle(cx, cy, r, p)
        p.alpha = 255
        // marker
        val a = Math.toRadians(h.toDouble())
        val mx = cx + (cos(a) * s * r).toFloat()
        val my = cy + (sin(a) * s * r).toFloat()
        p.color = color()
        c.drawCircle(mx, my, 11 * d, p)
        ring.strokeWidth = 3 * d
        ring.color = Color.WHITE
        c.drawCircle(mx, my, 12 * d, ring)
        ring.strokeWidth = 1.2f * d
        ring.color = Color.BLACK
        c.drawCircle(mx, my, 14 * d, ring)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 8 * d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val dx = e.x - cx
                val dy = e.y - cy
                var ang = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                if (ang < 0) ang += 360f
                h = ang
                s = (hypot(dx, dy) / r).coerceIn(0f, 1f)
                invalidate()
                onPick?.invoke(color(), false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onPick?.invoke(color(), true)
            }
        }
        return true
    }
}
