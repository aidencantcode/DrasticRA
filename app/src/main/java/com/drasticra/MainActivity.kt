package com.drasticra

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    companion object {
        /** true while the app is on screen (the engine then leaves online / offline notices to the app) */
        @Volatile var foreground = false
        /** true once the app has fully started in this process (later re-creates only show a short veil) */
        @Volatile var warmedOnce = false
    }

    internal lateinit var root: FrameLayout
    internal lateinit var bg: BackgroundHost
    private lateinit var mainLayer: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var profilePill: LinearLayout
    private lateinit var avatarTop: ImageView
    private lateinit var clockText: TextView
    private lateinit var batteryView: BatteryView
    private lateinit var batteryText: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var gridBtn: ImageView
    private lateinit var listBtn: ImageView
    private val tabViews = ArrayList<TextView>()
    private lateinit var halo: FocusHalo

    private lateinit var drawer: ScrollView
    private lateinit var drawerScrim: View
    private lateinit var drawerCol: LinearLayout
    private var drawerOpen = false
    private var drawerWidth = 0
    internal var page = "main"

    internal var logView: TextView? = null
    internal var logScroll: ScrollView? = null

    internal val prefs by lazy { getSharedPreferences("dsra", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var tab = 0
    private var grid = true

    private var playSig = 0L
    private var lastRefresh = 0L

    private var home: RaHome? = null
    private var earnedAll: List<RaAch>? = null
    private var completion: List<RaGame>? = null
    private var detail: DetailPage? = null
    private var congrats: CaughtView? = null
    /** what had the controller highlight on the home screen before the drawer / a game page took over */
    private var lastMainFocus: View? = null
    /** set by the Button mapping page while it records a combo: gets every key event first */
    internal var keyRecorder: ((KeyEvent) -> Boolean)? = null

    // ---------- system ui ----------
    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ---------- build ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        val lastCrash = CrashLog.take(this)
        if (lastCrash != null) AppLog.add("---- LAST CRASH ----\n$lastCrash\n--------------------")
        Pal.load(this)
        Fonts.load(this)
        Img.init(this)
        Hotkeys.load(this)
        Foil.load(this)
        NetMonitor.init(this)
        OfflineStore.init(this)
        Ra.cacheDir = java.io.File(filesDir, "webcache").also { it.mkdirs() }
        if (Build.VERSION.SDK_INT >= 28) {
            val wlp = window.attributes
            wlp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = wlp
        }
        grid = prefs.getBoolean("grid", true)
        tab = prefs.getInt("tab2", 0).coerceIn(0, 4)
        TrophyStore.load(this)

        root = FrameLayout(this)
        root.setBackgroundColor(Pal.BG)
        bg = BackgroundHost(this)
        root.addView(bg, FrameLayout.LayoutParams(MATCH, MATCH))
        bg.reload()

        mainLayer = LinearLayout(this)
        mainLayer.orientation = LinearLayout.VERTICAL
        pad(mainLayer, 16, 12, 16, 12)
        mainLayer.clipChildren = false
        mainLayer.clipToPadding = false
        val topBar = buildTopBar()
        val tabsRow = buildTabsRow()
        val bottomBar = buildBottomBar()
        // title bar + tabs share one holder so they can dissolve together while the list scrolls over them
        header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.clipChildren = false
        header.clipToPadding = false
        header.addView(topBar, ll(MATCH, WRAP))
        header.addView(tabsRow, ll(MATCH, WRAP, t = 12))
        mainLayer.addView(header, ll(MATCH, WRAP))

        contentScroll = ScrollView(this)
        contentScroll.clipToPadding = false
        contentScroll.clipChildren = false
        contentScroll.isVerticalScrollBarEnabled = false
        // the scroll view itself must never take the highlight (it would outline the whole list)
        contentScroll.isFocusable = false
        // Android 12+ "stretch" overscroll redraws the list clipped to its own box, which chopped the cards that
        // slide up over the faded header into a hard square at the end of the page - so no overscroll effect
        contentScroll.overScrollMode = View.OVER_SCROLL_NEVER
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.clipChildren = false
        content.clipToPadding = false
        pad(content, 4, 10, 4, 14)
        contentScroll.addView(content)
        contentScroll.setOnScrollChangeListener { _, _, y, _, _ ->
            GlassBackdrop.invalidateAll()
            updateHeaderFade(y)
        }
        mainLayer.addView(contentScroll, ll(MATCH, 0, 1f))

        // bottom bar sits on top of a white/black radial backdrop that overhangs it (up, left, right, down)
        val bottomHolder = FrameLayout(this)
        bottomHolder.clipChildren = false
        bottomHolder.clipToPadding = false
        val scrimLp = FrameLayout.LayoutParams(MATCH, MATCH)
        scrimLp.setMargins(-dp(16), -dp(72), -dp(16), -dp(12))
        bottomHolder.addView(BottomScrimView(this), scrimLp)
        bottomHolder.addView(bottomBar, FrameLayout.LayoutParams(MATCH, WRAP))
        mainLayer.addView(bottomHolder, ll(MATCH, WRAP, t = 8))
        root.addView(mainLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        buildDrawer(savedInstanceState)

        halo = FocusHalo(this)
        // above the settings drawer (elevation 16): siblings with a higher elevation are drawn on top whatever the
        // child order is, which is why the highlight vanished behind the settings panel
        halo.elevation = dp(40).toFloat()
        root.addView(halo, FrameLayout.LayoutParams(MATCH, MATCH))
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, n ->
            // safety net: while the drawer is closed nothing inside it may hold focus (it sits off screen)
            if (n != null && !drawerOpen && isInside(n, drawer)) {
                halo.follow(null)
                n.clearFocus()
                focusHome()
                return@addOnGlobalFocusChangeListener
            }
            halo.follow(n)
        }

        setContentView(root)
        hideSystemUi()

        renderProfile()
        renderTab()
        tickClock()
        topBar.popIn(0)
        tabsRow.popIn(70)
        bottomBar.popIn(140)
        val first = tabViews.getOrNull(tab)
        first?.post { first.requestFocus() }

        // Loading screen: a glowing line and a hint while the cached data loads and the glass is warmed up.
        // A full launch gets the hints; a quick re-create (theme / glass change) only gets a short veil.
        if (drawerOpen) {
            loadCaches()
        } else {
            val cold = !warmedOnce
            val loader = LoadingView(this, cold)
            loader.elevation = dp(60).toFloat()
            loader.outlineProvider = null
            root.addView(loader, FrameLayout.LayoutParams(MATCH, MATCH))
            // glass loading screen: the real background and home screen show through it, blurred
            loader.blurBehind(bg, mainLayer)
            val gate = LoadingGate(loader, if (cold) 2200L else 450L) {
                warmedOnce = true
                first?.requestFocus()
                if (!NetMonitor.online) {
                    showNotice("No internet running offline mode!", "Achievements still unlock - they are sent when you are back online", true)
                }
                if (lastCrash != null) {
                    root.postDelayed({
                        showNotice("The app crashed last time", "Details are in Settings -> Tools and log (Share log)", true)
                    }, if (NetMonitor.online) 0L else 4600L)
                }
            }
            prewarmGlass { gate.warmReady() }
            loadCaches { gate.dataReady() }
        }
    }

    /**
     * Draws the whole settings drawer once, under the loading screen. The first time glass is drawn the GPU has to
     * build its blur/refraction layers and compile the shader; doing that now means the drawer slides in smooth
     * and fully formed later instead of flashing black.
     */
    private fun prewarmGlass(done: () -> Unit) {
        if (glassMode() != "full" || Build.VERSION.SDK_INT < 33) {
            done()
            return
        }
        drawer.translationX = 0f
        var frames = 0
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                GlassBackdrop.tick()
                frames++
                if (frames < 8) {
                    Choreographer.getInstance().postFrameCallback(this)
                } else {
                    if (!drawerOpen) drawer.translationX = -drawerWidth.toFloat()
                    done()
                }
            }
        }
        Choreographer.getInstance().postFrameCallback(cb)
    }

    private fun buildTopBar(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.clipChildren = false
        row.addView(iconButton(R.drawable.ic_menu, 46) { toggleDrawer() }, ll(dp(46), dp(46)))

        val title = card(26)
        title.orientation = LinearLayout.HORIZONTAL
        title.gravity = Gravity.CENTER
        pad(title, 10, 9, 10, 9)
        val tt = tv("RetroAchievements", 21f, Pal.TEXT, true)
        tt.maxLines = 1
        title.addView(tt)
        row.addView(title, ll(0, WRAP, 1f, l = 10, r = 10))

        val right = card(26)
        right.orientation = LinearLayout.HORIZONTAL
        right.gravity = Gravity.CENTER_VERTICAL
        pad(right, 14, 9, 14, 9)
        clockText = tv("--:--", 14f, Pal.TEXT, true)
        batteryView = BatteryView(this)
        batteryText = tv("", 12f, Pal.SUB, true)
        right.addView(clockText)
        right.addView(batteryView, ll(WRAP, WRAP, l = 10))
        right.addView(batteryText, ll(WRAP, WRAP, l = 4))
        row.addView(right, ll(WRAP, WRAP, r = 10))

        avatarTop = ImageView(this)
        avatarTop.scaleType = ImageView.ScaleType.CENTER_CROP
        avatarTop.background = rounded(Pal.FIELD, 23)
        avatarTop.clipCircle()
        avatarTop.elevation = dp(5).toFloat()
        avatarTop.isClickable = true
        avatarTop.setOnClickListener { avatarEgg() }
        avatarTop.pressable()
        row.addView(avatarTop, ll(dp(46), dp(46)))
        return row
    }

    private fun buildTabsRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.clipChildren = false

        profilePill = card(22)
        profilePill.orientation = LinearLayout.HORIZONTAL
        profilePill.gravity = Gravity.CENTER_VERTICAL
        pad(profilePill, 8, 6, 14, 6)
        row.addView(profilePill, ll(WRAP, WRAP, r = 10))

        val names = arrayOf("Recent", "Recently earned", "Earned", "Completion", "Trophies")
        tabViews.clear()
        // four tabs: they scroll sideways on narrow screens instead of squashing the row
        val tabScroll = android.widget.HorizontalScrollView(this)
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.isFocusable = false
        tabScroll.clipChildren = false
        tabScroll.clipToPadding = false
        tabScroll.overScrollMode = View.OVER_SCROLL_NEVER
        val tabRow = LinearLayout(this)
        tabRow.orientation = LinearLayout.HORIZONTAL
        tabRow.clipChildren = false
        pad(tabRow, 4, 4, 4, 4)
        for (i in names.indices) {
            val t = tv(names[i], 15f, Pal.SUB, true)
            t.gravity = Gravity.CENTER
            pad(t, 14, 9, 14, 9)
            t.maxLines = 1
            t.isClickable = true
            makeFocusable(t)
            t.setOnClickListener { selectTab(i) }
            t.pressable()
            tabViews.add(t)
            tabRow.addView(t, ll(WRAP, WRAP, r = 6))
        }
        tabScroll.addView(tabRow)
        row.addView(tabScroll, ll(0, WRAP, 1f, r = 8))

        val toggle = LinearLayout(this)
        toggle.orientation = LinearLayout.HORIZONTAL
        toggle.gravity = Gravity.CENTER_VERTICAL
        pad(toggle, 8, 6, 8, 6)
        toggle.background = rounded(0xFF3B3D48.toInt(), 20)
        gridBtn = ImageView(this)
        gridBtn.setImageResource(R.drawable.ic_grid)
        listBtn = ImageView(this)
        listBtn.setImageResource(R.drawable.ic_list)
        pad(gridBtn, 6, 4, 6, 4)
        pad(listBtn, 6, 4, 6, 4)
        gridBtn.setOnClickListener { setGrid(true) }
        listBtn.setOnClickListener { setGrid(false) }
        makeFocusable(gridBtn)
        makeFocusable(listBtn)
        toggle.addView(gridBtn, ll(dp(34), dp(30)))
        toggle.addView(listBtn, ll(dp(34), dp(30)))
        row.addView(toggle, ll(WRAP, WRAP))
        styleTabs()
        styleToggle()
        return row
    }

    private fun buildBottomBar(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.clipChildren = false

        val st = card(22)
        st.orientation = LinearLayout.HORIZONTAL
        st.gravity = Gravity.CENTER_VERTICAL
        pad(st, 16, 12, 18, 12)
        statusDot = View(this)
        st.addView(statusDot, ll(dp(10), dp(10), r = 10))
        statusText = tv("Idle", 14f, Pal.TEXT, true)
        statusText.maxLines = 1
        statusText.ellipsize = TextUtils.TruncateAt.END
        st.addView(statusText)
        row.addView(st, ll(0, WRAP, 1f, r = 10))

        row.addView(pill("Start watching", true) { startWatching() }, ll(WRAP, WRAP, r = 8))
        row.addView(pill("Stop", false) { stopWatching() }, ll(WRAP, WRAP))
        return row
    }

    /**
     * The title bar and tabs dissolve over the first ~64dp of scrolling, so cards sliding up over them never
     * sit on a half see-through header. Once fully gone they are INVISIBLE (not focusable) until you scroll back.
     */
    private fun updateHeaderFade(scrollY: Int) {
        val t = (scrollY / dp(64).toFloat()).coerceIn(0f, 1f)
        val k = t * t * (3f - 2f * t)
        header.alpha = 1f - k
        header.translationY = -k * dp(12)
        header.visibility = if (t >= 1f) View.INVISIBLE else View.VISIBLE
    }

    // ---------- controller focus ----------
    internal fun isInside(v: View, group: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === group) return true
            p = p.parent as? View
        }
        return false
    }

    /** First focusable, visible view inside [g] in layout order. */
    internal fun firstFocusable(g: ViewGroup): View? {
        if (g.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS) return null
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (c.isFocusable && c.isEnabled) return c
            if (c is ViewGroup) firstFocusable(c)?.let { return it }
        }
        return null
    }

    /** Puts the highlight back on the home screen: the last thing selected there, else the current tab. */
    private fun focusHome() {
        val d = detail
        if (d != null) {
            firstFocusable(d)?.requestFocus()
            return
        }
        val last = lastMainFocus
        if (last != null && last.isAttachedToWindow && last.isShown && last.requestFocus()) return
        val t = tabViews.getOrNull(tab)
        if (t != null && t.isShown) t.requestFocus() else firstFocusable(content)?.requestFocus()
    }

    // ---------- tabs ----------
    private fun styleTabs() {
        for (i in tabViews.indices) {
            val t = tabViews[i]
            if (i == tab) {
                t.setTextColor(Pal.TEXT)
                t.background = glassCard(20)
                t.elevation = dp(5).toFloat()
            } else {
                t.setTextColor(Pal.SUB)
                t.background = rounded(Pal.CARD_T.withAlpha(0x66), 20)
                t.elevation = 0f
            }
        }
    }

    private fun styleToggle() {
        gridBtn.setColorFilter(if (grid) Color.WHITE else 0x88FFFFFF.toInt())
        listBtn.setColorFilter(if (!grid) Color.WHITE else 0x88FFFFFF.toInt())
    }

    private fun setGrid(g: Boolean) {
        grid = g
        prefs.edit().putBoolean("grid", g).apply()
        styleToggle()
        renderTab()
    }

    private fun selectTab(i: Int) {
        tab = (i + tabViews.size) % tabViews.size
        prefs.edit().putInt("tab2", tab).apply()
        styleTabs()
        // put the spinner up straight away, then build the list on the next frame so the switch never feels stuck
        val gen = ++renderGen
        val hadCard = currentFocus?.let { isInside(it, content) } == true
        content.removeAllViews()
        content.addView(spinnerCard("Loading..."), ll(MATCH, WRAP))
        if (hadCard) tabViews.getOrNull(tab)?.requestFocus()
        content.postOnAnimation { if (gen == renderGen) renderTab() }
        if ((tab == 1 || tab == 2) && earnedAll == null) fetchEarned()
        if (tab == 3 && completion == null) fetchCompletion()
        if (tab == 4) scanTrophies(false)
    }

    // ---------- rendering ----------
    internal fun renderProfile() {
        profilePill.removeAllViews()
        val p = home?.profile
        val name = p?.name ?: ((prefs.getString("user_ra", "") ?: "").ifEmpty { "Drastic RA" })
        val av = ImageView(this)
        av.scaleType = ImageView.ScaleType.CENTER_CROP
        av.background = rounded(Pal.FIELD, 15)
        av.clipCircle()
        if (p != null) {
            Img.load(MEDIA + p.pic, av)
            Img.load(MEDIA + p.pic, avatarTop)
        }
        profilePill.addView(av, ll(dp(30), dp(30), r = 8))
        profilePill.addView(tv(name, 15f, Pal.TEXT, true))
        if (p != null) {
            val ic = ImageView(this)
            ic.setImageResource(R.drawable.ic_trophy)
            ic.setColorFilter(Pal.TEXT)
            profilePill.addView(ic, ll(dp(18), dp(18), l = 8, r = 3))
            profilePill.addView(tv((p.softPts + p.hardPts).toString(), 15f, Pal.TEXT, true))
        }
    }

    /** bumped on every render so batches still queued from an older render stop building */
    private var renderGen = 0

    /** A card with the round accent spinner and a line of text (loading states). */
    private fun spinnerCard(msg: String): View {
        val m = card(24)
        m.orientation = LinearLayout.HORIZONTAL
        m.gravity = Gravity.CENTER_VERTICAL
        pad(m, 18, 16, 18, 16)
        m.addView(SpinnerView(this), ll(dp(30), dp(30), r = 14))
        m.addView(tv(msg, 14f, Pal.SUB), ll(0, WRAP, 1f))
        return m
    }

    internal fun renderTab() {
        // remember which card had the highlight, so a refresh does not throw it somewhere random
        val f = currentFocus
        var focusIdx = -1
        if (f != null && isInside(f, content)) {
            var i = 0
            for (r in 0 until content.childCount) {
                val row = content.getChildAt(r) as? ViewGroup ?: continue
                for (c in 0 until row.childCount) {
                    val cv = row.getChildAt(c)
                    if (cv.isFocusable) {
                        if (cv === f || isInside(f, cv)) focusIdx = i
                        i++
                    }
                }
            }
        }
        val gen = ++renderGen
        content.removeAllViews()
        // a new list may clamp the scroll position, so re-check the header once it has been laid out
        contentScroll.post { updateHeaderFade(contentScroll.scrollY) }
        if (tab == 4) {
            renderTrophies(focusIdx)
            return
        }
        val dsOnly = prefs.getBoolean("ds_only", false)
        // card builders: views are created in small batches so long lists (Earned can hold thousands) never freeze
        val makers = ArrayList<() -> View>()
        var emptyMsg = "Nothing here yet."
        var loading = false
        when (tab) {
            0 -> {
                val h = home
                if (h == null) {
                    emptyMsg = "Log in and add your Web API key in Settings (menu button) to see your games."
                } else {
                    // every console: DS, and whatever RetroArch / PPSSPP / ARMSX2 play
                    for (g in h.recent) makers.add { gameCard(g, true) }
                    if (makers.isEmpty()) emptyMsg = "No games in your recent list yet."
                    playSig = computePlaySig()
                }
            }
            1 -> {
                // Recently earned: every console, newest first, offline unlocks waiting to sync on top
                for (pu in OfflineProxy.pending()) makers.add { achCard(pendingAch(pu), true) }
                val e = earnedAll
                if (e == null) {
                    loading = haveCreds() && makers.isEmpty()
                    emptyMsg = if (loading) "Loading your recent unlocks..." else "Add your Web API key in Settings -> Account."
                } else {
                    var n = 0
                    for (a in e) if (n < 100) {
                        makers.add { achCard(a) }
                        n++
                    }
                    if (makers.isEmpty()) emptyMsg = "No achievements earned recently."
                }
            }
            2 -> {
                for (pu in OfflineProxy.pending()) makers.add { achCard(pendingAch(pu), true) }
                val e = earnedAll
                if (e == null) {
                    loading = haveCreds() && makers.isEmpty()
                    emptyMsg = if (loading) "Loading your achievements..." else "Add your Web API key in Settings -> Account."
                } else {
                    for (a in e) if (!dsOnly || a.console.contains("DS")) makers.add { achCard(a) }
                }
            }
            else -> {
                val c = completion
                if (c == null) {
                    loading = haveCreds()
                    emptyMsg = if (loading) "Loading your completion progress..." else "Add your Web API key in Settings -> Account."
                } else {
                    for (g in c) if (!dsOnly || g.console.contains("DS")) makers.add { gameCard(g) }
                }
            }
        }
        if (makers.isEmpty()) {
            val m: View = if (loading) spinnerCard(emptyMsg) else {
                val c = card(24)
                pad(c, 18, 16, 18, 16)
                c.addView(tv(emptyMsg, 14f, Pal.SUB))
                c
            }
            content.addView(m, ll(MATCH, WRAP))
            m.popIn(0)
            if (focusIdx >= 0) tabViews.getOrNull(tab)?.requestFocus()
            return
        }
        val cols = if (grid && resources.configuration.screenWidthDp >= 560) 2 else 1
        val built = ArrayList<View>()
        var tail: View? = null
        var focused = focusIdx < 0
        val step = object : Runnable {
            var i = 0
            override fun run() {
                if (gen != renderGen) return
                tail?.let { content.removeView(it) }
                tail = null
                // first screenful right away, then a few rows per frame
                val rows = if (i == 0) 6 else 4
                var r = 0
                while (r < rows && i < makers.size) {
                    val row = LinearLayout(this@MainActivity)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.clipChildren = false
                    for (c in 0 until cols) {
                        if (i + c < makers.size) {
                            val v = makers[i + c]()
                            row.addView(v, ll(0, WRAP, 1f, l = if (c == 0) 0 else 12))
                            if (built.size < 10) v.popIn(built.size * 45L)
                            built.add(v)
                        } else {
                            row.addView(View(this@MainActivity), ll(0, 1, 1f, l = 12))
                        }
                    }
                    content.addView(row, ll(MATCH, WRAP, t = if (i == 0) 0 else 12))
                    i += cols
                    r++
                }
                if (!focused && focusIdx < built.size) {
                    focused = true
                    val target = built[focusIdx]
                    target.post { if (!drawerOpen && detail == null) target.requestFocus() }
                }
                if (i < makers.size) {
                    // small spinner at the end of the list while the rest is still being added
                    val sp = LinearLayout(this@MainActivity)
                    sp.gravity = Gravity.CENTER
                    sp.addView(SpinnerView(this@MainActivity), ll(dp(26), dp(26)))
                    content.addView(sp, ll(MATCH, WRAP, t = 14))
                    tail = sp
                    content.postOnAnimation(this)
                } else if (!focused && built.isNotEmpty()) {
                    focused = true
                    val target = built.last()
                    target.post { if (!drawerOpen && detail == null) target.requestFocus() }
                }
                GlassBackdrop.invalidateAll()
            }
        }
        step.run()
    }

    // ---------- trophies tab ----------
    private var lastTrophyScan = 0L

    /** Looks for new trophies with root (in the background); [force] ignores the 90 s pause between scans. */
    internal fun scanTrophies(force: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastTrophyScan < 90000L && TrophyStore.games.isNotEmpty()) return
        lastTrophyScan = now
        Thread {
            val games = try { TrophyScanner.scan(this, true) } catch (_: Throwable) { null }
            if (games != null) TrophyStore.set(this, games)
            runOnUiThread { if (tab == 4) renderTab() }
        }.start()
    }

    private fun sectionHeader(text: String): View {
        val t = sectionTitle(text)
        pad(t, 6, 0, 6, 0)
        return t
    }

    /** PS3 / PS Vita / PS4 trophies: newest unlocks, then every game (recently played first). */
    private fun renderTrophies(focusIdx: Int) {
        val games = TrophyStore.games
        if (games.isEmpty()) {
            val m = card(24)
            pad(m, 18, 16, 18, 16)
            val busy = TrophyScanner.scanning
            if (busy) {
                m.orientation = LinearLayout.HORIZONTAL
                m.gravity = Gravity.CENTER_VERTICAL
                m.addView(SpinnerView(this), ll(dp(30), dp(30), r = 14))
            }
            m.addView(tv(if (busy) "Looking for trophies in ARMSX3, Vita3K and Bachata S4..." else
                "No trophies found yet. Play something in ARMSX3 (PS3), Vita3K (PS Vita) or Bachata S4 (PS4) - or set extra " +
                    "folders in Settings -> Emulators.", 14f, Pal.SUB), ll(0, WRAP, 1f))
            content.addView(m, ll(MATCH, WRAP))
            m.popIn(0)
            if (!busy) {
                val b = pill("Scan for trophies", true) { scanTrophies(true); renderTab() }
                content.addView(b, ll(MATCH, WRAP, t = 12))
            }
            return
        }
        val cards = ArrayList<View>()
        val recent = TrophyStore.recent(6)
        if (recent.isNotEmpty()) {
            content.addView(sectionHeader("RECENTLY UNLOCKED TROPHIES"), ll(MATCH, WRAP, b = 8))
            for ((g, t) in recent) {
                val v = trophyCard(g, t)
                content.addView(v, ll(MATCH, WRAP, b = 10))
                cards.add(v)
            }
        }
        content.addView(sectionHeader("GAMES  -  " + games.size), ll(MATCH, WRAP, t = 8, b = 8))
        val cols = if (grid && resources.configuration.screenWidthDp >= 560) 2 else 1
        var i = 0
        while (i < games.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.clipChildren = false
            for (c in 0 until cols) {
                if (i + c < games.size) {
                    val v = trophyGameCard(games[i + c])
                    row.addView(v, ll(0, WRAP, 1f, l = if (c == 0) 0 else 12))
                    cards.add(v)
                } else row.addView(View(this), ll(0, 1, 1f, l = 12))
            }
            content.addView(row, ll(MATCH, WRAP, b = 12))
            i += cols
        }
        for ((n, v) in cards.withIndex()) if (n < 10) v.popIn(n * 40L)
        if (focusIdx >= 0 && cards.isNotEmpty()) {
            val t = cards[minOf(focusIdx, cards.size - 1)]
            t.post { if (!drawerOpen && detail == null) t.requestFocus() }
        }
        GlassBackdrop.invalidateAll()
    }

    /** Sum of tracked play minutes over the games on the Recent tab, so we know when its cards are out of date. */
    private fun computePlaySig(): Long {
        var s = 0L
        val h = home ?: return 0L
        for (g in h.recent) s += PlayTime.ms(this, g.id) / 60000L + 1L
        return s
    }

    private fun gameCard(g: RaGame, showTime: Boolean = false): View {
        val c = card(26)
        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        pad(top, 14, 14, 14, 12)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(square(66, 16, if (g.icon.isEmpty()) null else MEDIA + g.icon), ll(dp(66), dp(66), r = 12))
        val title = tv(g.title, 16f, Pal.TEXT, true)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        val titleCol = LinearLayout(this)
        titleCol.orientation = LinearLayout.VERTICAL
        titleCol.addView(title)
        if (showTime) {
            val pt = PlayTime.format(PlayTime.ms(this, g.id))
            val line = if (pt.isEmpty()) tv("Play time: not tracked yet", 12f, Pal.SUB)
            else tv("Played  $pt", 12f, Pal.ACC1, true)
            line.maxLines = 1
            titleCol.addView(line, ll(WRAP, WRAP, t = 3))
        }
        head.addView(titleCol, ll(0, WRAP, 1f))
        head.addView(tag(shortConsole(g.console)), ll(WRAP, WRAP, l = 8))
        top.addView(head)

        if (g.badges.isNotEmpty()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (b in g.badges) {
                row.addView(square(32, 8, "$MEDIA/Badge/$b.png"), ll(dp(32), dp(32), r = 6))
            }
            top.addView(row, ll(WRAP, WRAP, t = 12))
        }
        c.addView(top, ll(MATCH, WRAP))

        val div = View(this)
        div.setBackgroundColor(Pal.DIV)
        c.addView(div, ll(MATCH, dp(1)))

        val done = g.total > 0 && g.achieved >= g.total
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        pad(bottom, 16, 11, 16, 13)
        val ic = ImageView(this)
        ic.setImageResource(if (done) R.drawable.ic_star else R.drawable.ic_trophy)
        ic.setColorFilter(if (done) Pal.GOLD2 else Pal.TEXT)
        bottom.addView(ic, ll(dp(20), dp(20), r = 8))
        bottom.addView(tv(g.achieved.toString() + "/" + g.total, 14f, Pal.TEXT, true), ll(WRAP, WRAP, r = 12))
        val pct = if (g.total > 0) g.achieved * 100 / g.total else 0
        bottom.addView(progressBar(pct, 7, done), ll(0, WRAP, 1f))
        c.addView(bottom, ll(MATCH, WRAP))

        c.isClickable = true
        makeFocusable(c)
        c.setOnClickListener { openDetail(g) }
        c.pressable()
        return c
    }

    /** An offline unlock that is not on the site yet, shown like an earned achievement. */
    private fun pendingAch(p: PendingUnlock): RaAch = RaAch(
        p.title, p.desc, p.points, p.badge, p.game.ifEmpty { "Offline play" }, p.gameIcon, "Nintendo DS",
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(p.at * 1000L)), p.gameId
    )

    private fun achCard(a: RaAch, pending: Boolean = false): View {
        val c = card(22)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        pad(c, 12, 12, 14, 12)
        c.addView(square(54, 14, "$MEDIA/Badge/" + a.badge + ".png"), ll(dp(54), dp(54), r = 12))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = tv(a.title, 15f, Pal.TEXT, true)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        val d = tv(a.desc, 12f, Pal.SUB)
        d.maxLines = 2
        d.ellipsize = TextUtils.TruncateAt.END
        val meta = tv(a.game + (if (a.date.length >= 10) "  -  " + a.date.substring(0, 10) else ""), 11f, Pal.SUB)
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.END
        col.addView(t)
        col.addView(d)
        col.addView(meta, ll(WRAP, WRAP, t = 2))
        c.addView(col, ll(0, WRAP, 1f, r = 8))
        val pts = tv(a.points.toString(), 13f, Color.WHITE, true)
        pad(pts, 11, 4, 11, 4)
        pts.background = accentFill(14)
        c.addView(pts, ll(WRAP, WRAP))
        if (pending) {
            // yellow tint + note: unlocked offline, waiting for internet to reach RetroAchievements
            c.foreground = rounded(0x38FFC21A, 22)
            meta.text = "Not synced yet  -  waiting for internet"
            meta.setTextColor(0xFFE0A100.toInt())
        }
        c.isClickable = true
        makeFocusable(c)
        c.setOnClickListener {
            if (a.gameId != 0) openDetail(RaGame(a.gameId, a.game, a.gameIcon, a.console, 0, 0, ArrayList<String>()))
        }
        c.pressable()
        return c
    }

    // ---------- detail / congrats ----------
    internal fun openDetail(g: RaGame) {
        if (detail != null || g.id == 0) return
        lastMainFocus = currentFocus?.takeIf { isInside(it, mainLayer) }
        val d = GameDetailView(this, g) { closeDetailNow() }
        detail = d
        mainLayer.visibility = View.INVISIBLE
        root.addView(d, 2, FrameLayout.LayoutParams(MATCH, MATCH))
        d.slideIn()
    }

    /** PS3 / Vita / PS4 trophy list of one game. */
    internal fun openTrophyDetail(g: TrophyGame) {
        if (detail != null) return
        lastMainFocus = currentFocus?.takeIf { isInside(it, mainLayer) }
        val d = TrophyDetailView(this, g) { closeDetailNow() }
        detail = d
        mainLayer.visibility = View.INVISIBLE
        root.addView(d, 2, FrameLayout.LayoutParams(MATCH, MATCH))
        d.slideIn()
    }

    private fun closeDetailNow() {
        val d = detail ?: return
        root.removeView(d)
        detail = null
        mainLayer.visibility = View.VISIBLE
        GlassBackdrop.tick()
        mainLayer.post { if (!drawerOpen) focusHome() }
    }

    internal fun showCongrats(items: List<UnseenItem>) {
        if (items.isEmpty() || congrats != null) return
        val v = CaughtView(this, items) {
            congrats?.let { root.removeView(it) }
            congrats = null
        }
        congrats = v
        v.elevation = dp(50).toFloat()
        v.outlineProvider = null
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        v.start()
    }

    // ---------- notices / edge glow ----------
    private var noticeGlow: EdgeGlowView? = null
    private var noticeCard: View? = null

    /** Edge glow inside the app; [colors] null = the Edge glow setting. Fades out after [holdMs]. */
    internal fun showEdgeGlow(colors: IntArray?, holdMs: Long, positions: FloatArray? = null): EdgeGlowView {
        val g = EdgeGlowView(this, root.width, root.height, colors, positions)
        g.isClickable = false
        g.isFocusable = false
        g.alpha = 0.88f
        g.elevation = dp(45).toFloat()
        g.outlineProvider = null
        root.addView(g, FrameLayout.LayoutParams(MATCH, MATCH))
        g.flash()
        g.calmAfter(holdMs / 2)
        g.postDelayed({ g.fadeOut(900L) { root.removeView(g) } }, holdMs)
        return g
    }

    /**
     * Online / offline notice: a glass card sliding down from the top plus an edge glow - yellow with a warning
     * when the internet is gone, your accent (Edge glow setting) when it is back.
     */
    internal fun showNotice(title: String, sub: String, warn: Boolean) {
        if (!::root.isInitialized || root.width == 0) {
            root.post { showNotice(title, sub, warn) }
            return
        }
        noticeGlow?.let { old -> old.fadeOut(300L) { root.removeView(old) } }
        noticeCard?.let { root.removeView(it) }
        noticeGlow = showEdgeGlow(if (warn) NetNotice.YELLOW else null, 3600L)

        val c = card(24)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        pad(c, 14, 12, 18, 12)
        val badge = TextView(this)
        badge.text = if (warn) "!" else "\u2713"
        badge.gravity = Gravity.CENTER
        badge.textSize = 20f
        badge.typeface = Fonts.title
        badge.setTextColor(if (warn) 0xFF3A2A00.toInt() else Color.WHITE)
        badge.background = if (warn) {
            val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFFE066.toInt(), 0xFFFFB020.toInt()))
            g.shape = GradientDrawable.OVAL
            g
        } else {
            val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, Pal.grad())
            g.shape = GradientDrawable.OVAL
            g
        }
        c.addView(badge, ll(dp(40), dp(40), r = 12))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val l0 = tv(if (warn) "OFFLINE MODE" else "ONLINE MODE", 10f, if (warn) 0xFFE0A100.toInt() else Pal.ACC1, true)
        l0.letterSpacing = 0.14f
        col.addView(l0)
        col.addView(tv(title, 15f, Pal.TEXT, true))
        val st = tv(sub, 11f, Pal.SUB)
        st.maxLines = 2
        col.addView(st)
        c.addView(col, ll(WRAP, WRAP))
        c.elevation = dp(46).toFloat()
        val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        lp.topMargin = dp(14)
        root.addView(c, lp)
        noticeCard = c
        c.alpha = 0f
        c.translationY = -dp(60).toFloat()
        c.animate().alpha(1f).translationY(0f).setDuration(460).setInterpolator(OvershootInterpolator(1.3f)).start()
        c.setOnClickListener { hideNotice(c) }
        c.postDelayed({ hideNotice(c) }, 4200L)
    }

    private fun hideNotice(c: View) {
        if (c.tag == "gone") return
        c.tag = "gone"
        c.animate().alpha(0f).translationY(-dp(30).toFloat()).setDuration(420).withEndAction {
            root.removeView(c)
            if (noticeCard === c) noticeCard = null
        }.start()
    }

    // ---------- Offline Play caching screen ----------
    private var cacheModal: View? = null
    private var cacheListener: ((RomCacher.Progress) -> Unit)? = null

    /** Starts caching every ROM in [folder] and shows the progress screen (titles left, time left). */
    internal fun startRomCache(folder: String, force: Boolean) {
        if (!NetMonitor.online) {
            showNotice("You are offline", "Caching needs internet - connect and try again", true)
            return
        }
        if (!RomCacher.running) RomCacher.start(this, folder, force)
        showCacheModal()
    }

    internal fun showCacheModal() {
        if (cacheModal != null) return
        val scrim = FrameLayout(this)
        scrim.setBackgroundColor(0xA0000000.toInt())
        scrim.isClickable = true
        scrim.elevation = dp(47).toFloat()
        val box = card(28)
        pad(box, 22, 20, 22, 18)
        box.addView(tv("CACHING FOR OFFLINE PLAY", 11f, Pal.ACC1, true).also { it.letterSpacing = 0.14f })
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(SpinnerView(this), ll(dp(34), dp(34), r = 14))
        val count = tv("Getting ready...", 20f, Pal.TEXT, true)
        head.addView(count, ll(0, WRAP, 1f))
        box.addView(head, ll(MATCH, WRAP, t = 10))
        val barHolder = FrameLayout(this)
        box.addView(barHolder, ll(MATCH, WRAP, t = 14))
        val left = tv("", 13f, Pal.TEXT, true)
        box.addView(left, ll(WRAP, WRAP, t = 10))
        val cur = tv("", 12f, Pal.SUB)
        cur.maxLines = 1
        cur.ellipsize = TextUtils.TruncateAt.END
        box.addView(cur, ll(MATCH, WRAP, t = 2))
        val stats = tv("", 12f, Pal.SUB)
        box.addView(stats, ll(WRAP, WRAP, t = 8))
        val btn = pill("Stop", false) { RomCacher.cancel() }
        box.addView(btn, ll(MATCH, WRAP, t = 16))
        val blp = FrameLayout.LayoutParams(minOf(dp(460), resources.displayMetrics.widthPixels - dp(40)), WRAP, Gravity.CENTER)
        scrim.addView(box, blp)
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        cacheModal = scrim
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(220).start()
        box.popIn(0)
        btn.post { btn.requestFocus() }

        fun fmtEta(ms: Long): String {
            val sec = ms / 1000L
            return if (sec >= 3600) "${sec / 3600}h ${(sec % 3600) / 60}m" else if (sec >= 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
        }
        val l: (RomCacher.Progress) -> Unit = { p ->
            if (p.running) {
                count.text = if (p.total == 0) "Looking for ROMs..." else "${p.done} / ${p.total} titles"
                left.text = if (p.total == 0) "" else "${p.total - p.done} left" +
                    (if (p.done > 0 && p.etaMs > 0) "   -   about ${fmtEta(p.etaMs)} to go" else "")
                cur.text = p.current
                stats.text = "Ready: ${p.cached}    No achievements: ${p.unsupported}    Failed: ${p.failed}"
                barHolder.removeAllViews()
                barHolder.addView(progressBar(if (p.total > 0) p.done * 100 / p.total else 0, 9, false),
                    FrameLayout.LayoutParams(MATCH, WRAP))
            } else {
                count.text = "Finished"
                left.text = p.message
                cur.text = ""
                barHolder.removeAllViews()
                barHolder.addView(progressBar(100, 9, true), FrameLayout.LayoutParams(MATCH, WRAP))
                btn.text = "Close"
                btn.setOnClickListener { hideCacheModal() }
                if (page == "offline") showPage("offline", false)
            }
        }
        cacheListener = l
        RomCacher.listeners.add(l)
        RomCacher.progress?.let { l(it) }
    }

    private fun hideCacheModal() {
        val m = cacheModal ?: return
        cacheModal = null
        cacheListener?.let { RomCacher.listeners.remove(it) }
        cacheListener = null
        m.animate().alpha(0f).setDuration(220).withEndAction { root.removeView(m) }.start()
    }

    // ---------- avatar easter egg ----------
    private var eggLevel = 0
    private var eggLast = 0L
    private var eggGlow: EdgeGlowView? = null

    /** Appearance -> highlight thickness changed: restyle the highlight without restarting. */
    internal fun reloadHalo() = halo.loadStyle()

    private fun buzz(effect: VibrationEffect) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (v.hasVibrator()) v.vibrate(effect)
        } catch (_: Exception) {
        }
    }

    /** Level 1 = your accent, 2..5 = that many random colours spread around the wheel, 6 = full rainbow. */
    private fun eggColors(level: Int): IntArray? {
        if (level <= 1) return null
        if (level >= 6) return intArrayOf(
            0xFFFF3B3B.toInt(), 0xFFFF9F1C.toInt(), 0xFFFFE53B.toInt(), 0xFF3BE07A.toInt(),
            0xFF2EC5FF.toInt(), 0xFF5B5BFF.toInt(), 0xFFC23BFF.toInt()
        )
        val off = (Math.random() * 360.0).toFloat()
        return IntArray(level) { i -> Color.HSVToColor(floatArrayOf((off + i * 360f / level) % 360f, 0.72f, 1f)) }
    }

    /**
     * Tap your picture (top right): a buzz and the achievement edge glow. Keep tapping to add colours - 1, 2, 3, 4, 5 -
     * until it goes full rainbow, then it starts over. Stop for a few seconds and it resets too.
     */
    internal fun avatarEgg() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - eggLast > 6000L) eggLevel = 0
        eggLast = now
        eggLevel = if (eggLevel >= 6) 1 else eggLevel + 1
        val level = eggLevel
        if (level >= 6) {
            buzz(VibrationEffect.createWaveform(longArrayOf(0, 40, 50, 40, 50, 90), intArrayOf(0, 160, 0, 200, 0, 255), -1))
        } else {
            buzz(VibrationEffect.createOneShot(25L + level * 10L, (70 + level * 30).coerceAtMost(255)))
        }
        avatarTop.animate().cancel()
        avatarTop.scaleX = 1.18f
        avatarTop.scaleY = 1.18f
        avatarTop.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()

        eggGlow?.let { old -> old.fadeOut(420L) { root.removeView(old) } }
        val g = EdgeGlowView(this, root.width, root.height, eggColors(level))
        g.isClickable = false
        g.isFocusable = false
        g.alpha = 0.88f
        g.elevation = dp(45).toFloat()
        g.outlineProvider = null
        root.addView(g, FrameLayout.LayoutParams(MATCH, MATCH))
        eggGlow = g
        g.flash()
        g.calmAfter(1500L)
        g.postDelayed({
            if (eggGlow === g) {
                eggGlow = null
                g.fadeOut(900L) { root.removeView(g) }
            }
        }, 2600L)
    }

    internal fun previewCongrats() {
        closeDrawer()
        showCongrats(
            listOf(
                UnseenItem("Test Achievement", "This is how a celebration looks.", 25, "", "Pokemon SoulSilver"),
                UnseenItem("Another One", "Second unlock", 10, "", "Pokemon SoulSilver"),
                UnseenItem("And A Third", "Third unlock", 5, "", "Pokemon SoulSilver")
            )
        )
    }

    // ---------- data ----------
    internal fun apiUser(): String {
        val u = prefs.getString("user_ra", "") ?: ""
        return if (u.isNotEmpty()) u else (prefs.getString("user", "") ?: "")
    }

    internal fun apiKey(): String = prefs.getString("apikey", "") ?: ""

    private fun loadCaches(onLoaded: () -> Unit = {}) {
        Thread {
            val u = apiUser()
            try {
                val s = prefs.getString("summary", null)
                if (s != null && u.isNotEmpty()) home = Ra.parseSummary(s, u)
            } catch (_: Exception) {
            }
            try {
                val e = prefs.getString("earned", null)
                if (e != null) earnedAll = Ra.parseEarned(e)
            } catch (_: Exception) {
            }
            try {
                val c = prefs.getString("completion", null)
                if (c != null) completion = Ra.parseCompletion(c)
            } catch (_: Exception) {
            }
            // warm the picture cache for the first screenful so icons are already there when the screen opens
            home?.recent?.take(14)?.forEach { g ->
                if (g.icon.isNotEmpty()) Img.prefetch(MEDIA + g.icon)
                for (b in g.badges.take(4)) Img.prefetch("$MEDIA/Badge/$b.png")
            }
            runOnUiThread {
                renderProfile()
                renderTab()
                onLoaded()
            }
        }.start()
    }

    private fun haveCreds(): Boolean {
        if (apiUser().isEmpty() || apiKey().isEmpty()) return false
        return true
    }

    internal fun clearData() {
        home = null
        earnedAll = null
        completion = null
        renderProfile()
        renderTab()
    }

    /** [force] = false (used when the app comes back to the front) skips the network if we refreshed a moment ago. */
    internal fun refreshAll(force: Boolean = true) {
        if (!haveCreds()) return
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (!force && home != null && nowMs - lastRefresh < 20000L) return
        lastRefresh = nowMs
        val u = apiUser()
        val k = apiKey()
        Thread {
            try {
                val t = Ra.summary(u, k)
                val h = Ra.parseSummary(t, u)
                prefs.edit().putString("summary", t).apply()
                home = h
                runOnUiThread {
                    renderProfile()
                    if (tab == 0) renderTab()
                }
            } catch (e: Exception) {
                AppLog.add("Profile load failed: " + e.message)
            }
        }.start()
        if (tab == 1 || tab == 2) fetchEarned()
        if (tab == 3) fetchCompletion()
    }

    private fun fetchEarned() {
        if (!haveCreds()) return
        val u = apiUser()
        val k = apiKey()
        Thread {
            try {
                val t = Ra.earnedAllText(u, k)
                val list = Ra.parseEarned(t)
                if (t.length < 900000) prefs.edit().putString("earned", t).apply()
                earnedAll = list
                runOnUiThread { if (tab == 1 || tab == 2) renderTab() }
            } catch (e: Exception) {
                AppLog.add("Earned list failed: " + e.message)
            }
        }.start()
    }

    private fun fetchCompletion() {
        if (!haveCreds()) return
        val u = apiUser()
        val k = apiKey()
        Thread {
            try {
                val t = Ra.completionText(u, k)
                val list = Ra.parseCompletion(t)
                prefs.edit().putString("completion", t).apply()
                completion = list
                runOnUiThread { if (tab == 3) renderTab() }
            } catch (e: Exception) {
                AppLog.add("Completion list failed: " + e.message)
            }
        }.start()
    }

    // ---------- settings drawer ----------
    private fun buildDrawer(saved: Bundle?) {
        val wpx = resources.displayMetrics.widthPixels
        drawerWidth = minOf(dp(420), (wpx * 0.9f).toInt())

        drawerScrim = View(this)
        drawerScrim.setBackgroundColor(0x88000000.toInt())
        drawerScrim.alpha = 0f
        drawerScrim.visibility = View.GONE
        drawerScrim.setOnClickListener { closeDrawer() }
        root.addView(drawerScrim, FrameLayout.LayoutParams(MATCH, MATCH))

        drawer = ScrollView(this)
        drawer.setBackgroundColor(Pal.PANEL)
        drawer.isVerticalScrollBarEnabled = false
        drawer.isFocusable = false
        drawer.overScrollMode = View.OVER_SCROLL_NEVER
        // closed drawer = off screen: nothing in it may be reached with the D-pad until it is opened from the menu
        drawer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        drawer.elevation = dp(16).toFloat()
        root.addView(drawer, FrameLayout.LayoutParams(drawerWidth, MATCH))
        drawer.translationX = -drawerWidth.toFloat()
        GlassBackdrop.drawer = drawer

        drawerCol = LinearLayout(this)
        drawerCol.orientation = LinearLayout.VERTICAL
        pad(drawerCol, 16, 18, 16, 24)
        drawer.addView(drawerCol)

        val wasOpen = saved?.getBoolean("drawer", false) == true
        showPage(if (wasOpen) (saved?.getString("page") ?: "main") else "main", false)
        if (wasOpen) {
            drawerOpen = true
            drawer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            mainLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            drawerScrim.visibility = View.VISIBLE
            drawerScrim.alpha = 1f
            drawer.translationX = 0f
            drawerCol.post { firstFocusable(drawerCol)?.requestFocus() }
        }
    }

    private fun pageTitle(name: String): String = when (name) {
        "account" -> "Account"
        "library" -> "Library"
        "glass" -> "Glass"
        "anim" -> "Themes"
        "look" -> "Appearance"
        "popups" -> "Popups"
        "buttons" -> "Button mapping"
        "offline" -> "Offline Play"
        "emulators" -> "Emulators"
        else -> "Tools and log"
    }

    internal fun showPage(name: String, animate: Boolean = true) {
        val from = page
        if (from == "buttons" && name != "buttons") stopRecording()
        page = name
        logView = null
        logScroll = null
        drawerCol.removeAllViews()
        if (name == "main") {
            buildMenu(drawerCol)
        } else {
            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.addView(iconButton(R.drawable.ic_back, 42) { showPage("main") }, ll(dp(42), dp(42), r = 12))
            head.addView(tv(pageTitle(name), 24f, Pal.TEXT, true))
            drawerCol.addView(head)
            val body = LinearLayout(this)
            body.orientation = LinearLayout.VERTICAL
            drawerCol.addView(body, ll(MATCH, WRAP, t = 12))
            when (name) {
                "account" -> buildAccountPage(body)
                "library" -> buildLibraryPage(body)
                "glass" -> buildGlassPage(body)
                "anim" -> buildAnimPage(body)
                "look" -> buildLookPage(body)
                "popups" -> buildPopupsPage(body)
                "buttons" -> buildButtonsPage(body)
                "offline" -> buildOfflinePage(body)
                "emulators" -> buildEmulatorsPage(body)
                else -> buildToolsPage(body)
            }
        }
        drawer.scrollTo(0, 0)
        if (animate) {
            for (i in 0 until drawerCol.childCount) drawerCol.getChildAt(i).popIn(i * 35L)
        }
        // keep the controller highlight inside the panel: back on the tile we came from, or the first control
        if (drawerOpen) {
            drawerCol.post {
                var target: View? = null
                if (name == "main" && from != "main") {
                    val idx = menuKeys.indexOf(from)
                    if (idx >= 0) {
                        var n = 0
                        for (i in 0 until drawerCol.childCount) {
                            val c = drawerCol.getChildAt(i)
                            if (c.isFocusable) {
                                if (n == idx) target = c
                                n++
                            }
                        }
                    }
                }
                (target ?: firstFocusable(drawerCol))?.requestFocus()
            }
        }
    }

    /** Order of the tiles on the main settings page (see buildMenu). */
    private val menuKeys = listOf("account", "library", "offline", "emulators", "glass", "anim", "look", "popups", "buttons", "tools")

    private fun toggleDrawer() {
        if (drawerOpen) closeDrawer() else openDrawer()
    }

    internal fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        currentFocus?.let { if (isInside(it, mainLayer)) lastMainFocus = it }
        mainLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        detail?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        drawer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        // glass in a closed drawer is not redrawn, so refresh it once before it slides into view
        if (drawer.translationX <= -drawerWidth + 1f) drawer.translationX = -drawerWidth + 3f
        GlassBackdrop.tick()
        drawerScrim.visibility = View.VISIBLE
        drawerScrim.animate().alpha(1f).setDuration(240).start()
        drawer.animate().translationX(0f).setDuration(340).setInterpolator(OvershootInterpolator(0.6f)).start()
        // child 1 on the main page is a text line, so look for the first real control instead
        drawerCol.post { firstFocusable(drawerCol)?.requestFocus() }
    }

    internal fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        stopRecording()
        // drop focus out of the panel before it is blocked, then hand it back to the home screen / game page
        currentFocus?.let { if (isInside(it, drawer)) it.clearFocus() }
        drawer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        mainLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        detail?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        focusHome()
        drawerScrim.animate().alpha(0f).setDuration(200).withEndAction { drawerScrim.visibility = View.GONE }.start()
        drawer.animate().translationX(-drawerWidth.toFloat()).setDuration(260).start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("drawer", drawerOpen)
        outState.putString("page", page)
    }

    internal fun pickFile(code: Int, mimes: Array<String>) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, mimes)
        startActivityForResult(i, code)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            try {
                if (req == 101) {
                    val mime = contentResolver.getType(uri) ?: ""
                    val dest = File(filesDir, "bg_" + System.currentTimeMillis())
                    contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                    val old = prefs.getString("bg_path", "") ?: ""
                    prefs.edit().putString("bg_type", if (mime.startsWith("video")) "video" else "image")
                        .putString("bg_path", dest.absolutePath).apply()
                    runOnUiThread {
                        bg.reload()
                        if (old.isNotEmpty()) File(old).delete()
                    }
                } else if (req == 102) {
                    contentResolver.openInputStream(uri)?.use { i -> File(filesDir, "font.ttf").outputStream().use { o -> i.copyTo(o) } }
                    runOnUiThread { recreate() }
                }
            } catch (e: Exception) {
                AppLog.add("Could not import file: " + e.message)
            }
        }.start()
    }

    // ---------- keys ----------
    /** Cancels a combo recording that is in progress (leaving the page, closing the panel). */
    internal fun stopRecording() {
        keyRecorder = null
        Hotkeys.paused = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val rec = keyRecorder
        if (rec != null && rec(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val cg = congrats
        if (cg != null && (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ENTER)) {
            cg.close()
            return true
        }
        val dt = detail
        if (dt != null && (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK)) {
            dt.close()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (!drawerOpen) {
                    if (dt != null) dt.cycleFilter(-1) else selectTab(tab - 1)
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (!drawerOpen) {
                    if (dt != null) dt.cycleFilter(1) else selectTab(tab + 1)
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> {
                toggleDrawer()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                if (drawerOpen) {
                    if (page != "main") showPage("main") else closeDrawer()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- clock / status ----------
    private fun tickClock() {
        val now = Date()
        clockText.text = SimpleDateFormat("h:mm a  |  MM/dd", Locale.getDefault()).format(now)
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (b != null) {
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level >= 0 && scale > 0) {
                val pct = level * 100 / scale
                batteryView.level = pct
                batteryView.charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                batteryView.invalidate()
                batteryText.text = "$pct%"
            }
        }
        handler.postDelayed({ tickClock() }, 20000)
    }

    private fun showStatus() {
        statusText.text = AppLog.status
        val dot = GradientDrawable()
        dot.shape = GradientDrawable.OVAL
        dot.setColor(if (AppLog.active) Pal.GOOD else Pal.SUB)
        statusDot.background = dot
    }

    /** online / offline changes while the app is open */
    private val netListener: (Boolean) -> Unit = { on ->
        if (on) {
            showNotice("Internet Established Online mode!", syncLine(), false)
            OfflineProxy.flushAsync()
            refreshAll(true)
        } else {
            showNotice("No internet running offline mode!", "Achievements still unlock - they are sent when you are back online", true)
        }
    }
    private val queueListener: () -> Unit = { if (tab == 1 || tab == 2) renderTab() }

    private fun syncLine(): String {
        val n = OfflineProxy.pendingCount()
        return if (n > 0) "Sending $n offline unlock${if (n == 1) "" else "s"} to RetroAchievements..." else "Everything is synced"
    }

    private var foilMotionHeld = false

    override fun onResume() {
        super.onResume()
        foreground = true
        NetMonitor.addListener(netListener)
        OfflineProxy.queueListeners.add(queueListener)
        if (OfflineProxy.pendingCount() > 0 && NetMonitor.online) OfflineProxy.flushAsync()
        if (Foil.enabledFor("ui") && Foil.motion && !foilMotionHeld) {
            FoilMotion.acquire(this)
            foilMotionHeld = true
        }
        bg.resume()
        showStatus()
        AppLog.listener = { line ->
            runOnUiThread {
                val lv = logView
                if (lv != null) {
                    lv.append(line + "\n")
                    logScroll?.post { logScroll?.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
        AppLog.statusListener = { runOnUiThread { showStatus() } }
        refreshAll(false)
        if (tab == 4) scanTrophies(false)
        // the Recent cards show play time, which the watcher keeps updating while the app is in the background
        if (tab == 0 && home != null && computePlaySig() != playSig) renderTab()
        val unseen = Unseen.take(this)
        if (unseen.isNotEmpty()) showCongrats(unseen)
    }

    override fun onPause() {
        foreground = false
        NetMonitor.removeListener(netListener)
        OfflineProxy.queueListeners.remove(queueListener)
        if (foilMotionHeld) {
            FoilMotion.release()
            foilMotionHeld = false
        }
        AppLog.listener = null
        AppLog.statusListener = null
        bg.pause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (GlassBackdrop.drawer === drawer) GlassBackdrop.drawer = null
        super.onDestroy()
    }

    // ---------- actions ----------
    private fun startWatching() {
        val token = prefs.getString("token", "") ?: ""
        if (token.isEmpty()) {
            AppLog.add("Log in first: menu button -> Account.")
            showPage("account")
            openDrawer()
            return
        }
        val folder = prefs.getString("folder", "/storage/emulated/0/ROMs") ?: ""
        val pkg = prefs.getString("pkg", "com.dsemu.drastic") ?: "com.dsemu.drastic"
        AppLog.add("preparing permissions (root)...")
        Thread {
            val n = packageName
            rootSh("appops set $n MANAGE_EXTERNAL_STORAGE allow; appops set $n SYSTEM_ALERT_WINDOW allow; " +
                "pm grant $n android.permission.POST_NOTIFICATIONS")
            runOnUiThread {
                if (!Environment.isExternalStorageManager()) {
                    AppLog.add("Turn ON 'Allow access to manage all files' for Drastic RA, go back, press Start again.")
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$n")))
                    return@runOnUiThread
                }
                if (!Settings.canDrawOverlays(this)) {
                    AppLog.add("note: overlay permission missing, popups will be plain toasts.")
                }
                val i = Intent(this, EngineService::class.java)
                i.action = EngineService.ACTION_START
                i.putExtra("folder", folder).putExtra("pkg", pkg)
                startForegroundService(i)
            }
        }.start()
    }

    internal fun stopWatching() {
        val i = Intent(this, EngineService::class.java)
        i.action = EngineService.ACTION_STOP
        startService(i)
    }

    internal fun testPopup() {
        AppLog.add("popup test: popups start in 5 s - close the panel and switch to DraStic now")
        Thread {
            rootSh("appops set $packageName SYSTEM_ALERT_WINDOW allow")
            runOnUiThread {
                if (!Settings.canDrawOverlays(this)) {
                    AppLog.add("Turn ON 'Display over other apps' for Drastic RA, then test again.")
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    return@runOnUiThread
                }
                val pop = Popup(applicationContext)
                // expanded test popups get the overlay button too; with no game running it opens the sample overlay
                pop.onOpenOverlay = {
                    if (LiveGame.id != 0) GameOverlay.show(applicationContext) else GameOverlay.showDemo(applicationContext)
                }
                val h = Handler(mainLooper)
                h.postDelayed({ pop.game("Pokemon SoulSilver", "", 12, 59, "35 / 410 pts") }, 5000)
                h.postDelayed({ pop.unlock("Test Achievement", "This is what an unlock looks like.", "10", null, "Pokemon SoulSilver", 1250) }, 5200)
                h.postDelayed({ pop.progress("Test Achievement", "3 / 10", null) }, 5300)
            }
        }.start()
    }

    /** Popups page: shows the in-game overlay (live game if one is being watched, otherwise sample data). */
    internal fun previewOverlay() {
        Thread {
            rootSh("appops set $packageName SYSTEM_ALERT_WINDOW allow")
            runOnUiThread {
                if (!Settings.canDrawOverlays(this)) {
                    AppLog.add("Turn ON 'Display over other apps' for Drastic RA, then try again.")
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    return@runOnUiThread
                }
                closeDrawer()
                if (LiveGame.id != 0) GameOverlay.show(applicationContext) else GameOverlay.showDemo(applicationContext)
            }
        }.start()
    }

    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    internal fun runScan(live: Boolean) {
        val pkg = prefs.getString("pkg", "com.dsemu.drastic") ?: "com.dsemu.drastic"
        val apk = applicationInfo.sourceDir
        Thread {
            try {
                val cmd = "CLASSPATH=${shq(apk)} app_process /system/bin com.drasticra.RootScan ${shq(pkg)}${if (live) " live" else ""}"
                AppLog.add("running scan as root...")
                val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        AppLog.add(line)
                        line = r.readLine()
                    }
                }
                AppLog.add("exit code: " + p.waitFor())
            } catch (e: Exception) {
                AppLog.add("FAILED to run su: ${e.message}")
            }
        }.start()
    }

    internal fun shareLog() {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, AppLog.all())
        startActivity(Intent.createChooser(i, "Share log"))
    }
}
