package com.drasticra

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** Short hints shown while the app warms up: RetroAchievements, DraStic, this app's own tools and retro play habits. */
object Tips {
    class Tip(val tag: String, val text: String)

    val all: List<Tip> = listOf(
        // this app
        Tip("DRASTIC RA", "Tap a game card to see every achievement in it: unlocked ones in colour, locked ones dimmed, with how to earn each."),
        Tip("DRASTIC RA", "Tap a popup while it is on screen to open it up: why you got it, and your total points."),
        Tip("DRASTIC RA", "L1 and R1 switch between Recent, Earned and Completion. START opens the settings menu and B goes back."),
        Tip("DRASTIC RA", "Press Start watching, then launch a game in DraStic. The app picks the running game up by itself."),
        Tip("DRASTIC RA", "Achievements you unlock while playing are celebrated the next time you open this app."),
        Tip("DRASTIC RA", "Recent shows how long you have played each game while Drastic RA was watching."),
        Tip("DRASTIC RA", "Set your ROM folder under Library. The app matches the running game to a .nds file in there."),
        Tip("DRASTIC RA", "Your Web API key comes from retroachievements.org, Settings, Keys. It fills Recent, Earned and Completion."),
        // tools
        Tip("TOOLS", "Test popups in the Popups menu: they appear 5 seconds later, so switch to your game to see them."),
        Tip("TOOLS", "RAM scan in Tools and log shows whether the app can find DraStic's memory. Run it with a game loaded."),
        Tip("TOOLS", "Share log in Tools and log is the quickest way to show someone what went wrong."),
        Tip("TOOLS", "Glass has a separate setting for popups, so they can be Full while the rest of the app stays Lite."),
        Tip("TOOLS", "Glass Off is plain solid cards. It is the lightest choice for older or hotter devices."),
        Tip("TOOLS", "Animations can run at 30 fps to save battery or 60 fps for smoothness."),
        Tip("TOOLS", "Appearance lets you use your own picture, GIF or video as the background."),
        // RetroAchievements
        Tip("RETROACHIEVEMENTS", "Hardcore mode is worth more, but it does not allow save states, cheats or slow motion."),
        Tip("RETROACHIEVEMENTS", "Some achievements are missable. Skim a game's list before a long playthrough."),
        Tip("RETROACHIEVEMENTS", "The Completion tab is sorted by progress, so the games you are closest to mastering come first."),
        Tip("RETROACHIEVEMENTS", "Many sets have achievements that need a harder difficulty, a New Game+ or a challenge run."),
        Tip("RETROACHIEVEMENTS", "Mastery means earning every core achievement in a game's set."),
        // DraStic
        Tip("DRASTIC", "Keep DraStic open while you play. The app reads its memory with root to follow your game."),
        Tip("DRASTIC", "Save states are handy for practice, but remember they are off limits in hardcore mode."),
        Tip("DRASTIC", "If a game runs slowly, try DraStic's frameskip setting, or switch off its high-resolution 3D option."),
        Tip("DRASTIC", "Try different screen layouts in DraStic. Some games are far easier to read with a bigger touch screen."),
        // retro play
        Tip("RETRO", "On a DS, the touch screen often hides the secrets. Tap around menus, maps and puzzles."),
        Tip("RETRO", "Keep more than one save slot. Old games are not forgiving when a save goes wrong."),
        Tip("RETRO", "Save before boss fights and before long dungeons, then forget about it until you need it."),
        Tip("RETRO", "Stuck on an achievement? Its description in the game list is usually a better hint than it looks.")
    )

    /** a shuffled order so every launch starts with a different hint */
    fun shuffled(): List<Tip> = all.shuffled()
}

/** Thin glowing gradient line with a bright comet that keeps sweeping along it. */
class GlowLineView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private var comet: LinearGradient? = null
    private var cometLen = 0f
    private val born = SystemClock.uptimeMillis()

    init {
        p.strokeCap = Paint.Cap.ROUND
        p.style = Paint.Style.STROKE
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0) return
        cometLen = w * 0.46f
        comet = LinearGradient(
            0f, 0f, cometLen, 0f,
            intArrayOf(
                Pal.ACC1.withAlpha(0), Pal.ACC1, Pal.ACC2,
                mixWhite(Pal.ACC2, 0.7f), Pal.ACC2.withAlpha(0)
            ),
            floatArrayOf(0f, 0.28f, 0.55f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun mixWhite(c: Int, t: Float): Int = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * t).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * t).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * t).toInt()
    )

    override fun onDraw(c: Canvas) {
        val sh = comet ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density
        val cy = h / 2f
        val pad = 14f * d
        val x0 = pad
        val x1 = w - pad
        val now = SystemClock.uptimeMillis()

        // faint track
        p.shader = null
        p.strokeWidth = 4f * d
        p.color = Pal.TEXT.withAlpha(if (Pal.light) 0x1A else 0x22)
        c.drawLine(x0, cy, x1, cy, p)

        // comet position: eased sweep left to right, then restarts just off the left end
        val t = ((now - born) % 1700L) / 1700f
        val e = t * t * (3f - 2f * t)
        val tx = x0 - cometLen + (x1 - x0 + cometLen) * e
        m.setTranslate(tx, 0f)
        sh.setLocalMatrix(m)
        p.shader = sh
        val breathe = 0.85f + 0.15f * kotlin.math.sin((now - born) / 380f)

        c.save()
        c.clipRect(x0 - 6f * d, 0f, x1 + 6f * d, h)
        // glow: the same comet drawn thicker and fainter, a few times
        p.strokeWidth = 18f * d
        p.alpha = (36 * breathe).toInt()
        c.drawLine(x0 - cometLen, cy, x1 + cometLen, cy, p)
        p.strokeWidth = 11f * d
        p.alpha = (70 * breathe).toInt()
        c.drawLine(x0 - cometLen, cy, x1 + cometLen, cy, p)
        p.strokeWidth = 6.5f * d
        p.alpha = (140 * breathe).toInt()
        c.drawLine(x0 - cometLen, cy, x1 + cometLen, cy, p)
        // bright core
        p.strokeWidth = 3.4f * d
        p.alpha = 255
        c.drawLine(x0 - cometLen, cy, x1 + cometLen, cy, p)
        c.restore()
        p.shader = null
        postInvalidateOnAnimation()
    }
}

/**
 * Covers the app while it warms up (glass shaders, drawer, pictures). A glowing gradient line, the app name and a
 * rotating hint. [fadeOut] dissolves it away.
 */
class LoadingView(ctx: Context, withTips: Boolean) : FrameLayout(ctx) {
    private val tips = Tips.shuffled()
    private var idx = 0
    private val tagView: TextView
    private val tipView: TextView
    private val box: LinearLayout
    private var running = false
    private var gone = false

    init {
        setBackgroundColor(Pal.BG)
        isClickable = true // swallow touches so nothing underneath can be pressed
        isFocusable = false

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        val side = ctx.dp(28)
        col.setPadding(side, 0, side, 0)

        val name = ctx.tv("DRASTIC RA", 13f, Pal.SUB, true)
        name.letterSpacing = 0.3f
        col.addView(name, ctx.ll(WRAP, WRAP, b = 14))

        val line = GlowLineView(ctx)
        col.addView(line, ctx.ll(min(ctx.dp(420), ctx.resources.displayMetrics.widthPixels - side * 2), ctx.dp(34)))

        box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        tagView = ctx.tv("", 10f, Pal.ACC1, true)
        tagView.letterSpacing = 0.16f
        tipView = ctx.tv("", 14f, Pal.TEXT)
        tipView.gravity = Gravity.CENTER
        tipView.setLineSpacing(0f, 1.15f)
        tipView.maxWidth = min(ctx.dp(380), ctx.resources.displayMetrics.widthPixels - side * 2)
        box.addView(tagView)
        box.addView(tipView, ctx.ll(WRAP, WRAP, t = 6))
        // reserve room for the longest hint so the line never jumps when the text changes
        box.minimumHeight = ctx.dp(112)
        if (withTips) col.addView(box, ctx.ll(WRAP, WRAP, t = 14))
        showTip()

        addView(col, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }

    private val rotate = object : Runnable {
        override fun run() {
            if (!running || gone) return
            if (box.parent == null) return // quick veil without hints
            idx = (idx + 1) % tips.size
            box.animate().alpha(0f).setDuration(220).withEndAction {
                showTip()
                box.animate().alpha(1f).setDuration(260).start()
            }.start()
            postDelayed(this, 3600)
        }
    }

    private fun showTip() {
        val t = tips[idx]
        tagView.text = t.tag
        tipView.text = t.text
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        postDelayed(rotate, 3600)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(rotate)
        super.onDetachedFromWindow()
    }

    /** Fades the screen away and then removes it from its parent. */
    fun fadeOut(after: () -> Unit) {
        if (gone) return
        gone = true
        running = false
        removeCallbacks(rotate)
        animate().alpha(0f).setDuration(380).withEndAction {
            (parent as? android.view.ViewGroup)?.removeView(this)
            after()
        }.start()
    }
}

/** Keeps the loading screen up until the work is done AND it has been visible long enough to read a hint. */
class LoadingGate(private val view: LoadingView, private val minMs: Long, private val onDone: () -> Unit) {
    private val start = SystemClock.uptimeMillis()
    private var warmDone = false
    private var dataDone = false
    private var finished = false

    fun warmReady() {
        warmDone = true
        check()
    }

    fun dataReady() {
        dataDone = true
        check()
    }

    private fun check() {
        if (finished || !warmDone || !dataDone) return
        val wait = max(0L, minMs - (SystemClock.uptimeMillis() - start))
        finished = true
        view.postDelayed({ view.fadeOut(onDone) }, wait)
    }
}
