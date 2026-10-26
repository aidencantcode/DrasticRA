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
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
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
    internal lateinit var root: FrameLayout
    internal lateinit var bg: BackgroundHost
    private lateinit var mainLayer: LinearLayout
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

    private var home: RaHome? = null
    private var earnedAll: List<RaAch>? = null
    private var completion: List<RaGame>? = null
    private var detail: GameDetailView? = null
    private var congrats: CongratsView? = null

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
        Pal.load(this)
        Fonts.load(this)
        if (Build.VERSION.SDK_INT >= 28) {
            val wlp = window.attributes
            wlp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = wlp
        }
        grid = prefs.getBoolean("grid", true)
        tab = prefs.getInt("tab", 0)

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
        mainLayer.addView(topBar, ll(MATCH, WRAP))
        mainLayer.addView(tabsRow, ll(MATCH, WRAP, t = 12))

        contentScroll = ScrollView(this)
        contentScroll.clipToPadding = false
        contentScroll.clipChildren = false
        contentScroll.isVerticalScrollBarEnabled = false
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.clipChildren = false
        content.clipToPadding = false
        pad(content, 4, 10, 4, 14)
        contentScroll.addView(content)
        contentScroll.setOnScrollChangeListener { _, _, _, _, _ -> GlassBackdrop.invalidateAll() }
        mainLayer.addView(contentScroll, ll(MATCH, 0, 1f))
        mainLayer.addView(bottomBar, ll(MATCH, WRAP, t = 8))
        root.addView(mainLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        buildDrawer(savedInstanceState)

        halo = FocusHalo(this)
        root.addView(halo, FrameLayout.LayoutParams(MATCH, MATCH))
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, n -> halo.follow(n) }

        setContentView(root)
        hideSystemUi()

        renderProfile()
        renderTab()
        loadCaches()
        tickClock()
        topBar.popIn(0)
        tabsRow.popIn(70)
        bottomBar.popIn(140)
        val first = tabViews.getOrNull(tab)
        first?.post { first.requestFocus() }
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

        val names = arrayOf("Recent", "Earned", "Completion")
        tabViews.clear()
        for (i in names.indices) {
            val t = tv(names[i], 15f, Pal.SUB, true)
            t.gravity = Gravity.CENTER
            pad(t, 16, 9, 16, 9)
            t.isClickable = true
            makeFocusable(t)
            t.setOnClickListener { selectTab(i) }
            t.pressable()
            tabViews.add(t)
            row.addView(t, ll(WRAP, WRAP, r = 6))
        }
        row.addView(View(this), ll(0, 1, 1f))

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
        prefs.edit().putInt("tab", tab).apply()
        styleTabs()
        renderTab()
        if (tab == 1 && earnedAll == null) fetchEarned()
        if (tab == 2 && completion == null) fetchCompletion()
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

    internal fun renderTab() {
        content.removeAllViews()
        val dsOnly = prefs.getBoolean("ds_only", false)
        val cards = ArrayList<View>()
        var emptyMsg = "Nothing here yet."
        when (tab) {
            0 -> {
                val h = home
                if (h == null) {
                    emptyMsg = "Log in and add your Web API key in Settings (menu button) to see your games."
                } else {
                    for (g in h.recent) if (!dsOnly || g.console.contains("DS")) cards.add(gameCard(g))
                }
            }
            1 -> {
                val e = earnedAll
                if (e == null) {
                    emptyMsg = "Loading your achievements..."
                } else {
                    for (a in e) if (!dsOnly || a.console.contains("DS")) cards.add(achCard(a))
                }
            }
            else -> {
                val c = completion
                if (c == null) {
                    emptyMsg = "Loading your completion progress..."
                } else {
                    for (g in c) if (!dsOnly || g.console.contains("DS")) cards.add(gameCard(g))
                }
            }
        }
        if (cards.isEmpty()) {
            val m = card(24)
            pad(m, 18, 16, 18, 16)
            m.addView(tv(emptyMsg, 14f, Pal.SUB))
            content.addView(m, ll(MATCH, WRAP))
            m.popIn(0)
            return
        }
        val cols = if (grid && resources.configuration.screenWidthDp >= 560) 2 else 1
        var i = 0
        var n = 0
        while (i < cards.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.clipChildren = false
            for (c in 0 until cols) {
                if (i + c < cards.size) {
                    row.addView(cards[i + c], ll(0, WRAP, 1f, l = if (c == 0) 0 else 12))
                    cards[i + c].popIn(minOf(n, 10) * 45L)
                    n++
                } else {
                    row.addView(View(this), ll(0, 1, 1f, l = 12))
                }
            }
            content.addView(row, ll(MATCH, WRAP, t = if (i == 0) 0 else 12))
            i += cols
        }
        GlassBackdrop.invalidateAll()
    }

    private fun gameCard(g: RaGame): View {
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
        head.addView(title, ll(0, WRAP, 1f))
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

    private fun achCard(a: RaAch): View {
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
        val d = GameDetailView(this, g) { closeDetailNow() }
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
    }

    internal fun showCongrats(items: List<UnseenItem>) {
        if (items.isEmpty() || congrats != null) return
        val v = CongratsView(this, items) {
            congrats?.let { root.removeView(it) }
            congrats = null
        }
        congrats = v
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        v.start()
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

    private fun loadCaches() {
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
            runOnUiThread {
                renderProfile()
                renderTab()
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

    internal fun refreshAll() {
        if (!haveCreds()) return
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
        if (tab == 1) fetchEarned()
        if (tab == 2) fetchCompletion()
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
                runOnUiThread { if (tab == 1) renderTab() }
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
                runOnUiThread { if (tab == 2) renderTab() }
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
        drawer.elevation = dp(16).toFloat()
        root.addView(drawer, FrameLayout.LayoutParams(drawerWidth, MATCH))
        drawer.translationX = -drawerWidth.toFloat()

        drawerCol = LinearLayout(this)
        drawerCol.orientation = LinearLayout.VERTICAL
        pad(drawerCol, 16, 18, 16, 24)
        drawer.addView(drawerCol)

        val wasOpen = saved?.getBoolean("drawer", false) == true
        showPage(if (wasOpen) (saved?.getString("page") ?: "main") else "main", false)
        if (wasOpen) {
            drawerOpen = true
            drawerScrim.visibility = View.VISIBLE
            drawerScrim.alpha = 1f
            drawer.translationX = 0f
        }
    }

    private fun pageTitle(name: String): String = when (name) {
        "account" -> "Account"
        "library" -> "Library"
        "glass" -> "Glass"
        "anim" -> "Animations"
        "look" -> "Appearance"
        "popups" -> "Popups"
        else -> "Tools and log"
    }

    internal fun showPage(name: String, animate: Boolean = true) {
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
                else -> buildToolsPage(body)
            }
        }
        drawer.scrollTo(0, 0)
        if (animate) {
            for (i in 0 until drawerCol.childCount) drawerCol.getChildAt(i).popIn(i * 35L)
        }
    }

    private fun toggleDrawer() {
        if (drawerOpen) closeDrawer() else openDrawer()
    }

    internal fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        mainLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        drawerScrim.visibility = View.VISIBLE
        drawerScrim.animate().alpha(1f).setDuration(240).start()
        drawer.animate().translationX(0f).setDuration(340).setInterpolator(OvershootInterpolator(0.6f)).start()
        drawerCol.post { drawerCol.getChildAt(1)?.requestFocus() }
    }

    internal fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        mainLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
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
                if (dt == null && !drawerOpen) selectTab(tab - 1)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (dt == null && !drawerOpen) selectTab(tab + 1)
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

    override fun onResume() {
        super.onResume()
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
        refreshAll()
        val unseen = Unseen.take(this)
        if (unseen.isNotEmpty()) showCongrats(unseen)
    }

    override fun onPause() {
        AppLog.listener = null
        AppLog.statusListener = null
        bg.pause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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
                val h = Handler(mainLooper)
                h.postDelayed({ pop.game("Pokemon SoulSilver", "", 12, 59, "35 / 410 pts") }, 5000)
                h.postDelayed({ pop.unlock("Test Achievement", "This is what an unlock looks like.", "10", null, "Pokemon SoulSilver", 1250) }, 5200)
                h.postDelayed({ pop.progress("Test Achievement", "3 / 10", null) }, 5300)
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
