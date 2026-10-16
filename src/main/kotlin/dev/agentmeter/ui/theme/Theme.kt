package dev.agentmeter.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalColors = staticCompositionLocalOf { DarkColors }
private val LocalType = staticCompositionLocalOf { DefaultType }

/** False renders every animation at its final value (used for the README screenshots). */
val LocalMotion = staticCompositionLocalOf { true }

/** Entry point to the design tokens: `AM.colors`, `AM.type`, `AM.spacing`, `AM.shapes`. */
object AM {
    val colors: AgentMeterColors @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: AgentMeterType @Composable @ReadOnlyComposable get() = LocalType.current
    val spacing = Spacing
    val shapes = Shapes
}

/** Follows the system light/dark setting unless [dark] is given. */
@Composable
fun AgentMeterTheme(dark: Boolean = isSystemInDarkTheme(), motion: Boolean = true, content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, background = c.background, surface = c.surface, onSurface = c.textPrimary,
            onBackground = c.textPrimary, surfaceVariant = c.track, onSurfaceVariant = c.textSecondary,
            outline = c.border, outlineVariant = c.border, error = c.negative,
        )
    } else {
        lightColorScheme(
            primary = c.accent, background = c.background, surface = c.surface, onSurface = c.textPrimary,
            onBackground = c.textPrimary, surfaceVariant = c.track, onSurfaceVariant = c.textSecondary,
            outline = c.border, outlineVariant = c.border, error = c.negative,
        )
    }
    CompositionLocalProvider(LocalColors provides c, LocalType provides DefaultType, LocalMotion provides motion) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Eases from 0 to [target] on first show and from the current value to a new target later. */
@Composable
fun animatedValue(target: Float, durationMs: Int = Motion.MEDIUM_MS): Float {
    if (!LocalMotion.current) return target
    val a = remember { Animatable(0f) }
    LaunchedEffect(target) { a.animateTo(target, tween(durationMs, easing = FastOutSlowInEasing)) }
    return a.value
}

/** 0 -> 1 every time [key] changes (bars growing in when the data or range changes). */
@Composable
fun animatedProgress(key: Any?, durationMs: Int = Motion.MEDIUM_MS): Float {
    if (!LocalMotion.current) return 1f
    val a = remember { Animatable(0f) }
    LaunchedEffect(key) {
        a.snapTo(0f)
        a.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
    }
    return a.value
}
