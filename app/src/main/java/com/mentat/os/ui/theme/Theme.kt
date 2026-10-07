package com.mentat.os.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Indigo = Color(0xFF3F3A8C)
private val IndigoLight = Color(0xFFC5C0FF)
private val Amber = Color(0xFFB8860B)
private val AmberLight = Color(0xFFFFC857)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3DFFF),
    onPrimaryContainer = Color(0xFF14104A),
    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE8B0),
    onSecondaryContainer = Color(0xFF3A2A00),
    tertiary = Color(0xFF2E7D6B),
    background = Color(0xFFFCFBFF),
    surface = Color(0xFFFCFBFF),
    surfaceVariant = Color(0xFFE5E1EC),
)

private val DarkColors = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF231D6E),
    primaryContainer = Color(0xFF3A3487),
    onPrimaryContainer = Color(0xFFE3DFFF),
    secondary = AmberLight,
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF5B4300),
    onSecondaryContainer = Color(0xFFFFE8B0),
    tertiary = Color(0xFF7FD8C1),
    background = Color(0xFF131218),
    surface = Color(0xFF131218),
)

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    )
}

@Composable
fun MentatTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
