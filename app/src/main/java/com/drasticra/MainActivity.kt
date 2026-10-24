package com.drasticra

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
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
    private lateinit var pkgInput: EditText
    private lateinit var scanBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (14 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Drastic RA - step 1b: find DraStic's DS RAM"
            textSize = 18f
        })
        root.addView(TextView(this).apply {
            text = "1) Start DraStic and load a game.  2) Come back here and tap Scan (grant root when asked)."
            textSize = 13f
            setPadding(0, pad / 2, 0, pad / 2)
        })
        pkgInput = EditText(this).apply {
            setText("com.dsemu.drastic")
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(pkgInput)

        scanBtn = Button(this).apply {
            text = "Scan DraStic RAM (root)"
            setOnClickListener { runScan(false) }
        }
        root.addView(scanBtn)
        root.addView(Button(this).apply {
            text = "Live RAM test (15 s)"
            setOnClickListener { runScan(true) }
        })
        root.addView(Button(this).apply {
            text = "Share / copy log"
            setOnClickListener { shareLog() }
        })

        scroll = ScrollView(this)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun append(s: String) = runOnUiThread {
        logView.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun runScan(live: Boolean) {
        val pkg = pkgInput.text.toString().trim()
        logView.text = ""
        scanBtn.isEnabled = false
        val apk = applicationInfo.sourceDir
        Thread {
            try {
                val cmd = "CLASSPATH=${shq(apk)} app_process /system/bin com.drasticra.RootScan ${shq(pkg)}${if (live) " live" else ""}"
                append("running as root...")
                val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        append(line)
                        line = r.readLine()
                    }
                }
                append("exit code: " + p.waitFor())
            } catch (e: Exception) {
                append("FAILED to run su: ${e.message}")
                append("Is root granted to Drastic RA (Magisk/KernelSU)?")
            } finally {
                runOnUiThread { scanBtn.isEnabled = true }
            }
        }.start()
    }

    private fun shareLog() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logView.text.toString())
        }
        startActivity(Intent.createChooser(i, "Share log"))
    }
}
