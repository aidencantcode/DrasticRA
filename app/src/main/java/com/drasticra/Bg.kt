package com.drasticra

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class BgPreset(
    val key: String, val label: String, val style: String, val light: Boolean,
    val base: Int, val c1: Int, val c2: Int, val c3: Int, val acc1: Int, val acc2: Int
)

object BgPresets {
    private fun c(v: Long): Int = v.toInt()

    val all: List<BgPreset> = listOf(
        BgPreset("aurora", "Aurora", "aurora", true, c(0xFFEDEEF5), c(0xFF7C7CFF), c(0xFFB57CFF), c(0xFF5BC8FF), c(0xFF7C7CFF), c(0xFFB57CFF)),
        BgPreset("waves", "Ocean waves", "waves", false, c(0xFF06182E), c(0xFF1E88E5), c(0xFF00C6FF), c(0xFF7B61FF), c(0xFF00C6FF), c(0xFF4F7CFF)),
        BgPreset("bubbles", "Bubbles", "bubbles", false, c(0xFF0B1020), c(0xFFFF7CC8), c(0xFF7C9CFF), c(0xFF5BE0C8), c(0xFFFF7CC8), c(0xFF7C9CFF)),
        BgPreset("ps3", "PS3 waves", "ps3", false, c(0xFF05060A), c(0xFFBFD0FF), c(0xFF7A8CFF), c(0xFFFFFFFF), c(0xFF9FB4FF), c(0xFFE8EEFF)),
        BgPreset("dots", "iiSU dots", "dots", true, c(0xFFF1F2F7), c(0xFFA9ADC4), c(0xFFCDBBFF), c(0xFFFFC4DC), c(0xFF8B7CFF), c(0xFFFF7CC8)),
        BgPreset("vista", "Frutiger Aero", "vista", true, c(0xFFD8F1FF), c(0xFF2E8BFF), c(0xFF62D8FF), c(0xFF8EE05A), c(0xFF2E9BFF), c(0xFF48D17A)),
        BgPreset("flow", "Gradient flow", "flow", false, c(0xFF12081C), c(0xFFFF6A3D), c(0xFF7A3CFF), c(0xFF2F8BFF), c(0xFFFF7A45), c(0xFFB04BFF))
    )

    fun apply(ctx: Context, p: BgPreset) {
        ctx.prefs().edit()
            .putString("bg_type", "anim")
            .putString("bg_style", p.style)
            .putBoolean("bg_link", false)
            .putInt("bg_base", p.base)
            .putInt("bgc1", p.c1).putInt("bgc2", p.c2).putInt("bgc3", p.c3)
            .putString("theme", if (p.light) "light" else "dark")
            .putInt("acc1", p.acc1).putInt("acc2", p.acc2)
            .apply()
    }
}

/** Procedural animated backgrounds. Driven by BackgroundHost's ticker (it never schedules itself). */
class AnimBg(ctx: Context) : View(ctx) {
    var style = "aurora"
    var speed = 1f
    var animate = true
    var base = 0
    var c1 = 0
    var c2 = 0
    var c3 = 0

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density
    private var last = SystemClock.uptimeMillis()
    private var time = 0f
    private val blobCache = HashMap<Long, RadialGradient>()
    private val wavePath = Path()
    private val ribbon = Path()
    private val ray = Path()
    private var waveShaders: Array<LinearGradient>? = null
    private var ribbonShaders: Array<LinearGradient>? = null
    private var skyShader: LinearGradient? = null
    private val rnd = Random(11)
    private val bx = FloatArray(26) { rnd.nextFloat() }
    private val by = FloatArray(26) { rnd.nextFloat() }
    private val br = FloatArray(26) { 0.2f + rnd.nextFloat() * 0.8f }
    private val bs = FloatArray(26) { 0.4f + rnd.nextFloat() * 1.0f }
    private val bp = FloatArray(26) { rnd.nextFloat() * 6.28f }
    private val dotPts = Array(4) { FloatArray(4000) }
    private val dotCnt = IntArray(4)

    fun configure(sp: SharedPreferences) {
        style = sp.getString("bg_style", "aurora") ?: "aurora"
        speed = sp.getInt("bg_speed", 100) / 100f
        animate = sp.getBoolean("bg_anim", true)
        base = if (sp.contains("bg_base")) sp.getInt("bg_base", Pal.BG) else Pal.BG
        if (sp.getBoolean("bg_link", true)) {
            c1 = Pal.ACC1
            c2 = Pal.ACC2
            c3 = if (Pal.acc3On) Pal.ACC3 else Color.rgb(
                (Color.red(Pal.ACC1) + Color.red(Pal.ACC2)) / 2,
                (Color.green(Pal.ACC1) + Color.green(Pal.ACC2)) / 2,
                (Color.blue(Pal.ACC1) + Color.blue(Pal.ACC2)) / 2
            )
        } else {
            c1 = sp.getInt("bgc1", Pal.ACC1)
            c2 = sp.getInt("bgc2", Pal.ACC2)
            c3 = sp.getInt("bgc3", Pal.ACC1)
        }
        resetCaches()
        invalidate()
    }

    private fun resetCaches() {
        blobCache.clear()
        waveShaders = null
        ribbonShaders = null
        skyShader = null
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        resetCaches()
    }

    private fun blob(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val key = color.toLong() * 100003L + r.toLong()
        var sh = blobCache[key]
        if (sh == null) {
            sh = RadialGradient(0f, 0f, r, color, color.withAlpha(0), Shader.TileMode.CLAMP)
            blobCache[key] = sh
        }
        p.shader = sh
        c.save()
        c.translate(cx, cy)
        c.drawCircle(0f, 0f, r, p)
        c.restore()
        p.shader = null
    }

    private fun pick(i: Int): Int = when (i % 3) {
        0 -> c1
        1 -> c2
        else -> c3
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (animate) time += (now - last).coerceAtMost(100L) / 1000f * speed
        last = now
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        when (style) {
            "waves" -> drawWaves(c, w, h)
            "bubbles" -> drawBubbles(c, w, h)
            "ps3" -> drawPs3(c, w, h)
            "dots" -> drawDots(c, w, h)
            "vista" -> drawVista(c, w, h)
            "flow" -> drawFlow(c, w, h)
            else -> drawAurora(c, w, h)
        }
    }

    private fun drawAurora(c: Canvas, w: Float, h: Float) {
        c.drawColor(base)
        val t = time
        val r = max(w, h) * 0.6f
        val a = if (Pal.light) 0x70 else 0x58
        blob(c, w * (0.22f + 0.14f * sin(t * 0.25f)), h * (0.22f + 0.14f * cos(t * 0.21f)), r, c1.withAlpha(a))
        blob(c, w * (0.78f + 0.14f * cos(t * 0.19f)), h * (0.35f + 0.18f * sin(t * 0.23f)), r, c2.withAlpha(a))
        blob(c, w * (0.5f + 0.2f * sin(t * 0.17f)), h * (0.88f + 0.08f * cos(t * 0.27f)), r, c3.withAlpha(a))
    }

    private fun drawFlow(c: Canvas, w: Float, h: Float) {
        c.drawColor(base)
        val t = time
        val r = max(w, h) * 0.62f
        val dark = Color.red(base) + Color.green(base) + Color.blue(base) < 300
        if (dark) p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        for (i in 0 until 6) {
            val ang = t * 0.22f + i * 1.047f
            val cx = w * (0.5f + 0.38f * cos(ang + i))
            val cy = h * (0.5f + 0.34f * sin(ang * 1.27f + i * 0.6f))
            blob(c, cx, cy, r, pick(i).withAlpha(if (dark) 0xB0 else 0x90))
        }
        p.xfermode = null
    }

    private fun drawWaves(c: Canvas, w: Float, h: Float) {
        c.drawColor(base)
        val t = time
        blob(c, w * 0.85f, -h * 0.05f, max(w, h) * 0.7f, c1.withAlpha(0x66))
        var sh = waveShaders
        if (sh == null) {
            sh = Array(4) { i ->
                val col = pick(i)
                val baseY = h * (0.52f + 0.1f * i)
                LinearGradient(0f, baseY - h * 0.06f, 0f, h, col.withAlpha(0x90 - i * 0x14), col.withAlpha(0x14), Shader.TileMode.CLAMP)
            }
            waveShaders = sh
        }
        for (i in 0 until 4) {
            val baseY = h * (0.52f + 0.1f * i)
            val amp = h * (0.05f + 0.012f * i)
            val f = (2f * PI.toFloat()) / (w * (0.9f - 0.12f * i))
            wavePath.reset()
            wavePath.moveTo(0f, h)
            var x = 0f
            while (x <= w + 12f) {
                val y = baseY + amp * sin(x * f + t * (0.6f + 0.25f * i) + i * 1.7f) +
                    amp * 0.45f * sin(x * f * 2.1f - t * (0.4f + 0.2f * i))
                wavePath.lineTo(x, y)
                x += 12f
            }
            wavePath.lineTo(w + 12f, h)
            wavePath.close()
            p.shader = sh[i]
            c.drawPath(wavePath, p)
        }
        p.shader = null
    }

    private fun drawBubbles(c: Canvas, w: Float, h: Float) {
        c.drawColor(base)
        val t = time
        blob(c, w * 0.2f, h * 0.15f, max(w, h) * 0.5f, c1.withAlpha(0x30))
        blob(c, w * 0.85f, h * 0.9f, max(w, h) * 0.5f, c2.withAlpha(0x30))
        for (i in 0 until 26) {
            val r = (5f + br[i] * 38f) * d
            val prog = (by[i] + t * bs[i] * 0.02f) % 1f
            val y = h * (1.12f - prog * 1.3f)
            val x = bx[i] * w + sin(t * 0.3f + bp[i]) * 18f * d
            val col = pick(i)
            blob(c, x, y, r * 2.2f, col.withAlpha(0x70))
            p.color = col.withAlpha(0xB0)
            c.drawCircle(x, y, r * 0.5f, p)
        }
    }

    private fun drawPs3(c: Canvas, w: Float, h: Float) {
        c.drawColor(base)
        val t = time
        blob(c, w * 0.5f, h * 0.58f, max(w, h) * 0.7f, c2.withAlpha(0x26))
        var sh = ribbonShaders
        if (sh == null) {
            sh = Array(4) { i ->
                val col = pick(i)
                LinearGradient(
                    0f, 0f, w, 0f,
                    intArrayOf(col.withAlpha(0), col.withAlpha(0x66), col.withAlpha(0x2A), col.withAlpha(0)),
                    floatArrayOf(0f, 0.25f, 0.7f, 1f), Shader.TileMode.CLAMP
                )
            }
            ribbonShaders = sh
        }
        for (i in 0 until 4) {
            val y0 = h * (0.46f + 0.05f * i)
            val amp = h * (0.07f + 0.015f * i)
            val th = h * (0.05f + 0.02f * i)
            val f = 2f * PI.toFloat() / (w * (1.1f + 0.2f * i))
            ribbon.reset()
            ribbon.moveTo(0f, y0)
            var x = 0f
            while (x <= w + 16f) {
                ribbon.lineTo(x, y0 + amp * sin(x * f + t * (0.45f + 0.12f * i) + i) + amp * 0.4f * sin(x * f * 2.3f - t * 0.3f))
                x += 16f
            }
            x = w + 16f
            while (x >= 0f) {
                val yy = y0 + amp * sin(x * f + t * (0.45f + 0.12f * i) + i) + amp * 0.4f * sin(x * f * 2.3f - t * 0.3f)
                ribbon.lineTo(x, yy + th * (0.5f + 0.5f * sin(x / w * 6f + t * 0.5f + i)))
                x -= 16f
            }
            ribbon.close()
            p.shader = sh[i]
            c.drawPath(ribbon, p)
        }
        p.shader = null
        for (i in 0 until 26) {
            val a = (50 + 110 * (0.5f + 0.5f * sin(t * 1.3f + i * 2.1f))).toInt()
            p.color = c3.withAlpha(a)
            c.drawCircle(bx[i] * w + sin(t * 0.2f + i) * 6f * d, by[i] * h + cos(t * 0.17f + i) * 4f * d, 1.4f * d, p)
        }
    }

    private fun drawDots(c: Canvas, w: Float, h: Float) {
        c.drawColor(base)
        val t = time
        blob(c, w * (0.12f + 0.04f * sin(t * 0.2f)), h * 0.2f, max(w, h) * 0.5f, c2.withAlpha(0x70))
        blob(c, w * (0.9f + 0.03f * cos(t * 0.17f)), h * 0.85f, max(w, h) * 0.5f, c3.withAlpha(0x70))
        val step = 22f * d
        for (k in 0 until 4) dotCnt[k] = 0
        var y = step / 2f
        while (y < h) {
            var x = step / 2f
            while (x < w) {
                val a = 0.5f + 0.5f * sin(x * 0.012f + y * 0.008f - t * 0.9f)
                val lv = (a * 3.99f).toInt()
                val n = dotCnt[lv]
                if (n < 1998) {
                    dotPts[lv][n * 2] = x
                    dotPts[lv][n * 2 + 1] = y
                    dotCnt[lv] = n + 1
                }
                x += step
            }
            y += step
        }
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 2.6f * d
        val alphas = intArrayOf(0x22, 0x44, 0x6A, 0x90)
        for (k in 0 until 4) {
            p.color = c1.withAlpha(alphas[k])
            c.drawPoints(dotPts[k], 0, dotCnt[k] * 2, p)
        }
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = 0f
    }

    private fun drawVista(c: Canvas, w: Float, h: Float) {
        val t = time
        var sky = skyShader
        if (sky == null) {
            sky = LinearGradient(0f, 0f, 0f, h, intArrayOf(c1, c2, c3), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            skyShader = sky
        }
        p.shader = sky
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        p.color = Color.WHITE.withAlpha(0x22)
        val reach = max(w, h) * 2f
        for (i in 0 until 6) {
            val a0 = Math.toRadians((18.0 + i * 15.0 + 5.0 * sin(t * 0.12 + i)))
            val a1 = a0 + Math.toRadians(5.5)
            ray.reset()
            ray.moveTo(w * 0.12f, -h * 0.1f)
            ray.lineTo(w * 0.12f + (cos(a0) * reach).toFloat(), -h * 0.1f + (sin(a0) * reach).toFloat())
            ray.lineTo(w * 0.12f + (cos(a1) * reach).toFloat(), -h * 0.1f + (sin(a1) * reach).toFloat())
            ray.close()
            c.drawPath(ray, p)
        }
        p.color = c3.withAlpha(0x66)
        c.drawOval(-w * 0.25f, h * 0.9f, w * 1.25f, h * 1.5f, p)
        for (i in 0 until 10) {
            val r = (14f + br[i] * 40f) * d
            val prog = (by[i] + t * bs[i] * 0.015f) % 1f
            val y = h * (1.1f - prog * 1.25f)
            val x = bx[i] * w + sin(t * 0.25f + bp[i]) * 14f * d
            p.style = Paint.Style.FILL
            p.color = Color.WHITE.withAlpha(0x1E)
            c.drawCircle(x, y, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.6f * d
            p.color = Color.WHITE.withAlpha(0x90)
            c.drawCircle(x, y, r, p)
            p.style = Paint.Style.FILL
            p.color = Color.WHITE.withAlpha(0xB0)
            c.drawOval(x - r * 0.55f, y - r * 0.62f, x - r * 0.1f, y - r * 0.3f, p)
        }
        p.style = Paint.Style.FILL
        p.strokeWidth = 0f
    }
}

/** Dynamic background: animated preset (default) or your own image / GIF / video. */
class BackgroundHost(ctx: Context) : FrameLayout(ctx) {
    private var anim: AnimBg? = null
    private var video: VideoBg? = null
    private var gifAnimated = false
    private var running = false
    private var delayMs = 33L

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            anim?.invalidate()
            GlassBackdrop.tick()
            postDelayed(this, delayMs)
        }
    }

    private fun full(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    private fun needsTicker(): Boolean = (anim?.animate == true) || video != null || gifAnimated

    fun reload() {
        removeCallbacks(ticker)
        removeAllViews()
        anim = null
        video = null
        gifAnimated = false
        val sp = context.prefs()
        var type = sp.getString("bg_type", "anim") ?: "anim"
        if (type == "aurora") type = "anim"
        val f = File(sp.getString("bg_path", "") ?: "")
        var custom = false
        if (type == "image" && f.exists()) {
            custom = addImage(f)
        } else if (type == "video" && f.exists()) {
            val v = VideoBg(context, f)
            video = v
            addView(v, full())
            custom = true
        }
        if (!custom) {
            val a = AnimBg(context)
            a.configure(sp)
            anim = a
            addView(a, full())
        } else {
            val dim = sp.getInt("bg_dim", 35)
            val scrim = View(context)
            val baseC = if (Pal.light) Color.WHITE else Color.BLACK
            scrim.setBackgroundColor(baseC.withAlpha(dim * 255 / 100))
            addView(scrim, full())
        }
        delayMs = if (sp.getInt("bg_fps", 30) >= 60) 16L else 33L
        GlassBackdrop.source = this
        if (running) startTicker()
        post { GlassBackdrop.tick() }
    }

    fun refreshConfig() {
        anim?.configure(context.prefs())
        delayMs = if (context.prefs().getInt("bg_fps", 30) >= 60) 16L else 33L
        if (running) startTicker()
    }

    private fun startTicker() {
        removeCallbacks(ticker)
        if (needsTicker()) postDelayed(ticker, delayMs)
    }

    private fun addImage(f: File): Boolean {
        val iv = ImageView(context)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val dr = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                    val s = max(1, info.size.width / 1600)
                    if (s > 1) dec.setTargetSampleSize(s)
                }
                iv.setImageDrawable(dr)
                if (dr is AnimatedImageDrawable) {
                    dr.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    dr.start()
                    gifAnimated = true
                }
            } else {
                iv.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
            }
        } catch (_: Exception) {
            return false
        }
        addView(iv, full())
        return true
    }

    fun pause() {
        running = false
        removeCallbacks(ticker)
        video?.pausePlayer()
    }

    fun resume() {
        running = true
        video?.resumePlayer()
        startTicker()
        post { GlassBackdrop.tick() }
    }
}
