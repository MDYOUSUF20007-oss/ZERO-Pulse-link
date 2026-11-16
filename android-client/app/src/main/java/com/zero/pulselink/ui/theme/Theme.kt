package com.zero.pulselink.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryCyan,
    secondary = AccentGreen,
    tertiary = WarningAmber,
    background = AmoledBackground,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onPrimary = AmoledBackground,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

// Clean minimalist typography for "Zero" branding
private val ZeroTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = (-0.5).sp,
        color = TextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = (-0.2).sp,
        color = TextPrimary
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp,
        color = PrimaryCyan
    )
)

@Composable
fun ZeroPulseLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = ZeroTypography,
        content = content
    )
}
