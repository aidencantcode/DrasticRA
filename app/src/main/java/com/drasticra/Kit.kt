package com.drasticra

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.ll(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.setMargins(dp(l), dp(t), dp(r), dp(b))
    return p
}

fun Context.pad(v: View, l: Int, t: Int, r: Int, b: Int) {
    v.setPadding(dp(l), dp(t), dp(r), dp(b))
}

fun Context.tv(text: String, size: Float, color: Int, title: Boolean = false): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = size
    t.setTextColor(color)
    t.typeface = if (title) Fonts.title else Fonts.body
    return t
}

/** Glass card (refraction / frosted) or a plain solid card, depending on the Glass setting. */
fun Context.card(radius: Int = 26): LinearLayout {
    if (glassMode() != "off") {
        val g = GlassLinear(this)
        g.orientation = LinearLayout.VERTICAL
        g.radiusDp = radius
        return g
    }
    val c = LinearLayout(this)
    c.orientation = LinearLayout.VERTICAL
    c.background = glassCard(radius)
    c.elevation = dp(5).toFloat()
    return c
}

fun Context.makeFocusable(v: View) {
    v.isFocusable = true
    v.defaultFocusHighlightEnabled = false
}

@SuppressLint("ClickableViewAccessibility")
fun View.pressable() {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2.6f)).start()
        }
        false
    }
}

fun View.popIn(delayMs: Long) {
    alpha = 0f
    translationY = context.dp(22).toFloat()
    scaleX = 0.94f
    scaleY = 0.94f
    animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
        .setStartDelay(delayMs).setDuration(420).setInterpolator(OvershootInterpolator(1.1f)).start()
}

fun Context.iconButton(res: Int, sizeDp: Int, onClick: () -> Unit): View {
    val b = ImageView(this)
    b.setImageResource(res)
    b.setColorFilter(Pal.TEXT)
    b.background = glassCard(sizeDp / 2)
    b.elevation = dp(5).toFloat()
    pad(b, sizeDp / 4, sizeDp / 4, sizeDp / 4, sizeDp / 4)
    b.isClickable = true
    makeFocusable(b)
    b.setOnClickListener { onClick() }
    b.pressable()
    return b
}

fun Context.pill(text: String, primary: Boolean, onClick: () -> Unit): TextView {
    val t = tv(text, 15f, if (primary) Color.WHITE else Pal.TEXT, true)
    t.gravity = Gravity.CENTER
    pad(t, 14, 13, 14, 13)
    t.background = withRipple(if (primary) accentFill(22) else glassCard(22))
    t.elevation = dp(if (primary) 8 else 4).toFloat()
    if (primary) {
        t.outlineSpotShadowColor = Pal.ACC1
        t.outlineAmbientShadowColor = Pal.ACC1
    }
    t.isClickable = true
    makeFocusable(t)
    t.setOnClickListener { onClick() }
    t.pressable()
    return t
}

fun Context.chip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
    val t = tv(text, 13f, if (selected) Color.WHITE else Pal.TEXT, true)
    t.gravity = Gravity.CENTER
    pad(t, 12, 11, 12, 11)
    t.background = withRipple(if (selected) accentFill(16) else rounded(Pal.FIELD, 16))
    if (selected) {
        t.elevation = dp(6).toFloat()
        t.outlineSpotShadowColor = Pal.ACC1
        t.outlineAmbientShadowColor = Pal.ACC1
    }
    t.isClickable = true
    makeFocusable(t)
    t.setOnClickListener { onClick() }
    t.pressable()
    return t
}

fun Context.field(hint: String, value: String, password: Boolean = false): EditText {
    val e = EditText(this)
    e.hint = hint
    e.setText(value)
    e.setSingleLine(true)
    e.textSize = 14f
    e.typeface = Fonts.body
    e.setTextColor(Pal.TEXT)
    e.setHintTextColor(Pal.SUB)
    e.background = rounded(Pal.FIELD, 16)
    pad(e, 15, 12, 15, 12)
    e.inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    else InputType.TYPE_CLASS_TEXT
    return e
}

fun Context.square(sizeDp: Int, radiusDp: Int, url: String?): ImageView {
    val img = ImageView(this)
    img.scaleType = ImageView.ScaleType.CENTER_CROP
    img.background = rounded(Pal.FIELD, radiusDp)
    img.clipRound(radiusDp)
    if (!url.isNullOrEmpty()) Img.load(url, img)
    return img
}

fun Context.tag(text: String): TextView {
    val t = tv(text, 11f, Pal.SUB, true)
    pad(t, 10, 3, 10, 3)
    t.background = rounded(Pal.FIELD, 12)
    return t
}

fun Context.sectionTitle(text: String): TextView {
    val t = tv(text, 12f, Pal.SUB, true)
    t.letterSpacing = 0.14f
    return t
}

fun shortConsole(c: String): String {
    val s = c.replace("Nintendo ", "").replace("PlayStation Portable", "PSP").replace("PlayStation", "PS")
    return if (s.isEmpty()) "DS" else s
}

/**
 * Backdrop behind the bottom bar: a wide radial gradient anchored at the bottom edge, white in the light
 * theme and black in the dark theme, solid where the bar sits and fading away towards the top so cards
 * scrolling underneath never show through the bar.
 *
 * It only paints; it reports a size of 0 until it is measured EXACTLY, so placing it in a wrap-content
 * parent (with negative margins to overhang the bar) can never make that parent grow.
 */
class BottomScrimView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private var shader: RadialGradient? = null

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.EXACTLY) View.MeasureSpec.getSize(widthMeasureSpec) else 0
        val h = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.EXACTLY) View.MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        val base = if (Pal.light) 0xFFFFFF else 0x000000
        // unit-radius gradient, stretched into a wide ellipse whose centre is the bottom middle of the view
        val s = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(base or (0xF4 shl 24), base or (0xE6 shl 24), base or (0x8C shl 24), base),
            floatArrayOf(0f, 0.34f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        m.reset()
        m.setScale(w * 0.95f, h.toFloat())
        m.postTranslate(w / 2f, h.toFloat())
        s.setLocalMatrix(m)
        shader = s
    }

    override fun onDraw(c: Canvas) {
        val s = shader ?: return
        p.shader = s
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    }
}

/** Moving gradient highlight that glides to whatever is focused with the D-pad / controller. */
class FocusHalo(ctx: Context) : View(ctx) {
    private var target: View? = null
    private val cur = RectF()
    private val dst = RectF()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sweep: SweepGradient? = null
    private val m = Matrix()
    private val loc = IntArray(2)
    private val my = IntArray(2)
    private var shown = 0f

    init {
        glow.style = Paint.Style.STROKE
        glow.strokeWidth = ctx.dp(6).toFloat()
        glow.maskFilter = BlurMaskFilter(ctx.dp(10).toFloat(), BlurMaskFilter.Blur.NORMAL)
        ring.style = Paint.Style.STROKE
        ring.strokeWidth = ctx.dp(2).toFloat()
    }

    fun follow(v: View?) {
        target = v
        shown = 0f
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val v = target ?: return
        if (!v.isFocused) return
        if (!v.isShown) {
            // e.g. a header that faded out while focused: check again shortly so the halo returns with it
            postInvalidateDelayed(120)
            return
        }
        v.getLocationInWindow(loc)
        getLocationInWindow(my)
        val pad = context.dp(4).toFloat()
        val l = (loc[0] - my[0]).toFloat() - pad
        val t = (loc[1] - my[1]).toFloat() - pad
        dst.set(l, t, l + v.width + pad * 2, t + v.height + pad * 2)
        if (cur.isEmpty()) {
            cur.set(dst)
        } else {
            cur.left += (dst.left - cur.left) * 0.32f
            cur.top += (dst.top - cur.top) * 0.32f
            cur.right += (dst.right - cur.right) * 0.32f
            cur.bottom += (dst.bottom - cur.bottom) * 0.32f
        }
        shown = min(1f, shown + 0.12f)
        val r = min(context.dp(28).toFloat(), min(cur.width(), cur.height()) / 2f)
        val ang = (SystemClock.uptimeMillis() % 2800L) / 2800f * 360f
        var s = sweep
        if (s == null) {
            s = SweepGradient(
                0f, 0f,
                intArrayOf(Pal.ACC1, Pal.ACC2, 0xFFFFFFFF.toInt(), Pal.ACC2, Pal.ACC1),
                floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f)
            )
            sweep = s
        }
        m.reset()
        m.postRotate(ang)
        m.postTranslate(cur.centerX(), cur.centerY())
        s.setLocalMatrix(m)
        fill.color = Pal.ACC1
        fill.alpha = (36 * shown).toInt()
        c.drawRoundRect(cur, r, r, fill)
        glow.shader = s
        glow.alpha = (210 * shown).toInt()
        c.drawRoundRect(cur, r, r, glow)
        ring.shader = s
        ring.alpha = (255 * shown).toInt()
        c.drawRoundRect(cur, r, r, ring)
        postInvalidateOnAnimation()
    }
}
