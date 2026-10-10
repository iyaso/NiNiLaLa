package dev.iyas.ninilala.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = NiNiGreen,
    onPrimary = CardWhite,
    primaryContainer = NiNiGreen.copy(alpha = 0.2f),
    onPrimaryContainer = TextDark,
    secondary = NiNiPink,
    onSecondary = CardWhite,
    background = CreamBlush,
    onBackground = TextDark,
    surface = CardWhite,
    onSurface = TextDark,
    surfaceVariant = SurfaceLiftLight,
    onSurfaceVariant = TextDark,
    outline = LightLavender,
    error = DangerSoft,
    onError = CardWhite,
    scrim = Color.Black.copy(alpha = 0.4f),
)

private val DarkColors = darkColorScheme(
    primary = NiNiGreenDark,
    onPrimary = DeepNight,
    primaryContainer = NiNiGreenDim,
    onPrimaryContainer = MutedWhite,
    secondary = NiNiGreenDim,
    onSecondary = MutedWhite,
    background = DeepNight,
    onBackground = MutedWhite,
    surface = DarkLavender,
    onSurface = MutedWhite,
    surfaceVariant = SurfaceLiftDark,
    onSurfaceVariant = MutedWhite,
    outline = DarkLavender,
    error = DangerSoft,
    onError = DeepNight,
    scrim = Color.Black.copy(alpha = 0.6f),
)

private val NiNiLaLaShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp),
)

@Composable
fun NiNiLaLaTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        shapes = NiNiLaLaShapes,
        content = content,
    )
}
