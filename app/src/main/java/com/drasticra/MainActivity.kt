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
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var root: FrameLayout
    private lateinit var bg: BackgroundHost
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

    private lateinit var drawer: ScrollView
    private lateinit var drawerScrim: View
    private var drawerOpen = false
    private var drawerWidth = 0

    private lateinit var accountBox: LinearLayout
    private lateinit var loginMsg: TextView
    private lateinit var userIn: EditText
    private lateinit var passIn: EditText
    private lateinit var keyIn: EditText
    private lateinit var folderIn: EditText
    private lateinit var pkgIn: EditText
    private lateinit var hex1: EditText
    private lateinit var hex2: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val prefs by lazy { getSharedPreferences("dsra", MODE_PRIVATE) }
    private val handler = Handler()
    private var tab = 0
    private var grid = true

    private var home: RaHome? = null
    private var earnedAll: List<RaAch>? = null
    private var completion: List<RaGame>? = null

    // ---------- tiny helpers ----------
    private fun ll(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, weight)
        p.setMargins(dp(l), dp(t), dp(r), dp(b))
        return p
    }

    private fun tv(text: String, size: Float, color: Int, title: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        t.typeface = if (title) Fonts.title else Fonts.body
        return t
    }

    private fun card(radius: Int = 26): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = glassCard(radius)
        c.elevation = dp(5).toFloat()
        return c
    }

    private fun pad(v: View, l: Int, t: Int, r: Int, b: Int) = v.setPadding(dp(l), dp(t), dp(r), dp(b))

    private fun iconButton(res: Int, sizeDp: Int, onClick: () -> Unit): ImageView {
        val b = ImageView(this)
        b.setImageResource(res)
        b.setColorFilter(Pal.TEXT)
        b.background = glassCard(sizeDp / 2)
        b.elevation = dp(5).toFloat()
        pad(b, sizeDp / 4, sizeDp / 4, sizeDp / 4, sizeDp / 4)
        b.isClickable = true
        b.isFocusable = true
        b.setOnClickListener { onClick() }
        return b
    }

    private fun pill(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        val t = tv(text, 15f, if (primary) Color.WHITE else Pal.TEXT, true)
        t.gravity = Gravity.CENTER
        pad(t, 14, 13, 14, 13)
        t.background = withRipple(if (primary) accentFill(22) else glassCard(22))
        t.elevation = dp(4).toFloat()
        t.isClickable = true
        t.isFocusable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val t = tv(text, 13f, if (selected) Color.WHITE else Pal.TEXT, true)
        t.gravity = Gravity.CENTER
        pad(t, 12, 11, 12, 11)
        t.background = withRipple(if (selected) accentFill(16) else rounded(Pal.FIELD, 16))
        t.isClickable = true
        t.isFocusable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun field(hint: String, value: String, password: Boolean = false): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.setSingleLine(true)
        e.textSize = 14f
        e.typeface = Fonts.body
        e.setTextColor(Pal.TEXT)
        e.setHintTextColor(Pal.SUB)
        e.background = rounded(Pal.FIELD, 16)
        pad(e, 15, 12, 15, 12)
        e.inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT
        return e
    }

    private fun square(sizeDp: Int, radiusDp: Int, url: String?): ImageView {
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.background = rounded(Pal.FIELD, radiusDp)
        img.clipRound(radiusDp)
        if (!url.isNullOrEmpty()) Img.load(url, img)
        return img
    }

    private fun tag(text: String): TextView {
        val t = tv(text, 11f, Pal.SUB, true)
        pad(t, 10, 3, 10, 3)
        t.background = rounded(Pal.FIELD, 12)
        return t
    }

    private fun sectionTitle(text: String): TextView {
        val t = tv(text, 12f, Pal.SUB, true)
        t.letterSpacing = 0.14f
        return t
    }

    private fun shortConsole(c: String): String {
        val s = c.replace("Nintendo ", "").replace("PlayStation Portable", "PSP").replace("PlayStation", "PS")
        return if (s.isEmpty()) "DS" else s
    }

    private fun isDs(console: String): Boolean = console.contains("DS")

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
        bg.reload()
        root.addView(bg, FrameLayout.LayoutParams(MATCH, MATCH))

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        pad(main, 16, 12, 16, 12)
        main.clipChildren = false
        main.clipToPadding = false
        main.addView(buildTopBar(), ll(MATCH, WRAP))
        main.addView(buildTabsRow(), ll(MATCH, WRAP, t = 12))

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
        main.addView(contentScroll, ll(MATCH, 0, 1f))
        main.addView(buildBottomBar(), ll(MATCH, WRAP, t = 8))
        root.addView(main, FrameLayout.LayoutParams(MATCH, MATCH))

        buildDrawer()
        setContentView(root)
        hideSystemUi()

        renderProfile()
        renderTab()
        loadCaches()
        tickClock()
    }

    private fun buildTopBar(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.clipChildren = false
        row.addView(iconButton(R.drawable.ic_menu, 46) { toggleDrawer() }, ll(dp(46), dp(46)))

        val title = tv("RetroAchievements", 21f, Pal.TEXT, true)
        title.gravity = Gravity.CENTER
        pad(title, 10, 9, 10, 9)
        title.background = glassCard(26)
        title.elevation = dp(5).toFloat()
        title.maxLines = 1
        row.addView(title, ll(0, WRAP, 1f, l = 10, r = 10))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.HORIZONTAL
        right.gravity = Gravity.CENTER_VERTICAL
        pad(right, 14, 9, 14, 9)
        right.background = glassCard(26)
        right.elevation = dp(5).toFloat()
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

        profilePill = LinearLayout(this)
        profilePill.orientation = LinearLayout.HORIZONTAL
        profilePill.gravity = Gravity.CENTER_VERTICAL
        pad(profilePill, 8, 6, 14, 6)
        profilePill.background = glassCard(22)
        profilePill.elevation = dp(5).toFloat()
        row.addView(profilePill, ll(WRAP, WRAP, r = 10))

        val names = arrayOf("Recent", "Earned", "Completion")
        tabViews.clear()
        for (i in names.indices) {
            val t = tv(names[i], 15f, Pal.SUB, true)
            t.gravity = Gravity.CENTER
            pad(t, 16, 9, 16, 9)
            t.isClickable = true
            t.isFocusable = true
            t.setOnClickListener { selectTab(i) }
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
        gridBtn.isFocusable = true
        listBtn.isFocusable = true
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

        val st = LinearLayout(this)
        st.orientation = LinearLayout.HORIZONTAL
        st.gravity = Gravity.CENTER_VERTICAL
        pad(st, 16, 12, 18, 12)
        st.background = glassCard(22)
        st.elevation = dp(5).toFloat()
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
    private fun renderProfile() {
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

    private fun renderTab() {
        content.removeAllViews()
        val dsOnly = prefs.getBoolean("ds_only", false)
        val cards = ArrayList<View>()
        var emptyMsg = "Nothing here yet."
        when (tab) {
            0 -> {
                val h = home
                if (h == null) emptyMsg = "Add your username and Web API key in Settings (menu button) to see your games."
                else for (g in h.recent) if (!dsOnly || isDs(g.console)) cards.add(gameCard(g))
            }
            1 -> {
                val e = earnedAll
                if (e == null) emptyMsg = "Loading your achievements..."
                else for (a in e) if (!dsOnly || isDs(a.console)) cards.add(achCard(a))
            }
            else -> {
                val c = completion
                if (c == null) emptyMsg = "Loading your completion progress..."
                else for (g in c) if (!dsOnly || isDs(g.console)) cards.add(gameCard(g))
            }
        }
        if (cards.isEmpty()) {
            val m = card(24)
            pad(m, 18, 16, 18, 16)
            m.addView(tv(emptyMsg, 14f, Pal.SUB))
            content.addView(m, ll(MATCH, WRAP))
            return
        }
        val cols = if (grid && resources.configuration.screenWidthDp >= 560) 2 else 1
        var i = 0
        while (i < cards.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.clipChildren = false
            for (c in 0 until cols) {
                if (i + c < cards.size) {
                    row.addView(cards[i + c], ll(0, WRAP, 1f, l = if (c == 0) 0 else 12))
                } else {
                    row.addView(View(this), ll(0, 1, 1f, l = 12))
                }
            }
            content.addView(row, ll(MATCH, WRAP, t = if (i == 0) 0 else 12))
            i += cols
        }
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
        val tg = tag(shortConsole(g.console))
        head.addView(tg, ll(WRAP, WRAP, l = 8))
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
        return c
    }

    // ---------- data ----------
    private fun apiUser(): String {
        val u = prefs.getString("user_ra", "") ?: ""
        return if (u.isNotEmpty()) u else (prefs.getString("user", "") ?: "")
    }

    private fun apiKey(): String = prefs.getString("apikey", "") ?: ""

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
        if (apiUser().isEmpty() || apiKey().isEmpty()) {
            AppLog.add("Add your username (log in) and Web API key in Settings to load your profile.")
            return false
        }
        return true
    }

    private fun refreshAll() {
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
    private fun buildDrawer() {
        val wpx = resources.displayMetrics.widthPixels
        drawerWidth = minOf(dp(400), (wpx * 0.88f).toInt())

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
        val lp = FrameLayout.LayoutParams(drawerWidth, MATCH)
        root.addView(drawer, lp)
        drawer.translationX = -drawerWidth.toFloat()

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        pad(col, 16, 18, 16, 24)
        drawer.addView(col)

        col.addView(tv("Settings", 26f, Pal.TEXT, true))
        col.addView(tv("L1 / R1 switch tabs  -  START opens this panel  -  B closes it", 11f, Pal.SUB), ll(WRAP, WRAP, t = 2, b = 6))

        // account
        val ac = card(24)
        pad(ac, 16, 14, 16, 16)
        ac.addView(sectionTitle("ACCOUNT"))
        accountBox = LinearLayout(this)
        accountBox.orientation = LinearLayout.VERTICAL
        ac.addView(accountBox, ll(MATCH, WRAP, t = 10))
        col.addView(ac, ll(MATCH, WRAP, t = 10))

        // home data key
        val kc = card(24)
        pad(kc, 16, 14, 16, 16)
        kc.addView(sectionTitle("HOME SCREEN DATA"))
        keyIn = field("Web API key", prefs.getString("apikey", "") ?: "")
        kc.addView(keyIn, ll(MATCH, WRAP, t = 10))
        kc.addView(tv("retroachievements.org -> Settings -> Keys", 11f, Pal.SUB), ll(WRAP, WRAP, t = 6))
        kc.addView(chip("Save and refresh", false) {
            prefs.edit().putString("apikey", keyIn.text.toString().trim()).apply()
            refreshAll()
        }, ll(MATCH, WRAP, t = 10))
        col.addView(kc, ll(MATCH, WRAP, t = 12))

        // library
        val lc = card(24)
        pad(lc, 16, 14, 16, 16)
        lc.addView(sectionTitle("LIBRARY"))
        folderIn = field("ROM folder", prefs.getString("folder", "/storage/emulated/0/ROMs") ?: "")
        pkgIn = field("DraStic package", prefs.getString("pkg", "com.dsemu.drastic") ?: "")
        lc.addView(folderIn, ll(MATCH, WRAP, t = 10))
        lc.addView(pkgIn, ll(MATCH, WRAP, t = 8))
        val dsRow = LinearLayout(this)
        dsRow.orientation = LinearLayout.HORIZONTAL
        dsRow.addView(chip("All games", !prefs.getBoolean("ds_only", false)) { setDsOnly(false) }, ll(0, WRAP, 1f))
        dsRow.addView(chip("DS only", prefs.getBoolean("ds_only", false)) { setDsOnly(true) }, ll(0, WRAP, 1f, l = 8))
        lc.addView(dsRow, ll(MATCH, WRAP, t = 10))
        col.addView(lc, ll(MATCH, WRAP, t = 12))

        // appearance
        val ap = card(24)
        pad(ap, 16, 14, 16, 16)
        ap.addView(sectionTitle("APPEARANCE"))
        val themeRow = LinearLayout(this)
        themeRow.orientation = LinearLayout.HORIZONTAL
        themeRow.addView(chip("Light", Pal.light) { setTheme("light") }, ll(0, WRAP, 1f))
        themeRow.addView(chip("Dark", !Pal.light) { setTheme("dark") }, ll(0, WRAP, 1f, l = 8))
        ap.addView(themeRow, ll(MATCH, WRAP, t = 10))

        ap.addView(tv("Accent gradient", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 14))
        ap.addView(buildSwatches(), ll(MATCH, WRAP, t = 8))
        val hexRow = LinearLayout(this)
        hexRow.orientation = LinearLayout.HORIZONTAL
        hex1 = field("#7C7CFF", hexOf(Pal.ACC1))
        hex2 = field("#B57CFF", hexOf(Pal.ACC2))
        hexRow.addView(hex1, ll(0, WRAP, 1f))
        hexRow.addView(hex2, ll(0, WRAP, 1f, l = 8))
        ap.addView(hexRow, ll(MATCH, WRAP, t = 8))
        ap.addView(chip("Apply custom colours", false) { applyHex() }, ll(MATCH, WRAP, t = 8))

        ap.addView(tv("Background", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 14))
        val bgRow = LinearLayout(this)
        bgRow.orientation = LinearLayout.HORIZONTAL
        bgRow.addView(chip("Aurora", (prefs.getString("bg_type", "aurora") ?: "aurora") == "aurora") { useAurora() }, ll(0, WRAP, 1f))
        bgRow.addView(chip("Pick image / GIF / video", false) { pickFile(101, arrayOf("image/*", "video/*")) }, ll(0, WRAP, 2f, l = 8))
        ap.addView(bgRow, ll(MATCH, WRAP, t = 8))
        val anim = prefs.getBoolean("bg_anim", true)
        ap.addView(chip(if (anim) "Aurora animation: on" else "Aurora animation: off", false) {
            prefs.edit().putBoolean("bg_anim", !prefs.getBoolean("bg_anim", true)).apply()
            recreate()
        }, ll(MATCH, WRAP, t = 8))
        val dimRow = LinearLayout(this)
        dimRow.orientation = LinearLayout.HORIZONTAL
        val dim = prefs.getInt("bg_dim", 35)
        dimRow.addView(chip("Dim low", dim == 15) { setDim(15) }, ll(0, WRAP, 1f))
        dimRow.addView(chip("Dim mid", dim == 35) { setDim(35) }, ll(0, WRAP, 1f, l = 8))
        dimRow.addView(chip("Dim high", dim == 60) { setDim(60) }, ll(0, WRAP, 1f, l = 8))
        ap.addView(dimRow, ll(MATCH, WRAP, t = 8))

        ap.addView(tv("Font", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 14))
        val fontRow = LinearLayout(this)
        fontRow.orientation = LinearLayout.HORIZONTAL
        fontRow.addView(chip("Pick font file (.ttf)", false) { pickFile(102, arrayOf("*/*")) }, ll(0, WRAP, 2f))
        fontRow.addView(chip("Default", false) {
            File(filesDir, "font.ttf").delete()
            recreate()
        }, ll(0, WRAP, 1f, l = 8))
        ap.addView(fontRow, ll(MATCH, WRAP, t = 8))

        ap.addView(tv("Achievement popup position", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 14))
        val posRow = LinearLayout(this)
        posRow.orientation = LinearLayout.HORIZONTAL
        val pos = prefs.getString("popup_pos", "top") ?: "top"
        posRow.addView(chip("Top", pos == "top") { setPos("top") }, ll(0, WRAP, 1f))
        posRow.addView(chip("Bottom", pos == "bottom") { setPos("bottom") }, ll(0, WRAP, 1f, l = 8))
        ap.addView(posRow, ll(MATCH, WRAP, t = 8))
        col.addView(ap, ll(MATCH, WRAP, t = 12))

        // tools
        val tc = card(24)
        pad(tc, 16, 14, 16, 16)
        tc.addView(sectionTitle("TOOLS"))
        val t1 = LinearLayout(this)
        t1.orientation = LinearLayout.HORIZONTAL
        t1.addView(chip("Test popups", false) { testPopup() }, ll(0, WRAP, 1f))
        t1.addView(chip("Share log", false) { shareLog() }, ll(0, WRAP, 1f, l = 8))
        tc.addView(t1, ll(MATCH, WRAP, t = 10))
        val t2 = LinearLayout(this)
        t2.orientation = LinearLayout.HORIZONTAL
        t2.addView(chip("RAM scan", false) { runScan(false) }, ll(0, WRAP, 1f))
        t2.addView(chip("Live RAM test", false) { runScan(true) }, ll(0, WRAP, 1f, l = 8))
        tc.addView(t2, ll(MATCH, WRAP, t = 8))
        tc.addView(tv("Test popups appear 5 s after tapping - close this panel and switch to your game.", 11f, Pal.SUB), ll(WRAP, WRAP, t = 8))
        col.addView(tc, ll(MATCH, WRAP, t = 12))

        // log
        val lg = card(24)
        pad(lg, 16, 14, 16, 16)
        lg.addView(sectionTitle("LOG"))
        logScroll = ScrollView(this)
        logView = tv("", 11f, Pal.SUB)
        logView.typeface = Typeface.MONOSPACE
        logView.setTextIsSelectable(true)
        logScroll.addView(logView)
        logScroll.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        lg.addView(logScroll, ll(MATCH, dp(200), t = 10))
        col.addView(lg, ll(MATCH, WRAP, t = 12))

        rebuildAccount()
    }

    private fun hexOf(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

    private fun buildSwatches(): View {
        val presets = arrayOf(
            intArrayOf(0xFF7C7CFF.toInt(), 0xFFB57CFF.toInt()),
            intArrayOf(0xFF4FD1FF.toInt(), 0xFF5B7CFF.toInt()),
            intArrayOf(0xFFFF9A5B.toInt(), 0xFFFF5B9A.toInt()),
            intArrayOf(0xFF7BE05B.toInt(), 0xFF19C5A0.toInt()),
            intArrayOf(0xFFFF6FB5.toInt(), 0xFFFFB27C.toInt()),
            intArrayOf(0xFFFFD36B.toInt(), 0xFFF2994A.toInt()),
            intArrayOf(0xFF9BE15D.toInt(), 0xFF00A86B.toInt()),
            intArrayOf(0xFF9AA0B5.toInt(), 0xFF5E647A.toInt())
        )
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (p in presets) {
            val sel = p[0] == Pal.ACC1 && p[1] == Pal.ACC2
            val s = TextView(this)
            s.gravity = Gravity.CENTER
            s.text = if (sel) "\u2713" else ""
            s.setTextColor(Color.WHITE)
            s.textSize = 16f
            val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, p)
            g.shape = GradientDrawable.OVAL
            s.background = g
            s.isClickable = true
            s.isFocusable = true
            s.setOnClickListener { setAccent(p[0], p[1]) }
            row.addView(s, ll(dp(40), dp(40), r = 10))
        }
        hs.addView(row)
        return hs
    }

    private fun rebuildAccount() {
        accountBox.removeAllViews()
        val token = prefs.getString("token", "") ?: ""
        val u = prefs.getString("user_ra", "") ?: ""
        if (token.isNotEmpty() && u.isNotEmpty()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val av = ImageView(this)
            av.scaleType = ImageView.ScaleType.CENTER_CROP
            av.background = rounded(Pal.FIELD, 22)
            av.clipCircle()
            Img.load("$MEDIA/UserPic/$u.png", av)
            row.addView(av, ll(dp(44), dp(44), r = 12))
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.addView(tv(u, 17f, Pal.TEXT, true))
            c.addView(tv("Logged in", 12f, Pal.GOOD, true))
            row.addView(c, ll(0, WRAP, 1f))
            accountBox.addView(row)
            accountBox.addView(chip("Log out", false) { logout() }, ll(MATCH, WRAP, t = 12))
        } else {
            userIn = field("Username", prefs.getString("user", "") ?: "")
            passIn = field("Password", "", true)
            loginMsg = tv("", 12f, Pal.SUB)
            accountBox.addView(userIn, ll(MATCH, WRAP))
            accountBox.addView(passIn, ll(MATCH, WRAP, t = 8))
            accountBox.addView(loginMsg, ll(WRAP, WRAP, t = 6))
            accountBox.addView(pill("Log in", true) { doLogin() }, ll(MATCH, WRAP, t = 10))
        }
    }

    private fun doLogin() {
        val u = userIn.text.toString().trim()
        val p = passIn.text.toString()
        if (u.isEmpty() || p.isEmpty()) {
            loginMsg.text = "Enter your username and password."
            return
        }
        loginMsg.text = "Logging in..."
        Thread {
            val r = Ra.login(u, p)
            runOnUiThread {
                if (r.ok && r.token.isNotEmpty()) {
                    prefs.edit().putString("token", r.token).putString("user_ra", r.user).putString("user", r.user).apply()
                    AppLog.add("Logged in as " + r.user)
                    rebuildAccount()
                    refreshAll()
                    renderProfile()
                } else {
                    loginMsg.text = if (r.error.isEmpty()) "Login failed." else r.error
                }
            }
        }.start()
    }

    private fun logout() {
        stopWatching()
        prefs.edit().remove("token").remove("user_ra").remove("summary").remove("earned").remove("completion").apply()
        home = null
        earnedAll = null
        completion = null
        rebuildAccount()
        renderProfile()
        renderTab()
        AppLog.add("Logged out.")
    }

    private fun setDsOnly(v: Boolean) {
        prefs.edit().putBoolean("ds_only", v).apply()
        recreate()
    }

    private fun setTheme(t: String) {
        prefs.edit().putString("theme", t).apply()
        recreate()
    }

    private fun setAccent(a: Int, b: Int) {
        prefs.edit().putInt("acc1", a).putInt("acc2", b).apply()
        recreate()
    }

    private fun applyHex() {
        try {
            val a = Color.parseColor(hex1.text.toString().trim())
            val b = Color.parseColor(hex2.text.toString().trim())
            setAccent(a or 0xFF000000.toInt(), b or 0xFF000000.toInt())
        } catch (_: Exception) {
            AppLog.add("Colours must look like #RRGGBB")
        }
    }

    private fun setDim(v: Int) {
        prefs.edit().putInt("bg_dim", v).apply()
        recreate()
    }

    private fun setPos(v: String) {
        prefs.edit().putString("popup_pos", v).apply()
        recreate()
    }

    private fun useAurora() {
        prefs.edit().putString("bg_type", "aurora").apply()
        bg.reload()
        recreate()
    }

    private fun pickFile(code: Int, mimes: Array<String>) {
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

    // ---------- drawer open/close ----------
    private fun toggleDrawer() {
        if (drawerOpen) closeDrawer() else openDrawer()
    }

    private fun openDrawer() {
        drawerOpen = true
        drawerScrim.visibility = View.VISIBLE
        drawerScrim.animate().alpha(1f).setDuration(220).start()
        drawer.animate().translationX(0f).setDuration(260).start()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        saveSettings()
        drawerScrim.animate().alpha(0f).setDuration(200).withEndAction { drawerScrim.visibility = View.GONE }.start()
        drawer.animate().translationX(-drawerWidth.toFloat()).setDuration(240).start()
    }

    private fun saveSettings() {
        prefs.edit().putString("folder", folderIn.text.toString().trim())
            .putString("pkg", pkgIn.text.toString().trim())
            .putString("apikey", keyIn.text.toString().trim()).apply()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> { selectTab(tab - 1); return true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { selectTab(tab + 1); return true }
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> { toggleDrawer(); return true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                if (drawerOpen) {
                    closeDrawer()
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
        logView.text = AppLog.all()
        showStatus()
        AppLog.listener = { line ->
            runOnUiThread {
                logView.append(line + "\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        AppLog.statusListener = { runOnUiThread { showStatus() } }
        refreshAll()
    }

    override fun onPause() {
        AppLog.listener = null
        AppLog.statusListener = null
        bg.pause()
        saveSettings()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------- actions ----------
    private fun startWatching() {
        saveSettings()
        val token = prefs.getString("token", "") ?: ""
        if (token.isEmpty()) {
            AppLog.add("Log in first: menu button -> Settings -> Account.")
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

    private fun stopWatching() {
        val i = Intent(this, EngineService::class.java)
        i.action = EngineService.ACTION_STOP
        startService(i)
    }

    private fun testPopup() {
        AppLog.add("popup test: three popups start in 5 s - close the panel and switch to DraStic now")
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
                h.postDelayed({ pop.unlock("Test Achievement", "This is what an unlock looks like", "10", null) }, 5500)
                h.postDelayed({ pop.progress("Test Achievement", "3 / 10", null) }, 5600)
            }
        }.start()
    }

    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun runScan(live: Boolean) {
        val pkg = pkgIn.text.toString().trim()
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

    private fun shareLog() {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, AppLog.all())
        startActivity(Intent.createChooser(i, "Share log"))
    }
}
