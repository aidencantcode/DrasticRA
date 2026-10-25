package com.drasticra

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File
import java.net.URL
import java.util.concurrent.Executors

const val MEDIA = "https://media.retroachievements.org"

fun Int.withAlpha(a: Int): Int = (this and 0x00FFFFFF) or (a shl 24)

object Pal {
    var light = true
    var BG = 0
    var CARD_T = 0
    var CARD_B = 0
    var FIELD = 0
    var TEXT = 0
    var SUB = 0
    var DIV = 0
    var TRACK = 0
    var PANEL = 0
    var ACC1 = 0xFF7C7CFF.toInt()
    var ACC2 = 0xFFB57CFF.toInt()
    val GOOD = 0xFF4ADE80.toInt()
    val GOLD1 = 0xFFFFD36B.toInt()
    val GOLD2 = 0xFFF2B33D.toInt()

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("dsra", Context.MODE_PRIVATE)
        light = (p.getString("theme", "light") ?: "light") == "light"
        ACC1 = p.getInt("acc1", 0xFF7C7CFF.toInt())
        ACC2 = p.getInt("acc2", 0xFFB57CFF.toInt())
        if (light) {
            BG = 0xFFEDEEF5.toInt()
            CARD_T = 0xF2FFFFFF.toInt()
            CARD_B = 0xF2E9E8F6.toInt()
            FIELD = 0xFFE2E3EE.toInt()
            TEXT = 0xFF23242C.toInt()
            SUB = 0xFF7B8094.toInt()
            DIV = 0x24000000
            TRACK = 0x26000000
            PANEL = 0xFFF4F4FA.toInt()
        } else {
            BG = 0xFF0C0D12.toInt()
            CARD_T = 0xF2262935.toInt()
            CARD_B = 0xF2181A22.toInt()
            FIELD = 0xFF2A2E3B.toInt()
            TEXT = 0xFFF1F2F7.toInt()
            SUB = 0xFF8C93A8.toInt()
            DIV = 0x26FFFFFF
            TRACK = 0x33FFFFFF
            PANEL = 0xFF14161D.toInt()
        }
    }
}

object Fonts {
    var title: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    var body: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun load(ctx: Context) {
        val f = File(ctx.filesDir, "font.ttf")
        if (f.exists()) {
            try {
                val t = Typeface.createFromFile(f)
                title = Typeface.create(t, Typeface.BOLD)
                body = t
                return
            } catch (_: Exception) {
            }
        }
        title = Typeface.create("sans-serif-black", Typeface.NORMAL)
        body = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radiusDp: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(radiusDp).toFloat()
    return g
}

fun Context.accentFill(radiusDp: Int): GradientDrawable {
    val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.ACC1, Pal.ACC2))
    g.cornerRadius = dp(radiusDp).toFloat()
    return g
}

fun Context.goldFill(radiusDp: Int): GradientDrawable {
    val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.GOLD1, Pal.GOLD2))
    g.cornerRadius = dp(radiusDp).toFloat()
    return g
}

fun Context.glassCard(radiusDp: Int): GradientDrawable {
    val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Pal.CARD_T, Pal.CARD_B))
    g.cornerRadius = dp(radiusDp).toFloat()
    return g
}

fun withRipple(content: Drawable): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x33808080), content, null)

fun View.clipRound(radiusDp: Int) {
    val r = context.dp(radiusDp).toFloat()
    clipToOutline = true
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, r)
        }
    }
}

fun View.clipCircle() {
    clipToOutline = true
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }
}

fun Context.progressBar(pct: Int, heightDp: Int, gold: Boolean): LinearLayout {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.weightSum = 100f
    row.background = rounded(Pal.TRACK, heightDp)
    val p = pct.coerceIn(0, 100)
    val fill = View(this)
    fill.background = if (gold) goldFill(heightDp) else accentFill(heightDp)
    row.addView(fill, LinearLayout.LayoutParams(0, dp(heightDp), p.toFloat()))
    row.addView(View(this), LinearLayout.LayoutParams(0, dp(heightDp), (100 - p).toFloat()))
    return row
}

object Img {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())

    fun load(url: String, into: ImageView) {
        into.tag = url
        val hit = cache.get(url)
        if (hit != null) {
            into.setImageBitmap(hit)
            return
        }
        pool.execute {
            try {
                val b = BitmapFactory.decodeStream(URL(url).openStream())
                if (b != null) {
                    cache.put(url, b)
                    ui.post { if (into.tag == url) into.setImageBitmap(b) }
                }
            } catch (_: Exception) {
            }
        }
    }
}
