package com.drasticra

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

private fun shade(c: Int, f: Float): Int =
    Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

private fun mixC(a: Int, b: Int, t: Float): Int = Color.rgb(
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
)

/** Dark vignette around the screen with an accent glow near the edges and a bloom in the middle. */
class VignetteView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var edge: RadialGradient? = null
    private var bloom: RadialGradient? = null
    var pulse = 1f
        set(v) {
            field = v
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val r = hypot(w.toFloat(), h.toFloat()) / 2f
        edge = RadialGradient(
            w / 2f, h / 2f, r,
            intArrayOf(0x00000000, 0x30000000, Pal.ACC1.withAlpha(0x70), 0xEE000000.toInt()),
            floatArrayOf(0f, 0.45f, 0.84f, 1f), Shader.TileMode.CLAMP
        )
        bloom = RadialGradient(
            w / 2f, h * 0.42f, max(w, h) * 0.42f,
            Pal.ACC2.withAlpha(0x66), Pal.ACC2.withAlpha(0), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        p.shader = bloom
        p.alpha = (255 * pulse).toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = edge
        p.alpha = 255
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    }
}

/** Chunky extruded rounded frame with a rotating accent-gradient face and the achievement icon inside. */
class BadgeFrame(ctx: Context, iconUrl: String?) : FrameLayout(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val m = Matrix()
    private var sweep: SweepGradient? = null
    var angle = 0f
        set(v) {
            field = v
            invalidate()
        }
    private val icon = ImageView(ctx)

    init {
        setWillNotDraw(false)
        icon.scaleType = ImageView.ScaleType.CENTER_CROP
        icon.background = ctx.rounded(0xFF1A1B22.toInt(), 26)
        icon.clipRound(26)
        val s = ctx.dp(118)
        val lp = FrameLayout.LayoutParams(s, s)
        lp.gravity = Gravity.CENTER
        addView(icon, lp)
        if (iconUrl.isNullOrEmpty()) {
            icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            ctx.pad(icon, 22, 22, 22, 22)
            icon.setImageResource(R.drawable.ic_trophy)
            icon.setColorFilter(Pal.ACC1)
        } else {
            Img.load(iconUrl, icon)
        }
        cameraDistance = 14000f * ctx.resources.displayMetrics.density
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = context.resources.displayMetrics.density
        val stroke = 22f * d
        val inset = 18f * d + stroke / 2f
        val rr = 54f * d
        val dark1 = shade(Pal.ACC1, 0.42f)
        val dark2 = shade(Pal.ACC2, 0.42f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = stroke
        p.shader = null
        val depth = 14
        for (i in depth downTo 1) {
            val o = i * d * 0.9f
            rf.set(inset + o * 0.4f, inset + o, w - inset + o * 0.4f, h - inset + o)
            p.color = mixC(dark1, dark2, i / depth.toFloat())
            c.drawRoundRect(rf, rr, rr, p)
        }
        rf.set(inset, inset, w - inset, h - inset)
        var s = sweep
        if (s == null) {
            s = SweepGradient(
                0f, 0f,
                intArrayOf(Pal.ACC1, Pal.ACC2, mixC(Pal.ACC2, Color.WHITE, 0.55f), Pal.ACC2, Pal.ACC1),
                floatArrayOf(0f, 0.3f, 0.5f, 0.75f, 1f)
            )
            sweep = s
        }
        m.reset()
        m.postRotate(angle)
        m.postTranslate(w / 2f, h / 2f)
        s.setLocalMatrix(m)
        p.shader = s
        c.drawRoundRect(rf, rr, rr, p)
        p.shader = null
        p.strokeWidth = 3f * d
        p.color = 0x66FFFFFF
        rf.inset(7f * d, 7f * d)
        c.drawRoundRect(rf, rr - 7f * d, rr - 7f * d, p)
    }
}

/**
 * Shown when you open the app after unlocking something: vignette + glow, a floating 3D badge that
 * spins up from small to big, tap anywhere to dismiss.
 */
class CongratsView(ctx: Context, items: List<UnseenItem>, private val onClose: () -> Unit) : FrameLayout(ctx) {
    private val vign = VignetteView(ctx)
    private val frame: BadgeFrame
    private val texts = LinearLayout(ctx)
    private val animators = ArrayList<ValueAnimator>()
    private var idle = false
    private var closing = false
    private var moreText: TextView? = null

    init {
        isClickable = true
        isFocusable = true
        val main = items.maxByOrNull { it.points } ?: items.last()
        val more = items.size - 1
        addView(vign, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        val hpx = ctx.resources.displayMetrics.heightPixels
        val fs = minOf(ctx.dp(250), (hpx * 0.46f).toInt())
        frame = BadgeFrame(ctx, if (main.badge.isEmpty()) null else "$MEDIA/Badge/" + main.badge + ".png")
        box.addView(frame, LinearLayout.LayoutParams(fs, fs))

        texts.orientation = LinearLayout.VERTICAL
        texts.gravity = Gravity.CENTER_HORIZONTAL
        val l0 = ctx.tv("ACHIEVEMENT UNLOCKED", 11f, 0xFFC4CCFF.toInt(), true)
        l0.letterSpacing = 0.16f
        texts.addView(l0)
        val t = ctx.tv(main.title, 24f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        t.maxWidth = ctx.dp(420)
        texts.addView(t, ctx.ll(WRAP, WRAP, t = 4))
        val d = ctx.tv(main.desc, 14f, 0xFFD0D3E0.toInt())
        d.gravity = Gravity.CENTER
        d.maxWidth = ctx.dp(400)
        texts.addView(d, ctx.ll(WRAP, WRAP, t = 4))
        val pts = ctx.tv("+" + main.points + " pts" + (if (main.game.isNotEmpty()) "   -   " + main.game else ""), 13f, 0xFFB9C4FF.toInt(), true)
        texts.addView(pts, ctx.ll(WRAP, WRAP, t = 8))
        if (more > 0) {
            val mt = ctx.tv("You earned $more more - go to Recent to admire them!", 13f, Color.WHITE, true)
            mt.gravity = Gravity.CENTER
            texts.addView(mt, ctx.ll(WRAP, WRAP, t = 12))
            moreText = mt
        }
        texts.addView(ctx.tv("Tap anywhere to close", 11f, 0x88FFFFFF.toInt()), ctx.ll(WRAP, WRAP, t = 14))
        box.addView(texts, ctx.ll(WRAP, WRAP, t = 6))
        addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setOnClickListener { close() }
    }

    fun start() {
        vign.alpha = 0f
        vign.animate().alpha(1f).setDuration(500).start()
        frame.scaleX = 0.1f
        frame.scaleY = 0.1f
        frame.rotationY = 540f
        frame.alpha = 0f
        frame.animate().scaleX(1f).scaleY(1f).rotationY(0f).alpha(1f)
            .setDuration(1100).setInterpolator(OvershootInterpolator(1.4f)).start()
        texts.alpha = 0f
        texts.translationY = context.dp(20).toFloat()
        texts.animate().alpha(1f).translationY(0f).setStartDelay(700).setDuration(450).start()
        postDelayed({ idle = true }, 1100)

        val loop = ValueAnimator.ofFloat(0f, 1f)
        loop.duration = 3400
        loop.repeatCount = ValueAnimator.INFINITE
        loop.interpolator = LinearInterpolator()
        loop.addUpdateListener {
            val v = it.animatedValue as Float
            frame.angle = v * 360f
            vign.pulse = 0.85f + 0.15f * sin(v * 2f * PI.toFloat())
            if (idle) {
                frame.translationY = sin(v * 2f * PI.toFloat()) * context.dp(8)
                frame.rotationY = sin(v * 2f * PI.toFloat() + 1f) * 14f
            }
        }
        loop.start()
        animators.add(loop)

        val mt = moreText
        if (mt != null) {
            val f = ValueAnimator.ofFloat(0.25f, 1f)
            f.duration = 1500
            f.repeatCount = ValueAnimator.INFINITE
            f.repeatMode = ValueAnimator.REVERSE
            f.addUpdateListener { mt.alpha = it.animatedValue as Float }
            f.start()
            animators.add(f)
        }
    }

    fun close() {
        if (closing) return
        closing = true
        animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(260).withEndAction { onClose() }.start()
    }

    override fun onDetachedFromWindow() {
        for (a in animators) a.cancel()
        animators.clear()
        super.onDetachedFromWindow()
    }
}
