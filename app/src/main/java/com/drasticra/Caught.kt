package com.drasticra

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

/** Black inverse vignette: darkest in the middle (behind the badge), lighter towards the glowing edges. */
private class CaughtVignette(ctx: android.content.Context) : View(ctx) {
    private val p = Paint()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        val r = kotlin.math.max(w, h) * 0.75f
        p.shader = RadialGradient(w / 2f, h / 2f, r,
            intArrayOf(0xF0000000.toInt(), 0xD8000000.toInt(), 0x8C000000.toInt()), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    }
}

/**
 * "New achievement caught!": the first time the app opens after new unlocks. A rainbow edge glow races round and
 * slows down, the badge spins up from tiny to full size in 3D and then floats. More than one unlock = one screen
 * with "N more achievements earned!". Back or a tap anywhere fades it all away.
 */
@SuppressLint("ViewConstructor")
class CaughtView(private val act: MainActivity, private val items: List<UnseenItem>, private val onDone: () -> Unit) :
    FrameLayout(act) {
    private val vignette = CaughtVignette(act)
    private var glow: EdgeGlowView? = null
    private val badgeBox = FrameLayout(act)
    private val texts = LinearLayout(act)
    private var floatAnim: ValueAnimator? = null
    private var closing = false

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { close() }
        addView(vignette, LayoutParams(MATCH, MATCH))

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.clipChildren = false
        clipChildren = false

        // the badge: a square with a thick theme-coloured edge (white on dark, black on light) and a soft halo
        val edge = if (Pal.light) Color.BLACK else Color.WHITE
        val size = act.dp(156)
        badgeBox.background = act.rounded(edge, 30)
        badgeBox.elevation = act.dp(24).toFloat()
        badgeBox.outlineSpotShadowColor = if (Pal.light) Color.WHITE else Pal.ACC1
        badgeBox.outlineAmbientShadowColor = if (Pal.light) Color.WHITE else Pal.ACC1
        badgeBox.cameraDistance = 9000f * act.resources.displayMetrics.density
        val img = ImageView(act)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.background = act.rounded(0xFF22252E.toInt(), 22)
        img.clipRound(22)
        val first = items.first()
        if (first.badge.isNotEmpty()) Img.load("$MEDIA/Badge/${first.badge}.png", img) else {
            img.scaleType = ImageView.ScaleType.CENTER_INSIDE
            img.setImageResource(R.drawable.ic_trophy)
            img.setColorFilter(Pal.GOLD1)
            act.pad(img, 30, 30, 30, 30)
        }
        val ilp = LayoutParams(MATCH, MATCH)
        val m = act.dp(9)
        ilp.setMargins(m, m, m, m)
        badgeBox.addView(img, ilp)
        col.addView(badgeBox, LinearLayout.LayoutParams(size, size))

        texts.orientation = LinearLayout.VERTICAL
        texts.gravity = Gravity.CENTER_HORIZONTAL
        val head = act.tv("New achievement caught!", 26f, Color.WHITE, true)
        head.gravity = Gravity.CENTER
        head.setShadowLayer(act.dp(10).toFloat(), 0f, 0f, Pal.ACC1)
        texts.addView(head)
        val name = act.tv(first.title + (if (first.game.isNotEmpty()) "  -  " + first.game else ""), 14f, 0xFFD6D9E4.toInt())
        name.gravity = Gravity.CENTER
        name.maxLines = 2
        texts.addView(name, act.ll(WRAP, WRAP, t = 6))
        if (items.size > 1) {
            val more = act.tv("${items.size - 1} more achievement${if (items.size - 1 == 1) "" else "s"} earned! see in recents!",
                16f, Pal.ACC1, true)
            more.gravity = Gravity.CENTER
            texts.addView(more, act.ll(WRAP, WRAP, t = 12))
        }
        val hint = act.tv("Tap anywhere or press B to continue", 11f, 0x99FFFFFF.toInt())
        texts.addView(hint, act.ll(WRAP, WRAP, t = 18))
        col.addView(texts, act.ll(WRAP, WRAP, t = 34))
        val clp = LayoutParams(WRAP, WRAP, Gravity.CENTER)
        addView(col, clp)
    }

    fun start() {
        alpha = 0f
        animate().alpha(1f).setDuration(260).start()
        // rainbow edge glow, racing first then slowing down
        post {
            val g = EdgeGlowView(act, width, height, EdgeStyle.RAINBOW)
            g.isClickable = false
            g.alpha = 0.95f
            addView(g, 1, LayoutParams(MATCH, MATCH))
            g.flash()
            g.spinFast(14f)
            glow = g
        }
        // badge: spins up from small to big in 3D
        badgeBox.scaleX = 0.06f
        badgeBox.scaleY = 0.06f
        badgeBox.rotationY = -720f
        badgeBox.rotationX = 25f
        badgeBox.alpha = 0f
        badgeBox.animate().scaleX(1f).scaleY(1f).rotationY(0f).rotationX(0f).alpha(1f)
            .setStartDelay(120).setDuration(1250).setInterpolator(DecelerateInterpolator(2.4f))
            .withEndAction { startFloat() }.start()
        texts.alpha = 0f
        texts.translationY = act.dp(24).toFloat()
        texts.animate().alpha(1f).translationY(0f).setStartDelay(800).setDuration(520)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    /** gentle bob and sway after the spin */
    private fun startFloat() {
        if (closing) return
        val a = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat())
        a.duration = 3600
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        val amp = act.dp(9).toFloat()
        a.addUpdateListener {
            val t = it.animatedValue as Float
            badgeBox.translationY = sin(t) * amp
            badgeBox.rotationY = cos(t) * 9f
            badgeBox.rotationX = sin(t * 2f) * 4f
            texts.translationY = sin(t - 0.6f) * amp * 0.35f
        }
        a.start()
        floatAnim = a
    }

    fun close() {
        if (closing) return
        closing = true
        floatAnim?.cancel()
        glow?.fadeOut(420L)
        badgeBox.animate().scaleX(1.25f).scaleY(1.25f).alpha(0f).setStartDelay(0).setDuration(380).start()
        animate().alpha(0f).setDuration(420).withEndAction { onDone() }.start()
    }

    override fun onDetachedFromWindow() {
        floatAnim?.cancel()
        super.onDetachedFromWindow()
    }
}
