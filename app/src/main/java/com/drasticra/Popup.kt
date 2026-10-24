package com.drasticra

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.URL

/** Overlay popup at the top of the screen, over any app (needs "draw over other apps"). */
class Popup(private val ctx: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private var overlay: View? = null

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

    fun show(label: String, title: String, desc: String, badge: String?, ms: Long) {
        ui.post {
            try {
                if (!Settings.canDrawOverlays(ctx)) throw IllegalStateException("no overlay permission")
                removeOverlay()
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(20), ctx.dp(12))
                row.background = ctx.rounded(0xF2161922.toInt(), 22)

                val img = ImageView(ctx)
                img.background = ctx.rounded(0xFF2A2E3B.toInt(), 12)
                img.clipToOutline = true
                val radius = ctx.dp(12).toFloat()
                img.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                val ilp = LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52))
                ilp.rightMargin = ctx.dp(14)
                row.addView(img, ilp)

                val col = LinearLayout(ctx)
                col.orientation = LinearLayout.VERTICAL
                val t0 = TextView(ctx)
                t0.text = label
                t0.setTextColor(0xFFA99BFF.toInt())
                t0.textSize = 10f
                t0.letterSpacing = 0.12f
                t0.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                val t1 = TextView(ctx)
                t1.text = title
                t1.setTextColor(Pal.TEXT)
                t1.textSize = 16f
                t1.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                val t2 = TextView(ctx)
                t2.text = desc
                t2.setTextColor(Pal.SUB)
                t2.textSize = 12f
                t2.maxWidth = ctx.dp(300)
                col.addView(t0)
                col.addView(t1)
                col.addView(t2)
                row.addView(col)

                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.y = ctx.dp(18)
                (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(row, lp)
                overlay = row
                ui.postDelayed({ if (overlay === row) removeOverlay() }, ms)
                if (!badge.isNullOrEmpty()) {
                    Thread {
                        try {
                            val bmp = BitmapFactory.decodeStream(URL("https://media.retroachievements.org/Badge/$badge.png").openStream())
                            ui.post { img.setImageBitmap(bmp) }
                        } catch (_: Exception) {
                        }
                    }.start()
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "$title\n$desc", Toast.LENGTH_LONG).show()
            }
        }
    }
}
