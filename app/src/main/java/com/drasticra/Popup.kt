package com.drasticra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Root screenshot of whatever is on screen (DraStic), so the popup glass can refract the real game. */
object ScreenGrab {
    private var last: Bitmap? = null
    private var lastAt = 0L

    fun capture(maxAgeMs: Long): Bitmap? {
        val now = SystemClock.uptimeMillis()
        val l = last
        if (l != null && now - lastAt < maxAgeMs) return l
        val b = grab()
        if (b != null) {
            last = b
            lastAt = now
        }
        return b
    }

    private fun grab(): Bitmap? {
        return try {
            val p = ProcessBuilder("su", "-c", "screencap").start()
            val bytes = p.inputStream.readBytes()
            p.waitFor()
            if (bytes.size < 64) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val w = bb.getInt(0)
            val h = bb.getInt(4)
            val f = bb.getInt(8)
            if (w <= 0 || h <= 0 || (f != 1 && f != 2)) return null
            val pix = w * h * 4
            val hdr = bytes.size - pix
            if (hdr != 12 && hdr != 16) return null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes, hdr, pix))
            bmp.setHasAlpha(false)
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    fun luma(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var sum = 0f
        var n = 0
        val sx = max(1, (x1 - x0) / 10)
        val sy = max(1, (y1 - y0) / 5)
        var y = y0.coerceIn(0, bmp.height - 1)
        while (y < min(y1, bmp.height)) {
            var x = x0.coerceIn(0, bmp.width - 1)
            while (x < min(x1, bmp.width)) {
                val c = bmp.getPixel(x, y)
                sum += (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
                n++
                x += sx
            }
            y += sy
        }
        return if (n == 0) 0.5f else sum / n
    }
}

private fun lightenC(c: Int, t: Float): Int = Color.rgb(
    (Color.red(c) + (255 - Color.red(c)) * t).toInt(),
    (Color.green(c) + (255 - Color.green(c)) * t).toInt(),
    (Color.blue(c) + (255 - Color.blue(c)) * t).toInt()
)

/**
 * Lights up the very edges of the display with a soft accent glow whose colours travel slowly around
 * the screen. It lives in its own full-screen, touch-through window behind the popup (so it can never
 * change the popup's size), flashes when a popup appears, breathes while it is up, and fades with [fadeOut].
 */
class EdgeGlowView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private val dst = RectF()
    private var sweep: SweepGradient? = null
    private var mask: Bitmap? = null
    private val bornAt = SystemClock.uptimeMillis()
    private var flashAt = 0L
    private var closeAt = 0L

    init {
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    fun flash() {
        flashAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun fadeOut() {
        if (closeAt == 0L) closeAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        sweep = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(Pal.ACC1, Pal.ACC2, lightenC(Pal.ACC2, 0.55f), Pal.ACC2, Pal.ACC1),
            floatArrayOf(0f, 0.3f, 0.5f, 0.75f, 1f)
        )
        mask = buildMask(w, h)
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    /** Small alpha-only picture: strongest right at the screen edge, melting away ~35dp inwards. */
    private fun buildMask(w: Int, h: Int): Bitmap? {
        return try {
            val sc = 0.3f
            val d = context.resources.displayMetrics.density
            val bw = max(8, (w * sc).toInt())
            val bh = max(8, (h * sc).toInt())
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.style = Paint.Style.STROKE
            p.color = Color.BLACK
            p.strokeWidth = 26f * d * sc
            p.maskFilter = BlurMaskFilter(22f * d * sc, BlurMaskFilter.Blur.NORMAL)
            val r = 34f * d * sc
            Canvas(bmp).drawRoundRect(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), r, r, p)
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    override fun onDraw(c: Canvas) {
        val bm = mask ?: return
        val s = sweep ?: return
        val now = SystemClock.uptimeMillis()
        val tIn = ((now - bornAt) / 420f).coerceIn(0f, 1f)
        val inK = tIn * tIn * (3f - 2f * tIn)
        val outK = if (closeAt == 0L) 1f else 1f - ((now - closeAt) / 240f).coerceIn(0f, 1f)
        val flashAge = (now - flashAt) / 700f
        val fl = if (flashAt != 0L && flashAge < 1f) 1f - flashAge else 0f
        val pulse = 0.82f + 0.18f * sin(now / 420f)
        val k = (inK * outK * pulse * (1f + 0.45f * fl)).coerceIn(0f, 1f)

        val w = width.toFloat()
        val h = height.toFloat()
        m.setRotate((now % 6000L) / 6000f * 360f, w / 2f, h / 2f)
        s.setLocalMatrix(m)
        fill.shader = s
        val sc = c.saveLayerAlpha(0f, 0f, w, h, (255f * k).toInt())
        c.drawRect(0f, 0f, w, h, fill)
        c.drawBitmap(bm, null, dst, maskPaint)
        c.restoreToCount(sc)
        if (outK > 0f) postInvalidateOnAnimation()
    }
}

/**
 * Liquid-glass achievement toasts shown over the game. Tap one to open it up (why you got it + your
 * total points); otherwise it closes by itself. Popups queue and play one after another.
 */
class Popup(private val ctx: Context) {
    private class Data(
        val label: String, val title: String, val chip: String, val sub: String, val iconUrl: String?,
        val desc: String, val extra: String, val pct: Int, val expandable: Boolean, val ms: Long
    )

    private class Pop(val root: FrameLayout, val glass: LinearLayout, val expandBox: View?)

    private val ui = Handler(Looper.getMainLooper())
    private val queue = LinkedList<Data>()
    private var showing = false
    private var current: View? = null
    private var currentEdge: EdgeGlowView? = null
    private var pendingDismiss: Runnable? = null

    private fun wm(): WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun secs(): Long = (ctx.prefs().getInt("popup_secs", 5)) * 1000L

    private fun fmt(n: Int): String = String.format("%,d", n)

    /** Full-screen, touch-through window for the edge glow. Alpha stays <= 0.8 so Android 12+ lets touches reach the game. */
    private fun edgeParams(): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.alpha = 0.78f
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    private fun addEdge(): EdgeGlowView? {
        return try {
            val e = EdgeGlowView(ctx)
            wm().addView(e, edgeParams())
            currentEdge = e
            e
        } catch (_: Exception) {
            null
        }
    }

    private fun removeEdge(e: View?) {
        if (e == null) return
        try {
            wm().removeView(e)
        } catch (_: Exception) {
        }
        if (currentEdge === e) currentEdge = null
    }

    fun hide() {
        ui.post {
            queue.clear()
            showing = false
            pendingDismiss?.let { ui.removeCallbacks(it) }
            val v = current
            if (v != null) {
                try {
                    wm().removeView(v)
                } catch (_: Exception) {
                }
            }
            current = null
            removeEdge(currentEdge)
        }
    }

    private fun enqueue(d: Data) {
        ui.post {
            queue.add(d)
            if (!showing) next()
        }
    }

    fun unlock(title: String, desc: String, pts: String, badge: String?, game: String, total: Int) {
        val url = if (badge.isNullOrEmpty()) null else "$MEDIA/Badge/$badge.png"
        val extra = if (total > 0) "Total: " + fmt(total) + " pts" else ""
        enqueue(Data("Achievement unlocked", title, "+$pts", game, url, desc, extra, -1, true, secs()))
    }

    fun progress(title: String, text: String, badge: String?) {
        val url = if (badge.isNullOrEmpty()) null else "$MEDIA/Badge/$badge.png"
        enqueue(Data("Progress", title, text, "", url, "", "", -1, false, 2600L))
    }

    fun game(title: String, iconName: String, got: Int, total: Int, pts: String) {
        val url = if (iconName.isEmpty()) null else "$MEDIA/Images/$iconName.png"
        val pct = if (total > 0) got * 100 / total else 0
        enqueue(Data("Tracking game", title, "$pct%", "$got / $total achievements", url, "", pts, pct, true, secs()))
    }

    fun mastered() {
        enqueue(Data("Game mastered", "You earned every achievement", "100%", "", null, "", "", 100, false, 6000L))
    }

    private fun next() {
        val d = queue.poll()
        if (d == null) {
            showing = false
            return
        }
        showing = true
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, d.title + "\n" + d.sub, Toast.LENGTH_LONG).show()
            ui.postDelayed({ next() }, 400)
            return
        }
        val capture = ctx.glassMode() != "off" && ctx.prefs().getBoolean("popup_capture", true)
        Thread {
            val bmp = if (capture) ScreenGrab.capture(1500L) else null
            ui.post { present(d, bmp) }
        }.start()
    }

    private fun present(d: Data, bmp: Bitmap?) {
        try {
            Pal.load(ctx)
            Fonts.load(ctx)
            val top = (ctx.prefs().getString("popup_pos", "top") ?: "top") != "bottom"
            val pop = build(d, bmp, top)
            // edge glow goes in first so the popup window sits above it
            val edge = addEdge()
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            lp.y = ctx.dp(2)
            val v = pop.root
            wm().addView(v, lp)
            current = v

            val off = (if (top) -ctx.dp(70) else ctx.dp(70)).toFloat()
            v.alpha = 0f
            v.scaleX = 0.55f
            v.scaleY = 0.55f
            v.translationY = off
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(520).setInterpolator(OvershootInterpolator(1.8f)).start()
            edge?.flash()

            var expanded = false
            val schedule = { ms: Long ->
                pendingDismiss?.let { ui.removeCallbacks(it) }
                val r = Runnable { dismiss(v, off, edge) }
                pendingDismiss = r
                ui.postDelayed(r, ms)
            }
            schedule(d.ms)
            v.setOnClickListener {
                if (!d.expandable || expanded) {
                    pendingDismiss?.let { ui.removeCallbacks(it) }
                    dismiss(v, off, edge)
                } else {
                    expanded = true
                    val ts = TransitionSet()
                    ts.addTransition(ChangeBounds())
                    ts.addTransition(Fade())
                    ts.duration = 300
                    ts.interpolator = OvershootInterpolator(0.8f)
                    TransitionManager.beginDelayedTransition(pop.root, ts)
                    pop.expandBox?.visibility = View.VISIBLE
                    (pop.glass as? GlassLinear)?.radiusDp = 30
                    edge?.flash()
                    schedule(d.ms + 3500L)
                }
            }
        } catch (e: Exception) {
            removeEdge(currentEdge)
            Toast.makeText(ctx, d.title, Toast.LENGTH_LONG).show()
            ui.postDelayed({ next() }, 400)
        }
    }

    private fun dismiss(v: View, off: Float, edge: EdgeGlowView?) {
        if (v.tag == "gone") return
        v.tag = "gone"
        edge?.fadeOut()
        v.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).translationY(off * 0.5f).setDuration(240).withEndAction {
            try {
                wm().removeView(v)
            } catch (_: Exception) {
            }
            removeEdge(edge)
            if (current === v) current = null
            ui.postDelayed({ next() }, 260)
        }.start()
    }

    private fun build(d: Data, bmp: Bitmap?, top: Boolean): Pop {
        val dm = ctx.resources.displayMetrics
        val mode = ctx.glassMode()
        // pick readable text colour from what is actually behind the popup
        var lightScene = mode != "off" && bmp == null
        if (bmp != null) {
            val bw = bmp.width
            val bh = bmp.height
            val y0 = if (top) ctx.dp(8) else bh - ctx.dp(110)
            lightScene = ScreenGrab.luma(bmp, (bw * 0.2f).toInt(), y0, (bw * 0.8f).toInt(), y0 + ctx.dp(100)) > 0.5f
        }
        val solid = mode == "off"
        val textC = if (lightScene && !solid) 0xFF1B1D26.toInt() else Color.WHITE
        val subC = textC.withAlpha(0xB8)
        val accent = if (lightScene && !solid) Pal.ACC1 else Color.WHITE

        val root = FrameLayout(ctx)
        root.setPadding(ctx.dp(20), ctx.dp(14), ctx.dp(20), ctx.dp(24))
        root.clipChildren = false
        root.clipToPadding = false

        val glass: LinearLayout
        if (solid) {
            glass = LinearLayout(ctx)
            val bg = GradientDrawable()
            bg.setColor(0xFF23262F.toInt())
            bg.cornerRadius = ctx.dp(34).toFloat()
            glass.background = bg
        } else {
            val g = GlassLinear(ctx)
            g.radiusDp = 34
            g.band = true
            if (lightScene) {
                g.tintTop = 0x66FFFFFF
                g.tintBottom = 0x33FFFFFF
                g.frostTop = 0xF0F4F2FB.toInt()
                g.frostBottom = 0xF0DEDAF2.toInt()
            } else {
                g.tintTop = 0x30FFFFFF
                g.tintBottom = 0x18FFFFFF
                g.frostTop = 0xF02B2E3A.toInt()
                g.frostBottom = 0xF01D2029.toInt()
            }
            g.refreshColors()
            if (bmp != null) {
                g.provider = { c, v ->
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    c.drawBitmap(bmp, -loc[0].toFloat(), -loc[1].toFloat(), null)
                }
            }
            glass = g
        }
        glass.orientation = LinearLayout.VERTICAL
        glass.minimumWidth = min(ctx.dp(300), dm.widthPixels - ctx.dp(60))
        root.addView(glass, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(16), ctx.dp(12))

        val img = ImageView(ctx)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.background = ctx.rounded(0x33000000, 16)
        img.clipRound(16)
        if (d.iconUrl.isNullOrEmpty()) {
            img.scaleType = ImageView.ScaleType.CENTER_INSIDE
            ctx.pad(img, 12, 12, 12, 12)
            img.setImageResource(R.drawable.ic_trophy)
            img.setColorFilter(if (lightScene && !solid) Pal.ACC1 else Color.WHITE)
        } else {
            Img.load(d.iconUrl, img)
        }
        row.addView(img, ctx.ll(ctx.dp(56), ctx.dp(56), r = 14))

        val maxText = min(ctx.dp(240), dm.widthPixels - ctx.dp(210))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val l0 = ctx.tv(d.label.uppercase(), 10f, accent, true)
        l0.letterSpacing = 0.12f
        val l1 = ctx.tv(d.title, 16f, textC, true)
        l1.maxLines = 1
        l1.ellipsize = android.text.TextUtils.TruncateAt.END
        l1.maxWidth = maxText
        col.addView(l0)
        col.addView(l1)
        if (d.sub.isNotEmpty()) {
            val l2 = ctx.tv(d.sub, 11f, subC)
            l2.maxLines = 1
            l2.ellipsize = android.text.TextUtils.TruncateAt.END
            l2.maxWidth = maxText
            col.addView(l2)
        }
        row.addView(col, ctx.ll(WRAP, WRAP, 1f))

        val chip = ctx.tv(d.chip, 13f, Color.WHITE, true)
        ctx.pad(chip, 11, 5, 11, 5)
        chip.background = ctx.accentFill(15)
        row.addView(chip, ctx.ll(WRAP, WRAP, l = 10))
        glass.addView(row)

        var expandBox: View? = null
        if (d.expandable) {
            val ex = LinearLayout(ctx)
            ex.orientation = LinearLayout.VERTICAL
            ex.setPadding(ctx.dp(18), 0, ctx.dp(18), ctx.dp(14))
            ex.visibility = View.GONE
            if (d.desc.isNotEmpty()) {
                val t = ctx.tv(d.desc, 13f, textC)
                t.maxWidth = ctx.dp(300)
                ex.addView(t)
            }
            if (d.pct >= 0) ex.addView(ctx.progressBar(d.pct, 6, false), ctx.ll(MATCH, WRAP, t = 8))
            if (d.extra.isNotEmpty()) ex.addView(ctx.tv(d.extra, 12f, accent, true), ctx.ll(WRAP, WRAP, t = 8))
            ex.addView(ctx.tv("Tap again to close", 10f, subC), ctx.ll(WRAP, WRAP, t = 6))
            glass.addView(ex)
            expandBox = ex
        }
        return Pop(root, glass, expandBox)
    }
}
