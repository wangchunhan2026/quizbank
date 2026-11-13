package com.daily.quizbank.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val BrandBlue = Color(0xFF1F6FEB)
private val BrandBlueDark = Color(0xFF8AB4F8)
private val BrandGreen = Color(0xFF10B981)
private val BrandAmber = Color(0xFFF59E0B)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E5FF),
    onPrimaryContainer = Color(0xFF0B2E5C),
    secondary = Color(0xFF475569),
    tertiary = BrandGreen,
    error = Color(0xFFDC2626),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFCBD5E1),
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF06213F),
    primaryContainer = Color(0xFF17365C),
    onPrimaryContainer = Color(0xFFD7E5FF),
    secondary = Color(0xFF94A3B8),
    tertiary = BrandGreen,
    error = Color(0xFFF87171),
    background = Color(0xFF0B0F16),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF141A23),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF1F2733),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF334155),
)

/** 题干和 AI 回答都是长文本，行高比 Material 默认值再松一点更好读。 */
private val AppTypography = Typography(
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
)

val WarningAmber = BrandAmber

@Composable
fun QuizBankTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // 状态栏底色交给 Activity 的 enableEdgeToEdge()，这里只调图标明暗，
            // 因为 window.statusBarColor 从 Android 15 起已经失效
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}
