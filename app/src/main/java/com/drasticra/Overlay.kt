package com.drasticra

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

/** One achievement of the running game, as rcheevos sees it right now. */
class LiveAch(
    val id: Int,
    val title: String,
    val desc: String,
    val badge: String,
    val points: Int,
    val unlocked: Boolean,
    val progress: String,
    val pct: Float,
    val unlockTime: Long,
    val bucket: Int,
    val fresh: Boolean
) {
    /** has a counter / partial progress, or rcheevos flags it as an active challenge / almost there */
    val inProgress: Boolean get() = !unlocked && (progress.isNotEmpty() || pct > 0f || bucket == 6 || bucket == 7)
}

/** What the overlay shows. Built from [LiveGame] while a game runs, or from sample data for the preview. */
class OverlaySnap(
    val id: Int,
    val title: String,
    val icon: String,
    val sessionMs: Long,
    val totalMs: Long,
    val list: List<LiveAch>,
    val version: Int
)

/**
 * Live state of the game the engine is watching. Written by the engine thread, read by the overlay on the UI thread
 * (every field is replaced as a whole, so readers always see a consistent value).
 */
object LiveGame {
    @Volatile var id = 0
    @Volatile var title = ""
    @Volatile var icon = ""
    /** play time counted in this session (same rules as the stored play time: game loaded and DraStic on screen) */
    @Volatile var sessionMs = 0L
    /** play time counted but not yet written to the preferences */
    @Volatile var pendingMs = 0L
    @Volatile var list: List<LiveAch> = emptyList()
    @Volatile var version = 0
    /** set by the overlay / events; the engine thread re-reads the list on its next loop */
    @Volatile var refreshWanted = false

    // engine thread only
    private val baseline = HashSet<Int>()
    private var baselineSet = false

    fun begin(gameId: Int, t: String, iconName: String) {
        id = gameId
        title = t
        icon = iconName
        sessionMs = 0L
        pendingMs = 0L
        list = emptyList()
        baseline.clear()
        baselineSet = false
        refreshWanted = true
        version++
    }

    fun clear() {
        id = 0
        title = ""
        icon = ""
        sessionMs = 0L
        pendingMs = 0L
        list = emptyList()
        baseline.clear()
        baselineSet = false
        version++
    }

    /** Parses [Native.achievementList]. Achievements unlocked after the first read are flagged as new this session. */
    fun setFromNative(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        val out = ArrayList<LiveAch>()
        val text = String(bytes, Charsets.UTF_8)
        for (rec in text.split('\u001e')) {
            val f = rec.split('\u001f')
            if (f.size < 10) continue
            val unlocked = f[5] == "1"
            val aid = f[0].toIntOrNull() ?: continue
            out.add(
                LiveAch(
                    aid, f[1], f[2], f[3], f[4].toIntOrNull() ?: 0, unlocked, f[6].trim(),
                    f[7].toFloatOrNull() ?: 0f, f[8].toLongOrNull() ?: 0L, f[9].toIntOrNull() ?: 0, false
                )
            )
        }
        if (out.isEmpty()) return
        if (!baselineSet) {
            for (a in out) if (a.unlocked) baseline.add(a.id)
            baselineSet = true
        }
        val marked = out.map {
            if (it.unlocked && !baseline.contains(it.id)) {
                LiveAch(it.id, it.title, it.desc, it.badge, it.points, true, it.progress, it.pct, it.unlockTime, it.bucket, true)
            } else it
        }
        list = marked
        version++
    }

    fun snap(ctx: Context): OverlaySnap =
        OverlaySnap(id, title, icon, sessionMs, PlayTime.ms(ctx, id) + pendingMs, list, version)
}

/** The in-game achievement overlay window (one at a time). Main thread only. */
object GameOverlay {
    @Volatile var showing = false
        private set
    private var view: OverlayView? = null
    private var opening = false
    private val ui = Handler(Looper.getMainLooper())

    fun toggle(ctx: Context) {
        if (view != null) hide() else show(ctx)
    }

    /** Opens the overlay for the game the engine is watching. */
    fun show(ctx: Context) {
        open(ctx.applicationContext) { LiveGame.snap(it) }
    }

    /** Opens the overlay with sample data, so it can be previewed without a game. */
    fun showDemo(ctx: Context) {
        val t0 = SystemClock.elapsedRealtime()
        val demo = demoList()
        open(ctx.applicationContext) {
            val s = SystemClock.elapsedRealtime() - t0 + 754_000L
            OverlaySnap(0, "Pokemon SoulSilver (Demo)", "", s, 19_380_000L + s, demo, 1)
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        showing = false
        v.close()
    }

    internal fun detached(v: OverlayView) {
        if (view === v) {
            view = null
            showing = false
        }
    }

    private fun open(app: Context, provider: (Context) -> OverlaySnap) {
        if (view != null || opening) return
        if (!Settings.canDrawOverlays(app)) {
            Toast.makeText(app, "Turn on 'Display over other apps' for Drastic RA to use the overlay.", Toast.LENGTH_LONG).show()
            return
        }
        opening = true
        val capture = app.popupGlassMode() != "off" && app.prefs().getBoolean("popup_capture", true)
        Thread {
            val bmp = if (capture) ScreenGrab.capture(400L) else null
            ui.post {
                opening = false
                if (view != null) return@post
                try {
                    Pal.load(app)
                    Fonts.load(app)
                    Img.init(app)
                    Foil.load(app)
                    val v = OverlayView(app, bmp, provider)
                    val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.addView(v, v.params())
                    view = v
                    showing = true
                    LiveGame.refreshWanted = true
                    v.enter()
                } catch (e: Exception) {
                    AppLog.add("Overlay failed: " + e.message)
                }
            }
        }.start()
    }

    private fun demoList(): List<LiveAch> = listOf(
        LiveAch(1, "A New Journey", "Choose your first Pokemon", "", 5, true, "", 0f, 1700000000L, 2, false),
        LiveAch(2, "Gym Rookie", "Defeat Falkner in Violet City", "", 10, true, "", 0f, 1700100000L, 2, true),
        LiveAch(3, "Gotta Catch 'Em", "Register 50 Pokemon in the Pokedex", "", 10, false, "32/50", 64f, 0L, 7, false),
        LiveAch(4, "Money Bags", "Have 100,000 Pokedollars at once", "", 5, false, "41,200/100,000", 41.2f, 0L, 1, false),
        LiveAch(5, "Bug Catching Champion", "Win the Bug-Catching Contest", "", 10, false, "", 0f, 0L, 1, false),
        LiveAch(6, "Elite", "Defeat the Elite Four and Lance", "", 25, false, "", 0f, 0L, 1, false),
        LiveAch(7, "Shiny Hunter", "Catch the red Gyarados at the Lake of Rage", "", 5, true, "", 0f, 1700200000L, 2, false)
    )
}

/**
 * Full-screen overlay window: a dim layer plus a glass panel (material follows the popup glass setting) with the game,
 * session / total play time, progress and the achievements in tabs. Tap outside, X or B closes it.
 */
@SuppressLint("ViewConstructor")
class OverlayView(
    private val ctx: Context,
    private val bmp: Bitmap?,
    private val provider: (Context) -> OverlaySnap
) : FrameLayout(ctx) {
    private val ui = Handler(Looper.getMainLooper())
    private val prefs = ctx.prefs()
    private val scrim = View(ctx)
    private val panel: LinearLayout
    private val halo = FocusHalo(ctx)
    private val listBox = LinearLayout(ctx)
    private val scroll = ScrollView(ctx)
    private val tabsRow = LinearLayout(ctx)
    private val toolRow = LinearLayout(ctx)
    private val iconView: ImageView
    private val titleView: TextView
    private val countView: TextView
    private val ptsView: TextView
    private val timeView: TextView
    private val barHolder = FrameLayout(ctx)
    private var tab = prefs.getInt("ov_tab", 0).coerceIn(0, 3)
    private var grid = prefs.getBoolean("ov_grid", false)
    private var shownVersion = -1
    private var shownId = -1
    private var closing = false
    private var snap: OverlaySnap = provider(ctx)
    private val textC: Int
    private val subC: Int
    private val goodC: Int
    private val badC: Int
    private val panelW: Int
    private val panelH: Int

    private val ticker = object : Runnable {
        override fun run() {
            if (closing) return
            refreshFromSource(false)
            ui.postDelayed(this, 1000L)
        }
    }

    init {
        clipChildren = false
        val size = displaySize()
        panelW = min(size.x - ctx.dp(24), ctx.dp(780))
        panelH = min(size.y - ctx.dp(24), ctx.dp(640))

        // pick readable colours from what is actually behind the panel
        val picked = ctx.popupGlassMode()
        val mode = if (picked == "full" && bmp == null) "lite" else picked
        val solid = mode == "off"
        var light = Pal.light
        if (bmp != null) {
            light = ScreenGrab.luma(bmp, (bmp.width * 0.2f).toInt(), (bmp.height * 0.2f).toInt(),
                (bmp.width * 0.8f).toInt(), (bmp.height * 0.8f).toInt()) > 0.45f
        }
        textC = if (light) 0xFF1B1D26.toInt() else Color.WHITE
        subC = if (light) 0xFF565C6E.toInt() else 0xFFB9BFD0.toInt()
        goodC = if (light) 0xFF158F6A.toInt() else 0xFF4ADE80.toInt()
        badC = if (light) 0xFFD6336C.toInt() else 0xFFFF7BA6.toInt()

        scrim.setBackgroundColor(0x59000000)
        scrim.setOnClickListener { close() }
        addView(scrim, LayoutParams(MATCH, MATCH))

        if (solid && !Foil.enabledFor("overlay")) {
            panel = LinearLayout(ctx)
            val bg = GradientDrawable()
            bg.setColor(if (light) 0xF2F4F4FA.toInt() else 0xF21D2029.toInt())
            bg.cornerRadius = ctx.dp(30).toFloat()
            panel.background = bg
            panel.elevation = ctx.dp(12).toFloat()
        } else if (solid) {
            // solid panel drawn by the glass view so the holo foil can sit on it
            val g = GlassLinear(ctx, "off")
            g.radiusDp = 30
            g.foilSurface = "overlay"
            val c = if (light) 0xF2F4F4FA.toInt() else 0xF21D2029.toInt()
            g.frostTop = c
            g.frostBottom = c
            g.tintTop = 0x00FFFFFF
            g.tintBottom = 0x00FFFFFF
            g.refreshColors()
            panel = g
        } else {
            val g = GlassLinear(ctx, mode)
            g.radiusDp = 30
            g.foilSurface = "overlay"
            if (light) {
                g.tintTop = 0x9EFFFFFF.toInt()
                g.tintBottom = 0x8CF2F3FA.toInt()
                g.frostTop = 0xD8F4F2FB.toInt()
                g.frostBottom = 0xD8DEDAF2.toInt()
            } else {
                g.tintTop = 0x9A1A1C25.toInt()
                g.tintBottom = 0x8C101218.toInt()
                g.frostTop = 0xD82B2E3A.toInt()
                g.frostBottom = 0xD81D2029.toInt()
            }
            g.refreshColors()
            if (bmp != null) {
                g.provider = { c, v ->
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    c.drawBitmap(bmp, -loc[0].toFloat(), -loc[1].toFloat(), null)
                }
            }
            panel = g
        }
        panel.orientation = LinearLayout.VERTICAL
        panel.isClickable = true // touches on the panel never reach the scrim
        val plp = LayoutParams(panelW, panelH)
        plp.gravity = Gravity.CENTER
        addView(panel, plp)

        // ---- header: icon, title, completion chip, play time, progress bar, close ----
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        ctx.pad(head, 18, 16, 14, 12)
        iconView = ctx.square(78, 18, null)
        head.addView(iconView, ctx.ll(ctx.dp(78), ctx.dp(78), r = 16))

        val hc = LinearLayout(ctx)
        hc.orientation = LinearLayout.VERTICAL
        titleView = ctx.tv("", 21f, textC, true)
        titleView.maxLines = 2
        titleView.ellipsize = TextUtils.TruncateAt.END
        hc.addView(titleView)

        val chipRow = LinearLayout(ctx)
        chipRow.orientation = LinearLayout.HORIZONTAL
        chipRow.gravity = Gravity.CENTER_VERTICAL
        val chip = LinearLayout(ctx)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        ctx.pad(chip, 10, 4, 12, 4)
        chip.background = ctx.accentFill(14)
        val trophy = ImageView(ctx)
        trophy.setImageResource(R.drawable.ic_trophy)
        trophy.setColorFilter(Pal.GOLD1)
        chip.addView(trophy, ctx.ll(ctx.dp(16), ctx.dp(16), r = 6))
        countView = ctx.tv("", 13f, Color.WHITE, true)
        chip.addView(countView)
        chipRow.addView(chip)
        ptsView = ctx.tv("", 12f, subC, true)
        chipRow.addView(ptsView, ctx.ll(WRAP, WRAP, l = 10))
        hc.addView(chipRow, ctx.ll(WRAP, WRAP, t = 6))

        timeView = ctx.tv("", 13f, textC, true)
        timeView.maxLines = 1
        timeView.ellipsize = TextUtils.TruncateAt.END
        hc.addView(timeView, ctx.ll(WRAP, WRAP, t = 6))
        hc.addView(barHolder, ctx.ll(MATCH, WRAP, t = 8, r = 24))
        head.addView(hc, ctx.ll(0, WRAP, 1f))

        val x = ctx.tv("\u2715", 18f, textC, true)
        x.gravity = Gravity.CENTER
        x.background = withRipple(ctx.rounded(textC.withAlpha(0x1A), 20))
        x.isClickable = true
        ctx.makeFocusable(x)
        x.setOnClickListener { close() }
        x.pressable()
        val xlp = ctx.ll(ctx.dp(40), ctx.dp(40), l = 8)
        xlp.gravity = Gravity.TOP
        head.addView(x, xlp)
        panel.addView(head, ctx.ll(MATCH, WRAP))

        val div = View(ctx)
        div.setBackgroundColor(textC.withAlpha(0x22))
        panel.addView(div, ctx.ll(MATCH, ctx.dp(1), l = 16, r = 16))

        // ---- tabs + tools (scrolls sideways on narrow screens) ----
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        hs.isFocusable = false
        hs.clipToPadding = false
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        ctx.pad(bar, 14, 10, 14, 8)
        tabsRow.orientation = LinearLayout.HORIZONTAL
        toolRow.orientation = LinearLayout.HORIZONTAL
        bar.addView(tabsRow)
        bar.addView(View(ctx), ctx.ll(ctx.dp(14), 1))
        bar.addView(toolRow)
        hs.addView(bar)
        panel.addView(hs, ctx.ll(MATCH, WRAP))

        // ---- list ----
        scroll.isVerticalScrollBarEnabled = false
        scroll.isFocusable = false
        // rows slightly outside the panel melt away into the glass instead of being cut off hard
        scroll.clipToPadding = true
        scroll.isVerticalFadingEdgeEnabled = true
        scroll.setFadingEdgeLength(ctx.dp(36))
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        listBox.orientation = LinearLayout.VERTICAL
        ctx.pad(listBox, 12, 4, 12, 12)
        scroll.addView(listBox)
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> panel.invalidate() }
        panel.addView(scroll, ctx.ll(MATCH, 0, 1f))

        val combo = Hotkeys.overlayCombo
        val hint = ctx.tv(
            (if (combo.isEmpty()) "" else Hotkeys.label(combo) + " toggles   -   ") +
                "Tap outside, X or B to close   -   L1 / R1 tabs   -   Y refresh   -   X list / grid", 10f, subC
        )
        hint.gravity = Gravity.CENTER
        hint.maxLines = 1
        hint.ellipsize = TextUtils.TruncateAt.END
        panel.addView(hint, ctx.ll(MATCH, WRAP, l = 16, r = 16, b = 10))

        // above the panel (a solid panel has elevation, which would otherwise draw over the highlight)
        halo.elevation = ctx.dp(40).toFloat()
        addView(halo, LayoutParams(MATCH, MATCH))
        viewTreeObserver.addOnGlobalFocusChangeListener { _, n -> halo.follow(n) }

        renderTools()
        refreshFromSource(true)
    }

    // ---------- window ----------
    private fun displaySize(): Point {
        val p = Point()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.maximumWindowMetrics.bounds
                p.set(b.width(), b.height())
            } else {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealSize(p)
            }
        } catch (_: Throwable) {
        }
        if (p.x <= 0 || p.y <= 0) {
            val dm = ctx.resources.displayMetrics
            p.set(dm.widthPixels, dm.heightPixels)
        }
        return p
    }

    fun params(): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.windowAnimations = 0
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            lp.setFitInsetsTypes(0)
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    /** The overlay takes focus (for the controller), so it has to keep the game's bars hidden itself. */
    @Suppress("DEPRECATION")
    private fun keepImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = windowInsetsController
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private var motionHeld = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Foil.enabledFor("overlay") && Foil.motion) {
            FoilMotion.acquire(ctx)
            motionHeld = true
        }
        keepImmersive()
        ui.postDelayed(ticker, 1000L)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) keepImmersive()
    }

    override fun onDetachedFromWindow() {
        if (motionHeld) {
            motionHeld = false
            FoilMotion.release()
        }
        ui.removeCallbacksAndMessages(null)
        GameOverlay.detached(this)
        super.onDetachedFromWindow()
    }

    fun enter() {
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(220).start()
        panel.alpha = 0f
        panel.translationY = ctx.dp(28).toFloat()
        panel.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator(1.8f)).start()
    }

    fun close() {
        if (closing) return
        closing = true
        GameOverlay.detached(this)
        ui.removeCallbacks(ticker)
        scrim.animate().alpha(0f).setDuration(260).start()
        panel.animate().alpha(0f).translationY(ctx.dp(20).toFloat()).setDuration(260)
            .setInterpolator(AccelerateInterpolator(1.2f)).withEndAction {
                visibility = View.INVISIBLE
                ui.post {
                    try {
                        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(this)
                    } catch (_: Exception) {
                    }
                }
            }.start()
    }

    // ---------- controller ----------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        val handled = when (k) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> {
                if (event.action == KeyEvent.ACTION_UP) close()
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (event.action == KeyEvent.ACTION_DOWN) setTab((tab + 3) % 4)
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (event.action == KeyEvent.ACTION_DOWN) setTab((tab + 1) % 4)
                true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (event.action == KeyEvent.ACTION_DOWN) refreshNow()
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                if (event.action == KeyEvent.ACTION_DOWN) setGrid(!grid)
                true
            }
            else -> false
        }
        return handled || super.dispatchKeyEvent(event)
    }

    // ---------- data ----------
    private fun refreshFromSource(force: Boolean) {
        val s = provider(ctx)
        snap = s
        timeView.text = timeLine(s)
        if (force || s.version != shownVersion || s.id != shownId) {
            shownVersion = s.version
            shownId = s.id
            renderHeader()
            renderTabs(false)
            renderList(false)
        }
    }

    private fun refreshNow() {
        LiveGame.refreshWanted = true
        ui.postDelayed({ if (!closing) refreshFromSource(true) }, 350L)
    }

    private fun clock(ms: Long): String {
        val s = max(0L, ms / 1000L)
        val h = s / 3600L
        val m = (s % 3600L) / 60L
        val sec = s % 60L
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    private fun timeLine(s: OverlaySnap): String {
        val total = PlayTime.format(s.totalMs).ifEmpty { "<1m" }
        return "This session  " + clock(s.sessionMs) + "     Total  " + total
    }

    private fun renderHeader() {
        val s = snap
        titleView.text = if (s.title.isEmpty()) "No game running" else s.title
        if (s.icon.isNotEmpty()) {
            iconView.clearColorFilter()
            iconView.scaleType = ImageView.ScaleType.CENTER_CROP
            Img.load("$MEDIA/Images/${s.icon}.png", iconView)
        } else {
            iconView.setImageResource(R.drawable.ic_trophy)
            iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
            iconView.setColorFilter(Pal.ACC1)
        }
        val total = s.list.size
        val got = s.list.count { it.unlocked }
        var ptsAll = 0
        var ptsGot = 0
        for (a in s.list) {
            ptsAll += a.points
            if (a.unlocked) ptsGot += a.points
        }
        countView.text = if (total == 0) "Loading achievements..." else "$got of $total completed"
        ptsView.text = if (total == 0) "" else "$ptsGot / $ptsAll pts"
        barHolder.removeAllViews()
        val pct = if (total > 0) got * 100 / total else 0
        barHolder.addView(ctx.progressBar(pct, 6, total > 0 && got >= total), FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun ochip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val t = ctx.tv(text, 13f, if (selected) Color.WHITE else textC, true)
        t.gravity = Gravity.CENTER
        ctx.pad(t, 14, 9, 14, 9)
        t.background = withRipple(
            if (selected) ctx.accentFill(18) else {
                val g = ctx.rounded(textC.withAlpha(0x14), 18)
                g.setStroke(ctx.dp(1), textC.withAlpha(0x30))
                g
            }
        )
        t.isClickable = true
        ctx.makeFocusable(t)
        t.setOnClickListener { onClick() }
        t.pressable()
        return t
    }

    private fun counts(): IntArray {
        val l = snap.list
        return intArrayOf(l.count { it.inProgress }, l.count { it.unlocked }, l.count { !it.unlocked }, l.size)
    }

    private fun renderTabs(refocus: Boolean) {
        val hadFocus = tabsRow.findFocus() != null
        tabsRow.removeAllViews()
        val n = counts()
        val names = arrayOf("In progress", "Unlocked", "Locked", "All")
        for (i in names.indices) {
            val label = if (snap.list.isEmpty()) names[i] else names[i] + "  " + n[i]
            tabsRow.addView(ochip(label, tab == i) { setTab(i) }, ctx.ll(WRAP, WRAP, r = 8))
        }
        if (refocus || hadFocus) tabsRow.getChildAt(tab)?.requestFocus()
    }

    private fun renderTools() {
        val hadFocus = toolRow.findFocus()
        val focusIdx = if (hadFocus != null) toolRow.indexOfChild(hadFocus) else -1
        toolRow.removeAllViews()
        toolRow.addView(ochip("\u21BB  Refresh", false) { refreshNow() }, ctx.ll(WRAP, WRAP, r = 8))
        toolRow.addView(ochip("\u2630  List", !grid) { setGrid(false) }, ctx.ll(WRAP, WRAP, r = 8))
        toolRow.addView(ochip("\u25A6  Grid", grid) { setGrid(true) }, ctx.ll(WRAP, WRAP, r = 8))
        if (snap.id > 0) {
            toolRow.addView(ochip("RetroAchievements \u2197", false) { openSite() }, ctx.ll(WRAP, WRAP))
        }
        if (focusIdx >= 0) toolRow.getChildAt(focusIdx)?.requestFocus()
    }

    private fun openSite() {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://retroachievements.org/game/" + snap.id))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            close()
        } catch (_: Exception) {
        }
    }

    private fun setTab(i: Int) {
        tab = i
        prefs.edit().putInt("ov_tab", i).apply()
        renderTabs(true)
        renderList(true)
    }

    private fun setGrid(g: Boolean) {
        grid = g
        prefs.edit().putBoolean("ov_grid", g).apply()
        renderTools()
        renderList(false)
    }

    private fun filtered(): List<LiveAch> {
        val l = snap.list
        return when (tab) {
            // closest to done first; active challenges lead
            0 -> l.filter { it.inProgress }.sortedWith(
                compareByDescending<LiveAch> { it.bucket == 6 }.thenByDescending { it.pct }
            )
            // newest unlock first, this session's unlocks on top
            1 -> l.filter { it.unlocked }.sortedWith(
                compareByDescending<LiveAch> { it.fresh }.thenByDescending { it.unlockTime }
            )
            2 -> l.filter { !it.unlocked }.sortedWith(compareByDescending<LiveAch> { it.inProgress }.thenByDescending { it.pct })
            else -> l
        }
    }

    private fun renderList(resetScroll: Boolean) {
        val keepY = scroll.scrollY
        val focusedRow = listBox.findFocus()
        listBox.removeAllViews()
        val s = snap
        if (s.list.isEmpty()) {
            listBox.addView(message(if (s.title.isEmpty()) "Start a game in DraStic to see its achievements here."
            else "Reading the achievement list..."))
            return
        }
        val items = filtered()
        if (items.isEmpty()) {
            listBox.addView(message(when (tab) {
                0 -> "Nothing in progress right now. Achievements with a counter (like 3 / 10) show up here while you play."
                1 -> "No achievements unlocked yet - go get the first one!"
                2 -> "Everything is unlocked. Mastered!"
                else -> "This game has no achievements."
            }))
            return
        }
        val index = HashMap<Int, Int>()
        for (i in s.list.indices) index[s.list[i].id] = i + 1
        val total = s.list.size
        if (grid) {
            val cols = max(2, (panelW / ctx.resources.displayMetrics.density / 170f).toInt())
            var i = 0
            while (i < items.size) {
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                for (c in 0 until cols) {
                    val v: View = if (i + c < items.size) tile(items[i + c]) else View(ctx)
                    row.addView(v, ctx.ll(0, WRAP, 1f, l = if (c == 0) 0 else 8))
                }
                listBox.addView(row, ctx.ll(MATCH, WRAP, t = if (i == 0) 0 else 8))
                i += cols
            }
        } else {
            for ((n, a) in items.withIndex()) {
                listBox.addView(row(a, index[a.id] ?: 0, total), ctx.ll(MATCH, WRAP, t = if (n == 0) 0 else 6))
            }
        }
        if (resetScroll) {
            scroll.scrollTo(0, 0)
        } else {
            scroll.post { scroll.scrollTo(0, keepY) }
            if (focusedRow != null) listBox.post { firstFocusable(listBox)?.requestFocus() }
        }
    }

    private fun firstFocusable(g: ViewGroup): View? {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (c.isFocusable) return c
            if (c is ViewGroup) firstFocusable(c)?.let { return it }
        }
        return null
    }

    private fun message(t: String): View {
        val m = ctx.tv(t, 14f, subC)
        ctx.pad(m, 8, 18, 8, 18)
        m.gravity = Gravity.CENTER
        return m
    }

    private fun badgeUrl(a: LiveAch): String? =
        if (a.badge.isEmpty()) null else MEDIA + "/Badge/" + a.badge + (if (a.unlocked) "" else "_lock") + ".png"

    private fun badge(a: LiveAch, sizeDp: Int): ImageView {
        val url = badgeUrl(a)
        val img = ctx.square(sizeDp, 14, url)
        if (url == null) {
            img.scaleType = ImageView.ScaleType.CENTER_INSIDE
            ctx.pad(img, sizeDp / 5, sizeDp / 5, sizeDp / 5, sizeDp / 5)
            img.setImageResource(if (a.unlocked) R.drawable.ic_star else R.drawable.ic_lock)
            img.setColorFilter(if (a.unlocked) Pal.GOLD2 else subC)
        }
        return img
    }

    private fun statusView(a: LiveAch): View {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        val line = LinearLayout(ctx)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        when {
            a.unlocked && OfflineProxy.isPending(a.id) ->
                line.addView(ctx.tv("\u2714  Completed - not synced yet", 13f, 0xFFE0A100.toInt(), true))
            a.unlocked -> line.addView(ctx.tv("\u2714  Completed", 13f, goodC, true))
            a.inProgress -> {
                val t = if (a.progress.isNotEmpty()) "In progress  " + a.progress
                else if (a.bucket == 6) "Challenge active" else "Almost there"
                line.addView(ctx.tv(t, 13f, Pal.ACC1, true))
            }
            else -> line.addView(ctx.tv("Incomplete", 13f, badC, true))
        }
        if (a.fresh) {
            val nw = ctx.tv("NEW", 10f, Color.WHITE, true)
            ctx.pad(nw, 7, 2, 7, 2)
            nw.background = ctx.goldFill(10)
            line.addView(nw, ctx.ll(WRAP, WRAP, l = 8))
        }
        col.addView(line)
        if (a.inProgress && a.pct > 0f) {
            col.addView(ctx.progressBar(a.pct.toInt().coerceIn(1, 100), 5, false), ctx.ll(MATCH, WRAP, t = 5, r = 12))
        }
        return col
    }

    private fun pointsView(a: LiveAch): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        ctx.pad(box, 6, 5, 6, 5)
        box.background = ctx.rounded(textC.withAlpha(0x14), 18)
        val star = ImageView(ctx)
        star.setImageResource(R.drawable.ic_star)
        star.setColorFilter(Color.WHITE)
        ctx.pad(star, 4, 4, 4, 4)
        star.background = ctx.goldFill(13)
        box.addView(star, ctx.ll(ctx.dp(26), ctx.dp(26), r = 6))
        val p = ctx.tv(a.points.toString() + " Pts", 14f, Color.WHITE, true)
        ctx.pad(p, 10, 3, 10, 3)
        p.background = ctx.rounded(0xFF3A3F4B.toInt(), 14)
        box.addView(p)
        return box
    }

    private fun row(a: LiveAch, idx: Int, total: Int): View {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        ctx.pad(r, 12, 10, 12, 10)
        r.background = withRipple(
            ctx.rounded(if (a.fresh) Pal.ACC1.withAlpha(0x2E) else textC.withAlpha(if (a.unlocked) 0x12 else 0x08), 20)
        )
        val ic = badge(a, 58)
        if (!a.unlocked) ic.alpha = 0.85f
        r.addView(ic, ctx.ll(ctx.dp(58), ctx.dp(58), r = 14))

        val wide = panelW / ctx.resources.displayMetrics.density >= 560f
        val c1 = LinearLayout(ctx)
        c1.orientation = LinearLayout.VERTICAL
        val t = ctx.tv(a.title, 16f, textC, true)
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        c1.addView(t)
        c1.addView(statusView(a), ctx.ll(MATCH, WRAP, t = 3))
        if (!wide && a.desc.isNotEmpty()) {
            val d = ctx.tv(a.desc, 12f, subC)
            d.maxLines = 3
            d.ellipsize = TextUtils.TruncateAt.END
            c1.addView(d, ctx.ll(WRAP, WRAP, t = 3))
        }
        r.addView(c1, ctx.ll(0, WRAP, 1f, r = 10))

        if (wide) {
            val c2 = LinearLayout(ctx)
            c2.orientation = LinearLayout.VERTICAL
            c2.addView(ctx.tv("Achievement $idx of $total", 11f, subC))
            val d = ctx.tv(a.desc, 13f, textC)
            d.maxLines = 3
            d.ellipsize = TextUtils.TruncateAt.END
            c2.addView(d, ctx.ll(WRAP, WRAP, t = 2))
            r.addView(c2, ctx.ll(0, WRAP, 1.1f, r = 10))
        }
        r.addView(pointsView(a))
        r.isClickable = true
        ctx.makeFocusable(r)
        return r
    }

    private fun tile(a: LiveAch): View {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        ctx.pad(c, 10, 12, 10, 12)
        c.background = withRipple(
            ctx.rounded(if (a.fresh) Pal.ACC1.withAlpha(0x2E) else textC.withAlpha(if (a.unlocked) 0x12 else 0x08), 20)
        )
        val ic = badge(a, 64)
        if (!a.unlocked) ic.alpha = 0.85f
        c.addView(ic, ctx.ll(ctx.dp(64), ctx.dp(64)))
        val t = ctx.tv(a.title, 13f, textC, true)
        t.gravity = Gravity.CENTER
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        c.addView(t, ctx.ll(WRAP, WRAP, t = 8))
        val st = statusView(a)
        (st as LinearLayout).gravity = Gravity.CENTER_HORIZONTAL
        (st.getChildAt(0) as LinearLayout).gravity = Gravity.CENTER
        c.addView(st, ctx.ll(MATCH, WRAP, t = 4))
        val pts = ctx.tv(a.points.toString() + " Pts", 12f, Color.WHITE, true)
        ctx.pad(pts, 9, 2, 9, 2)
        pts.background = ctx.rounded(0xFF3A3F4B.toInt(), 12)
        c.addView(pts, ctx.ll(WRAP, WRAP, t = 6))
        c.isClickable = true
        ctx.makeFocusable(c)
        return c
    }
}
