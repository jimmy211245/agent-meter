package dev.agentmeter.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Every colour the UI uses; screens read these instead of hard-coding values. */
@Immutable
class AgentMeterColors(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val positive: Color,
    val negative: Color,
    val warning: Color,
    val track: Color,
    val grid: Color,
    val limitLow: Color,
    val limitMid: Color,
    val limitHigh: Color,
)

val DarkColors = AgentMeterColors(
    dark = true,
    background = Color(0xFF0B0D12),
    surface = Color(0xFF11141B),
    surfaceRaised = Color(0xFF181C25),
    border = Color(0xFF222733),
    textPrimary = Color(0xFFECEEF4),
    textSecondary = Color(0xFFA0A7B8),
    textMuted = Color(0xFF8189A0),
    accent = Color(0xFF8B93FF),
    positive = Color(0xFF3DD68C),
    negative = Color(0xFFFF7A7A),
    warning = Color(0xFFF5C451),
    track = Color(0xFF1E232E),
    grid = Color(0xFF1C212B),
    limitLow = Color(0xFF3DD68C),
    limitMid = Color(0xFFF5C451),
    limitHigh = Color(0xFFFF5C5C),
)

val LightColors = AgentMeterColors(
    dark = false,
    background = Color(0xFFF5F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF0F2F6),
    border = Color(0xFFE3E6ED),
    textPrimary = Color(0xFF11141A),
    textSecondary = Color(0xFF566073),
    textMuted = Color(0xFF5F6A7D),
    accent = Color(0xFF5561E6),
    positive = Color(0xFF0B7A4C),
    negative = Color(0xFFD9363E),
    warning = Color(0xFF8F5F00),
    track = Color(0xFFE9ECF2),
    grid = Color(0xFFEDEFF4),
    limitLow = Color(0xFF22B573),
    limitMid = Color(0xFFE9B02E),
    limitHigh = Color(0xFFE5484D),
)

/**
 * One colour per agent, taken from the Okabe-Ito palette that stays distinguishable with the common kinds of
 * colour blindness (they also differ in lightness, so the stacked chart works in grayscale).
 * The light variants are darker so every one has at least 3:1 contrast against a white surface.
 */
private val agentPaletteDark = mapOf(
    "claude-code" to Color(0xFFE8743B), // vermillion
    "codex" to Color(0xFF1DB386), // bluish green
    "opencode" to Color(0xFFCC79A7), // reddish purple
    "deepseek-harness" to Color(0xFF56B4E9), // sky blue
    "antigravity" to Color(0xFFF0C230), // yellow
)
private val agentPaletteLight = mapOf(
    "claude-code" to Color(0xFFC9531A),
    "codex" to Color(0xFF0B8A63),
    "opencode" to Color(0xFFA8528A),
    "deepseek-harness" to Color(0xFF1F7FBF),
    "antigravity" to Color(0xFFB07F00),
)

fun agentColor(agent: String, dark: Boolean = true): Color =
    (if (dark) agentPaletteDark else agentPaletteLight)[agent] ?: Color(0xFF8C93A6)

/** Display name for an agent id. */
fun agentLabel(agent: String): String = when (agent) {
    "claude-code" -> "Claude Code"
    "codex" -> "Codex"
    "opencode" -> "OpenCode"
    "deepseek-harness" -> "DeepSeek Harness"
    "antigravity" -> "Antigravity"
    else -> agent
}
