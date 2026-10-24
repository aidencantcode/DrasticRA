package com.drasticra

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var userIn: EditText
    private lateinit var passIn: EditText
    private lateinit var keyIn: EditText
    private lateinit var folderIn: EditText
    private lateinit var pkgIn: EditText
    private lateinit var profileBox: LinearLayout
    private lateinit var gamesBox: LinearLayout
    private lateinit var earnedBox: LinearLayout
    private lateinit var settingsBox: LinearLayout

    private val prefs by lazy { getSharedPreferences("dsra", MODE_PRIVATE) }
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // ---------- small view helpers ----------
    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = medium
        return t
    }

    private fun lp(topDp: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.topMargin = dp(topDp)
        return p
    }

    private fun weighted(w: Float, leftDp: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
        p.leftMargin = dp(leftDp)
        return p
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(16), dp(18), dp(18))
        c.background = rounded(Pal.CARD, 24)
        return c
    }

    private fun section(text: String): TextView {
        val t = label(text, 11f, Pal.SUB, true)
        t.letterSpacing = 0.14f
        t.setPadding(dp(4), 0, 0, 0)
        return t
    }

    private fun field(hint: String, value: String, password: Boolean = false): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.setSingleLine(true)
        e.textSize = 15f
        e.setTextColor(Pal.TEXT)
        e.setHintTextColor(0xFF5E6579.toInt())
        e.background = rounded(Pal.FIELD, 16)
        e.setPadding(dp(16), dp(14), dp(16), dp(14))
        e.inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT
        return e
    }

    private fun pill(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 15f
        t.typeface = medium
        t.gravity = Gravity.CENTER
        t.setTextColor(if (primary) 0xFFFFFFFF.toInt() else Pal.TEXT)
        t.setPadding(dp(12), dp(15), dp(12), dp(15))
        t.background = withRipple(if (primary) gradientPill(20) else rounded(Pal.FIELD, 20))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun chip(text: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 13f
        t.typeface = medium
        t.gravity = Gravity.CENTER
        t.setTextColor(0xFFC9CEDD.toInt())
        t.setPadding(dp(10), dp(13), dp(10), dp(13))
        t.background = withRipple(rounded(Pal.CARD, 16))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun tag(text: String): TextView {
        val t = label(text, 11f, 0xFFC9CEDD.toInt(), true)
        t.setPadding(dp(10), dp(4), dp(10), dp(4))
        t.background = rounded(Pal.FIELD, 12)
        return t
    }

    private fun square(sizeDp: Int, radiusDp: Int, url: String?): ImageView {
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.background = rounded(Pal.FIELD, radiusDp)
        img.clipRound(radiusDp)
        if (!url.isNullOrEmpty()) Img.load(url, img)
        return img
    }

    // ---------- screen ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Pal.BG
        window.navigationBarColor = Pal.BG

        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(dp(18), dp(22), dp(18), dp(28))

        // profile header
        profileBox = card()
        page.addView(profileBox, lp(0))

        // status + start/stop
        val cc = card()
        val st = LinearLayout(this)
        st.orientation = LinearLayout.HORIZONTAL
        st.gravity = Gravity.CENTER_VERTICAL
        statusDot = View(this)
        val dotLp = LinearLayout.LayoutParams(dp(10), dp(10))
        dotLp.rightMargin = dp(12)
        st.addView(statusDot, dotLp)
        statusText = label("Idle", 16f, Pal.TEXT, true)
        st.addView(statusText)
        cc.addView(st)
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.addView(pill("Start watching", true) { start() }, weighted(2f))
        actions.addView(pill("Stop", false) { stopService() }, weighted(1f, 10))
        cc.addView(actions, lp(14))
        page.addView(cc, lp(14))

        // recently played + earned
        page.addView(section("RECENTLY PLAYED"), lp(24))
        gamesBox = LinearLayout(this)
        gamesBox.orientation = LinearLayout.VERTICAL
        page.addView(gamesBox, lp(10))

        page.addView(section("RECENTLY EARNED"), lp(24))
        earnedBox = card()
        page.addView(earnedBox, lp(10))

        // settings (collapsible)
        page.addView(chip("Settings & tools") { toggleSettings() }, lp(26))
        settingsBox = LinearLayout(this)
        settingsBox.orientation = LinearLayout.VERTICAL
        val savedUser = prefs.getString("user", "") ?: ""
        settingsBox.visibility = if (savedUser.isEmpty()) View.VISIBLE else View.GONE

        val ac = card()
        ac.addView(section("ACCOUNT"))
        userIn = field("Username", savedUser)
        passIn = field("Password (first time only)", "", true)
        keyIn = field("Web API key (for the home screen)", prefs.getString("apikey", "") ?: "")
        ac.addView(userIn, lp(12))
        ac.addView(passIn, lp(10))
        ac.addView(keyIn, lp(10))
        ac.addView(label("Find the key on retroachievements.org -> Settings -> Keys.", 11f, Pal.SUB), lp(8))
        settingsBox.addView(ac, lp(12))

        val lc = card()
        lc.addView(section("LIBRARY"))
        folderIn = field("ROM folder", prefs.getString("folder", "/storage/emulated/0/ROMs") ?: "")
        pkgIn = field("DraStic package", prefs.getString("pkg", "com.dsemu.drastic") ?: "")
        lc.addView(folderIn, lp(12))
        lc.addView(pkgIn, lp(10))
        settingsBox.addView(lc, lp(12))

        val t1 = LinearLayout(this)
        t1.orientation = LinearLayout.HORIZONTAL
        t1.addView(chip("Test popups") { testPopup() }, weighted(1f))
        t1.addView(chip("Refresh profile") { refreshHome() }, weighted(1f, 10))
        settingsBox.addView(t1, lp(12))
        val t2 = LinearLayout(this)
        t2.orientation = LinearLayout.HORIZONTAL
        t2.addView(chip("RAM scan") { runScan(false) }, weighted(1f))
        t2.addView(chip("Live RAM test") { runScan(true) }, weighted(1f, 10))
        t2.addView(chip("Share log") { shareLog() }, weighted(1f, 10))
        settingsBox.addView(t2, lp(10))
        settingsBox.addView(label("Test popups: appear 5 s after tapping - switch to your game to see them.", 11f, Pal.SUB), lp(8))

        val lg = card()
        lg.addView(section("LOG"))
        logScroll = ScrollView(this)
        logView = TextView(this)
        logView.typeface = Typeface.MONOSPACE
        logView.textSize = 11f
        logView.setTextColor(0xFFB4BACB.toInt())
        logView.setTextIsSelectable(true)
        logScroll.addView(logView)
        logScroll.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        lg.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(10) })
        settingsBox.addView(lg, lp(14))
        page.addView(settingsBox, lp(0))

        val outer = ScrollView(this)
        outer.setBackgroundColor(Pal.BG)
        outer.isFillViewport = true
        outer.addView(page)
        setContentView(outer)

        showStatus()
        // show last known data instantly, then refresh
        val cached = prefs.getString("summary", null)
        if (cached != null && savedUser.isNotEmpty()) {
            try {
                renderHome(Ra.parse(cached, savedUser))
            } catch (_: Exception) {
                renderEmpty()
            }
        } else {
            renderEmpty()
        }
    }

    private fun toggleSettings() {
        settingsBox.visibility = if (settingsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    // ---------- home data ----------
    private fun renderEmpty() {
        renderProfile(null)
        gamesBox.removeAllViews()
        val c = card()
        c.addView(label("Add your username and Web API key in Settings to see your games here.", 13f, Pal.SUB))
        gamesBox.addView(c, lp(0))
        earnedBox.removeAllViews()
        earnedBox.addView(label("Nothing yet.", 13f, Pal.SUB))
    }

    private fun renderProfile(p: RaProfile?) {
        profileBox.removeAllViews()
        profileBox.orientation = LinearLayout.HORIZONTAL
        profileBox.gravity = Gravity.CENTER_VERTICAL
        val av = ImageView(this)
        av.scaleType = ImageView.ScaleType.CENTER_CROP
        av.background = rounded(Pal.FIELD, 40)
        av.clipCircle()
        if (p != null) Img.load(MEDIA + p.pic, av)
        val alp = LinearLayout.LayoutParams(dp(62), dp(62))
        alp.rightMargin = dp(16)
        profileBox.addView(av, alp)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val name = p?.name ?: ((prefs.getString("user", "") ?: "").ifEmpty { "Drastic RA" })
        col.addView(label(name, 24f, Pal.TEXT, true))
        val pills = LinearLayout(this)
        pills.orientation = LinearLayout.HORIZONTAL
        if (p != null) {
            pills.addView(tag(p.softPts.toString() + " softcore pts"))
            if (p.hardPts > 0) {
                val hp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                hp.leftMargin = dp(8)
                pills.addView(tag(p.hardPts.toString() + " hardcore"), hp)
            }
        } else {
            pills.addView(label("RetroAchievements for DraStic", 12f, Pal.SUB))
        }
        col.addView(pills, lp(8))
        profileBox.addView(col)
    }

    private fun renderHome(h: RaHome) {
        renderProfile(h.profile)
        gamesBox.removeAllViews()
        if (h.games.isEmpty()) {
            val c = card()
            c.addView(label("No games yet - play something and it will show up here.", 13f, Pal.SUB))
            gamesBox.addView(c, lp(0))
        }
        for (g in h.games) gamesBox.addView(gameCard(g), lp(10))

        earnedBox.removeAllViews()
        if (h.earned.isEmpty()) {
            earnedBox.addView(label("No recent achievements.", 13f, Pal.SUB))
        }
        var first = true
        for (a in h.earned) {
            earnedBox.addView(earnedRow(a), lp(if (first) 0 else 14))
            first = false
        }
    }

    private fun gameCard(g: RaGame): View {
        val c = card()
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val ilp = LinearLayout.LayoutParams(dp(66), dp(66))
        ilp.rightMargin = dp(14)
        top.addView(square(66, 16, if (g.icon.isEmpty()) null else MEDIA + g.icon), ilp)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val title = label(g.title, 16f, Pal.TEXT, true)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        col.addView(title)
        val tg = tag(g.console.ifEmpty { "DS" })
        col.addView(tg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(top)

        if (g.badges.isNotEmpty()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (b in g.badges) {
                val lpb = LinearLayout.LayoutParams(dp(34), dp(34))
                lpb.rightMargin = dp(8)
                row.addView(square(34, 9, "$MEDIA/Badge/$b.png"), lpb)
            }
            c.addView(row, lp(14))
        }

        val pr = LinearLayout(this)
        pr.orientation = LinearLayout.HORIZONTAL
        pr.gravity = Gravity.CENTER_VERTICAL
        val pct = if (g.total > 0) g.achieved * 100 / g.total else 0
        val num = label(g.achieved.toString() + " / " + g.total, 13f, Pal.TEXT, true)
        val nlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        nlp.rightMargin = dp(12)
        pr.addView(num, nlp)
        pr.addView(progressBar(pct, 6), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(pr, lp(14))
        return c
    }

    private fun earnedRow(a: RaAch): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val ilp = LinearLayout.LayoutParams(dp(46), dp(46))
        ilp.rightMargin = dp(14)
        row.addView(square(46, 12, "$MEDIA/Badge/" + a.badge + ".png"), ilp)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = label(a.title, 14f, Pal.TEXT, true)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        val d = label(a.desc, 12f, Pal.SUB)
        d.maxLines = 1
        d.ellipsize = TextUtils.TruncateAt.END
        col.addView(t)
        col.addView(d)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label(a.points.toString(), 13f, 0xFFA99BFF.toInt(), true))
        return row
    }

    private fun refreshHome() {
        val user = userIn.text.toString().trim()
        val key = keyIn.text.toString().trim()
        if (user.isEmpty() || key.isEmpty()) {
            AppLog.add("Enter username + Web API key to load your profile.")
            return
        }
        prefs.edit().putString("user", user).putString("apikey", key).apply()
        Thread {
            try {
                val text = Ra.fetch(user, key)
                val home = Ra.parse(text, user)
                prefs.edit().putString("summary", text).apply()
                runOnUiThread { renderHome(home) }
            } catch (e: Exception) {
                AppLog.add("Profile load failed: " + e.message)
            }
        }.start()
    }

    // ---------- status / log ----------
    private fun showStatus() {
        statusText.text = AppLog.status
        val dot = GradientDrawable()
        dot.shape = GradientDrawable.OVAL
        dot.setColor(if (AppLog.active) Pal.GOOD else Pal.SUB)
        statusDot.background = dot
    }

    override fun onResume() {
        super.onResume()
        logView.text = AppLog.all()
        showStatus()
        AppLog.listener = { line ->
            runOnUiThread {
                logView.append(line + "\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        AppLog.statusListener = { runOnUiThread { showStatus() } }
        if ((prefs.getString("apikey", "") ?: "").isNotEmpty() && (prefs.getString("user", "") ?: "").isNotEmpty()) {
            userIn.setText(prefs.getString("user", ""))
            keyIn.setText(prefs.getString("apikey", ""))
            refreshHome()
        }
    }

    override fun onPause() {
        AppLog.listener = null
        AppLog.statusListener = null
        super.onPause()
    }

    // ---------- actions ----------
    private fun start() {
        val user = userIn.text.toString().trim()
        val pass = passIn.text.toString()
        val folder = folderIn.text.toString().trim()
        val pkg = pkgIn.text.toString().trim()
        if (user.isEmpty()) {
            AppLog.add("Enter your RetroAchievements username.")
            settingsBox.visibility = View.VISIBLE
            return
        }
        prefs.edit().putString("user", user).putString("folder", folder).putString("pkg", pkg)
            .putString("apikey", keyIn.text.toString().trim()).apply()
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
                i.putExtra("user", user).putExtra("pass", pass).putExtra("folder", folder).putExtra("pkg", pkg)
                startForegroundService(i)
                passIn.setText("")
            }
        }.start()
    }

    private fun testPopup() {
        AppLog.add("popup test: three popups start in 5 s - switch to DraStic now")
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
                h.postDelayed({ pop.showGame("Pokemon SoulSilver", "", 12, 59, "35 / 410 points", 6000) }, 5000)
                h.postDelayed({
                    pop.show("ACHIEVEMENT UNLOCKED", "Test Achievement", "This is what an unlock looks like   -   10 pts", null, 5000)
                }, 12500)
                h.postDelayed({ pop.show("PROGRESS", "Test Achievement", "3 / 10", null, 3000) }, 19000)
            }
        }.start()
    }

    private fun stopService() {
        val i = Intent(this, EngineService::class.java)
        i.action = EngineService.ACTION_STOP
        startService(i)
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
