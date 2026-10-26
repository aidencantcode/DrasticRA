package com.drasticra

import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Every achievement of one game: unlocked ones in colour, locked ones dimmed, with how to earn each. */
class GameDetailView(private val act: MainActivity, private val game: RaGame, private val onClose: () -> Unit) : FrameLayout(act) {
    private var detail: RaGameDetail? = null
    private var filter = 0
    private val header = LinearLayout(act)
    private val chips = LinearLayout(act)
    private val list = LinearLayout(act)
    private var closing = false

    init {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        act.pad(col, 16, 12, 16, 12)
        col.clipChildren = false

        val top = LinearLayout(act)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(act.iconButton(R.drawable.ic_back, 46) { close() }, act.ll(act.dp(46), act.dp(46), r = 10))
        val tp = act.card(26)
        tp.orientation = LinearLayout.HORIZONTAL
        tp.gravity = Gravity.CENTER_VERTICAL
        act.pad(tp, 18, 10, 14, 10)
        val tt = act.tv(game.title, 19f, Pal.TEXT, true)
        tt.maxLines = 1
        tt.ellipsize = TextUtils.TruncateAt.END
        tp.addView(tt, act.ll(0, WRAP, 1f, r = 8))
        tp.addView(act.tag(shortConsole(game.console)))
        top.addView(tp, act.ll(0, WRAP, 1f))
        col.addView(top, act.ll(MATCH, WRAP))

        header.orientation = LinearLayout.VERTICAL
        col.addView(header, act.ll(MATCH, WRAP, t = 12))

        chips.orientation = LinearLayout.HORIZONTAL
        col.addView(chips, act.ll(MATCH, WRAP, t = 12))

        val sv = ScrollView(act)
        sv.isVerticalScrollBarEnabled = false
        sv.clipToPadding = false
        list.orientation = LinearLayout.VERTICAL
        list.clipChildren = false
        act.pad(list, 2, 8, 2, 16)
        sv.addView(list)
        sv.setOnScrollChangeListener { _, _, _, _, _ -> GlassBackdrop.invalidateAll() }
        col.addView(sv, act.ll(MATCH, 0, 1f))
        addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val msg = act.tv("Loading achievements...", 14f, Pal.SUB)
        list.addView(msg)
        load()
    }

    fun slideIn() {
        alpha = 0f
        translationX = act.dp(60).toFloat()
        animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun load() {
        val u = act.apiUser()
        val k = act.apiKey()
        if (u.isEmpty() || k.isEmpty()) {
            list.removeAllViews()
            list.addView(act.tv("Add your Web API key in Settings -> Account to see achievement lists.", 14f, Pal.SUB))
            return
        }
        Thread {
            try {
                val d = Ra.parseGame(Ra.gameText(u, k, game.id))
                act.runOnUiThread {
                    detail = d
                    renderHeader()
                    renderChips()
                    renderList()
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    list.removeAllViews()
                    list.addView(act.tv("Could not load this game: " + e.message, 14f, Pal.SUB))
                }
            }
        }.start()
    }

    private fun renderHeader() {
        val d = detail ?: return
        header.removeAllViews()
        val c = act.card(26)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        act.pad(c, 14, 14, 16, 14)
        c.addView(act.square(72, 18, if (d.icon.isEmpty()) null else MEDIA + d.icon), act.ll(act.dp(72), act.dp(72), r = 14))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val pct = if (d.total > 0) d.got * 100 / d.total else 0
        var ptsAll = 0
        var ptsGot = 0
        for (a in d.list) {
            ptsAll += a.points
            if (a.earned) ptsGot += a.points
        }
        col.addView(act.tv(d.got.toString() + " / " + d.total + " unlocked   -   " + pct + "%", 15f, Pal.TEXT, true))
        col.addView(act.progressBar(pct, 8, d.total > 0 && d.got >= d.total), act.ll(MATCH, WRAP, t = 8))
        col.addView(act.tv("$ptsGot / $ptsAll points", 12f, Pal.SUB), act.ll(WRAP, WRAP, t = 6))
        c.addView(col, act.ll(0, WRAP, 1f))
        header.addView(c, act.ll(MATCH, WRAP))
        c.popIn(0)
    }

    private fun renderChips() {
        chips.removeAllViews()
        val names = arrayOf("All", "Unlocked", "Locked")
        for (i in names.indices) {
            chips.addView(act.chip(names[i], filter == i) {
                filter = i
                renderChips()
                renderList()
            }, act.ll(0, WRAP, 1f, l = if (i == 0) 0 else 8))
        }
    }

    private fun renderList() {
        val d = detail ?: return
        list.removeAllViews()
        var n = 0
        for (a in d.list) {
            if (filter == 1 && !a.earned) continue
            if (filter == 2 && a.earned) continue
            val row = row(a)
            list.addView(row, act.ll(MATCH, WRAP, t = if (n == 0) 0 else 10))
            row.popIn(minOf(n, 10) * 40L)
            n++
        }
        if (n == 0) list.addView(act.tv("Nothing in this list.", 14f, Pal.SUB))
    }

    private fun row(a: RaAchDetail): View {
        val c = act.card(22)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        act.pad(c, 12, 12, 14, 12)
        val badgeUrl = MEDIA + "/Badge/" + a.badge + (if (a.earned) "" else "_lock") + ".png"
        val ic = act.square(56, 14, badgeUrl)
        if (!a.earned) ic.alpha = 0.8f
        c.addView(ic, act.ll(act.dp(56), act.dp(56), r = 12))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val t = act.tv(a.title, 15f, if (a.earned) Pal.TEXT else Pal.SUB, true)
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        val d = act.tv(a.desc, 12f, Pal.SUB)
        d.maxLines = 4
        d.ellipsize = TextUtils.TruncateAt.END
        col.addView(t)
        col.addView(d, act.ll(WRAP, WRAP, t = 2))
        val status = if (a.earned) {
            act.tv("Unlocked" + (if (a.date.length >= 10) "   " + a.date.substring(0, 10) else ""), 11f, Pal.ACC1, true)
        } else {
            act.tv("Locked", 11f, Pal.SUB, true)
        }
        col.addView(status, act.ll(WRAP, WRAP, t = 4))
        c.addView(col, act.ll(0, WRAP, 1f, r = 8))
        val pts = act.tv(a.points.toString(), 13f, if (a.earned) Color.WHITE else Pal.SUB, true)
        act.pad(pts, 11, 4, 11, 4)
        pts.background = if (a.earned) act.accentFill(14) else act.rounded(Pal.FIELD, 14)
        c.addView(pts)
        c.isClickable = true
        act.makeFocusable(c)
        c.pressable()
        return c
    }

    fun close() {
        if (closing) return
        closing = true
        animate().alpha(0f).translationX(act.dp(60).toFloat()).setDuration(240).withEndAction { onClose() }.start()
    }
}
