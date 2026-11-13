package com.daily.quizbank.service

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 悬浮窗的 UI 用原生 View 而不是 Compose。
 *
 * 原因：Compose 在 WindowManager 里需要一个手动挂载的 LifecycleOwner +
 * SavedStateRegistryOwner 才能正常工作，出错时表现是「窗口空白」而不是崩溃，很难查。
 * 悬浮窗本身只有十几个控件，用原生 View 建反而更短、更稳、启动更快。
 */
object OverlayUi {

    fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density).roundToInt()

    fun dp(context: Context, value: Int): Int = dp(context, value.toFloat())

    fun rounded(
        color: Int,
        radiusPx: Float,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidthPx: Int = 0,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusPx
        if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
    }

    fun text(
        context: Context,
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        includeFontPadding = false
    }

    fun button(
        context: Context,
        label: String,
        background: Int,
        textColor: Int = Color.WHITE,
    ): TextView = text(context, label, 14f, textColor, bold = true).apply {
        gravity = Gravity.CENTER
        val padH = dp(context, 14f)
        val padV = dp(context, 9f)
        setPadding(padH, padV, padH, padV)
        setBackground(rounded(background, dp(context, 10f).toFloat()))
        isClickable = true
        isFocusable = true
    }

    fun vertical(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun horizontal(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun spacer(context: Context, weight: Float = 1f): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, weight)
    }

    fun margin(context: Context, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(dp(context, left), dp(context, top), dp(context, right), dp(context, bottom))
        }
}
