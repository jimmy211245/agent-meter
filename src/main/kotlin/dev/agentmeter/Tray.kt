package dev.agentmeter

import dev.agentmeter.ui.LIMIT_RESET_TEXT
import dev.agentmeter.ui.formatResetTime
import dev.agentmeter.ui.limitName
import dev.agentmeter.ui.usd
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/** Tray icon colour: normal, first threshold reached, second threshold reached. */
enum class TrayLevel(val rgb: Int) {
    NORMAL(0x2EAD6B), WARNING(0xF5B301), CRITICAL(0xE5484D)
}

/** Warnings from [evaluateLimitWarnings] already exclude windows that have reset. */
fun trayLevel(warnings: List<LimitWarning>): TrayLevel = when {
    warnings.any { it.level == LimitWarningLevel.CRITICAL } -> TrayLevel.CRITICAL
    warnings.isNotEmpty() -> TrayLevel.WARNING
    else -> TrayLevel.NORMAL
}

/** What closing / starting the window does, given the settings and what the OS offers. */
data class WindowPolicy(val closeToTray: Boolean, val startVisible: Boolean)

fun windowPolicy(settings: Settings, traySupported: Boolean) = WindowPolicy(
    closeToTray = settings.minimizeToTray && traySupported,
    startVisible = !(settings.startMinimized && traySupported),
)

/** Most used current window per agent; if all of an agent's windows have reset, its newest snapshot. */
fun highestLimitPerAgent(snapshots: List<RateLimitSnapshot>, nowMs: Long): List<RateLimitSnapshot> =
    snapshots.groupBy { it.agent }.toSortedMap().values.map { list ->
        list.filterNot { it.hasReset(nowMs) }.maxByOrNull { it.usedPercent } ?: list.maxBy { it.timestampMs }
    }

fun trayLines(
    d: Dashboard,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(Locale.Category.FORMAT),
): List<String> {
    val limits = highestLimitPerAgent(d.rateLimits, nowMs).map { s ->
        val head = "${s.agent.replaceFirstChar { it.uppercase() }} · ${limitName(s.window)}"
        if (s.hasReset(nowMs)) "$head: ${LIMIT_RESET_TEXT.replaceFirstChar { it.lowercase() }}"
        else "$head: ${s.usedPercent.toInt()}% · ${formatResetTime(s.resetsAtInstant, nowMs, zone, locale)}"
    }
    return listOf("Today: ${usd(d.today)}") + limits.ifEmpty { listOf("No limit data yet") }
}

/** Windows truncates tray tooltips at 127 characters. */
fun trayTooltip(lines: List<String>): String =
    ("Agent Meter\n" + lines.joinToString("\n")).let { if (it.length <= 127) it else it.take(126) + "…" }

/**
 * Decides when to show a desktop notification. A threshold notifies once per window and reset cycle;
 * crossing a higher threshold in the same cycle (yellow -> red) notifies again.
 * Thresholds already exceeded when the app starts are recorded silently.
 */
class NotificationTracker {
    private class Cycle(val resetsAt: Long?, var notifiedThreshold: Double)

    private val cycles = HashMap<Pair<String, String>, Cycle>()
    private var primed = false

    /** [warnings] must come from [evaluateLimitWarnings] so windows that have reset are already excluded. */
    fun update(warnings: List<LimitWarning>): List<LimitWarning> {
        val toNotify = mutableListOf<LimitWarning>()
        for (w in warnings) {
            val key = w.snapshot.agent to w.snapshot.window
            val cycle = cycles[key]?.takeIf { sameCycle(it.resetsAt, w.snapshot.resetsAt) }
            if (cycle == null || w.threshold > cycle.notifiedThreshold) {
                cycles[key] = Cycle(w.snapshot.resetsAt, w.threshold)
                toNotify += w
            }
        }
        val result = if (primed) toNotify else emptyList()
        primed = true
        return result
    }

    private fun sameCycle(a: Long?, b: Long?) =
        if (a == null || b == null) a == b else abs(a - b) < CYCLE_TOLERANCE_SECONDS

    private companion object {
        // resets_at can wobble by a few seconds between log lines; a new cycle shifts it by hours.
        const val CYCLE_TOLERANCE_SECONDS = 600L
    }
}

fun notificationText(
    w: LimitWarning,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(Locale.Category.FORMAT),
): Pair<String, String> {
    val s = w.snapshot
    val title = "${s.agent.replaceFirstChar { it.uppercase() }} ${limitName(s.window)} at ${s.usedPercent.toInt()}%"
    val body = "Threshold ${w.threshold.toInt()}% reached. ${formatResetTime(s.resetsAtInstant, nowMs, zone, locale)}"
    return title to body
}

/** Round badge in the level colour with three white bars; sized for hi-dpi tray slots. */
fun trayIconImage(level: TrayLevel, size: Int = 64): BufferedImage {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val u = size / 64.0
        g.color = java.awt.Color(level.rgb)
        g.fillOval((2 * u).toInt(), (2 * u).toInt(), (60 * u).toInt(), (60 * u).toInt())
        g.color = java.awt.Color.WHITE
        for ((i, h) in listOf(16, 26, 36).withIndex()) {
            val x = (19 + i * 10) * u
            g.fillRoundRect(x.toInt(), ((46 - h) * u).toInt(), (7 * u).toInt(), (h * u).toInt(), (2 * u).toInt(), (2 * u).toInt())
        }
    } finally {
        g.dispose()
    }
    return img
}
