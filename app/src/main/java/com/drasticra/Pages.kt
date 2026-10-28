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
    val pend = OfflineProxy.pendingCount()
    val heldN = OfflineProxy.heldCount()
    menuTile(col, "offline", "Offline Play", (if (NetMonitor.online) "Online" else "Offline") + "  -  " +
        RomCacher.cachedGames() + " games cached" + (if (pend > 0) "  -  $pend to sync" else "") +
        (if (heldN > 0) "  -  $heldN held" else ""))
    menuTile(col, "emulators", "Emulators", "RetroArch, PPSSPP, ARMSX2 + PS3 / Vita / PS4 trophies")
    menuTile(col, "glass", "Glass", gm)
    menuTile(col, "anim", "Themes", bgLabel)
    menuTile(col, "look", "Appearance", (if (Pal.light) "Light" else "Dark") + " theme, accent, font, background")
    menuTile(col, "popups", "Popups", "Position, time, glass backdrop")
    menuTile(col, "buttons", "Button mapping", "Achievement overlay: " + Hotkeys.label(Hotkeys.stored(this)))
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
    lc.addView(tv("Recent and Recently earned show every console.", 11f, Pal.SUB), ll(WRAP, WRAP, t = 8))
    col.addView(lc, ll(MATCH, WRAP))
}


// ---------- offline play ----------
internal fun MainActivity.buildOfflinePage(col: LinearLayout) {
    val sc = sectionCard("STATUS")
    val on = NetMonitor.online
    val st = tv(if (on) "Online" else "Offline mode", 20f, if (on) Pal.GOOD else 0xFFE0A100.toInt(), true)
    sc.addView(st, ll(WRAP, WRAP, t = 8))
    val pend = OfflineProxy.pendingCount()
    sc.addView(tv(
        if (pend == 0) "No offline unlocks waiting - everything is on RetroAchievements."
        else "$pend achievement${if (pend == 1) "" else "s"} unlocked offline, waiting to be sent. They go out by themselves " +
            "as soon as the internet is back, with the time you really earned them.", 12f, Pal.SUB
    ), ll(WRAP, WRAP, t = 6))
    if (pend > 0) {
        sc.addView(chip("Sync now", false) {
            if (!NetMonitor.online) {
                showNotice("Still offline", "Unlocks are kept safe until the internet is back", true)
            } else {
                Thread {
                    val n = OfflineProxy.flush()
                    runOnUiThread {
                        AppLog.add("Synced $n offline unlocks.")
                        showPage("offline", false)
                        renderTab()
                    }
                }.start()
            }
        }, ll(MATCH, WRAP, t = 10))
    }
    if (pend > 0) {
        sc.addView(chip("Discard unsynced unlocks", false) {
            OfflineProxy.discardPending()
            AppLog.add("Discarded $pend unsynced offline unlocks.")
            showPage("offline", false)
            renderTab()
        }, ll(MATCH, WRAP, t = 8))
    }
    col.addView(sc, ll(MATCH, WRAP))

    // ---- unlock burst protection ----
    val gc = sectionCard("UNLOCK BURST PROTECTION")
    val held = OfflineProxy.held()
    gc.addView(tv(
        "Loading a save state (like DraStic's Continue) can make memory jump so many achievements fire at once. " +
            "When 3+ unlock within seconds (or 6+ within 2 minutes) they are held back - not sent - and the game is " +
            "reloaded with your real progress. Genuine bursts can be sent from here.", 11f, Pal.SUB
    ), ll(WRAP, WRAP, t = 8))
    val guardOn = prefs.getBoolean("burst_guard", true)
    gc.addView(rowOf(
        chip("Protection on", guardOn) { prefs.edit().putBoolean("burst_guard", true).apply(); BurstGuard.load(this); showPage("offline", false) },
        chip("Off", !guardOn) { prefs.edit().putBoolean("burst_guard", false).apply(); BurstGuard.load(this); showPage("offline", false) }
    ), ll(MATCH, WRAP, t = 10))
    if (held.isEmpty()) {
        gc.addView(tv("Nothing held back.", 12f, Pal.SUB), ll(WRAP, WRAP, t = 10))
    } else {
        gc.addView(tv("${held.size} unlock${if (held.size == 1) "" else "s"} held back (not sent):", 13f, 0xFFE0A100.toInt(), true),
            ll(WRAP, WRAP, t = 10))
        for (h in held.take(40)) {
            val line = tv("-  " + h.title + (if (h.game.isNotEmpty()) "   (" + h.game + ")" else ""), 12f, Pal.TEXT)
            line.maxLines = 1
            line.ellipsize = TextUtils.TruncateAt.END
            gc.addView(line, ll(MATCH, WRAP, t = 2))
        }
        if (held.size > 40) gc.addView(tv("...and ${held.size - 40} more", 12f, Pal.SUB), ll(WRAP, WRAP, t = 2))
        gc.addView(rowOf(
            chip("Discard them", false) {
                OfflineProxy.discardHeld()
                AppLog.add("Discarded ${held.size} held unlocks.")
                showPage("offline", false)
            },
            chip("They were real - send", false) {
                OfflineProxy.releaseHeld()
                AppLog.add("Sending ${held.size} held unlocks.")
                showPage("offline", false)
            }
        ), ll(MATCH, WRAP, t = 10))
    }
    col.addView(gc, ll(MATCH, WRAP, t = 12))

    val cc = sectionCard("CACHE YOUR ROMS")
    cc.addView(tv(
        "Caches every Nintendo DS game in the folder: its achievement list and what you have already unlocked. " +
            "Cached games load and earn achievements with no internet at all - the unlocks are saved and only those " +
            "are sent to RetroAchievements later. No limit on how many games.", 11f, Pal.SUB
    ), ll(WRAP, WRAP, t = 8))
    val folderIn = field("ROM folder", prefs.getString("offline_folder", null) ?: (prefs.getString("folder", "/storage/emulated/0/ROMs") ?: ""))
    bindPref(folderIn, "offline_folder")
    cc.addView(folderIn, ll(MATCH, WRAP, t = 12))
    cc.addView(tv("${RomCacher.cachedGames()} games cached so far", 13f, Pal.TEXT, true), ll(WRAP, WRAP, t = 10))
    cc.addView(pill("Cache achievements", true) {
        startRomCache(folderIn.text.toString().trim(), false)
    }, ll(MATCH, WRAP, t = 12))
    cc.addView(rowOf(
        chip("Re-cache all", false) { startRomCache(folderIn.text.toString().trim(), true) },
        chip("Clear cache", false) {
            OfflineStore.clear("patch_")
            OfflineStore.clear("unlocks_")
            OfflineStore.clear("gameid_")
            OfflineStore.clear("rom_")
            showPage("offline", false)
        }
    ), ll(MATCH, WRAP, t = 8))
    if (RomCacher.running) cc.addView(chip("Show caching progress", false) { showCacheModal() }, ll(MATCH, WRAP, t = 8))
    cc.addView(tv(
        "Re-cache all refreshes games that are already cached (new achievements, newer unlocks). Games you play " +
            "while online are cached automatically too.", 11f, Pal.SUB
    ), ll(WRAP, WRAP, t = 8))
    col.addView(cc, ll(MATCH, WRAP, t = 12))
}


// ---------- emulators ----------
internal fun MainActivity.buildEmulatorsPage(col: LinearLayout) {
    val rc = sectionCard("RETROACHIEVEMENTS EMULATORS")
    rc.addView(tv(
        "Drastic RA can sit between RetroArch, PPSSPP or ARMSX2 and RetroAchievements (a local proxy on this device). " +
            "Then they get offline play like DraStic - unlocks are kept and sent later - and Drastic RA's popups, edge glow, " +
            "overlay and play time. Log in to RetroAchievements inside the emulator as usual.", 11f, Pal.SUB
    ), ll(WRAP, WRAP, t = 8))
    rc.addView(tv(
        "Keep Drastic RA watching (Start watching) while you play them: when it is not running they cannot reach " +
            "RetroAchievements - switch an emulator back to Direct if you stop using Drastic RA. Applying closes the emulator " +
            "if it is open, because it would overwrite the change when it exits.", 11f, 0xFFE0A100.toInt()
    ), ll(WRAP, WRAP, t = 8))
    val hide = prefs.getBoolean("emu_hide_own", false)
    rc.addView(rowOf(
        chip("Show their own popups too", !hide) { prefs.edit().putBoolean("emu_hide_own", false).apply(); showPage("emulators", false) },
        chip("Only Drastic RA popups", hide) { prefs.edit().putBoolean("emu_hide_own", true).apply(); showPage("emulators", false) }
    ), ll(MATCH, WRAP, t = 10))
    rc.addView(tv("(RetroArch and ARMSX2 can hide theirs; PPSSPP always shows its own.)", 10f, Pal.SUB), ll(WRAP, WRAP, t = 4))
    val status = tv("", 12f, Pal.ACC1, true)
    for (e in RaEmus.all) {
        val on = prefs.getBoolean("emu_on_" + e.key, false)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.addView(tv(e.label + (if (on) "   -   via Drastic RA" else "   -   direct"), 15f, Pal.TEXT, true), ll(WRAP, WRAP, t = 14))
        row.addView(rowOf(
            chip("Use Drastic RA", on) {
                status.text = "Setting up ${e.label}..."
                Thread {
                    val r = RaEmus.apply(this, e.key, true, prefs.getBoolean("emu_hide_own", false))
                    runOnUiThread { AppLog.add(r); status.text = r; showPage("emulators", false) }
                }.start()
            },
            chip("Direct", !on) {
                status.text = "Restoring ${e.label}..."
                Thread {
                    val r = RaEmus.apply(this, e.key, false, false)
                    runOnUiThread { AppLog.add(r); status.text = r; showPage("emulators", false) }
                }.start()
            }
        ), ll(MATCH, WRAP, t = 6))
        rc.addView(row, ll(MATCH, WRAP))
    }
    rc.addView(status, ll(WRAP, WRAP, t = 10))
    rc.addView(tv("Proxy: " + (if (ProxyServer.running) "running on " + RaEmus.HOST else "starts with Start watching"), 11f, Pal.SUB),
        ll(WRAP, WRAP, t = 6))
    col.addView(rc, ll(MATCH, WRAP))

    val tc = sectionCard("PS3 / PS VITA / PS4 TROPHIES")
    tc.addView(tv(
        "Trophies are read straight from ARMSX3 (PS3), Vita3K / Vita3K Plus (PS Vita) and Bachata S4 / S4+ (PS4) with root - " +
            "they show in the Trophies tab, pop up while you play (with Start watching) and work in the overlay. " +
            "Other RPCS3 / Vita3K / shadPS4 builds are found too.", 11f, Pal.SUB
    ), ll(WRAP, WRAP, t = 8))
    tc.addView(tv("${TrophyStore.games.size} games with trophies found", 14f, Pal.TEXT, true), ll(WRAP, WRAP, t = 10))
    val found = tv("", 12f, Pal.SUB)
    tc.addView(found, ll(WRAP, WRAP, t = 4))
    Thread {
        val pk = try { TrophyScanner.packages() } catch (_: Throwable) { emptyList() }
        runOnUiThread { found.text = if (pk.isEmpty()) "No trophy emulators detected." else "Detected: " + pk.joinToString(", ") }
    }.start()
    val extra = field("Extra folders (comma separated)", prefs.getString("trophy_dirs", "") ?: "")
    bindPref(extra, "trophy_dirs")
    tc.addView(tv("If your emulator keeps its data somewhere unusual, add that folder here.", 11f, Pal.SUB), ll(WRAP, WRAP, t = 10))
    tc.addView(extra, ll(MATCH, WRAP, t = 6))
    tc.addView(pill("Scan for trophies now", true) {
        found.text = "Scanning..."
        Thread {
            val games = try { TrophyScanner.scan(this, true) } catch (_: Throwable) { null }
            if (games != null) TrophyStore.set(this, games)
            runOnUiThread {
                AppLog.add("Trophy scan: ${TrophyStore.games.size} games")
                showPage("emulators", false)
                renderTab()
            }
        }.start()
    }, ll(MATCH, WRAP, t = 10))
    col.addView(tc, ll(MATCH, WRAP, t = 12))
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
    prefs.edit().putInt("acc1", a).putInt("acc2", b).putInt("acc_n", 2).apply()
    recreate()
}

private fun MainActivity.setAccent3(a: Int, b: Int, c: Int) {
    prefs.edit().putInt("acc1", a).putInt("acc2", b).putInt("acc3", c).putInt("acc_n", 3).apply()
    recreate()
}

private val presets2 = arrayOf(
    intArrayOf(0xFF7C7CFF.toInt(), 0xFFB57CFF.toInt()),
    intArrayOf(0xFF4FD1FF.toInt(), 0xFF5B7CFF.toInt()),
    intArrayOf(0xFFFF9A5B.toInt(), 0xFFFF5B9A.toInt()),
    intArrayOf(0xFF7BE05B.toInt(), 0xFF19C5A0.toInt()),
    intArrayOf(0xFFFF6FB5.toInt(), 0xFFFFB27C.toInt()),
    intArrayOf(0xFFFFD36B.toInt(), 0xFFF2994A.toInt()),
    intArrayOf(0xFF9BE15D.toInt(), 0xFF00A86B.toInt()),
    intArrayOf(0xFF9AA0B5.toInt(), 0xFF5E647A.toInt())
)

private val presets3 = arrayOf(
    intArrayOf(0xFF7C7CFF.toInt(), 0xFFB57CFF.toInt(), 0xFF5BC8FF.toInt()), // aurora
    intArrayOf(0xFFFF5F6D.toInt(), 0xFFFFC371.toInt(), 0xFFFF8E53.toInt()), // sunset
    intArrayOf(0xFF00C6FF.toInt(), 0xFF0072FF.toInt(), 0xFF7B61FF.toInt()), // deep sea
    intArrayOf(0xFFFF6FD8.toInt(), 0xFF3813C2.toInt(), 0xFF00D4FF.toInt()), // synthwave
    intArrayOf(0xFF43E97B.toInt(), 0xFF38F9D7.toInt(), 0xFF4FACFE.toInt()), // lagoon
    intArrayOf(0xFFF7971E.toInt(), 0xFFFFD200.toInt(), 0xFFFF4E50.toInt()), // fire
    intArrayOf(0xFFFC67FA.toInt(), 0xFFF4C4F3.toInt(), 0xFF7F7FD5.toInt()), // candy
    intArrayOf(0xFF8E2DE2.toInt(), 0xFF4A00E0.toInt(), 0xFFFF6CAB.toInt()), // galaxy
    intArrayOf(0xFF11998E.toInt(), 0xFF38EF7D.toInt(), 0xFFD4FC79.toInt()), // forest
    intArrayOf(0xFFFFFFFF.toInt(), 0xFF9AA0B5.toInt(), 0xFF5E647A.toInt())  // silver
)

/** A pleasant random gradient: colours spread around the wheel from a random start, vivid but not neon. */
private fun randomGradient(n: Int): IntArray {
    val r = java.util.Random()
    val h0 = r.nextFloat() * 360f
    // neighbouring (analogous) or wide (triadic-ish) spacing, picked at random
    val step = if (r.nextBoolean()) 28f + r.nextFloat() * 32f else 90f + r.nextFloat() * 40f
    return IntArray(n) { i ->
        Color.HSVToColor(floatArrayOf((h0 + i * step) % 360f, 0.55f + r.nextFloat() * 0.3f, 0.85f + r.nextFloat() * 0.15f))
    }
}

private fun MainActivity.swatches(three: Boolean): View {
    val hs = HorizontalScrollView(this)
    hs.isHorizontalScrollBarEnabled = false
    hs.isFocusable = false
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    for (p in if (three) presets3 else presets2) {
        val sel = if (three) Pal.acc3On && p[0] == Pal.ACC1 && p[1] == Pal.ACC2 && p[2] == Pal.ACC3
        else !Pal.acc3On && p[0] == Pal.ACC1 && p[1] == Pal.ACC2
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
        s.setOnClickListener { if (three) setAccent3(p[0], p[1], p[2]) else setAccent(p[0], p[1]) }
        s.pressable()
        row.addView(s, ll(dp(42), dp(42), r = 10))
    }
    hs.addView(row)
    return hs
}

/** Live preview strip of the accent gradient currently set. */
private fun MainActivity.accentStrip(): View {
    val v = View(this)
    v.background = accentFill(12)
    return v
}

internal fun MainActivity.buildLookPage(col: LinearLayout) {
    val th = sectionCard("THEME")
    th.addView(rowOf(
        chip("Light", Pal.light) { prefs.edit().putString("theme", "light").remove("bg_base").apply(); recreate() },
        chip("Dark", !Pal.light) { prefs.edit().putString("theme", "dark").remove("bg_base").apply(); recreate() }
    ), ll(MATCH, WRAP, t = 12))

    // ---- accent: 2 or 3 colour gradient, presets for the chosen kind only, randomizer, custom hex ----
    val three = Pal.acc3On
    th.addView(tv("Accent gradient", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 14))
    th.addView(accentStrip(), ll(MATCH, dp(12), t = 8))
    th.addView(rowOf(
        chip("2 colours", !three) {
            prefs.edit().putInt("acc_n", 2).apply()
            recreate()
        },
        chip("3 colours", three) {
            prefs.edit().putInt("acc_n", 3).apply()
            recreate()
        }
    ), ll(MATCH, WRAP, t = 10))
    th.addView(swatches(three), ll(MATCH, WRAP, t = 10))
    th.addView(pill("\u2728  Randomize gradient", true) {
        val g = randomGradient(if (three) 3 else 2)
        if (three) setAccent3(g[0], g[1], g[2]) else setAccent(g[0], g[1])
    }, ll(MATCH, WRAP, t = 10))
    val a1 = field("#7C7CFF", hexOf(Pal.ACC1))
    val a2 = field("#B57CFF", hexOf(Pal.ACC2))
    val a3 = field("#5BC8FF", hexOf(Pal.ACC3))
    th.addView(if (three) rowOf(a1, a2, a3) else rowOf(a1, a2), ll(MATCH, WRAP, t = 10))
    th.addView(chip("Apply custom accent", false) {
        val a = parseHex(a1.text.toString())
        val b = parseHex(a2.text.toString())
        val c = parseHex(a3.text.toString())
        if (a == null || b == null || (three && c == null)) AppLog.add("Colours must look like #RRGGBB")
        else if (three) setAccent3(a, b, c!!) else setAccent(a, b)
    }, ll(MATCH, WRAP, t = 8))
    col.addView(th, ll(MATCH, WRAP))

    // ---- controller highlight thickness ----
    val hc = sectionCard("SELECTION HIGHLIGHT")
    val hw = prefs.getInt("halo_w", 20)
    fun setW(v: Int) {
        prefs.edit().putInt("halo_w", v).apply()
        reloadHalo()
        showPage("look", false)
    }
    hc.addView(tv("Outline thickness of the controller / D-pad highlight", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 10))
    hc.addView(rowOf(
        chip("Thin", hw == 10) { setW(10) },
        chip("Normal", hw == 20) { setW(20) },
        chip("Thick", hw == 35) { setW(35) },
        chip("Extra", hw == 50) { setW(50) }
    ), ll(MATCH, WRAP, t = 8))
    col.addView(hc, ll(MATCH, WRAP, t = 12))

    // ---- holographic foil ----
    buildFoilCard(col)
    // ---- edge glow ----
    buildEdgeCard(col)

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


// ---------- holographic foil ----------
/** A SeekBar that saves an int pref live (no page rebuild, so dragging stays smooth). */
private fun MainActivity.prefSlider(label: String, key: String, def: Int, min: Int, max: Int, fmt: (Int) -> String,
                                    after: () -> Unit): View {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    val v = prefs.getInt(key, def)
    val t = tv(label + "   " + fmt(v), 12f, Pal.SUB, true)
    box.addView(t)
    val sb = SeekBar(this)
    sb.max = max - min
    sb.progress = (v - min).coerceIn(0, max - min)
    sb.progressTintList = ColorStateList.valueOf(Pal.ACC1)
    sb.thumbTintList = ColorStateList.valueOf(Pal.ACC2)
    sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
            t.text = label + "   " + fmt(p + min)
            if (fromUser) {
                prefs.edit().putInt(key, p + min).apply()
                after()
            }
        }

        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    })
    box.addView(sb, ll(MATCH, WRAP, t = 2))
    return box
}

/** Shows where the foil thinks the device is tilted: a dot that slides around a ring. */
private class TiltDotView(ctx: android.content.Context) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val tilt = FloatArray(2)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        FoilMotion.acquire(context)
    }

    override fun onDetachedFromWindow() {
        FoilMotion.release()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - context.dp(10)
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = context.dp(2).toFloat()
        p.color = Pal.TEXT.withAlpha(0x44)
        c.drawCircle(cx, cy, r, p)
        c.drawLine(cx - r, cy, cx + r, cy, p)
        c.drawLine(cx, cy - r, cx, cy + r, p)
        Foil.tilt(tilt)
        p.style = android.graphics.Paint.Style.FILL
        p.color = Pal.ACC1
        c.drawCircle(cx + tilt[0] / 1.5f * r, cy + tilt[1] / 1.5f * r, context.dp(9).toFloat(), p)
        postInvalidateOnAnimation()
    }
}

private fun MainActivity.foilSet(key: String, v: Boolean, rebuild: Boolean = true) {
    prefs.edit().putBoolean(key, v).apply()
    Foil.load(this)
    if (rebuild) recreate()
}

private fun MainActivity.buildFoilCard(col: LinearLayout) {
    Foil.load(this)
    val fc = sectionCard("HOLOGRAPHIC FOIL")
    fc.addView(tv("A holo-card shimmer on top of the glass, like a rare Pokemon card.", 11f, Pal.SUB), ll(WRAP, WRAP, t = 6))

    // live preview card: always shows the foil with the current settings
    val prev = GlassLinear(this)
    prev.radiusDp = 22
    prev.foilSurface = "preview"
    prev.orientation = LinearLayout.VERTICAL
    prev.gravity = Gravity.CENTER
    pad(prev, 16, 26, 16, 26)
    prev.addView(tv("HOLO PREVIEW", 18f, Pal.TEXT, true).also { it.letterSpacing = 0.2f; it.gravity = Gravity.CENTER })
    prev.addView(tv(Foil.styles.firstOrNull { it.first == Foil.style }?.second ?: "CD", 12f, Pal.SUB, true).also { it.gravity = Gravity.CENTER })
    fc.addView(prev, ll(MATCH, WRAP, t = 12))

    fc.addView(rowOf(
        chip("Foil on", Foil.on) { foilSet("foil_on", true) },
        chip("Foil off", !Foil.on) { foilSet("foil_on", false) }
    ), ll(MATCH, WRAP, t = 12))

    fc.addView(tv("Show it on", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 12))
    fc.addView(rowOf(
        chip("Home UI", Foil.ui) { foilSet("foil_ui", !Foil.ui) },
        chip("Popups", Foil.popup) { foilSet("foil_popup", !Foil.popup, false); showPage("look", false) },
        chip("Overlay", Foil.overlay) { foilSet("foil_overlay", !Foil.overlay, false); showPage("look", false) }
    ), ll(MATCH, WRAP, t = 8))

    fc.addView(tv("Style", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 12))
    var i = 0
    while (i < Foil.styles.size) {
        val a = Foil.styles[i]
        val b = Foil.styles.getOrNull(i + 1)
        val ca = chip(a.second, Foil.style == a.first) { prefs.edit().putString("foil_style", a.first).apply(); Foil.load(this); showPage("look", false) }
        val cb: View = if (b != null) chip(b.second, Foil.style == b.first) {
            prefs.edit().putString("foil_style", b.first).apply(); Foil.load(this); showPage("look", false)
        } else View(this)
        fc.addView(rowOf(ca, cb), ll(MATCH, WRAP, t = 8))
        i += 2
    }

    fc.addView(prefSlider("Opacity", "foil_opacity", 45, 5, 100, { "$it%" }) { Foil.load(this) }, ll(MATCH, WRAP, t = 14))
    fc.addView(prefSlider("Scale", "foil_scale", 100, 40, 300, { "$it%" }) { Foil.load(this) }, ll(MATCH, WRAP, t = 10))

    fc.addView(tv("Tint", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 12))
    var j = 0
    while (j < Foil.tints.size) {
        val row = ArrayList<View>()
        for (k in j until minOf(j + 3, Foil.tints.size)) {
            val t = Foil.tints[k]
            row.add(chip(t.second, Foil.tint == t.first) { prefs.edit().putString("foil_tint", t.first).apply(); Foil.load(this); showPage("look", false) })
        }
        while (row.size < 3) row.add(View(this))
        fc.addView(rowOf(*row.toTypedArray()), ll(MATCH, WRAP, t = 8))
        j += 3
    }
    if (Foil.tint != "none") {
        fc.addView(prefSlider("Tint strength", "foil_tint_amt", 50, 0, 100, { "$it%" }) { Foil.load(this) }, ll(MATCH, WRAP, t = 10))
    }

    fc.addView(tv("Finish", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 12))
    fc.addView(rowOf(
        chip("Soft", Foil.finish == "soft") { prefs.edit().putString("foil_finish", "soft").apply(); Foil.load(this); showPage("look", false) },
        chip("Shine", Foil.finish == "shine") { prefs.edit().putString("foil_finish", "shine").apply(); Foil.load(this); showPage("look", false) },
        chip("Vivid", Foil.finish == "vivid") { prefs.edit().putString("foil_finish", "vivid").apply(); Foil.load(this); showPage("look", false) }
    ), ll(MATCH, WRAP, t = 8))

    fc.addView(tv("Motion", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 12))
    fc.addView(rowOf(
        chip("Shift with tilt", Foil.motion) { foilSet("foil_motion", true, false); recreate() },
        chip("Shimmer animation", !Foil.motion) { foilSet("foil_motion", false, false); recreate() }
    ), ll(MATCH, WRAP, t = 8))
    if (Foil.motion) {
        fc.addView(prefSlider("Sensitivity", "foil_sens", 100, 20, 300, { String.format("%.1fx", it / 100f) }) { Foil.load(this) },
            ll(MATCH, WRAP, t = 10))
        fc.addView(tv("Test it: tilt your device - the dot and the preview follow.", 11f, Pal.SUB), ll(WRAP, WRAP, t = 8))
        val testRow = LinearLayout(this)
        testRow.orientation = LinearLayout.HORIZONTAL
        testRow.gravity = Gravity.CENTER_VERTICAL
        testRow.addView(TiltDotView(this), ll(dp(110), dp(110), r = 14))
        testRow.addView(chip("Recenter", false) { FoilMotion.recenter() }, ll(0, WRAP, 1f))
        fc.addView(testRow, ll(MATCH, WRAP, t = 8))
    } else {
        fc.addView(prefSlider("Shimmer speed", "foil_speed", 100, 20, 300, { String.format("%.1fx", it / 100f) }) { Foil.load(this) },
            ll(MATCH, WRAP, t = 10))
    }
    col.addView(fc, ll(MATCH, WRAP, t = 12))
}

// ---------- edge glow ----------
private fun MainActivity.buildEdgeCard(col: LinearLayout) {
    val ec = sectionCard("EDGE GLOW")
    ec.addView(tv("The glow around the screen for unlocks, popups and online notices.", 11f, Pal.SUB), ll(WRAP, WRAP, t = 6))
    val mode = EdgeStyle.mode(this)
    fun setMode(m: String) {
        prefs.edit().putString("edge_mode", m).apply()
        showPage("look", false)
    }
    ec.addView(rowOf(
        chip("Same as accent", mode == "accent") { setMode("accent") },
        chip("Rainbow", mode == "rainbow") { setMode("rainbow") },
        chip("Custom gradient", mode == "custom") { setMode("custom") }
    ), ll(MATCH, WRAP, t = 10))

    if (mode == "custom") {
        val stops = EdgeStyle.stops(this)
        val editor = GradientEditorView(this, stops)
        val wheel = ColorWheelView(this)
        val hex = field("#RRGGBB", "")
        val value = SeekBar(this)
        value.max = 100
        value.progressTintList = ColorStateList.valueOf(Pal.ACC1)
        value.thumbTintList = ColorStateList.valueOf(Pal.ACC2)
        var syncing = false
        fun showStop(st: EdgeStyle.Stop) {
            syncing = true
            wheel.setColor(st.color)
            value.progress = (wheel.value * 100).toInt()
            hex.setText(String.format("#%06X", st.color and 0xFFFFFF))
            syncing = false
        }
        editor.onSelect = { showStop(it) }
        editor.onChange = { done -> if (done) EdgeStyle.saveStops(this, stops) }
        wheel.onPick = { c, done ->
            editor.setSelectedColor(c)
            syncing = true
            hex.setText(String.format("#%06X", c and 0xFFFFFF))
            syncing = false
            if (done) EdgeStyle.saveStops(this, stops)
        }
        value.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || syncing) return
                wheel.value = p / 100f
                editor.setSelectedColor(wheel.color())
                hex.setText(String.format("#%06X", wheel.color() and 0xFFFFFF))
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                EdgeStyle.saveStops(this@buildEdgeCard, stops)
            }
        })
        ec.addView(tv("Tap the bar to add a colour stop, drag a stop to move it, tap one to pick its colour.", 11f, Pal.SUB),
            ll(WRAP, WRAP, t = 12))
        ec.addView(editor, ll(MATCH, WRAP, t = 6))
        ec.addView(wheel, ll(MATCH, WRAP, t = 10))
        ec.addView(tv("Brightness", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 8))
        ec.addView(value, ll(MATCH, WRAP, t = 2))
        ec.addView(rowOf(hex, chip("Set hex", false) {
            val c = parseHex(hex.text.toString())
            if (c == null) AppLog.add("Colours must look like #RRGGBB") else {
                editor.setSelectedColor(c)
                wheel.setColor(c)
                value.progress = (wheel.value * 100).toInt()
                EdgeStyle.saveStops(this, stops)
            }
        }), ll(MATCH, WRAP, t = 8))
        ec.addView(rowOf(
            chip("Remove stop", false) { if (editor.removeSelected()) EdgeStyle.saveStops(this, stops) },
            chip("Reset", false) {
                prefs.edit().remove("edge_stops").apply()
                showPage("look", false)
            }
        ), ll(MATCH, WRAP, t = 8))
        showStop(stops[0])
    }
    ec.addView(pill("Preview glow", true) { showEdgeGlow(null, 2600L) }, ll(MATCH, WRAP, t = 12))
    col.addView(ec, ll(MATCH, WRAP, t = 12))
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

    val oc = sectionCard("IN-GAME OVERLAY")
    oc.addView(tv(
        "While a game is being watched, tap the Drastic RA notification (or 'View achievements' in an opened popup) " +
            "to show the game's achievements over it: session and total play time, progress, and In progress / " +
            "Unlocked / Locked / All tabs. Its glass follows Popup glass in the Glass page.", 11f, Pal.SUB
    ), ll(WRAP, WRAP, t = 8))
    oc.addView(chip("Preview overlay", false) { previewOverlay() }, ll(MATCH, WRAP, t = 12))
    col.addView(oc, ll(MATCH, WRAP, t = 12))
}

// ---------- button mapping ----------
/**
 * Records a combo the RetroArch way: hold 2 or 3 buttons together, let go, done. Every key event goes to the
 * recorder first (so Start / B do not trigger their usual menu actions), and the in-game combo is paused meanwhile.
 */
private fun MainActivity.recordCombo(status: TextView, live: TextView) {
    Hotkeys.paused = true
    val held = LinkedHashSet<String>()
    val best = LinkedHashSet<String>()
    status.text = "Hold 2 or 3 buttons together on your controller, then let go to save."
    status.setTextColor(Pal.ACC1)
    live.text = "Waiting for buttons..."
    var rec: ((android.view.KeyEvent) -> Boolean)? = null
    rec = { e ->
        val b = Hotkeys.fromKeyCode(e.keyCode)
        if (b == null) {
            // Back (touch / system) cancels; anything else that is not a pad button is ignored
            val back = e.keyCode == android.view.KeyEvent.KEYCODE_BACK
            if (back && e.action == android.view.KeyEvent.ACTION_UP) {
                stopRecording()
                status.text = "Recording cancelled."
                status.setTextColor(Pal.SUB)
                live.text = ""
            }
            back
        } else {
            if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                if (held.size < Hotkeys.MAX_BUTTONS) held.add(b)
                if (held.size > best.size) {
                    best.clear()
                    best.addAll(held)
                }
                live.text = Hotkeys.label(held)
            } else if (e.action == android.view.KeyEvent.ACTION_UP) {
                held.remove(b)
                if (held.isEmpty()) {
                    if (best.size >= 2) {
                        val combo = Hotkeys.encode(best)
                        prefs.edit().putString("ov_combo_custom", combo).apply()
                        Hotkeys.save(this, combo)
                        stopRecording()
                        AppLog.add("Overlay combo set to " + Hotkeys.label(best))
                        showPage("buttons", false)
                    } else {
                        status.text = "Combos need at least 2 buttons - keep the first one held while pressing the next."
                        best.clear()
                        live.text = "Waiting for buttons..."
                    }
                } else {
                    live.text = Hotkeys.label(held)
                }
            }
            true
        }
    }
    keyRecorder = rec
    status.postDelayed({
        if (keyRecorder === rec) {
            stopRecording()
            status.text = "Nothing recorded (timed out). Tap Record to try again."
            status.setTextColor(Pal.SUB)
            live.text = ""
        }
    }, 15000L)
}

internal fun MainActivity.buildButtonsPage(col: LinearLayout) {
    val cur = Hotkeys.stored(this)
    val oc = sectionCard("ACHIEVEMENT OVERLAY COMBO")
    oc.addView(tv("Hold this while playing to show / hide the achievement overlay.", 11f, Pal.SUB), ll(WRAP, WRAP, t = 6))
    val big = tv(Hotkeys.label(cur), 22f, if (cur == "off") Pal.SUB else Pal.ACC1, true)
    oc.addView(big, ll(WRAP, WRAP, t = 10))

    oc.addView(tv("Presets", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 14))
    val opts = Hotkeys.presets + "off"
    var i = 0
    while (i < opts.size) {
        val a = opts[i]
        val b = opts.getOrNull(i + 1)
        val ca = chip(Hotkeys.label(a), cur == a) { Hotkeys.save(this, a); showPage("buttons", false) }
        val cb: View = if (b != null) chip(Hotkeys.label(b), cur == b) { Hotkeys.save(this, b); showPage("buttons", false) } else View(this)
        oc.addView(rowOf(ca, cb), ll(MATCH, WRAP, t = 8))
        i += 2
    }
    oc.addView(tv("Up + Down only works on pads whose d-pad can press both at once.", 11f, Pal.SUB), ll(WRAP, WRAP, t = 8))
    col.addView(oc, ll(MATCH, WRAP))

    // ---- custom combo: record it with the controller (RetroArch style) or type it ----
    val cc = sectionCard("CUSTOM COMBO")
    val custom = prefs.getString("ov_combo_custom", "") ?: ""
    val customValid = Hotkeys.parse(custom) != null
    val isCustom = cur != "off" && !Hotkeys.presets.contains(cur)
    cc.addView(tv(if (customValid) "Saved: " + Hotkeys.label(custom) + (if (isCustom) "   (in use)" else "") else "No custom combo yet",
        13f, Pal.TEXT, true), ll(WRAP, WRAP, t = 10))
    val status = tv("", 12f, Pal.SUB, true)
    val live = tv("", 20f, Pal.TEXT, true)
    cc.addView(rowOf(
        pill("Record combo", true) { recordCombo(status, live) },
        chip("Use saved", isCustom) {
            if (customValid) {
                Hotkeys.save(this, custom)
                showPage("buttons", false)
            } else {
                status.text = "Record or type a combo first."
            }
        }
    ), ll(MATCH, WRAP, t = 10))
    cc.addView(status, ll(WRAP, WRAP, t = 8))
    cc.addView(live, ll(WRAP, WRAP, t = 4))

    cc.addView(tv("Or type it", 12f, Pal.SUB, true), ll(WRAP, WRAP, t = 14))
    val typed = field("e.g. Select + R2", if (customValid) Hotkeys.label(custom) else "")
    cc.addView(typed, ll(MATCH, WRAP, t = 8))
    cc.addView(chip("Apply typed combo", false) {
        val set = Hotkeys.parse(typed.text.toString())
        if (set == null) {
            status.text = "Use 2 or 3 of: Start, Select, Home, L, R, L2, R2, L3, R3, A, B, X, Y, Up, Down, Left, Right (joined with +)."
            status.setTextColor(0xFFE5484D.toInt())
        } else {
            val combo = Hotkeys.encode(set)
            prefs.edit().putString("ov_combo_custom", combo).apply()
            Hotkeys.save(this, combo)
            AppLog.add("Overlay combo set to " + Hotkeys.label(set))
            showPage("buttons", false)
        }
    }, ll(MATCH, WRAP, t = 8))
    col.addView(cc, ll(MATCH, WRAP, t = 12))

    // ---- how long to hold ----
    val hc = sectionCard("HOLD TO TRIGGER")
    val hold = prefs.getInt("ov_combo_hold", 0)
    fun setHold(ms: Int) {
        prefs.edit().putInt("ov_combo_hold", ms).apply()
        Hotkeys.load(this)
        showPage("buttons", false)
    }
    hc.addView(rowOf(
        chip("Instant", hold == 0) { setHold(0) },
        chip("0.5 s", hold == 500) { setHold(500) },
        chip("1 s", hold == 1000) { setHold(1000) }
    ), ll(MATCH, WRAP, t = 12))
    hc.addView(tv(
        "A short hold avoids opening the overlay by accident. The combo is read straight from the controller " +
            "(root), so it works while DraStic is in front - and the buttons still reach the game, so pick one your " +
            "game does not need (some games soft-reset on Start + Select). Takes effect immediately.", 11f, Pal.SUB
    ), ll(WRAP, WRAP, t = 10))
    col.addView(hc, ll(MATCH, WRAP, t = 12))
}

// ---------- tools ----------
internal fun MainActivity.buildToolsPage(col: LinearLayout) {
    val tc = sectionCard("TOOLS")
    tc.addView(rowOf(chip("RAM scan", false) { runScan(false) }, chip("Live RAM test", false) { runScan(true) }), ll(MATCH, WRAP, t = 12))
    tc.addView(rowOf(chip("Share log", false) { shareLog() }, chip("Preview congrats", false) { previewCongrats() }), ll(MATCH, WRAP, t = 8))
    col.addView(tc, ll(MATCH, WRAP))

    val lg = sectionCard("LOG")
    val sv = ScrollView(this)
    sv.isFocusable = false
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
