package com.drasticra

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.View
import java.io.File
import kotlin.math.max

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
