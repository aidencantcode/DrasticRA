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
    /** title bar + progress card + filter chips: they dissolve together while the list scrolls over them */
    private val topBlock = LinearLayout(act)
    private val header = LinearLayout(act)
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
        topBlock.clipToPadding = false

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
        tp.addView(act.tag(shortConsole(game.console)))
        top.addView(tp, act.ll(0, WRAP, 1f))
        topBlock.addView(top, act.ll(MATCH, WRAP))

        header.orientation = LinearLayout.VERTICAL
        topBlock.addView(header, act.ll(MATCH, WRAP, t = 12))

        chips.orientation = LinearLayout.HORIZONTAL
        chips.clipChildren = false
        topBlock.addView(chips, act.ll(MATCH, WRAP, t = 12))
        col.addView(topBlock, act.ll(MATCH, WRAP))

        sv.isVerticalScrollBarEnabled = false
        sv.isFocusable = false
        // no stretch overscroll: it clips the rows that overhang the faded header into a hard square
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.clipToPadding = false
        // like the home screen, rows slide up over the (fading) header instead of being cut off under it
        sv.clipChildren = false
        list.orientation = LinearLayout.VERTICAL
        list.clipChildren = false
        act.pad(list, 2, 8, 2, 16)
        sv.addView(list)
        sv.setOnScrollChangeListener { _, _, y, _, _ ->
            GlassBackdrop.invalidateAll()
            updateHeaderFade(y)
        }
        col.addView(sv, act.ll(MATCH, 0, 1f))
        addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val msg = act.tv("Loading achievements...", 14f, Pal.SUB)
        list.addView(msg)
        load()
    }

    /**
     * Same behaviour as the home screen: the game name, progress card and chips dissolve over the first ~64dp of
     * scrolling so achievement rows never slide over a half see-through header. Fully gone = INVISIBLE (not focusable).
     */
    private fun updateHeaderFade(scrollY: Int) {
        val t = (scrollY / act.dp(64).toFloat()).coerceIn(0f, 1f)
        val k = t * t * (3f - 2f * t)
        topBlock.alpha = 1f - k
        topBlock.translationY = -k * act.dp(12)
        topBlock.visibility = if (t >= 1f) View.INVISIBLE else View.VISIBLE
    }

    fun slideIn() {
        alpha = 0f
        translationX = act.dp(60).toFloat()
        animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(DecelerateInterpolator(1.6f)).start()
        post { backBtn.requestFocus() }
    }

    /** L1 / R1 on a controller: previous / next filter (All, Unlocked, Locked). */
    fun cycleFilter(step: Int) {
        if (detail == null) return
        filter = (filter + step + 3) % 3
        renderChips(true)
        renderList()
    }

    /** achievements of this game unlocked offline and not sent yet (shown with a yellow tint) */
    private var pending: Set<Int> = emptySet()

    private fun load() {
        val u = act.apiUser()
        val k = act.apiKey()
        Thread {
            pending = OfflineProxy.pendingIds(game.id)
            var d: RaGameDetail? = null
            var err = ""
            if (u.isNotEmpty() && k.isNotEmpty()) {
                try {
                    d = Ra.parseGame(Ra.gameTextCached(u, k, game.id))
                } catch (e: Exception) {
                    err = e.message ?: "network error"
                }
            }
            // offline and never opened online: build the list from the Offline Play cache
            if (d == null) d = OfflineProxy.detailFromCache(game.id)
            val res = d
            act.runOnUiThread {
                if (res == null) {
                    list.removeAllViews()
                    list.addView(act.tv(
                        if (u.isEmpty() || k.isEmpty()) "Add your Web API key in Settings -> Account to see achievement lists."
                        else "Could not load this game: $err", 14f, Pal.SUB))
                    return@runOnUiThread
                }
                // offline unlocks count as earned here (they are, just not on the site yet)
                detail = if (pending.isEmpty()) res else RaGameDetail(res.id, res.title, res.icon, res.console, res.total,
                    res.list.count { it.earned || pending.contains(it.id) },
                    res.list.map {
                        if (!it.earned && pending.contains(it.id)) RaAchDetail(it.id, it.title, it.desc, it.points, it.badge,
                            true, "", it.order) else it
                    })
                renderHeader()
                // focus the filter chips once there is something to pick (unless the user already moved on)
                renderChips(backBtn.isFocused)
                renderList()
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

    private fun renderChips(focusSelected: Boolean = false) {
        val hadFocus = chips.findFocus() != null
        chips.removeAllViews()
        val names = arrayOf("All", "Unlocked", "Locked")
        for (i in names.indices) {
            chips.addView(act.chip(names[i], filter == i) {
                filter = i
                renderChips(true)
                renderList()
            }, act.ll(0, WRAP, 1f, l = if (i == 0) 0 else 8))
        }
        // the chip that had focus was just replaced: keep the controller highlight on the selected one
        if (focusSelected || hadFocus) chips.getChildAt(filter)?.let { c -> c.post { c.requestFocus() } }
    }

    private fun renderList() {
        val d = detail ?: return
        list.removeAllViews()
        sv.scrollTo(0, 0)
        sv.post { updateHeaderFade(sv.scrollY) }
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
        val isPending = pending.contains(a.id)
        if (isPending) {
            // yellow tint: unlocked while offline, waiting to be sent
            c.foreground = act.rounded(0x38FFC21A, 22)
        }
        val status = if (isPending) {
            act.tv("Unlocked offline  -  not synced yet, waiting for internet", 11f, 0xFFE0A100.toInt(), true)
        } else if (a.earned) {
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
