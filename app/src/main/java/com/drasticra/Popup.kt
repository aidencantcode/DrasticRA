package com.drasticra

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Glass popups at the top of the screen, over any app (needs "draw over other apps"). */
class Popup(private val ctx: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun hide() {
        ui.post { removeOverlay() }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean): TextView {
        val v = TextView(ctx)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.typeface = medium
        return v
    }

    /** gradient rim + translucent dark glass body */
    private fun glass(content: View): View {
        val outer = LinearLayout(ctx)
        val rim = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xCC4FD1FF.toInt(), 0xCC8B7CFF.toInt(), 0xCCFF7CC8.toInt())
        )
        rim.cornerRadius = ctx.dp(24).toFloat()
        outer.background = rim
        outer.setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
        val card = LinearLayout(ctx)
        card.background = ctx.rounded(0xB8101319.toInt(), 22)
        card.setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(22), ctx.dp(14))
        card.addView(content)
        outer.addView(card)
        return outer
    }

    private fun present(view: View, ms: Long, fallback: String) {
        try {
            if (!Settings.canDrawOverlays(ctx)) throw IllegalStateException("no overlay permission")
            removeOverlay()
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = ctx.dp(18)
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(view, lp)
            overlay = view
            ui.postDelayed({ if (overlay === view) removeOverlay() }, ms)
        } catch (e: Exception) {
            Toast.makeText(ctx, fallback, Toast.LENGTH_LONG).show()
        }
    }

    private fun icon(sizeDp: Int, radiusDp: Int, url: String?): ImageView {
        val img = ImageView(ctx)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.background = ctx.rounded(0xFF2A2E3B.toInt(), radiusDp)
        img.clipRound(radiusDp)
        if (!url.isNullOrEmpty()) Img.load(url, img)
        return img
    }

    /** achievement unlock / progress */
    fun show(label: String, title: String, desc: String, badge: String?, ms: Long) {
        ui.post {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val lpI = LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52))
            lpI.rightMargin = ctx.dp(14)
            row.addView(icon(52, 12, if (badge.isNullOrEmpty()) null else "$MEDIA/Badge/$badge.png"), lpI)
            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            val t0 = text(label, 10f, 0xFF9FD8FF.toInt(), true)
            t0.letterSpacing = 0.12f
            val t2 = text(desc, 12f, Pal.SUB, false)
            t2.maxWidth = ctx.dp(300)
            col.addView(t0)
            col.addView(text(title, 16f, Pal.TEXT, true))
            col.addView(t2)
            row.addView(col)
            present(glass(row), ms, "$title\n$desc")
        }
    }

    /** game recognised: icon, title, how many achievements, completion bar */
    fun showGame(title: String, iconName: String, got: Int, total: Int, pts: String, ms: Long) {
        ui.post {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val lpI = LinearLayout.LayoutParams(ctx.dp(68), ctx.dp(68))
            lpI.rightMargin = ctx.dp(14)
            row.addView(icon(68, 16, if (iconName.isEmpty()) null else "$MEDIA/Images/$iconName.png"), lpI)
            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.minimumWidth = ctx.dp(240)
            val t0 = text("GAME LOADED  -  TRACKING", 10f, 0xFF9FD8FF.toInt(), true)
            t0.letterSpacing = 0.12f
            col.addView(t0)
            val t1 = text(title, 17f, Pal.TEXT, true)
            t1.maxWidth = ctx.dp(280)
            col.addView(t1)
            val pct = if (total > 0) got * 100 / total else 0
            col.addView(text("$got / $total achievements   -   $pct%", 12f, Pal.SUB, false))
            val bar = ctx.progressBar(pct, 6)
            val lpB = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lpB.topMargin = ctx.dp(8)
            col.addView(bar, lpB)
            col.addView(text(pts, 11f, Pal.SUB, false))
            row.addView(col)
            present(glass(row), ms, "$title: $got / $total achievements")
        }
    }
}
