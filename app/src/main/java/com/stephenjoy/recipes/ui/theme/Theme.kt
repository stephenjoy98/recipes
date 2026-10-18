package com.stephenjoy.recipes.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Fallback palette (warm "paprika" seed) for devices without Material You dynamic color.
private val LightColors = lightColorScheme(
    primary = Color(0xFF8B4A26),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBC9),
    onPrimaryContainer = Color(0xFF331200),
    secondary = Color(0xFF765849),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBC9),
    onSecondaryContainer = Color(0xFF2B160B),
    tertiary = Color(0xFF656032),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFECE4AA),
    onTertiaryContainer = Color(0xFF1F1C00),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF221A15),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF221A15),
    surfaceVariant = Color(0xFFF4DED4),
    onSurfaceVariant = Color(0xFF52443C),
    outline = Color(0xFF85736B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainer = Color(0xFFFCEAE3),
    surfaceContainerHigh = Color(0xFFF6E5DD),
    surfaceContainerHighest = Color(0xFFF0DFD7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB68F),
    onPrimary = Color(0xFF532200),
    primaryContainer = Color(0xFF6F3411),
    onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = Color(0xFFE6BEAB),
    onSecondary = Color(0xFF432A1E),
    secondaryContainer = Color(0xFF5C4033),
    onSecondaryContainer = Color(0xFFFFDBC9),
    tertiary = Color(0xFFD0C890),
    onTertiary = Color(0xFF353107),
    tertiaryContainer = Color(0xFF4C481C),
    onTertiaryContainer = Color(0xFFECE4AA),
    background = Color(0xFF1A120E),
    onBackground = Color(0xFFF0DFD7),
    surface = Color(0xFF1A120E),
    onSurface = Color(0xFFF0DFD7),
    surfaceVariant = Color(0xFF52443C),
    onSurfaceVariant = Color(0xFFD7C2B9),
    outline = Color(0xFFA08D85),
    surfaceContainerLowest = Color(0xFF140D09),
    surfaceContainerLow = Color(0xFF221A15),
    surfaceContainer = Color(0xFF271E19),
    surfaceContainerHigh = Color(0xFF322823),
    surfaceContainerHighest = Color(0xFF3D332D),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun RecipesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    // Material 3 Expressive: springy expressive motion on top of the M3 color/shape/type system.
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        shapes = AppShapes,
        content = content,
    )
}
