package com.drasticra

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File

// ---------- small helpers ----------
private fun MainActivity.rowOf(vararg v: View): LinearLayout {
    val r = LinearLayout(this)
    r.orientation = LinearLayout.HORIZONTAL
    for (i in v.indices) r.addView(v[i], ll(0, WRAP, 1f, l = if (i == 0) 0 else 8))
    return r
}

private fun MainActivity.sectionCard(title: String): LinearLayout {
    val c = card(24)
    pad(c, 16, 14, 16, 16)
    c.addView(sectionTitle(title))
    return c
}

private fun MainActivity.bindPref(e: EditText, key: String) {
    e.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            prefs.edit().putString(key, (s?.toString() ?: "").trim()).apply()
        }
    })
}

private fun hexOf(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

private fun parseHex(s: String): Int? = try {
    Color.parseColor(s.trim()) or 0xFF000000.toInt()
} catch (_: Exception) {
    null
}

// ---------- main menu ----------
private fun MainActivity.menuTile(col: LinearLayout, key: String, title: String, sub: String) {
    val c = card(22)
    c.orientation = LinearLayout.HORIZONTAL
    c.gravity = Gravity.CENTER_VERTICAL
    pad(c, 16, 14, 16, 14)
    val t = LinearLayout(this)
    t.orientation = LinearLayout.VERTICAL
    t.addView(tv(title, 16f, Pal.TEXT, true))
    val s = tv(sub, 11f, Pal.SUB)
    s.maxLines = 1
    s.ellipsize = TextUtils.TruncateAt.END
    t.addView(s, ll(WRAP, WRAP, t = 2))
    c.addView(t, ll(0, WRAP, 1f, r = 8))
    c.addView(tv("\u203A", 24f, Pal.SUB, true))
    c.isClickable = true
    makeFocusable(c)
    c.setOnClickListener { showPage(key) }
    c.pressable()
    col.addView(c, ll(MATCH, WRAP, t = 10))
}

internal fun MainActivity.buildMenu(col: LinearLayout) {
    col.addView(tv("Settings", 26f, Pal.TEXT, true))
    col.addView(tv("L1 / R1 switch tabs  -  START opens this  -  B goes back", 11f, Pal.SUB), ll(WRAP, WRAP, t = 2, b = 6))
    val user = prefs.getString("user_ra", "") ?: ""
    val loggedIn = (prefs.getString("token", "") ?: "").isNotEmpty() && user.isNotEmpty()
    fun glassName(m: String) = when (m) {
        "off" -> "Off"
        "lite" -> "Lite"
        else -> "Full"
    }
    val uiGlass = glassMode()
    val popGlass = popupGlassMode()
    val gm = if (popGlass == uiGlass) "UI and popups: " + glassName(uiGlass)
    else "UI: " + glassName(uiGlass) + "  -  Popups: " + glassName(popGlass)
    val style = prefs.getString("bg_style", "aurora") ?: "aurora"
    val preset = BgPresets.all.firstOrNull { it.style == style }?.label ?: "Aurora"
    val mediaType = prefs.getString("bg_type", "anim") ?: "anim"
    val bgLabel = if (mediaType == "image" || mediaType == "video") "Your $mediaType" else preset
    menuTile(col, "account", "Account", if (loggedIn) "Logged in as $user" else "Not logged in")
    menuTile(col, "library", "Library", prefs.getString("folder", "/storage/emulated/0/ROMs") ?: "")
    menuTile(col, "glass", "Glass", gm)
    menuTile(col, "anim", "Animations", bgLabel)
    menuTile(col, "look", "Appearance", (if (Pal.light) "Light" else "Dark") + " theme, accent, font, background")
    menuTile(col, "popups", "Popups", "Position, time, glass backdrop")
    menuTile(col, "tools", "Tools and log", "Scans, share log, previews")
}

// ---------- account ----------
private fun MainActivity.doLoginNow(userIn: EditText, passIn: EditText, msg: TextView) {
    val u = userIn.text.toString().trim()
    val p = passIn.text.toString()
    if (u.isEmpty() || p.isEmpty()) {
        msg.text = "Enter your username and password."
        return
    }
    msg.text = "Logging in..."
    Thread {
        val r = Ra.login(u, p)
        runOnUiThread {
            if (r.ok && r.token.isNotEmpty()) {
                prefs.edit().putString("token", r.token).putString("user_ra", r.user).putString("user", r.user).apply()
                AppLog.add("Logged in as " + r.user)
                showPage("account")
                refreshAll()
                renderProfile()
            } else {
                msg.text = if (r.error.isEmpty()) "Login failed." else r.error
            }
        }
    }.start()
}

private fun MainActivity.logoutNow() {
    stopWatching()
    prefs.edit().remove("token").remove("user_ra").remove("summary").remove("earned").remove("completion").apply()
    clearData()
    AppLog.add("Logged out.")
    showPage("account")
}

internal fun MainActivity.buildAccountPage(col: LinearLayout) {
    val ac = sectionCard("LOGIN")
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
        ac.addView(row, ll(MATCH, WRAP, t = 12))
        ac.addView(chip("Log out", false) { logoutNow() }, ll(MATCH, WRAP, t = 12))
    } else {
        val userIn = field("Username", prefs.getString("user", "") ?: "")
        val passIn = field("Password", "", true)
        val msg = tv("", 12f, Pal.SUB)
        ac.addView(userIn, ll(MATCH, WRAP, t = 12))
        ac.addView(passIn, ll(MATCH, WRAP, t = 8))
        ac.addView(msg, ll(WRAP, WRAP, t = 6))
        ac.addView(pill("Log in", true) { doLoginNow(userIn, passIn, msg) }, ll(MATCH, WRAP, t = 10))
    }
    col.addView(ac, ll(MATCH, WRAP))

    val kc = sectionCard("HOME SCREEN DATA")
    val keyIn = field("Web API key", prefs.getString("apikey", "") ?: "")
    bindPref(keyIn, "apikey")
    kc.addView(keyIn, ll(MATCH, WRAP, t = 12))
    kc.addView(tv("retroachievements.org -> Settings -> Keys", 11f, Pal.SUB), ll(WRAP, WRAP, t = 6))
    kc.addView(chip("Save and refresh", false) {
        prefs.edit().putString("apikey", keyIn.text.toString().trim()).apply()
        refreshAll()
    }, ll(MATCH, WRAP, t = 10))
    col.addView(kc, ll(MATCH, WRAP, t = 12))
}

// ---------- library ----------
internal fun MainActivity.buildLibraryPage(col: LinearLayout) {
    val lc = sectionCard("ROMS AND EMULATOR")
    val folderIn = field("ROM folder", prefs.getString("folder", "/storage/emulated/0/ROMs") ?: "")
    val pkgIn = field("DraStic package", prefs.getString("pkg", "com.dsemu.drastic") ?: "")
    bindPref(folderIn, "folder")
    bindPref(pkgIn, "pkg")
    lc.addView(folderIn, ll(MATCH, WRAP, t = 12))
    lc.addView(pkgIn, ll(MATCH, WRAP, t = 8))
    lc.addView(tv("Earned and Completion show", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 14))
    val ds = prefs.getBoolean("ds_only", false)
    lc.addView(rowOf(
        chip("All games", !ds) {
            prefs.edit().putBoolean("ds_only", false).apply()
            renderTab()
            showPage("library", false)
        },
        chip("DS only", ds) {
            prefs.edit().putBoolean("ds_only", true).apply()
            renderTab()
            showPage("library", false)
        }
    ), ll(MATCH, WRAP, t = 8))
    lc.addView(tv("Recent always shows Nintendo DS games only.", 11f, Pal.SUB), ll(WRAP, WRAP, t = 8))
    col.addView(lc, ll(MATCH, WRAP))
}

// ---------- glass ----------
internal fun MainActivity.buildGlassPage(col: LinearLayout) {
    val gc = sectionCard("MENU, HOME AND UI GLASS")
    val mode = glassMode()
    gc.addView(rowOf(
        chip("Off", mode == "off") { prefs.edit().putString("glass_mode", "off").apply(); recreate() },
        chip("Lite", mode == "lite") { prefs.edit().putString("glass_mode", "lite").apply(); recreate() },
        chip("Full", mode == "full") { prefs.edit().putString("glass_mode", "full").apply(); recreate() }
    ), ll(MATCH, WRAP, t = 12))
    gc.addView(tv(
        "Full bends and blurs the animated background behind every card (liquid refraction). Lite is a frosted look " +
            "without the live effect. Off is plain solid cards - best for low powered devices.", 11f, Pal.SUB
    ), ll(WRAP, WRAP, t = 10))
    gc.addView(tv("Refraction strength", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 14))
    val st = prefs.getString("glass_strength", "normal") ?: "normal"
    gc.addView(rowOf(
        chip("Soft", st == "soft") { prefs.edit().putString("glass_strength", "soft").apply(); recreate() },
        chip("Normal", st == "normal") { prefs.edit().putString("glass_strength", "normal").apply(); recreate() },
        chip("Strong", st == "strong") { prefs.edit().putString("glass_strength", "strong").apply(); recreate() }
    ), ll(MATCH, WRAP, t = 8))
    col.addView(gc, ll(MATCH, WRAP))

    // popups get their own material, so they can be Full while the rest of the app stays Lite (or the reverse)
    val pcard = sectionCard("POPUP GLASS")
    val pg = prefs.getString("popup_glass", "same") ?: "same"
    pcard.addView(rowOf(
        chip("Same", pg != "off" && pg != "lite" && pg != "full") { prefs.edit().putString("popup_glass", "same").apply(); showPage("glass", false) },
        chip("Off", pg == "off") { prefs.edit().putString("popup_glass", "off").apply(); showPage("glass", false) },
        chip("Lite", pg == "lite") { prefs.edit().putString("popup_glass", "lite").apply(); showPage("glass", false) },
        chip("Full", pg == "full") { prefs.edit().putString("popup_glass", "full").apply(); showPage("glass", false) }
    ), ll(MATCH, WRAP, t = 12))
    pcard.addView(tv(
        "Only affects achievement popups over your game. Same follows the setting above. Full bends the live game " +
            "screen behind the popup (needs the root screenshot in Popups). Changes apply to the next popup.", 11f, Pal.SUB
    ), ll(WRAP, WRAP, t = 10))
    col.addView(pcard, ll(MATCH, WRAP, t = 12))
}

// ---------- animations ----------
internal fun MainActivity.buildAnimPage(col: LinearLayout) {
    val tc = sectionCard("THEMES")
    val cur = prefs.getString("bg_style", "aurora") ?: "aurora"
    val mt = prefs.getString("bg_type", "anim") ?: "anim"
    val isAnim = mt == "anim" || mt == "aurora"
    val list = BgPresets.all
    var i = 0
    while (i < list.size) {
        val a = list[i]
        val b = if (i + 1 < list.size) list[i + 1] else null
        val ca = chip(a.label, isAnim && cur == a.style) { BgPresets.apply(this, a); recreate() }
        val cb: View = if (b != null) chip(b.label, isAnim && cur == b.style) { BgPresets.apply(this, b); recreate() } else View(this)
        tc.addView(rowOf(ca, cb), ll(MATCH, WRAP, t = 8))
        i += 2
    }
    tc.addView(tv("A theme sets the pattern, its colours and the accent. Tweak the colours below afterwards.", 11f, Pal.SUB), ll(WRAP, WRAP, t = 10))
    col.addView(tc, ll(MATCH, WRAP))

    val mc = sectionCard("MOTION")
    val sp = prefs.getInt("bg_speed", 100)
    val lbl = tv("Speed   " + String.format("%.1f", sp / 100f) + "x", 13f, Pal.TEXT, true)
    mc.addView(lbl, ll(WRAP, WRAP, t = 12))
    val sb = SeekBar(this)
    sb.max = 280
    sb.progress = (sp - 20).coerceIn(0, 280)
    sb.progressTintList = ColorStateList.valueOf(Pal.ACC1)
    sb.thumbTintList = ColorStateList.valueOf(Pal.ACC2)
    sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
            lbl.text = "Speed   " + String.format("%.1f", (p + 20) / 100f) + "x"
            if (fromUser) {
                prefs.edit().putInt("bg_speed", p + 20).apply()
                bg.refreshConfig()
            }
        }

        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    })
    mc.addView(sb, ll(MATCH, WRAP, t = 6))
    val fps = prefs.getInt("bg_fps", 30)
    mc.addView(tv("Smoothness", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 12))
    mc.addView(rowOf(
        chip("30 fps (saves battery)", fps < 60) { prefs.edit().putInt("bg_fps", 30).apply(); bg.refreshConfig(); showPage("anim", false) },
        chip("60 fps", fps >= 60) { prefs.edit().putInt("bg_fps", 60).apply(); bg.refreshConfig(); showPage("anim", false) }
    ), ll(MATCH, WRAP, t = 8))
    val on = prefs.getBoolean("bg_anim", true)
    mc.addView(rowOf(
        chip("Animation on", on) { prefs.edit().putBoolean("bg_anim", true).apply(); bg.refreshConfig(); showPage("anim", false) },
        chip("Animation off", !on) { prefs.edit().putBoolean("bg_anim", false).apply(); bg.refreshConfig(); showPage("anim", false) }
    ), ll(MATCH, WRAP, t = 8))
    col.addView(mc, ll(MATCH, WRAP, t = 12))

    val cc = sectionCard("COLOURS")
    val link = prefs.getBoolean("bg_link", true)
    cc.addView(rowOf(
        chip("Follow accent", link) { prefs.edit().putBoolean("bg_link", true).apply(); bg.refreshConfig(); showPage("anim", false) },
        chip("Custom colours", !link) { prefs.edit().putBoolean("bg_link", false).apply(); bg.refreshConfig(); showPage("anim", false) }
    ), ll(MATCH, WRAP, t = 12))
    val h1 = field("#RRGGBB", hexOf(prefs.getInt("bgc1", Pal.ACC1)))
    val h2 = field("#RRGGBB", hexOf(prefs.getInt("bgc2", Pal.ACC2)))
    val h3 = field("#RRGGBB", hexOf(prefs.getInt("bgc3", Pal.ACC1)))
    val hb = field("#RRGGBB", hexOf(if (prefs.contains("bg_base")) prefs.getInt("bg_base", Pal.BG) else Pal.BG))
    cc.addView(tv("Colour 1 / 2 / 3 and base", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 12))
    cc.addView(rowOf(h1, h2), ll(MATCH, WRAP, t = 8))
    cc.addView(rowOf(h3, hb), ll(MATCH, WRAP, t = 8))
    cc.addView(chip("Apply colours", false) {
        val a = parseHex(h1.text.toString())
        val b = parseHex(h2.text.toString())
        val c = parseHex(h3.text.toString())
        val d = parseHex(hb.text.toString())
        if (a == null || b == null || c == null || d == null) {
            AppLog.add("Colours must look like #RRGGBB")
        } else {
            prefs.edit().putBoolean("bg_link", false).putInt("bgc1", a).putInt("bgc2", b).putInt("bgc3", c)
                .putInt("bg_base", d).apply()
            bg.refreshConfig()
            showPage("anim", false)
        }
    }, ll(MATCH, WRAP, t = 8))
    col.addView(cc, ll(MATCH, WRAP, t = 12))
}

// ---------- appearance ----------
private fun MainActivity.setAccent(a: Int, b: Int) {
    prefs.edit().putInt("acc1", a).putInt("acc2", b).apply()
    recreate()
}

private fun MainActivity.swatches(): View {
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
        makeFocusable(s)
        s.setOnClickListener { setAccent(p[0], p[1]) }
        s.pressable()
        row.addView(s, ll(dp(42), dp(42), r = 10))
    }
    hs.addView(row)
    return hs
}

internal fun MainActivity.buildLookPage(col: LinearLayout) {
    val th = sectionCard("THEME")
    th.addView(rowOf(
        chip("Light", Pal.light) { prefs.edit().putString("theme", "light").remove("bg_base").apply(); recreate() },
        chip("Dark", !Pal.light) { prefs.edit().putString("theme", "dark").remove("bg_base").apply(); recreate() }
    ), ll(MATCH, WRAP, t = 12))
    th.addView(tv("Accent gradient", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 14))
    th.addView(swatches(), ll(MATCH, WRAP, t = 8))
    val a1 = field("#7C7CFF", hexOf(Pal.ACC1))
    val a2 = field("#B57CFF", hexOf(Pal.ACC2))
    th.addView(rowOf(a1, a2), ll(MATCH, WRAP, t = 8))
    th.addView(chip("Apply custom accent", false) {
        val a = parseHex(a1.text.toString())
        val b = parseHex(a2.text.toString())
        if (a == null || b == null) AppLog.add("Colours must look like #RRGGBB") else setAccent(a, b)
    }, ll(MATCH, WRAP, t = 8))
    col.addView(th, ll(MATCH, WRAP))

    val bc = sectionCard("BACKGROUND")
    bc.addView(tv("Use your own picture, GIF or video instead of an animated theme.", 11f, Pal.SUB), ll(WRAP, WRAP, t = 8))
    val mt = prefs.getString("bg_type", "anim") ?: "anim"
    bc.addView(rowOf(
        chip("Animated theme", mt == "anim" || mt == "aurora") {
            prefs.edit().putString("bg_type", "anim").apply()
            bg.reload()
            showPage("look", false)
        },
        chip("Pick image / GIF / video", mt == "image" || mt == "video") { pickFile(101, arrayOf("image/*", "video/*")) }
    ), ll(MATCH, WRAP, t = 10))
    val dim = prefs.getInt("bg_dim", 35)
    bc.addView(tv("Dim your own background", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 14))
    bc.addView(rowOf(
        chip("Low", dim == 15) { prefs.edit().putInt("bg_dim", 15).apply(); bg.reload(); showPage("look", false) },
        chip("Medium", dim == 35) { prefs.edit().putInt("bg_dim", 35).apply(); bg.reload(); showPage("look", false) },
        chip("High", dim == 60) { prefs.edit().putInt("bg_dim", 60).apply(); bg.reload(); showPage("look", false) }
    ), ll(MATCH, WRAP, t = 8))
    col.addView(bc, ll(MATCH, WRAP, t = 12))

    val fc = sectionCard("FONT")
    fc.addView(tv("Titles use a heavy rounded system font. Pick any .ttf / .otf to replace it everywhere.", 11f, Pal.SUB), ll(WRAP, WRAP, t = 8))
    fc.addView(rowOf(
        chip("Pick font file", false) { pickFile(102, arrayOf("*/*")) },
        chip("Default font", false) {
            File(filesDir, "font.ttf").delete()
            recreate()
        }
    ), ll(MATCH, WRAP, t = 10))
    col.addView(fc, ll(MATCH, WRAP, t = 12))
}

// ---------- popups ----------
internal fun MainActivity.buildPopupsPage(col: LinearLayout) {
    val pc = sectionCard("ACHIEVEMENT POPUPS")
    val pos = prefs.getString("popup_pos", "top") ?: "top"
    pc.addView(tv("Position", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 12))
    pc.addView(rowOf(
        chip("Top", pos == "top") { prefs.edit().putString("popup_pos", "top").apply(); showPage("popups", false) },
        chip("Bottom", pos == "bottom") { prefs.edit().putString("popup_pos", "bottom").apply(); showPage("popups", false) }
    ), ll(MATCH, WRAP, t = 8))
    val secs = prefs.getInt("popup_secs", 5)
    pc.addView(tv("Stay on screen (tap one to open it up)", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 14))
    pc.addView(rowOf(
        chip("4 s", secs == 4) { prefs.edit().putInt("popup_secs", 4).apply(); showPage("popups", false) },
        chip("6 s", secs == 6) { prefs.edit().putInt("popup_secs", 6).apply(); showPage("popups", false) },
        chip("9 s", secs == 9) { prefs.edit().putInt("popup_secs", 9).apply(); showPage("popups", false) }
    ), ll(MATCH, WRAP, t = 8))
    val cap = prefs.getBoolean("popup_capture", true)
    pc.addView(tv("Glass sees your game (root screenshot)", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 14))
    pc.addView(rowOf(
        chip("On", cap) { prefs.edit().putBoolean("popup_capture", true).apply(); showPage("popups", false) },
        chip("Off (frosted only)", !cap) { prefs.edit().putBoolean("popup_capture", false).apply(); showPage("popups", false) }
    ), ll(MATCH, WRAP, t = 8))
    pc.addView(chip("Test popups", false) { testPopup() }, ll(MATCH, WRAP, t = 14))
    pc.addView(tv("Test popups appear 5 s after tapping - close this panel and switch to your game.", 11f, Pal.SUB), ll(WRAP, WRAP, t = 8))
    col.addView(pc, ll(MATCH, WRAP))
}

// ---------- tools ----------
internal fun MainActivity.buildToolsPage(col: LinearLayout) {
    val tc = sectionCard("TOOLS")
    tc.addView(rowOf(chip("RAM scan", false) { runScan(false) }, chip("Live RAM test", false) { runScan(true) }), ll(MATCH, WRAP, t = 12))
    tc.addView(rowOf(chip("Share log", false) { shareLog() }, chip("Preview congrats", false) { previewCongrats() }), ll(MATCH, WRAP, t = 8))
    col.addView(tc, ll(MATCH, WRAP))

    val lg = sectionCard("LOG")
    val sv = ScrollView(this)
    val lv = tv(AppLog.all(), 11f, Pal.SUB)
    lv.typeface = Typeface.MONOSPACE
    lv.setTextIsSelectable(true)
    sv.addView(lv)
    sv.setOnTouchListener { v, _ ->
        v.parent.requestDisallowInterceptTouchEvent(true)
        false
    }
    lg.addView(sv, ll(MATCH, dp(220), t = 10))
    logView = lv
    logScroll = sv
    col.addView(lg, ll(MATCH, WRAP, t = 12))
}
