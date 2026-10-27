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

fun Context.prefs(): android.content.SharedPreferences = getSharedPreferences("dsra", Context.MODE_PRIVATE)

/** True for Nintendo DS / DSi games (RetroAchievements console names). */
fun isDsConsole(c: String): Boolean = c.contains("Nintendo DS", ignoreCase = true)

/** "full" = refraction glass, "lite" = frosted look without backdrop sampling, "off" = plain cards */
fun Context.glassMode(): String = prefs().getString("glass_mode", "full") ?: "full"

/** Popups can have their own glass material ("off" / "lite" / "full"); anything else (default "same") follows the UI glass. */
fun Context.popupGlassMode(): String {
    val v = prefs().getString("popup_glass", "same") ?: "same"
    return if (v == "off" || v == "lite" || v == "full") v else glassMode()
}

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

/** Picture loader: memory cache first, then a copy on disk (instant + works offline on the next launch), then the network. */
object Img {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var dir: File? = null

    /** Turns the disk copy on. Safe to call many times. */
    fun init(ctx: Context) {
        if (dir != null) return
        val d = File(ctx.applicationContext.cacheDir, "img")
        d.mkdirs()
        dir = d
        pool.execute { trim(d) }
    }

    private fun name(url: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Keeps the disk copy under ~48 MB by dropping the least recently used files. */
    private fun trim(d: File) {
        try {
            val files = d.listFiles() ?: return
            var total = 0L
            for (x in files) total += x.length()
            if (total <= 48L * 1024 * 1024) return
            for (x in files.sortedBy { it.lastModified() }) {
                if (total <= 32L * 1024 * 1024) break
                total -= x.length()
                x.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun fetch(url: String): Bitmap? {
        val hit = cache.get(url)
        if (hit != null) return hit
        val d = dir
        val f = if (d != null) File(d, name(url)) else null
        if (f != null && f.length() > 0) {
            val b = BitmapFactory.decodeFile(f.absolutePath)
            if (b != null) {
                f.setLastModified(System.currentTimeMillis())
                cache.put(url, b)
                return b
            }
            f.delete()
        }
        val c = URL(url).openConnection()
        c.connectTimeout = 10000
        c.readTimeout = 15000
        val bytes = c.getInputStream().use { it.readBytes() }
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        cache.put(url, b)
        if (f != null) {
            try {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(f)
            } catch (_: Exception) {
            }
        }
        return b
    }

    fun load(url: String, into: ImageView) {
        into.tag = url
        val hit = cache.get(url)
        if (hit != null) {
            into.setImageBitmap(hit)
            return
        }
        pool.execute {
            try {
                val b = fetch(url)
                if (b != null) ui.post { if (into.tag == url) into.setImageBitmap(b) }
            } catch (_: Exception) {
            }
        }
    }

    /** Warm the caches for a picture that will be shown soon. */
    fun prefetch(url: String) {
        if (url.isEmpty() || cache.get(url) != null) return
        pool.execute {
            try {
                fetch(url)
            } catch (_: Exception) {
            }
        }
    }
}

/** Play time per RetroAchievements game id, written by the engine while it watches DraStic. */
object PlayTime {
    fun ms(ctx: Context, gameId: Int): Long = if (gameId <= 0) 0L else ctx.prefs().getLong("play_ms_$gameId", 0L)

    /** "<1m", "42m", "3h 05m", "120h"; empty when nothing has been tracked yet. */
    fun format(ms: Long): String {
        if (ms <= 0L) return ""
        val min = ms / 60000L
        if (min < 1L) return "<1m"
        if (min < 60L) return min.toString() + "m"
        val h = min / 60L
        val m = min % 60L
        return if (h >= 100L || m == 0L) h.toString() + "h" else h.toString() + "h " + String.format("%02d", m) + "m"
    }
}
