package dev.agentmeter

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class Range(val label: String, val days: Long?) {
    TODAY("Today", 1), WEEK("7 days", 7), MONTH("30 days", 30), ALL("All time", null);

    fun includes(day: LocalDate, today: LocalDate) = days == null || day > today.minusDays(days)
}

data class Breakdown(val name: String, val cost: Double, val tokens: Long)

data class SessionCost(
    val agent: String,
    val sessionId: String,
    val project: String?,
    val models: Set<String>,
    val firstTs: Long,
    val lastTs: Long,
    val cost: Double,
    val tokens: Long,
)

data class Dashboard(
    val today: Double,
    val thisWeek: Double,
    val thisMonth: Double,
    val byAgent: List<Breakdown>,
    val byModel: List<Breakdown>,
    val byProject: List<Breakdown>,
    /** Cost per day and agent for the last [historyDays] days, oldest first. */
    val daily: List<Pair<LocalDate, Map<String, Double>>>,
    val topSessions: List<SessionCost>,
    /** Models with usage but no price entry; their cost counts as 0. */
    val unpricedModels: Set<String>,
    /** Models costed with a legacy, unverified price ("status": "legacy-unverified" in prices.json). */
    val estimatedModels: Set<String> = emptySet(),
    val rateLimits: List<RateLimitSnapshot> = emptyList(),
    val limitWarnings: List<LimitWarning> = emptyList(),
)

fun buildDashboard(
    rows: List<UsageRow>,
    prices: PriceTable,
    today: LocalDate,
    range: Range = Range.MONTH,
    historyDays: Int = 30,
    rateLimits: List<RateLimitSnapshot> = emptyList(),
    settings: Settings = Settings(),
    nowMs: Long = System.currentTimeMillis(),
): Dashboard {
    val unpriced = mutableSetOf<String>()
    val estimated = rows.filter { it.reportedCost == null && it.tokens.total > 0 && prices.isEstimated(it.model) }.map { it.model }.toSortedSet()
    val currentLimits = rateLimits.filterNot { it.isObsolete(nowMs) }
    val costs = rows.map { r ->
        r to (r.reportedCost ?: prices.cost(r.model, r.tokens, r.hourStartMs) ?: 0.0.also { if (r.tokens.total > 0) unpriced += r.model })
    }
    fun sumWhere(p: (UsageRow) -> Boolean) = costs.filter { p(it.first) }.sumOf { it.second }
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    val inRange = costs.filter { range.includes(it.first.day, today) }
    fun breakdown(key: (UsageRow) -> String) = inRange.groupBy { key(it.first) }
        .map { (k, v) -> Breakdown(k, v.sumOf { it.second }, v.sumOf { it.first.tokens.total }) }
        .sortedWith(compareByDescending<Breakdown> { it.cost }.thenByDescending { it.tokens })

    val firstDay = today.minusDays(historyDays - 1L)
    val byDay = costs.filter { it.first.day >= firstDay }.groupBy { it.first.day }
    val daily = (0 until historyDays).map { i ->
        val d = firstDay.plusDays(i.toLong())
        d to (byDay[d].orEmpty().groupBy { it.first.agent }.mapValues { (_, v) -> v.sumOf { it.second } })
    }

    val sessions = inRange.groupBy { it.first.agent to it.first.sessionId }.map { (k, v) ->
        val rs = v.map { it.first }
        SessionCost(
            agent = k.first, sessionId = k.second,
            project = rs.firstNotNullOfOrNull { it.project },
            models = rs.map { it.model }.toSortedSet(),
            firstTs = rs.minOf { it.firstTs }, lastTs = rs.maxOf { it.lastTs },
            cost = v.sumOf { it.second }, tokens = rs.sumOf { it.tokens.total },
        )
    }.sortedByDescending { it.cost }.take(10)

    val warnings = evaluateLimitWarnings(currentLimits, settings.warningThresholds, nowMs)

    return Dashboard(
        today = sumWhere { it.day == today },
        thisWeek = sumWhere { it.day >= weekStart && it.day <= today },
        thisMonth = sumWhere { it.day.year == today.year && it.day.month == today.month && it.day <= today },
        byAgent = breakdown { it.agent },
        byModel = breakdown { it.model },
        byProject = breakdown { it.project ?: "(unknown)" },
        daily = daily,
        topSessions = sessions,
        unpricedModels = unpriced,
        estimatedModels = estimated,
        rateLimits = currentLimits,
        limitWarnings = warnings,
    )
}

