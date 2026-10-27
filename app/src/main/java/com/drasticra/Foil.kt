package com.drasticra

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.WindowManager
import kotlin.math.sin

/**
 * Holographic foil, like a holo Pokemon card: a shimmering rainbow layer that sits on top of every glass surface
 * (above the glass, below the text). Styles, opacity, scale, tint, finish, where it shows, and tilt-to-shift.
 */
object Foil {
    val styles = listOf(
        "cd" to "CD", "stars" to "Stars", "shapes" to "Shapes", "dots" to "Dots",
        "cards" to "Cards", "candy" to "Candy wrapper", "patterns" to "Patterns"
    )
    val tints = listOf(
        "none" to "Rainbow", "accent" to "Accent", "gold" to "Gold", "silver" to "Silver",
        "rose" to "Rose", "ice" to "Ice", "emerald" to "Emerald"
    )

    var on = false
    var ui = true
    var popup = true
    var overlay = true
    var style = "cd"
    var opacity = 0.45f
    var scale = 1f
    var tint = "none"
    var tintAmount = 0.5f
    var finish = "soft"
    var motion = false
    var sens = 1f
    var speed = 1f
    private val born = SystemClock.uptimeMillis()

    fun load(ctx: Context) {
        val p = ctx.prefs()
        on = p.getBoolean("foil_on", false)
        ui = p.getBoolean("foil_ui", true)
        popup = p.getBoolean("foil_popup", true)
        overlay = p.getBoolean("foil_overlay", true)
        style = p.getString("foil_style", "cd") ?: "cd"
        opacity = p.getInt("foil_opacity", 45) / 100f
        scale = p.getInt("foil_scale", 100) / 100f
        tint = p.getString("foil_tint", "none") ?: "none"
        tintAmount = p.getInt("foil_tint_amt", 50) / 100f
        finish = p.getString("foil_finish", "soft") ?: "soft"
        motion = p.getBoolean("foil_motion", false)
        sens = p.getInt("foil_sens", 100) / 100f
        speed = p.getInt("foil_speed", 100) / 100f
    }

    /** "ui" (home, settings, game pages), "popup", "overlay", or "preview" (always on, for the settings preview). */
    fun enabledFor(surface: String): Boolean = when (surface) {
        "preview" -> true
        "ui" -> on && ui
        "popup" -> on && popup
        "overlay" -> on && overlay
        else -> false
    }

    fun styleIndex(): Float = styles.indexOfFirst { it.first == style }.coerceAtLeast(0).toFloat()

    fun tintColor(): Int = when (tint) {
        "accent" -> Pal.ACC1
        "gold" -> 0xFFFFC94A.toInt()
        "silver" -> 0xFFE3E7EF.toInt()
        "rose" -> 0xFFFF8FB8.toInt()
        "ice" -> 0xFF8FE3FF.toInt()
        "emerald" -> 0xFF4BE3A0.toInt()
        else -> 0
    }

    fun time(): Float = (SystemClock.uptimeMillis() - born) / 1000f

    /** Current tilt (-1.5 .. 1.5): from the device sensors when Motion is on, otherwise a back-and-forth shimmer. */
    fun tilt(out: FloatArray) {
        if (motion && FoilMotion.active) {
            out[0] = (FoilMotion.x * sens).coerceIn(-1.5f, 1.5f)
            out[1] = (FoilMotion.y * sens).coerceIn(-1.5f, 1.5f)
        } else {
            val t = time() * speed
            out[0] = sin(t * 0.9f) * 0.9f
            out[1] = sin(t * 0.6f + 1f) * 0.4f
        }
    }
}

/**
 * Device tilt for the foil (gravity sensor, accelerometer as fallback), smoothed and relative to how the device was
 * held when it started (or the last Recenter). Reference counted: whoever shows foil acquires it, then releases it.
 */
object FoilMotion : SensorEventListener {
    @Volatile var x = 0f
        private set
    @Volatile var y = 0f
        private set
    @Volatile var active = false
        private set
    private var users = 0
    private var sm: SensorManager? = null
    private var wm: WindowManager? = null
    private var baseX = Float.NaN
    private var baseY = 0f

    @Synchronized
    fun acquire(ctx: Context) {
        users++
        if (users > 1) return
        val app = ctx.applicationContext
        val m = app.getSystemService(SensorManager::class.java) ?: return
        wm = app.getSystemService(WindowManager::class.java)
        val s = m.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm = m
        baseX = Float.NaN
        active = m.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    @Synchronized
    fun release() {
        if (users <= 0) return
        users--
        if (users > 0) return
        sm?.unregisterListener(this)
        sm = null
        active = false
    }

    /** Takes the way the device is held right now as "flat". */
    fun recenter() {
        baseX = Float.NaN
    }

    @Suppress("DEPRECATION")
    override fun onSensorChanged(e: SensorEvent) {
        var gx = e.values[0] / 9.81f
        var gy = e.values[1] / 9.81f
        // follow the screen orientation so "tilt left" is always left on screen
        when (wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> { val t = gx; gx = -gy; gy = t }
            Surface.ROTATION_180 -> { gx = -gx; gy = -gy }
            Surface.ROTATION_270 -> { val t = gx; gx = gy; gy = -t }
        }
        if (baseX.isNaN()) {
            baseX = gx
            baseY = gy
        }
        val tx = -(gx - baseX) * 2.2f
        val ty = (gy - baseY) * 2.2f
        x += (tx - x) * 0.18f
        y += (ty - y) * 0.18f
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

private const val FOIL_AGSL = """
uniform float2 size;
uniform float2 origin;
uniform float time;
uniform float2 tilt;
uniform float scale;
uniform float style;
uniform float4 tint;
uniform float alpha;
uniform float shine;

float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * vnoise(p);
        p *= 2.03;
        a *= 0.5;
    }
    return v;
}
float3 spectrum(float t) {
    return 0.5 + 0.5 * cos(6.2831853 * (t + float3(0.0, 0.333, 0.667)));
}
float starMask(float2 p) {
    // exact signed distance to a 5-point star (sharp points, straight edges)
    p.y = -p.y;
    float2 k1 = float2(0.809016994, -0.587785252);
    float2 k2 = float2(-k1.x, k1.y);
    p.x = abs(p.x);
    p -= 2.0 * max(dot(k1, p), 0.0) * k1;
    p -= 2.0 * max(dot(k2, p), 0.0) * k2;
    p.x = abs(p.x);
    float r = 0.34;
    p.y -= r;
    float2 ba = 0.42 * float2(-k1.y, k1.x) - float2(0.0, 1.0);
    float h = clamp(dot(p, ba) / dot(ba, ba), 0.0, r);
    float d = length(p - ba * h) * sign(p.y * ba.x - p.x * ba.y);
    return 1.0 - smoothstep(-0.01, 0.015, d);
}
half4 main(float2 fc) {
    float2 sp = (fc + origin) / scale;
    float2 uv = fc / size;
    float2 tl = tilt;
    float phase = 0.0;
    float mask = 1.0;
    float spark = 0.0;
    if (style < 0.5) {
        // CD: rainbow arms around the centre with fine grooves
        float2 d = fc - size * 0.5;
        float ang = atan(d.y, d.x);
        float r = length(d) / scale;
        phase = ang / 6.2831853 * 2.0 + tl.x * 0.6 + tl.y * 0.35 + r * 0.004;
        mask = 0.55 + 0.45 * (0.5 + 0.5 * sin(r * 0.35 + tl.y * 3.0));
    } else if (style < 1.5) {
        // Stars
        float2 g = sp / 38.0;
        float2 id = floor(g);
        float2 f = fract(g) - 0.5;
        float h = hash21(id);
        f += (float2(hash21(id + 7.1), hash21(id + 3.7)) - 0.5) * 0.3;
        float s = starMask(f / (0.6 + 0.6 * h));
        phase = (sp.x + sp.y) * 0.004 + tl.x * 0.8 + h * 0.3;
        mask = 0.25 + 0.75 * s;
        spark = s * pow(0.5 + 0.5 * sin(time * 3.0 + h * 40.0 + tl.x * 6.0), 6.0);
    } else if (style < 2.5) {
        // Shapes: triangle tessellation, every facet its own shade
        float2 g = sp / 46.0;
        g.x += g.y * 0.57735;
        float2 id = floor(g);
        float2 f = fract(g);
        float tri = step(f.x, f.y);
        float h = hash21(id + tri * 13.0);
        float edge = min(min(f.x, f.y), min(1.0 - f.x, 1.0 - f.y));
        edge = min(edge, abs(f.x - f.y) * 0.7071);
        float line = 1.0 - smoothstep(0.02, 0.06, edge);
        phase = h * 0.6 + (sp.x - sp.y) * 0.003 + tl.x * 0.9 - tl.y * 0.5;
        mask = 0.45 + 0.35 * h + 0.4 * line;
    } else if (style < 3.5) {
        // Dots: halftone that swells in waves
        float2 g = sp / 14.0;
        float2 f = fract(g) - 0.5;
        float2 id = floor(g);
        float wave = 0.5 + 0.5 * sin((id.x + id.y) * 0.25 + tl.x * 4.0 + time * 0.5);
        float rad = 0.18 + 0.22 * wave;
        float dm = 1.0 - smoothstep(rad - 0.06, rad, length(f));
        phase = (sp.x * 0.6 + sp.y) * 0.004 + tl.y * 0.8;
        mask = 0.2 + 0.8 * dm;
    } else if (style < 4.5) {
        // Cards: diamond lattice like a card back
        float2 g = sp / 30.0;
        float2 r = float2(g.x + g.y, g.x - g.y) * 0.7071;
        float2 f = abs(fract(r) - 0.5);
        float dia = 1.0 - smoothstep(0.04, 0.09, abs(max(f.x, f.y) - 0.42));
        float inner = 1.0 - smoothstep(0.18, 0.22, f.x + f.y);
        phase = (g.x + g.y) * 0.05 + tl.x * 0.7 + tl.y * 0.7;
        mask = 0.35 + 0.5 * dia + 0.35 * inner;
    } else if (style < 5.5) {
        // Candy wrapper: crinkled foil facets catching the light
        float2 q = sp / 22.0;
        float n1 = fbm(q);
        float2 nrm = float2(fbm(q + float2(0.15, 0.0)) - n1, fbm(q + float2(0.0, 0.15)) - n1) * 14.0;
        phase = n1 * 1.8 + dot(nrm, tl) * 0.6 + tl.x * 0.4 + (sp.x + sp.y) * 0.0015;
        float facet = pow(clamp(0.5 + dot(nrm, normalize(float2(0.6, 0.8) + tl * 0.5)) * 0.6, 0.0, 1.0), 3.0);
        mask = 0.55 + 0.45 * facet;
        spark = facet * 0.5;
    } else {
        // Patterns: soft liquid pastel swirls
        float2 q = sp / 160.0;
        float2 w = float2(fbm(q + time * 0.03), fbm(q + float2(3.1, 7.7) - time * 0.02));
        float n = fbm(q + w * 1.6 + tl * 0.4);
        phase = n * 2.2 + tl.x * 0.3;
        mask = 0.85;
    }
    // bright sheen band that sweeps with the tilt
    float band = dot(uv - 0.5, normalize(float2(1.0, 0.6))) - tl.x * 0.6 - tl.y * 0.3;
    float sheen = exp(-band * band * 18.0);
    float3 col = spectrum(phase);
    col = mix(col, float3(1.0), 0.28);
    float lum = dot(col, float3(0.299, 0.587, 0.114));
    col = mix(col, tint.rgb * (0.6 + 0.6 * lum), tint.a);
    col += float3(sheen * 0.35 * shine + spark);
    float a = clamp(alpha * mask * (0.75 + 0.5 * sheen), 0.0, 1.0);
    return half4(half3(clamp(col, 0.0, 1.0) * a), half(a));
}
"""

/** Draws the foil for one view (each glass view owns one; the shader is per view because its uniforms are). */
class FoilPainter {
    private var shader: RuntimeShader? = null
    private var failed = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loc = IntArray(2)
    private val tilt = FloatArray(2)
    private val m = Matrix()
    private var fallback: LinearGradient? = null
    private var fbW = 0f

    fun draw(c: Canvas, v: View, rect: RectF) {
        val w = rect.width()
        val h = rect.height()
        if (w <= 0f || h <= 0f) return
        Foil.tilt(tilt)
        v.getLocationOnScreen(loc)
        if (Build.VERSION.SDK_INT >= 29) {
            paint.blendMode = when (Foil.finish) {
                "shine" -> BlendMode.SCREEN
                "vivid" -> BlendMode.OVERLAY
                else -> BlendMode.SRC_OVER
            }
        } else {
            paint.xfermode = if (Foil.finish == "soft") null
            else android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
        }
        if (Build.VERSION.SDK_INT >= 33 && !failed) {
            try {
                val s = shader ?: RuntimeShader(FOIL_AGSL).also { shader = it }
                s.setFloatUniform("size", w, h)
                s.setFloatUniform("origin", loc[0].toFloat(), loc[1].toFloat())
                s.setFloatUniform("time", Foil.time() * Foil.speed)
                s.setFloatUniform("tilt", tilt[0], tilt[1])
                s.setFloatUniform("scale", v.resources.displayMetrics.density * Foil.scale)
                s.setFloatUniform("style", Foil.styleIndex())
                val tc = Foil.tintColor()
                val ta = if (tc == 0) 0f else Foil.tintAmount
                s.setFloatUniform("tint", Color.red(tc) / 255f, Color.green(tc) / 255f, Color.blue(tc) / 255f, ta)
                s.setFloatUniform("alpha", Foil.opacity)
                s.setFloatUniform("shine", if (Foil.finish == "soft") 0.6f else 1f)
                paint.shader = s
                paint.alpha = 255
                c.drawRect(rect, paint)
                paint.shader = null
                return
            } catch (_: Throwable) {
                failed = true
            }
        }
        // older Android: a sliding rainbow sheet
        if (fallback == null || fbW != w) {
            fbW = w
            fallback = LinearGradient(0f, 0f, w * 1.2f, h * 0.6f,
                intArrayOf(0xFFFF9AA2.toInt(), 0xFFFFDAC1.toInt(), 0xFFE2F0CB.toInt(), 0xFFB5EAD7.toInt(),
                    0xFFC7CEEA.toInt(), 0xFFFF9AA2.toInt()), null, Shader.TileMode.MIRROR)
        }
        m.setTranslate((tilt[0] + loc[0] / 900f) * w * 0.8f, tilt[1] * h * 0.5f)
        fallback?.setLocalMatrix(m)
        paint.shader = fallback
        paint.alpha = (255 * Foil.opacity).toInt()
        c.drawRect(rect, paint)
        paint.shader = null
    }
}
