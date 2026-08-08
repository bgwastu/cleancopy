package com.byebyemeta

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private val LightPurple = lightColorScheme(
    primary = Color(0xFF7655A6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF2B174D),
    secondary = Color(0xFF625B71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1E192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color.White,
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454E),
    outline = Color(0xFF79747E)
)

private val DarkPurple = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF3D2669),
    primaryContainer = Color(0xFF594080),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D3D),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454E),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99)
)

private val ByeByeMetaTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
)

@Composable
fun ByeByeMetaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) {
        DarkPurple
    } else {
        LightPurple
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ByeByeMetaTypography,
        content = content
    )
}
