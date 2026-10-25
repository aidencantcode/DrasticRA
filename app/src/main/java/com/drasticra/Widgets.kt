package com.drasticra

import android.content.Context
import android.graphics.AnimatedImageDrawable
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Small battery glyph for the top bar. */
class BatteryView(ctx: Context) : View(ctx) {
    var level = 100
    var charging = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(context.dp(30), context.dp(14))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val nub = h * 0.22f
        val body = w - nub
        p.style = Paint.Style.FILL
        p.color = Pal.TEXT.withAlpha(0x33)
        c.drawRoundRect(0f, 0f, body, h, h * 0.3f, h * 0.3f, p)
        p.color = if (level <= 15 && !charging) 0xFFE5484D.toInt() else Pal.TEXT
        val inset = h * 0.15f
        val fw = (body - 2 * inset) * level.coerceIn(0, 100) / 100f
        c.drawRoundRect(inset, inset, inset + fw, h - inset, h * 0.2f, h * 0.2f, p)
        p.color = Pal.TEXT.withAlpha(0x66)
        c.drawRoundRect(body + nub * 0.2f, h * 0.3f, w, h * 0.7f, nub * 0.4f, nub * 0.4f, p)
    }
}

/** Slowly drifting colour blobs in the accent colours. */
class AuroraView(ctx: Context) : View(ctx) {
    var animate = true
    var running = true
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t0 = SystemClock.uptimeMillis()

    private fun blob(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        p.shader = RadialGradient(cx, cy, r, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Pal.BG)
        val w = width.toFloat()
        val h = height.toFloat()
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        val r = max(w, h) * 0.6f
        val a = if (Pal.light) 0x70 else 0x55
        val mid = android.graphics.Color.rgb(
            (Color.red(Pal.ACC1) + Color.red(Pal.ACC2)) / 2,
            (Color.green(Pal.ACC1) + Color.green(Pal.ACC2)) / 2,
            (Color.blue(Pal.ACC1) + Color.blue(Pal.ACC2)) / 2
        )
        blob(c, w * (0.22f + 0.14f * sin(t * 0.25f)), h * (0.22f + 0.14f * cos(t * 0.21f)), r, Pal.ACC1.withAlpha(a))
        blob(c, w * (0.78f + 0.14f * cos(t * 0.19f)), h * (0.35f + 0.18f * sin(t * 0.23f)), r, Pal.ACC2.withAlpha(a))
        blob(c, w * (0.5f + 0.2f * sin(t * 0.17f)), h * (0.88f + 0.08f * cos(t * 0.27f)), r, mid.withAlpha(a))
        p.shader = null
        if (animate && running) postInvalidateDelayed(33)
    }
}

/** Looping, muted, cover-scaled video background. */
class VideoBg(ctx: Context, private val file: File) : TextureView(ctx), TextureView.SurfaceTextureListener {
    private var mp: MediaPlayer? = null
    private var vw = 0
    private var vh = 0

    init {
        surfaceTextureListener = this
    }

    private fun fit() {
        if (vw == 0 || vh == 0 || width == 0 || height == 0) return
        val s = max(width.toFloat() / vw, height.toFloat() / vh)
        val m = Matrix()
        m.setScale(vw * s / width, vh * s / height, width / 2f, height / 2f)
        setTransform(m)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        try {
            val m = MediaPlayer()
            m.setDataSource(file.absolutePath)
            m.setSurface(Surface(st))
            m.isLooping = true
            m.setVolume(0f, 0f)
            m.setOnVideoSizeChangedListener { _, a, b ->
                vw = a
                vh = b
                fit()
            }
            m.setOnPreparedListener { it.start() }
            m.prepareAsync()
            mp = m
        } catch (_: Exception) {
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        fit()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        try {
            mp?.release()
        } catch (_: Exception) {
        }
        mp = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    fun pausePlayer() {
        try {
            mp?.pause()
        } catch (_: Exception) {
        }
    }

    fun resumePlayer() {
        try {
            mp?.start()
        } catch (_: Exception) {
        }
    }
}

/** Dynamic background: animated aurora (default) or your own image / GIF / video. */
class BackgroundHost(ctx: Context) : FrameLayout(ctx) {
    private var aurora: AuroraView? = null
    private var video: VideoBg? = null

    private fun full(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    fun reload() {
        removeAllViews()
        aurora = null
        video = null
        val p = context.getSharedPreferences("dsra", Context.MODE_PRIVATE)
        val type = p.getString("bg_type", "aurora") ?: "aurora"
        val path = p.getString("bg_path", "") ?: ""
        val f = File(path)
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
            val a = AuroraView(context)
            a.animate = p.getBoolean("bg_anim", true)
            aurora = a
            addView(a, full())
        } else {
            val dim = p.getInt("bg_dim", 35)
            val scrim = View(context)
            val base = if (Pal.light) Color.WHITE else Color.BLACK
            scrim.setBackgroundColor(base.withAlpha(dim * 255 / 100))
            addView(scrim, full())
        }
    }

    private fun addImage(f: File): Boolean {
        val iv = ImageView(context)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                    val s = max(1, info.size.width / 1600)
                    if (s > 1) dec.setTargetSampleSize(s)
                }
                iv.setImageDrawable(d)
                if (d is AnimatedImageDrawable) {
                    d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    d.start()
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
        aurora?.running = false
        video?.pausePlayer()
    }

    fun resume() {
        aurora?.let {
            it.running = true
            it.invalidate()
        }
        video?.resumePlayer()
    }
}

/** Xbox-360-style badge: dark disc, four-segment ring that lights up in your accent gradient, icon in the middle. */
class RingBadge(ctx: Context, private val fill: Float) : FrameLayout(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val image = ImageView(ctx)

    init {
        setWillNotDraw(false)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0xFF1B1B1B.toInt())
        image.background = bg
        image.clipCircle()
        val size = ctx.dp(38)
        val lp = FrameLayout.LayoutParams(size, size)
        lp.gravity = Gravity.CENTER
        addView(image, lp)
    }

    fun setIcon(url: String?) {
        if (url.isNullOrEmpty()) {
            image.scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = context.dp(7)
            image.setPadding(pad, pad, pad, pad)
            image.setImageResource(R.drawable.ic_trophy)
            image.setColorFilter(Pal.ACC1)
        } else {
            Img.load(url, image)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = height / 2f
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = 0xFF050505.toInt()
        c.drawCircle(cx, cy, w / 2f, p)
        val sw = w * 0.095f
        val r = w / 2f - sw * 0.95f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.strokeCap = Paint.Cap.BUTT
        val starts = floatArrayOf(185f, 275f, 5f, 95f)
        for (i in 0..3) {
            p.shader = null
            p.color = 0xFF6F6C6B.toInt()
            c.drawArc(rect, starts[i], 80f, false, p)
            val lit = (fill - i).coerceIn(0f, 1f)
            if (lit > 0f) {
                p.shader = LinearGradient(0f, 0f, w, w, Pal.ACC1, Pal.ACC2, Shader.TileMode.CLAMP)
                c.drawArc(rect, starts[i], 80f * lit, false, p)
            }
        }
        p.shader = null
    }
}
