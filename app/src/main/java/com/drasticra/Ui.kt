package com.drasticra

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View

object Pal {
    val BG = 0xFF0E1016.toInt()
    val CARD = 0xFF171A22.toInt()
    val FIELD = 0xFF222633.toInt()
    val TEXT = 0xFFF2F3F8.toInt()
    val SUB = 0xFF8C93A8.toInt()
    val ACC1 = 0xFF7C7CFF.toInt()
    val ACC2 = 0xFFB57CFF.toInt()
    val GOOD = 0xFF4ADE80.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radiusDp: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(radiusDp).toFloat()
    return g
}

fun Context.gradientPill(radiusDp: Int): GradientDrawable {
    val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.ACC1, Pal.ACC2))
    g.cornerRadius = dp(radiusDp).toFloat()
    return g
}

fun withRipple(content: Drawable): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null)

const val MEDIA = "https://media.retroachievements.org"

fun View.clipRound(radiusDp: Int) {
    val r = context.dp(radiusDp).toFloat()
    clipToOutline = true
    outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, r)
        }
    }
}

fun View.clipCircle() {
    clipToOutline = true
    outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }
}

fun Context.progressBar(pct: Int, heightDp: Int = 6): android.widget.LinearLayout {
    val row = android.widget.LinearLayout(this)
    row.orientation = android.widget.LinearLayout.HORIZONTAL
    row.weightSum = 100f
    row.background = rounded(0x33FFFFFF, heightDp)
    val p = pct.coerceIn(0, 100)
    val fill = View(this)
    fill.background = gradientPill(heightDp)
    row.addView(fill, android.widget.LinearLayout.LayoutParams(0, dp(heightDp), p.toFloat()))
    row.addView(View(this), android.widget.LinearLayout.LayoutParams(0, dp(heightDp), (100 - p).toFloat()))
    return row
}

object Img {
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    fun load(url: String, into: android.widget.ImageView) {
        into.tag = url
        val hit = cache.get(url)
        if (hit != null) {
            into.setImageBitmap(hit)
            return
        }
        pool.execute {
            try {
                val b = android.graphics.BitmapFactory.decodeStream(java.net.URL(url).openStream())
                if (b != null) {
                    cache.put(url, b)
                    ui.post { if (into.tag == url) into.setImageBitmap(b) }
                }
            } catch (_: Exception) {
            }
        }
    }
}
