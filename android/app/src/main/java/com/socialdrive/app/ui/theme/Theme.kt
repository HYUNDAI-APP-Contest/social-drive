package com.socialdrive.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.socialdrive.app.domain.ThemeMode

private val NightColors = darkColorScheme(
    primary = HighlightMint,
    onPrimary = NightSurface,
    secondary = RouteBlue,
    tertiary = MomentAmber,
    background = NightSurface,
    onBackground = TextNightPrimary,
    surface = NightSurface,
    surfaceVariant = NightSurfaceRaised,
    onSurface = TextNightPrimary,
    onSurfaceVariant = TextNightSecondary,
    error = RouteError,
    outline = NightOutline,
)

private val DayColors = lightColorScheme(
    primary = HyundaiNavy,
    onPrimary = Color.White,
    secondary = RouteBlue,
    tertiary = MomentAmber,
    background = DaySurface,
    onBackground = TextDayPrimary,
    surface = DaySurfaceRaised,
    surfaceVariant = DaySurface,
    onSurface = TextDayPrimary,
    onSurfaceVariant = TextDaySecondary,
    error = RouteError,
    outline = DayOutline,
)

@Immutable
data class RouteMemoryColors(
    val route: Color,
    val highlight: Color,
    val moment: Color,
    val brand: Color,
)

val LocalRouteMemoryColors = staticCompositionLocalOf {
    RouteMemoryColors(RouteBlue, HighlightMint, MomentAmber, HyundaiNavy)
}

private val RouteMemoryShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun SocialDriveTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Day -> false
        ThemeMode.Night -> true
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalRouteMemoryColors provides RouteMemoryColors(RouteBlue, HighlightMint, MomentAmber, HyundaiNavy),
    ) {
        MaterialTheme(
            colorScheme = if (dark) NightColors else DayColors,
            typography = RouteMemoryTypography,
            shapes = RouteMemoryShapes,
            content = content,
        )
    }
}
