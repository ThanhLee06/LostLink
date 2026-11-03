package com.example.lostlink.ui.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

internal val HomeLightColors = lightColorScheme(
    primary = Color(0xFF276B45), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F5ED), onPrimaryContainer = Color(0xFF1E5738),
    secondary = Color(0xFF315E83), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF2FA), onSecondaryContainer = Color(0xFF244D70),
    tertiary = Color(0xFF526158), onTertiary = Color.White,
    background = Color(0xFFF7FAF8), onBackground = Color(0xFF1D2922),
    surface = Color.White, onSurface = Color(0xFF1D2922),
    surfaceVariant = Color(0xFFEEF4F0), onSurfaceVariant = Color(0xFF526158),
    surfaceContainer = Color(0xFFEEF4F0), surfaceContainerLow = Color(0xFFF2F7F3),
    surfaceContainerHigh = Color(0xFFE8F0EB), surfaceContainerHighest = Color(0xFFE0EAE3),
    outline = Color(0xFF7B8C81), outlineVariant = Color(0xFFDCE6DF),
    error = Color(0xFFA52D2D), onError = Color.White,
    errorContainer = Color(0xFFFFF0ED), onErrorContainer = Color(0xFF7D2222),
    inverseSurface = Color(0xFF233329), inverseOnSurface = Color(0xFFEAF2EC),
    surfaceTint = Color(0xFF276B45),
)

internal val HomeDarkColors = darkColorScheme(
    primary = Color(0xFF9AD5AD), onPrimary = Color(0xFF103820),
    primaryContainer = Color(0xFF1C4530), onPrimaryContainer = Color(0xFFCCEDD6),
    secondary = Color(0xFFA9CAE6), onSecondary = Color(0xFF153449),
    secondaryContainer = Color(0xFF20394E), onSecondaryContainer = Color(0xFFD6E9F9),
    tertiary = Color(0xFFB2C3B6), onTertiary = Color(0xFF24362B),
    background = Color(0xFF101A14), onBackground = Color(0xFFE2ECE4),
    surface = Color(0xFF17231B), onSurface = Color(0xFFE2ECE4),
    surfaceVariant = Color(0xFF26382B), onSurfaceVariant = Color(0xFFB2C3B6),
    surfaceContainer = Color(0xFF1E2E23), surfaceContainerLow = Color(0xFF17231B),
    surfaceContainerHigh = Color(0xFF26382B), surfaceContainerHighest = Color(0xFF304536),
    outline = Color(0xFF849B8B), outlineVariant = Color(0xFF344A3B),
    error = Color(0xFFFFB4A9), onError = Color(0xFF650C0C),
    errorContainer = Color(0xFF58241F), onErrorContainer = Color(0xFFFFDAD3),
    inverseSurface = Color(0xFFE2ECE4), inverseOnSurface = Color(0xFF1D2922),
    surfaceTint = Color(0xFF9AD5AD),
)

internal object HomeTokens {
    val Gutter = 20.dp
    val CardPadding = 16.dp
    val SectionGap = 24.dp
    val Target = 48.dp
    val MaxWidth = 760.dp
    val CardShape = RoundedCornerShape(16.dp)
    val ImageShape = RoundedCornerShape(12.dp)
    const val ImageFadeMillis = 120
}

private fun style(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = height.sp,
    fontWeight = weight, letterSpacing = 0.sp,
)

internal val HomeTypography = Typography(
    headlineMedium = style(24, 32, FontWeight.SemiBold),
    titleLarge = style(22, 28, FontWeight.Bold).copy(letterSpacing = (-0.4).sp),
    titleMedium = style(18, 24, FontWeight.SemiBold),
    titleSmall = style(16, 22, FontWeight.SemiBold),
    bodyLarge = style(16, 24), bodyMedium = style(14, 20), bodySmall = style(13, 18),
    labelLarge = style(14, 20, FontWeight.SemiBold),
    labelMedium = style(13, 18, FontWeight.Medium), labelSmall = style(13, 18, FontWeight.Medium),
)

/** Home owns its palette; the shared Auth theme and all data contracts stay untouched. */
@Composable
fun HomeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) HomeDarkColors else HomeLightColors,
        typography = HomeTypography, content = content)
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Suppress("DEPRECATION")
@Composable
internal fun HomeSystemBars() {
    val view = LocalView.current
    val background = MaterialTheme.colorScheme.background
    if (!view.isInEditMode) DisposableEffect(view, background) {
        val window = view.context.findActivity()?.window
        if (window == null) onDispose {} else {
            val controller = WindowCompat.getInsetsController(window, view)
            val oldStatus = controller.isAppearanceLightStatusBars
            val oldNavigation = controller.isAppearanceLightNavigationBars
            val oldNavColor = window.navigationBarColor
            val oldStatusColor = window.statusBarColor
            val light = background == HomeLightColors.background
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = false
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Black.toArgb()
            onDispose {
                controller.isAppearanceLightStatusBars = oldStatus
                controller.isAppearanceLightNavigationBars = oldNavigation
                window.navigationBarColor = oldNavColor
                window.statusBarColor = oldStatusColor
            }
        }
    }
}
