package io.github.biofe.sensorlogger

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Velowind colours: a Material 3 scheme around one teal, in light and dark. Android's "colours from
 * the wallpaper" are deliberately not used, so the brand and the bike colours look the same on
 * every phone. All text pairs reach at least 4.5:1 contrast.
 */

private val Light = lightColorScheme(
    primary = Color(0xFF0A6C69),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEBE7),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF4A635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE8E3),
    onSecondaryContainer = Color(0xFF06201C),
    background = Color(0xFFF4F7F6),
    onBackground = Color(0xFF13201D),
    surface = Color(0xFFF4F7F6),
    onSurface = Color(0xFF13201D),
    surfaceVariant = Color(0xFFE7EEEC),
    onSurfaceVariant = Color(0xFF4E5E5A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEEF3F1),
    surfaceContainerHigh = Color(0xFFE7EEEC),
    surfaceContainerHighest = Color(0xFFE0E8E6),
    outline = Color(0xFF6F7E7B),
    outlineVariant = Color(0xFFC9D4D1),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF601410),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF62D3C7),
    onPrimary = Color(0xFF00201D),
    primaryContainer = Color(0xFF12403C),
    onPrimaryContainer = Color(0xFFCDEBE7),
    secondary = Color(0xFFB1CCC7),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF324B47),
    onSecondaryContainer = Color(0xFFCDE8E3),
    background = Color(0xFF0E1413),
    onBackground = Color(0xFFE2EBE9),
    surface = Color(0xFF0E1413),
    onSurface = Color(0xFFE2EBE9),
    surfaceVariant = Color(0xFF212B2A),
    onSurfaceVariant = Color(0xFFA3B3B0),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceContainerLow = Color(0xFF172020),
    surfaceContainer = Color(0xFF1B2423),
    surfaceContainerHigh = Color(0xFF212B2A),
    surfaceContainerHighest = Color(0xFF2B3634),
    outline = Color(0xFF8A9A97),
    outlineVariant = Color(0xFF3A4746),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFF9DEDC),
)

/** One bike colour: [color] behind the bike's icon (drawn in [on]), [tint] behind its rides. */
@Immutable
class BikeColor(val color: Color, val on: Color, val tint: Color)

/** Colours Material 3 has no role for: good and warning states, and the bike colours. */
@Immutable
class VelowindColors(
    val ok: Color,
    val okContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val bikes: List<BikeColor>,
) {
    fun bike(index: Int?): BikeColor = bikes[(index ?: bikes.lastIndex).mod(bikes.size)]
}

/*
 * Bike colours from the Okabe–Ito palette, chosen to stay distinguishable with colour blindness.
 * Order: orange, blue, bluish green, vermilion, sky blue, reddish purple, yellow, grey (the last
 * also stands for rides whose bike is unknown).
 */
private val bikeHues = listOf(
    Triple(0xFFE69F00, 0xFFFAECCC, 0xFF4F3E0D),
    Triple(0xFF0072B2, 0xFFCCE3F0, 0xFF0A3042),
    Triple(0xFF009E73, 0xFFCCECE3, 0xFF0A3D30),
    Triple(0xFFD55E00, 0xFFF7DFCC, 0xFF4A2A0D),
    Triple(0xFF56B4E9, 0xFFDDF0FB, 0xFF244453),
    Triple(0xFFCC79A7, 0xFFF5E4ED, 0xFF47323F),
    Triple(0xFFF0E442, 0xFFFCFAD9, 0xFF525221),
    Triple(0xFF7A7A7A, 0xFFE4E4E4, 0xFF2F3433),
)

/** Blue and grey are dark enough for a white icon; the others need a dark one. */
private val whiteIcon = setOf(1, 7)

private fun bikeColors(dark: Boolean) = bikeHues.mapIndexed { i, (c, light, darkTint) ->
    BikeColor(
        Color(c),
        if (i in whiteIcon) Color.White else Color(0xFF1A1A1A),
        Color(if (dark) darkTint else light),
    )
}

private val LightExtra = VelowindColors(
    ok = Color(0xFF1E7A46), okContainer = Color(0xFFD6F0DF),
    warn = Color(0xFF8A5100), warnContainer = Color(0xFFFFE7C7),
    bikes = bikeColors(dark = false),
)

private val DarkExtra = VelowindColors(
    ok = Color(0xFF7DDB9F), okContainer = Color(0xFF163A26),
    warn = Color(0xFFFFB95C), warnContainer = Color(0xFF4A3000),
    bikes = bikeColors(dark = true),
)

val LocalVelowind = staticCompositionLocalOf { LightExtra }

@Composable
fun VelowindTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dark) Dark else Light
    CompositionLocalProvider(LocalVelowind provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
