package dev.agentmeter

import dev.agentmeter.importers.lenientJson
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

enum class LimitWarningLevel { WARNING, CRITICAL }

data class LimitWarning(
    val snapshot: RateLimitSnapshot,
    val threshold: Double,
    val level: LimitWarningLevel,
)

@Serializable
data class Settings(
    /** Percent values. The first reached turns the tray icon yellow, the second (and any higher) red. */
    val warningThresholds: List<Double> = listOf(80.0, 95.0),
    /** Closing the window hides it in the tray instead of quitting (ignored if the OS has no tray). */
    val minimizeToTray: Boolean = true,
    /** Start with the window hidden in the tray, e.g. when launched at login (ignored if the OS has no tray). */
    val startMinimized: Boolean = false,
) {
    companion object {
        private val log = Logger.getLogger("settings")

        fun parse(json: String): Settings = lenientJson.decodeFromString(serializer(), json)

        fun default(): Settings = Settings()

        /**
         * Loads settings from [file], creating a default file if missing.
         */
        fun load(file: Path): Settings {
            if (!file.exists()) {
                file.parent?.createDirectories()
                val defaultContent = """
                    {
                      "warningThresholds": [80.0, 95.0],
                      "minimizeToTray": true,
                      "startMinimized": false
                    }
                """.trimIndent()
                runCatching { file.writeText(defaultContent) }
                return default()
            }
            return runCatching { parse(file.readText()) }.getOrElse {
                log.warning("Invalid $file (${it.message}); using default settings")
                default()
            }
        }
    }
}

/**
 * Checks snapshots against warning thresholds (defaults 80% and 95%).
 * For each snapshot that reaches or exceeds one or more thresholds, generates a warning
 * corresponding to the highest reached threshold.
 * Reaching the first (lowest) threshold is a [LimitWarningLevel.WARNING], the second or any higher one is
 * [LimitWarningLevel.CRITICAL]; with a single threshold there is only WARNING.
 * Snapshots whose window has already reset ([RateLimitSnapshot.hasReset]) never warn.
 */
fun evaluateLimitWarnings(
    snapshots: List<RateLimitSnapshot>,
    thresholds: List<Double>,
    nowMs: Long = System.currentTimeMillis(),
): List<LimitWarning> {
    if (thresholds.isEmpty() || snapshots.isEmpty()) return emptyList()
    val sortedThresholds = thresholds.distinct().sorted()
    return snapshots.mapNotNull { s ->
        if (s.hasReset(nowMs)) return@mapNotNull null
        val reached = sortedThresholds.filter { s.usedPercent >= it }
        if (reached.isEmpty()) null
        else {
            val highest = reached.last()
            val level = if (sortedThresholds.indexOf(highest) >= 1) LimitWarningLevel.CRITICAL else LimitWarningLevel.WARNING
            LimitWarning(snapshot = s, threshold = highest, level = level)
        }
    }
}
