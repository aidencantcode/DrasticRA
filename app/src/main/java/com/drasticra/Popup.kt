package com.drasticra

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.LinkedList

/**
 * Xbox-360-style achievement toasts: solid dark pill, ring badge that lights up in your accent gradient.
 * Drawn in an overlay window, so they appear on top of DraStic. Popups queue up and play one after another.
 */
class Popup(private val ctx: Context) {
    private class Item(val view: View, val ms: Long, val fallback: String)

    private val ui = Handler(Looper.getMainLooper())
    private val queue = LinkedList<Item>()
    private var showing = false
    private var current: View? = null

    private fun wm(): WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun hide() {
        ui.post {
            queue.clear()
            showing = false
            val v = current
            if (v != null) {
                try {
                    wm().removeView(v)
                } catch (_: Exception) {
                }
            }
            current = null
        }
    }

    private fun line(t: String, size: Float, color: Int): TextView {
        val v = TextView(ctx)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        v.typeface = Fonts.body
        v.maxLines = 1
        v.ellipsize = android.text.TextUtils.TruncateAt.END
        return v
    }

    private fun pill(ring: RingBadge, l1: String, l2: String, l3: String?): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xFF2A2827.toInt())
        bg.cornerRadius = ctx.dp(200).toFloat()
        bg.setStroke(ctx.dp(2), 0xFF575351.toInt())
        row.background = bg
        row.setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(30), ctx.dp(6))
        val rlp = LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(64))
        rlp.rightMargin = ctx.dp(16)
        row.addView(ring, rlp)
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.minimumWidth = ctx.dp(190)
        col.addView(line(l1, 15f, 0xFFF4F1EE.toInt()))
        val t2 = line(l2, 14f, 0xFFD9D4D0.toInt())
        t2.maxWidth = ctx.dp(300)
        col.addView(t2)
        if (l3 != null) {
            val t3 = line(l3, 11f, 0xFF9E9893.toInt())
            t3.maxWidth = ctx.dp(300)
            col.addView(t3)
        }
        row.addView(col)
        return row
    }

    private fun enqueue(v: View, ms: Long, fallback: String) {
        queue.add(Item(v, ms, fallback))
        if (!showing) next()
    }

    private fun next() {
        val item = queue.poll()
        if (item == null) {
            showing = false
            return
        }
        showing = true
        val top = (ctx.getSharedPreferences("dsra", Context.MODE_PRIVATE).getString("popup_pos", "top") ?: "top") != "bottom"
        try {
            if (!Settings.canDrawOverlays(ctx)) throw IllegalStateException("no overlay permission")
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            lp.y = ctx.dp(18)
            val v = item.view
            wm().addView(v, lp)
            current = v
            val off = (if (top) -ctx.dp(90) else ctx.dp(90)).toFloat()
            v.alpha = 0f
            v.translationY = off
            v.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(OvershootInterpolator(0.9f)).start()
            ui.postDelayed({ dismiss(v, off) }, item.ms)
        } catch (e: Exception) {
            Toast.makeText(ctx, item.fallback, Toast.LENGTH_LONG).show()
            ui.postDelayed({ next() }, 400)
        }
    }

    private fun dismiss(v: View, off: Float) {
        v.animate().alpha(0f).translationY(off).setDuration(260).withEndAction {
            try {
                wm().removeView(v)
            } catch (_: Exception) {
            }
            if (current === v) current = null
            ui.postDelayed({ next() }, 200)
        }.start()
    }

    private fun ring(fill: Float, url: String?): RingBadge {
        val r = RingBadge(ctx, fill)
        r.setIcon(url)
        return r
    }

    fun unlock(title: String, desc: String, pts: String, badge: String?) {
        ui.post {
            val url = if (badge.isNullOrEmpty()) null else "$MEDIA/Badge/$badge.png"
            enqueue(pill(ring(4f, url), "Achievement unlocked", title, "$desc   -   $pts pts"), 6000, "$title\n$desc")
        }
    }

    fun progress(title: String, text: String, badge: String?) {
        ui.post {
            val url = if (badge.isNullOrEmpty()) null else "$MEDIA/Badge/$badge.png"
            var fill = 1f
            val m = Regex("(\\d+)\\s*/\\s*(\\d+)").find(text)
            if (m != null) {
                val a = m.groupValues[1].toFloat()
                val b = m.groupValues[2].toFloat()
                if (b > 0f) fill = 4f * a / b
            } else {
                val pc = Regex("(\\d+(?:\\.\\d+)?)\\s*%").find(text)
                if (pc != null) fill = 4f * pc.groupValues[1].toFloat() / 100f
            }
            enqueue(pill(ring(fill, url), "Progress", title, text), 2600, "$title: $text")
        }
    }

    fun game(title: String, iconName: String, got: Int, total: Int, pts: String) {
        ui.post {
            val url = if (iconName.isEmpty()) null else "$MEDIA/Images/$iconName.png"
            val pct = if (total > 0) got * 100 / total else 0
            enqueue(
                pill(ring(4f * pct / 100f, url), "Game loaded - tracking", title, "$got / $total achievements   -   $pct%   -   $pts"),
                6000, "$title: $got / $total achievements"
            )
        }
    }

    fun mastered() {
        ui.post { enqueue(pill(ring(4f, null), "Game mastered", "You earned every achievement", null), 6000, "Game mastered") }
    }
}
