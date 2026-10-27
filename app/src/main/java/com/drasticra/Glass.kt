package com.drasticra

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

private const val AGSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bevel;
uniform float refr;
uniform float disp;
half4 samp(float2 p) {
    return content.eval(clamp(p, float2(0.5), size - float2(0.5)));
}
half4 main(float2 fc) {
    float2 c = size * 0.5;
    float2 q = abs(fc - c) - (c - float2(radius));
    float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    float depth = -d;
    float t = clamp(1.0 - depth / bevel, 0.0, 1.0);
    float k = t * t * (3.0 - 2.0 * t);
    float2 sg = float2(fc.x < c.x ? -1.0 : 1.0, fc.y < c.y ? -1.0 : 1.0);
    float2 n;
    if (q.x > 0.0 && q.y > 0.0) {
        n = normalize(q) * sg;
    } else if (q.x > q.y) {
        n = float2(sg.x, 0.0);
    } else {
        n = float2(0.0, sg.y);
    }
    float2 off = -n * k * refr;
    half r = samp(fc + off * (1.0 + disp)).r;
    half4 mid = samp(fc + off);
    half b = samp(fc + off * (1.0 - disp)).b;
    return half4(r, mid.g, b, 1.0);
}
"""

/**
 * One shared recording of the animated background per tick. Every glass view draws a piece of it,
 * blurs it and bends it near its edges (refraction), so the glass really "sees" what is behind it.
 */
object GlassBackdrop {
    var source: View? = null

    /**
     * The sliding settings drawer. Glass inside it samples the background as if the drawer were already at
     * rest, so it never shows black while it is parked off-screen or sliding in, and it is not re-recorded
     * every tick while the drawer is closed.
     */
    var drawer: View? = null
    private var node: RenderNode? = null
    private val views = ArrayList<WeakReference<GlassLinear>>()

    private fun drawerHidden(): Boolean {
        val d = drawer ?: return false
        return d.width > 0 && d.translationX <= -d.width + 1f
    }

    fun register(v: GlassLinear) {
        views.add(WeakReference(v))
    }

    fun unregister(v: GlassLinear) {
        val it = views.iterator()
        while (it.hasNext()) {
            val x = it.next().get()
            if (x == null || x === v) it.remove()
        }
    }

    fun ready(): Boolean = node != null && source != null

    fun record() {
        if (Build.VERSION.SDK_INT < 29) return
        val s = source ?: return
        val w = s.width
        val h = s.height
        if (w <= 0 || h <= 0) return
        val n = node ?: RenderNode("dsra-backdrop").also { node = it }
        n.setPosition(0, 0, w, h)
        val c = n.beginRecording(w, h)
        try {
            s.draw(c)
        } catch (_: Throwable) {
        } finally {
            n.endRecording()
        }
    }

    fun invalidateAll() {
        val hidden = drawerHidden()
        val it = views.iterator()
        while (it.hasNext()) {
            val v = it.next().get()
            if (v == null) it.remove() else if (v.isShown && !(hidden && v.inDrawer)) v.invalidate()
        }
    }

    fun tick() {
        if (views.isEmpty()) return
        record()
        invalidateAll()
    }

    /** default provider for in-app glass: draw the shared backdrop aligned to the glass view */
    fun draw(c: Canvas, v: View) {
        val n = node ?: return
        val s = source ?: return
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationInWindow(a)
        s.getLocationInWindow(b)
        // glass in the drawer: pretend the drawer is already open, whatever it is doing right now
        val dv = drawer
        if (dv != null && v is GlassLinear && v.inDrawer) a[0] -= dv.translationX.toInt()
        c.translate((b[0] - a[0]).toFloat(), (b[1] - a[1]).toFloat())
        c.drawRenderNode(n)
    }
}

/**
 * A LinearLayout with a liquid-glass material behind its children.
 * [modeOverride] ("off" / "lite" / "full") lets one view (the popups) use a different material than the UI.
 */
class GlassLinear(ctx: Context, modeOverride: String? = null) : LinearLayout(ctx) {
    /** true when this view lives inside the settings drawer (worked out when it is attached) */
    var inDrawer = false
        private set
    var radiusDp = 26
        set(v) {
            field = v
            rebuildPath()
            invalidate()
        }
    var band = false
    var tintTop = 0
    var tintBottom = 0
    var frostTop = Pal.CARD_T
    var frostBottom = Pal.CARD_B
    var provider: ((Canvas, View) -> Unit)? = null

    private val mode: String = modeOverride ?: ctx.glassMode()
    private val path = Path()
    private val rect = RectF()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val q = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var node: RenderNode? = null
    private var agsl: RuntimeShader? = null
    private var failed = false
    private var effectSet = false
    private var effW = 0
    private var effH = 0
    private var effR = 0f
    private var rpx = 0f
    private val strength: Float
    private val blurPx: Float
    private val strokeW = ctx.dp(1).toFloat() * 1.6f
    private var tintShader: LinearGradient? = null
    private var glareShader: LinearGradient? = null
    private var rimShader: LinearGradient? = null
    private var frostShader: LinearGradient? = null
    private var bandMask: LinearGradient? = null
    private var bandColor: LinearGradient? = null
    private val bandM = Matrix()

    init {
        val s = ctx.prefs().getString("glass_strength", "normal") ?: "normal"
        strength = when (s) {
            "soft" -> 0.6f
            "strong" -> 1.5f
            else -> 1f
        }
        blurPx = ctx.dp(
            when (s) {
                "soft" -> 5
                "strong" -> 12
                else -> 8
            }
        ).toFloat()
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rebuildPath()
    }

    private fun tints(): Pair<Int, Int> {
        if (tintTop != 0 || tintBottom != 0) return Pair(tintTop, tintBottom)
        return if (Pal.light) Pair(0x78FFFFFF, 0x46FFFFFF) else Pair(0x30FFFFFF, 0x14FFFFFF)
    }

    private fun rebuildPath() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        rect.set(0f, 0f, w, h)
        rpx = min(context.dp(radiusDp).toFloat(), min(w, h) / 2f)
        path.reset()
        path.addRoundRect(rect, rpx, rpx, Path.Direction.CW)
        val tt = tints()
        tintShader = LinearGradient(0f, 0f, 0f, h, tt.first, tt.second, Shader.TileMode.CLAMP)
        glareShader = LinearGradient(0f, 0f, 0f, h * 0.55f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        rimShader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xDDFFFFFF.toInt(), 0x33FFFFFF, 0x10FFFFFF, 0x66FFFFFF),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        frostShader = LinearGradient(0f, 0f, 0f, h, frostTop, frostBottom, Shader.TileMode.CLAMP)
        bandMask = LinearGradient(0f, h * 0.40f, 0f, h, 0x00000000, 0xC0000000.toInt(), Shader.TileMode.CLAMP)
        bandColor = LinearGradient(
            0f, 0f, w * 1.2f, 0f,
            intArrayOf(Pal.ACC1, Pal.ACC2, Pal.ACC1), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.MIRROR
        )
    }

    fun refreshColors() {
        rebuildPath()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        inDrawer = false
        val dv = GlassBackdrop.drawer
        if (dv != null) {
            var p = parent
            while (p != null) {
                if (p === dv) {
                    inDrawer = true
                    break
                }
                p = p.parent
            }
        }
        if (mode == "full") GlassBackdrop.register(this)
    }

    override fun onDetachedFromWindow() {
        GlassBackdrop.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (width > 0 && height > 0 && !path.isEmpty) drawGlass(canvas)
        super.dispatchDraw(canvas)
    }

    private fun backdropReady(): Boolean = provider != null || GlassBackdrop.ready()

    private fun drawGlass(c: Canvas) {
        val refract = mode == "full" && Build.VERSION.SDK_INT >= 33 && c.isHardwareAccelerated && backdropReady()
        c.save()
        c.clipPath(path)
        var drew = false
        if (refract) drew = drawRefracted(c)
        if (!drew) {
            p.shader = frostShader
            c.drawRect(rect, p)
            p.shader = null
        }
        p.shader = tintShader
        c.drawRect(rect, p)
        p.shader = null
        drawBand(c)
        p.shader = glareShader
        c.drawRect(rect, p)
        p.shader = null
        c.restore()
        q.style = Paint.Style.STROKE
        q.strokeWidth = strokeW
        q.shader = rimShader
        c.drawPath(path, q)
        q.shader = null
    }

    private fun drawRefracted(c: Canvas): Boolean {
        try {
            val w = width
            val h = height
            val n = node ?: RenderNode("dsra-glass").also { node = it }
            n.setPosition(0, 0, w, h)
            val rc = n.beginRecording(w, h)
            try {
                val pr = provider
                if (pr != null) pr(rc, this) else GlassBackdrop.draw(rc, this)
            } finally {
                n.endRecording()
            }
            // the effect only depends on the size and corner radius, so build it once and reuse it every frame
            if (!effectSet || effW != w || effH != h || effR != rpx) {
                val blur = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                var effect: RenderEffect = blur
                if (!failed) {
                    try {
                        val sh = agsl ?: RuntimeShader(AGSL).also { agsl = it }
                        val md = min(w, h).toFloat()
                        sh.setFloatUniform("size", w.toFloat(), h.toFloat())
                        sh.setFloatUniform("radius", rpx)
                        sh.setFloatUniform("bevel", max(1f, min(context.dp(26).toFloat(), md * 0.45f)))
                        sh.setFloatUniform("refr", min(context.dp(16).toFloat() * strength, md * 0.35f))
                        sh.setFloatUniform("disp", 0.09f)
                        effect = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(sh, "content"), blur)
                    } catch (e: Throwable) {
                        failed = true
                    }
                }
                n.setRenderEffect(effect)
                effectSet = true
                effW = w
                effH = h
                effR = rpx
            }
            c.drawRenderNode(n)
            return true
        } catch (e: Throwable) {
            return false
        }
    }

    private fun drawBand(c: Canvas) {
        if (!band) return
        val w = width.toFloat()
        val h = height.toFloat()
        val top = h * 0.40f
        val bc = bandColor ?: return
        val bm = bandMask ?: return
        val phase = (SystemClock.uptimeMillis() % 4000L) / 4000f
        bandM.reset()
        bandM.postTranslate(-phase * w * 2.4f, 0f)
        bc.setLocalMatrix(bandM)
        val sc = c.saveLayer(0f, top, w, h, null)
        p.shader = bc
        c.drawRect(0f, top, w, h, p)
        maskPaint.shader = bm
        c.drawRect(0f, top, w, h, maskPaint)
        c.restoreToCount(sc)
        p.shader = null
        postInvalidateOnAnimation()
    }
}
