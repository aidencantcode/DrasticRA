package com.drasticra

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

object Pal {
    val BG = 0xFF0E1016.toInt()
    val CARD = 0xFF171A22.toInt()
    val FIELD = 0xFF222633.toInt()
    val TEXT = 0xFFF2F3F8.toInt()
    val SUB = 0xFF8C93A8.toInt()
    val ACC1 = 0xFF7C7CFF.toInt()
    val ACC2 = 0xFFB57CFF.toInt()
    val GOOD = 0xFF4ADE80.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radiusDp: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(radiusDp).toFloat()
    return g
}

fun Context.gradientPill(radiusDp: Int): GradientDrawable {
    val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.ACC1, Pal.ACC2))
    g.cornerRadius = dp(radiusDp).toFloat()
    return g
}

fun withRipple(content: Drawable): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null)
