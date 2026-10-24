package com.drasticra

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var userIn: EditText
    private lateinit var passIn: EditText
    private lateinit var folderIn: EditText
    private lateinit var pkgIn: EditText

    private fun field(hint: String, value: String, password: Boolean = false): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.setSingleLine(true)
        e.textSize = 14f
        e.inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT
        return e
    }

    private fun btn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 12f
        b.setOnClickListener { onClick() }
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (12 * d).toInt()
        val prefs = getSharedPreferences("dsra", MODE_PRIVATE)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Drastic RA"
        title.textSize = 18f
        root.addView(title)

        userIn = field("RetroAchievements username", prefs.getString("user", "") ?: "")
        passIn = field("Password (only needed the first time)", "", true)
        folderIn = field("ROM folder", prefs.getString("folder", "/storage/emulated/0/ROMs") ?: "")
        pkgIn = field("DraStic package", prefs.getString("pkg", "com.dsemu.drastic") ?: "")
        root.addView(userIn)
        root.addView(passIn)
        root.addView(folderIn)
        root.addView(pkgIn)

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.addView(btn("Start watching") { start() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(btn("Stop") { stopService() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row1)
        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(btn("RAM scan") { runScan(false) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(btn("Live test") { runScan(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(btn("Share log") { shareLog() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row2)
        val row3 = LinearLayout(this)
        row3.orientation = LinearLayout.HORIZONTAL
        row3.addView(btn("Test popup (5 s delay - switch to DraStic!)") { testPopup() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row3)

        scroll = ScrollView(this)
        logView = TextView(this)
        logView.typeface = Typeface.MONOSPACE
        logView.textSize = 11f
        logView.setTextIsSelectable(true)
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        logView.text = AppLog.all()
        AppLog.listener = { line ->
            runOnUiThread {
                logView.append(line + "\n")
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    override fun onPause() {
        AppLog.listener = null
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
        AppLog.add("popup test: grant overlay permission via root, popups come in 5 s...")
        Thread {
            rootSh("appops set $packageName SYSTEM_ALERT_WINDOW allow")
            runOnUiThread {
                if (!Settings.canDrawOverlays(this)) {
                    AppLog.add("Overlay permission still off: enable 'Display over other apps' for Drastic RA, then test again.")
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    return@runOnUiThread
                }
                val pop = Popup(applicationContext)
                val h = android.os.Handler(mainLooper)
                h.postDelayed({
                    pop.show("Achievement Unlocked", "Test Achievement - this is what an unlock looks like (10)", null, 5000)
                }, 5000)
                h.postDelayed({
                    pop.show("Test Achievement", "Progress: 3/10", null, 3000)
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
