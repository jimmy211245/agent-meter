package dev.agentmeter

import java.time.Instant

/**
 * One billable model response. Only numbers and metadata — never prompt or response text.
 *
 * Token fields are disjoint so cost = sum(field * price):
 * [inputTokens] excludes cache reads/writes, [outputTokens] includes reasoning/thinking.
 */
data class UsageEvent(
    val agent: String,
    /** Unique per agent; re-importing the same response overwrites instead of double counting. */
    val eventKey: String,
    val sessionId: String,
    val timestampMs: Long,
    val model: String,
    val project: String?,
    val inputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cacheWrite1hTokens: Long = 0,
    val outputTokens: Long = 0,
    /** Upstream provider if the log names one (e.g. an OpenCode providerID); informational only. */
    val provider: String? = null,
    /** Cost in USD as reported by the agent itself. Only used if > 0, otherwise it is computed from prices.json. */
    val reportedCostUsd: Double? = null,
)

/**
 * Rate limit snapshot for an agent window (e.g. primary 5h, secondary weekly).
 * Only numbers and metadata — no prompt or response text.
 */
data class RateLimitSnapshot(
    val agent: String,
    val window: String,
    val usedPercent: Double,
    /** Epoch seconds when the limit resets, or null if unknown. */
    val resetsAt: Long?,
    /** Timestamp in epoch milliseconds when the snapshot was recorded. */
    val timestampMs: Long,
) {
    val resetsAtInstant: Instant?
        get() = resetsAt?.let {
            if (it > 100_000_000_000L) Instant.ofEpochMilli(it) else Instant.ofEpochSecond(it)
        }

    /**
     * True if the window reset after this snapshot was taken, so [usedPercent] is outdated
     * and nothing newer has been logged yet.
     */
    fun hasReset(nowMs: Long): Boolean {
        val reset = resetsAtInstant?.toEpochMilli() ?: return false
        return reset <= nowMs && timestampMs < reset
    }

    /** A window that has reset and was last seen over a day ago (e.g. from old logs) is not worth showing. */
    fun isObsolete(nowMs: Long): Boolean = hasReset(nowMs) && nowMs - timestampMs > OBSOLETE_AFTER_MS

    private companion object {
        const val OBSOLETE_AFTER_MS = 24 * 3_600_000L
    }
}

/**
 * Label for a rate limit window from its length: 300 -> "5h", 10080 -> "7d", 90 -> "90m".
 * Logs name windows "primary"/"secondary" but older ones put the 7-day window in "primary",
 * so the length is the only reliable identity.
 */
fun windowLabel(minutes: Long): String = when {
    minutes >= 1440 && minutes % 1440 == 0L -> "${minutes / 1440}d"
    minutes >= 60 && minutes % 60 == 0L -> "${minutes / 60}h"
    else -> "${minutes}m"
}

