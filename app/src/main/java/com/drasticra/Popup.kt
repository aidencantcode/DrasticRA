package com.drasticra

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
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
import java.net.URL

/** Overlay popup shown at the top of the screen, over any app (needs "draw over other apps"). */
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

    fun show(title: String, desc: String, badge: String?, ms: Long) {
        ui.post {
            try {
                if (!Settings.canDrawOverlays(ctx)) throw IllegalStateException("no overlay permission")
                removeOverlay()
                val d = ctx.resources.displayMetrics.density
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding((12 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
                val bg = GradientDrawable()
                bg.setColor(0xEE202020.toInt())
                bg.cornerRadius = 14 * d
                row.background = bg
                val img = ImageView(ctx)
                val ilp = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt())
                ilp.rightMargin = (12 * d).toInt()
                row.addView(img, ilp)
                val col = LinearLayout(ctx)
                col.orientation = LinearLayout.VERTICAL
                val t1 = TextView(ctx)
                t1.text = title
                t1.setTextColor(Color.WHITE)
                t1.textSize = 15f
                t1.typeface = Typeface.DEFAULT_BOLD
                val t2 = TextView(ctx)
                t2.text = desc
                t2.setTextColor(0xFFCCCCCC.toInt())
                t2.textSize = 12f
                t2.maxWidth = (300 * d).toInt()
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
                lp.y = (20 * d).toInt()
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
