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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmtWhen(sec: Long): String =
    if (sec <= 0L) "" else SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()).format(Date(sec * 1000L))

/** Small picture from a local path (trophy icons), with a grade-coloured placeholder when missing. */
internal fun MainActivity.localIcon(path: String, sizeDp: Int, radiusDp: Int, fallbackGrade: String = ""): ImageView {
    val img = square(sizeDp, radiusDp, null)
    if (path.isNotEmpty()) Img.load(path, img) else {
        img.scaleType = ImageView.ScaleType.CENTER_INSIDE
        pad(img, sizeDp / 5, sizeDp / 5, sizeDp / 5, sizeDp / 5)
        img.setImageResource(R.drawable.ic_trophy)
        img.setColorFilter(if (fallbackGrade.isEmpty()) Pal.ACC1 else Grades.color(fallbackGrade))
    }
    return img
}

/** "P 1  G 2  S 5  B 20" with grade-coloured dots. */
internal fun MainActivity.gradeRow(g: TrophyGame, textC: Int = Pal.TEXT): View {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    for (gr in listOf("P", "G", "S", "B")) {
        val total = g.trophies.count { it.grade == gr }
        if (total == 0) continue
        val dot = View(this)
        dot.background = rounded(Grades.color(gr), 6)
        row.addView(dot, ll(dp(11), dp(11), r = 5))
        row.addView(tv("${g.count(gr)}/$total", 12f, textC, true), ll(WRAP, WRAP, r = 12))
    }
    return row
}

internal fun MainActivity.trophyGameCard(g: TrophyGame): View {
    val c = card(26)
    val top = LinearLayout(this)
    top.orientation = LinearLayout.HORIZONTAL
    top.gravity = Gravity.CENTER_VERTICAL
    pad(top, 14, 14, 14, 12)
    top.addView(localIcon(g.icon, 66, 16), ll(dp(66), dp(66), r = 12))
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    val t = tv(g.title, 16f, Pal.TEXT, true)
    t.maxLines = 2
    t.ellipsize = TextUtils.TruncateAt.END
    col.addView(t)
    // console name right below the title (with the emulator it comes from)
    col.addView(tv(g.console + (if (g.emulator.isNotEmpty()) "  -  " + g.emulator else ""), 12f, Pal.ACC1, true), ll(WRAP, WRAP, t = 2))
    if (g.lastUnlock > 0) col.addView(tv("Last trophy  " + fmtWhen(g.lastUnlock), 11f, Pal.SUB), ll(WRAP, WRAP, t = 2))
    top.addView(col, ll(0, WRAP, 1f))
    top.addView(tag(g.console), ll(WRAP, WRAP, l = 8))
    c.addView(top, ll(MATCH, WRAP))
    val div = View(this)
    div.setBackgroundColor(Pal.DIV)
    c.addView(div, ll(MATCH, dp(1)))
    val bottom = LinearLayout(this)
    bottom.orientation = LinearLayout.VERTICAL
    pad(bottom, 16, 10, 16, 12)
    val pr = LinearLayout(this)
    pr.orientation = LinearLayout.HORIZONTAL
    pr.gravity = Gravity.CENTER_VERTICAL
    val done = g.trophies.isNotEmpty() && g.got >= g.trophies.size
    pr.addView(tv("${g.got}/${g.trophies.size}", 14f, Pal.TEXT, true), ll(WRAP, WRAP, r = 12))
    pr.addView(progressBar(if (g.trophies.isNotEmpty()) g.got * 100 / g.trophies.size else 0, 7, done), ll(0, WRAP, 1f))
    bottom.addView(pr)
    bottom.addView(gradeRow(g), ll(WRAP, WRAP, t = 8))
    c.addView(bottom, ll(MATCH, WRAP))
    c.isClickable = true
    makeFocusable(c)
    c.setOnClickListener { openTrophyDetail(g) }
    c.pressable()
    return c
}

internal fun MainActivity.trophyCard(g: TrophyGame, t: Trophy): View {
    val c = card(22)
    c.orientation = LinearLayout.HORIZONTAL
    c.gravity = Gravity.CENTER_VERTICAL
    pad(c, 12, 12, 14, 12)
    c.addView(localIcon(t.icon, 54, 14, t.grade), ll(dp(54), dp(54), r = 12))
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    val n = tv(t.name, 15f, Pal.TEXT, true)
    n.maxLines = 1
    n.ellipsize = TextUtils.TruncateAt.END
    col.addView(n)
    val d = tv(t.detail, 12f, Pal.SUB)
    d.maxLines = 2
    d.ellipsize = TextUtils.TruncateAt.END
    col.addView(d)
    val meta = tv(g.title + "  -  " + g.console + (if (t.time > 0) "  -  " + fmtWhen(t.time) else ""), 11f, Pal.SUB)
    meta.maxLines = 1
    meta.ellipsize = TextUtils.TruncateAt.END
    col.addView(meta, ll(WRAP, WRAP, t = 2))
    c.addView(col, ll(0, WRAP, 1f, r = 8))
    val gp = tv(Grades.name(t.grade), 12f, 0xFF1B1D26.toInt(), true)
    pad(gp, 10, 4, 10, 4)
    gp.background = rounded(Grades.color(t.grade), 14)
    c.addView(gp)
    c.isClickable = true
    makeFocusable(c)
    c.setOnClickListener { openTrophyDetail(g) }
    c.pressable()
    return c
}

/** Every trophy of one game, grade-coloured, with unlock times. */
class TrophyDetailView(private val act: MainActivity, private val game: TrophyGame, private val onClose: () -> Unit) :
    DetailPage(act) {
    private var filter = 0
    private val topBlock = LinearLayout(act)
    private val chips = LinearLayout(act)
    private val list = LinearLayout(act)
    private val sv = ScrollView(act)
    private val backBtn: View
    private var closing = false

    init {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        act.pad(col, 16, 12, 16, 12)
        col.clipChildren = false
        col.clipToPadding = false
        topBlock.orientation = LinearLayout.VERTICAL
        topBlock.clipChildren = false

        val top = LinearLayout(act)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        backBtn = act.iconButton(R.drawable.ic_back, 46) { close() }
        top.addView(backBtn, act.ll(act.dp(46), act.dp(46), r = 10))
        val tp = act.card(26)
        tp.orientation = LinearLayout.HORIZONTAL
        tp.gravity = Gravity.CENTER_VERTICAL
        act.pad(tp, 18, 10, 14, 10)
        val tt = act.tv(game.title, 19f, Pal.TEXT, true)
        tt.maxLines = 1
        tt.ellipsize = TextUtils.TruncateAt.END
        tp.addView(tt, act.ll(0, WRAP, 1f, r = 8))
        tp.addView(act.tag(game.console))
        top.addView(tp, act.ll(0, WRAP, 1f))
        topBlock.addView(top, act.ll(MATCH, WRAP))

        val head = act.card(26)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        act.pad(head, 14, 14, 16, 14)
        head.addView(act.localIcon(game.icon, 72, 18), act.ll(act.dp(72), act.dp(72), r = 14))
        val hc = LinearLayout(act)
        hc.orientation = LinearLayout.VERTICAL
        val pct = if (game.trophies.isNotEmpty()) game.got * 100 / game.trophies.size else 0
        hc.addView(act.tv("${game.got} / ${game.trophies.size} trophies   -   $pct%", 15f, Pal.TEXT, true))
        hc.addView(act.tv(game.console + (if (game.emulator.isNotEmpty()) "  -  " + game.emulator else ""), 12f, Pal.ACC1, true),
            act.ll(WRAP, WRAP, t = 2))
        hc.addView(act.progressBar(pct, 8, game.trophies.isNotEmpty() && game.got >= game.trophies.size), act.ll(MATCH, WRAP, t = 8))
        hc.addView(act.gradeRow(game), act.ll(WRAP, WRAP, t = 8))
        head.addView(hc, act.ll(0, WRAP, 1f))
        topBlock.addView(head, act.ll(MATCH, WRAP, t = 12))

        chips.orientation = LinearLayout.HORIZONTAL
        topBlock.addView(chips, act.ll(MATCH, WRAP, t = 12))
        col.addView(topBlock, act.ll(MATCH, WRAP))

        sv.isVerticalScrollBarEnabled = false
        sv.isFocusable = false
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.clipToPadding = false
        sv.clipChildren = false
        list.orientation = LinearLayout.VERTICAL
        list.clipChildren = false
        act.pad(list, 2, 8, 2, 16)
        sv.addView(list)
        sv.setOnScrollChangeListener { _, _, y, _, _ ->
            GlassBackdrop.invalidateAll()
            val t = (y / act.dp(64).toFloat()).coerceIn(0f, 1f)
            val k = t * t * (3f - 2f * t)
            topBlock.alpha = 1f - k
            topBlock.translationY = -k * act.dp(12)
            topBlock.visibility = if (t >= 1f) View.INVISIBLE else View.VISIBLE
        }
        col.addView(sv, act.ll(MATCH, 0, 1f))
        addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        renderChips(false)
        renderList()
    }

    override fun slideIn() {
        alpha = 0f
        translationX = act.dp(60).toFloat()
        animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(DecelerateInterpolator(1.6f)).start()
        post { backBtn.requestFocus() }
    }

    override fun cycleFilter(step: Int) {
        filter = (filter + step + 3) % 3
        renderChips(true)
        renderList()
    }

    private fun renderChips(focus: Boolean) {
        chips.removeAllViews()
        val names = arrayOf("All", "Unlocked", "Locked")
        for (i in names.indices) {
            chips.addView(act.chip(names[i], filter == i) {
                filter = i
                renderChips(true)
                renderList()
            }, act.ll(0, WRAP, 1f, l = if (i == 0) 0 else 8))
        }
        if (focus) chips.getChildAt(filter)?.let { c -> c.post { c.requestFocus() } }
    }

    private fun renderList() {
        list.removeAllViews()
        sv.scrollTo(0, 0)
        var n = 0
        val items = game.trophies.sortedWith(compareByDescending<Trophy> { it.unlocked }.thenByDescending { it.time }.thenBy { it.id })
        for (t in items) {
            if (filter == 1 && !t.unlocked) continue
            if (filter == 2 && t.unlocked) continue
            val r = row(t)
            list.addView(r, act.ll(MATCH, WRAP, t = if (n == 0) 0 else 10))
            r.popIn(minOf(n, 10) * 40L)
            n++
        }
        if (n == 0) list.addView(act.tv("Nothing in this list.", 14f, Pal.SUB))
    }

    private fun row(t: Trophy): View {
        val c = act.card(22)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        act.pad(c, 12, 12, 14, 12)
        val ic = act.localIcon(t.icon, 56, 14, t.grade)
        if (!t.unlocked) ic.alpha = 0.45f
        c.addView(ic, act.ll(act.dp(56), act.dp(56), r = 12))
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val secret = t.hidden && !t.unlocked
        val n = act.tv(if (secret) "Hidden trophy" else t.name, 15f, if (t.unlocked) Pal.TEXT else Pal.SUB, true)
        n.maxLines = 2
        n.ellipsize = TextUtils.TruncateAt.END
        col.addView(n)
        val d = act.tv(if (secret) "Keep playing to reveal this trophy." else t.detail, 12f, Pal.SUB)
        d.maxLines = 4
        col.addView(d, act.ll(WRAP, WRAP, t = 2))
        col.addView(
            if (t.unlocked) act.tv("Unlocked" + (if (t.time > 0) "   " + fmtWhen(t.time) else ""), 11f, Pal.ACC1, true)
            else act.tv("Locked", 11f, Pal.SUB, true), act.ll(WRAP, WRAP, t = 4)
        )
        c.addView(col, act.ll(0, WRAP, 1f, r = 8))
        val gp = act.tv(Grades.name(t.grade), 12f, if (t.unlocked) 0xFF1B1D26.toInt() else Pal.SUB, true)
        act.pad(gp, 10, 4, 10, 4)
        gp.background = if (t.unlocked) act.rounded(Grades.color(t.grade), 14) else act.rounded(Pal.FIELD, 14)
        c.addView(gp)
        c.isClickable = true
        act.makeFocusable(c)
        c.pressable()
        return c
    }

    override fun close() {
        if (closing) return
        closing = true
        animate().alpha(0f).translationX(act.dp(60).toFloat()).setDuration(240).withEndAction { onClose() }.start()
    }
}
