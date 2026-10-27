package com.drasticra

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Button combos (RetroArch-style hotkeys). Buttons are stored by canonical names ("START", "SELECT", "L1", "UP", ...)
 * so the same combo works for the in-app recorder (Android key events) and the in-game watcher (raw Linux input).
 */
object Hotkeys {
    const val DEFAULT = "START+SELECT"
    const val MAX_BUTTONS = 3

    /** Two-button presets offered on the Button mapping page. */
    val presets = listOf("START+SELECT", "L1+R1", "UP+DOWN", "SELECT+L1", "SELECT+R1", "L3+R3", "SELECT+Y")

    private val order = listOf(
        "SELECT", "START", "HOME", "L1", "R1", "L2", "R2", "L3", "R3", "A", "B", "X", "Y", "UP", "DOWN", "LEFT", "RIGHT"
    )

    private val pretty = mapOf(
        "START" to "Start", "SELECT" to "Select", "HOME" to "Home", "L1" to "L", "R1" to "R", "L2" to "L2", "R2" to "R2",
        "L3" to "L3", "R3" to "R3", "A" to "A", "B" to "B", "X" to "X", "Y" to "Y",
        "UP" to "Up", "DOWN" to "Down", "LEFT" to "Left", "RIGHT" to "Right"
    )

    /** What players may type in the custom field, mapped to canonical names. */
    private val aliases = mapOf(
        "START" to "START", "SELECT" to "SELECT", "BACK" to "SELECT", "HOME" to "HOME", "GUIDE" to "HOME", "MODE" to "HOME",
        "L" to "L1", "L1" to "L1", "LB" to "L1", "R" to "R1", "R1" to "R1", "RB" to "R1",
        "L2" to "L2", "LT" to "L2", "ZL" to "L2", "R2" to "R2", "RT" to "R2", "ZR" to "R2",
        "L3" to "L3", "LS" to "L3", "R3" to "R3", "RS" to "R3",
        "A" to "A", "B" to "B", "X" to "X", "Y" to "Y",
        "UP" to "UP", "DOWN" to "DOWN", "LEFT" to "LEFT", "RIGHT" to "RIGHT",
        "DPADUP" to "UP", "DPADDOWN" to "DOWN", "DPADLEFT" to "LEFT", "DPADRIGHT" to "RIGHT"
    )

    /** Overlay combo in use (empty = off). Read by the watcher thread. */
    @Volatile var overlayCombo: Set<String> = setOf("START", "SELECT")
    @Volatile var holdMs = 0L
    /** true while the Button mapping page is recording, so the in-game combo cannot fire meanwhile */
    @Volatile var paused = false

    fun load(ctx: Context) {
        val p = ctx.prefs()
        val v = p.getString("ov_combo", DEFAULT) ?: DEFAULT
        overlayCombo = if (v == "off") emptySet() else parse(v) ?: parse(DEFAULT)!!
        holdMs = p.getInt("ov_combo_hold", 0).toLong()
    }

    /** Current combo as stored ("off", a preset or a custom one). */
    fun stored(ctx: Context): String = ctx.prefs().getString("ov_combo", DEFAULT) ?: DEFAULT

    fun save(ctx: Context, combo: String) {
        ctx.prefs().edit().putString("ov_combo", combo).apply()
        load(ctx)
    }

    fun sorted(s: Collection<String>): List<String> = s.distinct().sortedBy { val i = order.indexOf(it); if (i < 0) 99 else i }

    fun encode(s: Collection<String>): String = sorted(s).joinToString("+")

    /** "select + r2", "L+R", "Start, Select" ... -> canonical set, or null when it is not a valid 2-3 button combo. */
    fun parse(text: String): Set<String>? {
        val parts = text.uppercase().split('+', ',', '&', ' ').map { it.trim().replace("_", "").replace("-", "") }
            .filter { it.isNotEmpty() }
        val out = LinkedHashSet<String>()
        for (p in parts) out.add(aliases[p] ?: return null)
        return if (out.size in 2..MAX_BUTTONS) out else null
    }

    fun label(s: Collection<String>): String =
        if (s.isEmpty()) "Off" else sorted(s).joinToString(" + ") { pretty[it] ?: it }

    fun label(stored: String): String = if (stored == "off") "Off" else label(parse(stored) ?: emptySet())

    /** Android key event -> canonical button (used by the in-app recorder). */
    fun fromKeyCode(code: Int): String? = when (code) {
        KeyEvent.KEYCODE_BUTTON_START -> "START"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "SELECT"
        KeyEvent.KEYCODE_BUTTON_MODE -> "HOME"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_DPAD_UP -> "UP"
        KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        else -> null
    }

    /** Linux input code name (or hex number) from `getevent -l` -> canonical button. */
    fun fromLinux(code: String): String? = when (code) {
        "BTN_START", "013b" -> "START"
        "BTN_SELECT", "013a" -> "SELECT"
        "BTN_MODE", "013c" -> "HOME"
        "BTN_TL", "0136" -> "L1"
        "BTN_TR", "0137" -> "R1"
        "BTN_TL2", "0138" -> "L2"
        "BTN_TR2", "0139" -> "R2"
        "BTN_THUMBL", "013d" -> "L3"
        "BTN_THUMBR", "013e" -> "R3"
        "BTN_A", "BTN_SOUTH", "BTN_GAMEPAD", "0130" -> "A"
        "BTN_B", "BTN_EAST", "0131" -> "B"
        "BTN_X", "BTN_NORTH", "0133" -> "X"
        "BTN_Y", "BTN_WEST", "0134" -> "Y"
        "BTN_DPAD_UP", "KEY_UP", "0220", "0067" -> "UP"
        "BTN_DPAD_DOWN", "KEY_DOWN", "0221", "006c" -> "DOWN"
        "BTN_DPAD_LEFT", "KEY_LEFT", "0222", "0069" -> "LEFT"
        "BTN_DPAD_RIGHT", "KEY_RIGHT", "0223", "006a" -> "RIGHT"
        else -> null
    }
}

/**
 * Watches the controller while you play. DraStic has the focus, so Android never hands its key presses to us;
 * instead a root `getevent` stream reports the raw buttons of every input device. The buttons still reach the game
 * (just like RetroArch hotkeys without an enable key). When the overlay combo is held, [onCombo] runs on the main thread.
 */
class HotkeyWatcher(private val ctx: Context, private val onCombo: () -> Unit) {
    @Volatile private var stop = false
    private var proc: Process? = null
    private val ui = Handler(Looper.getMainLooper())
    private val down = HashSet<String>()
    private var fired = false
    private var heldSince = 0L

    fun start() {
        Hotkeys.load(ctx)
        Thread {
            var failures = 0
            while (!stop && failures < 5) {
                val began = SystemClock.elapsedRealtime()
                try {
                    // exec: the root shell becomes getevent, so closing our end ends it too
                    val p = ProcessBuilder("su", "-c", "exec getevent -ql").redirectErrorStream(true).start()
                    proc = p
                    p.inputStream.bufferedReader().use { r ->
                        while (!stop) {
                            val line = r.readLine() ?: break
                            handle(line)
                        }
                    }
                } catch (_: Exception) {
                }
                if (stop) break
                // restart a stream that died, but give up if it keeps failing straight away
                failures = if (SystemClock.elapsedRealtime() - began < 5000L) failures + 1 else 0
                synchronized(down) { down.clear() }
                Thread.sleep(1500L)
            }
            if (!stop) AppLog.add("Button combo watcher stopped (getevent unavailable).")
        }.start()
    }

    fun stop() {
        stop = true
        try {
            proc?.destroy()
        } catch (_: Exception) {
        }
    }

    private fun handle(line: String) {
        // "/dev/input/event3: EV_KEY       BTN_START            DOWN"  (device prefix may be missing)
        val t = line.trim().split(Regex("\\s+"))
        if (t.size < 3) return
        val n = t.size
        val type = t[n - 3]
        val code = t[n - 2]
        val value = t[n - 1]
        when (type) {
            "EV_KEY", "0001" -> {
                val b = Hotkeys.fromLinux(code) ?: return
                when (value) {
                    "DOWN", "00000001" -> press(b, true)
                    "UP", "00000000" -> press(b, false)
                }
            }
            "EV_ABS", "0003" -> {
                // most built-in d-pads are a hat axis: -1 / 0 / +1
                val v = value.toLongOrNull(16)?.toInt() ?: return
                when (code) {
                    "ABS_HAT0X", "0010" -> {
                        press("LEFT", v < 0)
                        press("RIGHT", v in 1..0x7fffffff)
                    }
                    "ABS_HAT0Y", "0011" -> {
                        press("UP", v < 0)
                        press("DOWN", v in 1..0x7fffffff)
                    }
                }
            }
        }
    }

    private fun press(b: String, isDown: Boolean) {
        val combo = Hotkeys.overlayCombo
        synchronized(down) {
            if (isDown) down.add(b) else down.remove(b)
            if (combo.isEmpty() || Hotkeys.paused) return
            val held = down.containsAll(combo)
            if (!held) {
                fired = false
                heldSince = 0L
                return
            }
            if (fired || heldSince != 0L) return
            heldSince = SystemClock.uptimeMillis()
            val since = heldSince
            val hold = Hotkeys.holdMs
            ui.postDelayed({ confirm(since) }, hold)
        }
    }

    /** Fires once the combo has been held for the chosen time (immediately with "Instant"). */
    private fun confirm(since: Long) {
        synchronized(down) {
            if (fired || heldSince != since || !down.containsAll(Hotkeys.overlayCombo)) return
            fired = true
        }
        onCombo()
    }
}
