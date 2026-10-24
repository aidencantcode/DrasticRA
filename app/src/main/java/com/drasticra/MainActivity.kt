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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
    private lateinit var folderIn: EditText
    private lateinit var pkgIn: EditText

    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

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
        t.setPadding(dp(12), dp(16), dp(12), dp(16))
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Pal.BG
        window.navigationBarColor = Pal.BG
        val prefs = getSharedPreferences("dsra", MODE_PRIVATE)

        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(dp(20), dp(24), dp(20), dp(28))

        page.addView(label("Drastic RA", 32f, Pal.TEXT, true))
        page.addView(label("RetroAchievements for DraStic", 14f, Pal.SUB), lp(2))

        // status
        val sc = card()
        sc.orientation = LinearLayout.HORIZONTAL
        sc.gravity = Gravity.CENTER_VERTICAL
        statusDot = View(this)
        val dotLp = LinearLayout.LayoutParams(dp(10), dp(10))
        dotLp.rightMargin = dp(12)
        sc.addView(statusDot, dotLp)
        statusText = label("Idle", 16f, Pal.TEXT, true)
        sc.addView(statusText)
        page.addView(sc, lp(20))

        // account
        val ac = card()
        ac.addView(section("ACCOUNT"))
        userIn = field("Username", prefs.getString("user", "") ?: "")
        passIn = field("Password (first time only)", "", true)
        ac.addView(userIn, lp(12))
        ac.addView(passIn, lp(10))
        page.addView(ac, lp(14))

        // library
        val lc = card()
        lc.addView(section("LIBRARY"))
        folderIn = field("ROM folder", prefs.getString("folder", "/storage/emulated/0/ROMs") ?: "")
        pkgIn = field("DraStic package", prefs.getString("pkg", "com.dsemu.drastic") ?: "")
        lc.addView(folderIn, lp(12))
        lc.addView(pkgIn, lp(10))
        page.addView(lc, lp(14))

        // main actions
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.addView(pill("Start watching", true) { start() }, weighted(2f))
        actions.addView(pill("Stop", false) { stopService() }, weighted(1f, 10))
        page.addView(actions, lp(18))

        // tools
        val tools1 = LinearLayout(this)
        tools1.orientation = LinearLayout.HORIZONTAL
        tools1.addView(chip("Test popup") { testPopup() }, weighted(1f))
        tools1.addView(chip("Share log") { shareLog() }, weighted(1f, 10))
        page.addView(tools1, lp(12))
        val tools2 = LinearLayout(this)
        tools2.orientation = LinearLayout.HORIZONTAL
        tools2.addView(chip("RAM scan") { runScan(false) }, weighted(1f))
        tools2.addView(chip("Live RAM test") { runScan(true) }, weighted(1f, 10))
        page.addView(tools2, lp(10))
        page.addView(label("Test popup appears 5 s after tapping - switch to your game to see it.", 11f, Pal.SUB), lp(8))

        // log
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
        page.addView(lg, lp(18))

        val outer = ScrollView(this)
        outer.setBackgroundColor(Pal.BG)
        outer.isFillViewport = true
        outer.addView(page)
        setContentView(outer)
        showStatus()
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
        logView.text = AppLog.all()
        showStatus()
        AppLog.listener = { line ->
            runOnUiThread {
                logView.append(line + "\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        AppLog.statusListener = { runOnUiThread { showStatus() } }
    }

    override fun onPause() {
        AppLog.listener = null
        AppLog.statusListener = null
        super.onPause()
    }

    private fun start() {
        val user = userIn.text.toString().trim()
        val pass = passIn.text.toString()
        val folder = folderIn.text.toString().trim()
        val pkg = pkgIn.text.toString().trim()
        if (user.isEmpty()) {
            AppLog.add("Enter your RetroAchievements username.")
            return
        }
        getSharedPreferences("dsra", MODE_PRIVATE).edit()
            .putString("user", user).putString("folder", folder).putString("pkg", pkg).apply()
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
        AppLog.add("popup test: popups appear in 5 s - switch to DraStic now")
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
                h.postDelayed({
                    pop.show("ACHIEVEMENT UNLOCKED", "Test Achievement", "This is what an unlock looks like   -   10 pts", null, 5000)
                }, 5000)
                h.postDelayed({
                    pop.show("PROGRESS", "Test Achievement", "3 / 10", null, 3000)
                }, 11000)
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
