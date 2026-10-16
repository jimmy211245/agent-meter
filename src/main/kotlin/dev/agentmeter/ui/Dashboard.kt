package dev.agentmeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentmeter.Breakdown
import dev.agentmeter.Dashboard
import dev.agentmeter.LimitWarning
import dev.agentmeter.LimitWarningLevel
import dev.agentmeter.Range
import dev.agentmeter.ui.theme.AM
import dev.agentmeter.ui.theme.agentColor
import dev.agentmeter.ui.theme.agentLabel
import dev.agentmeter.ui.theme.animatedProgress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

fun usd(v: Double): String = if (v < 0.01 && v > 0) "<$0.01" else String.format(Locale.US, "$%,.2f", v)

fun tokens(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1e9)
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1e3)
    else -> n.toString()
}

private val tsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

fun limitName(window: String) = "$window limit"

/** "Resets at 3:45 PM" for today, "Resets 10/21/26 3:45 PM" otherwise; date/time style follows the system locale. */
fun formatResetTime(
    instant: Instant?,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(Locale.Category.FORMAT),
): String {
    if (instant == null) return "No reset time"
    val at = instant.atZone(zone)
    val time = at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    if (at.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()) return "Resets at $time"
    return "Resets ${at.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))} $time"
}

/** "resets in 2h 14m", "resets in 4d 3h": the time left until [instant]. */
fun formatResetsIn(instant: Instant?, nowMs: Long = System.currentTimeMillis()): String {
    if (instant == null) return "No reset time"
    val left = instant.toEpochMilli() - nowMs
    if (left < 0) return "resetting now"
    val mins = left / 60_000
    return when {
        mins < 1 -> "resets in <1m"
        mins < 60 -> "resets in ${mins}m"
        mins < 1440 -> "resets in ${mins / 60}h ${mins % 60}m"
        else -> "resets in ${mins / 1440}d ${(mins % 1440) / 60}h"
    }
}

fun formatUpdatedAgo(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val mins = (nowMs - timestampMs).coerceAtLeast(0) / 60_000
    return when {
        mins < 1 -> "updated <1 min ago"
        mins < 60 -> "updated $mins min ago"
        mins < 1440 -> "updated ${mins / 60} h ago"
        else -> "updated ${mins / 1440} d ago"
    }
}

const val LIMIT_RESET_TEXT = "Reset, waiting for new data"

/** Change of spend against the comparable part of the previous period. */
class Change(val direction: Direction, val percentText: String, val reference: String) {
    enum class Direction { UP, DOWN, FLAT }
}

/** Percent change from [previous] to [current]; null when there is nothing to compare with. */
fun deltaPercent(current: Double, previous: Double): Double? = if (previous <= 0.0) null else (current - previous) / previous * 100.0

fun changeBetween(current: Double, previous: Double, reference: String): Change {
    val pct = deltaPercent(current, previous)?.roundToInt() ?: return Change(Change.Direction.FLAT, "–", reference)
    return when {
        pct > 0 -> Change(Change.Direction.UP, "+$pct%", reference)
        pct < 0 -> Change(Change.Direction.DOWN, "−${-pct}%", reference)
        else -> Change(Change.Direction.FLAT, "±0%", reference)
    }
}

fun projectName(path: String) = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

/** True when nothing has been imported yet (no cost in the history, no lists, no limits). */
fun isEmptyDashboard(d: Dashboard) = d.daily.all { it.second.isEmpty() } && d.byAgent.isEmpty() && d.rateLimits.isEmpty()

@Composable
fun DashboardScreen(d: Dashboard, range: Range, onRange: (Range) -> Unit, status: String, loading: Boolean = false) {
    val c = AM.colors
    Box(Modifier.fillMaxSize().background(c.background), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 1180.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = AM.spacing.xxl - 4.dp, vertical = AM.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(AM.spacing.lg),
        ) {
            Header(status)
            when {
                loading -> LoadingState()
                isEmptyDashboard(d) -> EmptyState(Modifier.padding(top = AM.spacing.lg))
                else -> Content(d, range, onRange)
            }
            Text(
                "Costs are API list prices from prices.json, not your subscription bill. All data stays on this machine.",
                style = AM.type.caption, color = c.textMuted, modifier = Modifier.padding(top = AM.spacing.sm),
            )
        }
    }
}

@Composable
private fun Header(status: String) {
    val c = AM.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(c.accent, 10.dp)
        Spacer(Modifier.width(AM.spacing.md))
        Text("Agent Meter", style = AM.type.title, color = c.textPrimary)
        Spacer(Modifier.width(AM.spacing.lg))
        Text(status, style = AM.type.caption, color = c.textMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Content(d: Dashboard, range: Range, onRange: (Range) -> Unit) {
    val c = AM.colors
    if (d.limitWarnings.isNotEmpty()) WarningBanner(d.limitWarnings)
    Row(horizontalArrangement = Arrangement.spacedBy(AM.spacing.lg)) {
        KpiCard("Today", d.today, changeBetween(d.today, d.comparison.yesterday, "vs. yesterday"), Modifier.weight(1f))
        KpiCard("This week", d.thisWeek, changeBetween(d.thisWeek, d.comparison.lastWeekSoFar, "vs. last week"), Modifier.weight(1f))
        KpiCard("This month", d.thisMonth, changeBetween(d.thisMonth, d.comparison.lastMonthSoFar, "vs. last month"), Modifier.weight(1f))
    }
    Notes(d)
    Panel("Limits") { LimitsCard(d.rateLimits) }
    Panel("Daily cost", trailing = { Text("last ${d.daily.size} days", style = AM.type.caption, color = c.textMuted) }) { DailyChart(d) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Breakdown", style = AM.type.subtitle, color = c.textPrimary, modifier = Modifier.weight(1f))
        SegmentedControl(Range.entries.toList(), range, { it.label }, onRange)
    }
    Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(AM.spacing.lg)) {
        Panel("By agent", Modifier.weight(1f).fillMaxHeight()) { BreakdownList(d.byAgent, label = ::agentLabel, dot = ::agentColor) }
        Panel("By model", Modifier.weight(1f).fillMaxHeight()) {
            BreakdownList(d.byModel, label = { if (it in d.estimatedModels) "$it (estimated)" else it }, dot = { c.accent })
        }
        Panel("By project", Modifier.weight(1f).fillMaxHeight()) { BreakdownList(d.byProject, label = ::projectName, dot = { c.accent }) }
    }
    Panel("Most expensive sessions") { Sessions(d) }
}

@Composable
private fun Notes(d: Dashboard) {
    val c = AM.colors
    if (d.unpricedModels.isNotEmpty()) {
        Text(
            "No price for: ${d.unpricedModels.sorted().joinToString()}. Their usage counts as \$0 — add them to prices.json.",
            style = AM.type.caption, color = c.negative,
        )
    }
    if (d.estimatedModels.isNotEmpty()) {
        Text(
            "Estimated cost for ${d.estimatedModels.joinToString()}: legacy prices that could not be verified against the official price page.",
            style = AM.type.caption, color = c.textMuted,
        )
    }
    if (d.byAgent.any { it.name == "antigravity" }) {
        Text("Antigravity runs on a subscription: its cost here is the API list price, not what you pay.", style = AM.type.caption, color = c.textMuted)
    }
}

@Composable
private fun WarningBanner(warnings: List<LimitWarning>) {
    val c = AM.colors
    Column(verticalArrangement = Arrangement.spacedBy(AM.spacing.sm)) {
        for (w in warnings) {
            val critical = w.level == LimitWarningLevel.CRITICAL
            val tone = if (critical) c.negative else c.warning
            Row(
                Modifier.fillMaxWidth().background(tone.copy(alpha = 0.10f), AM.shapes.medium)
                    .border(1.dp, tone.copy(alpha = 0.35f), AM.shapes.medium)
                    .padding(horizontal = AM.spacing.lg, vertical = AM.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AM.spacing.md),
            ) {
                Dot(tone, 10.dp)
                Text(
                    "Limit warning: ${agentLabel(w.snapshot.agent)} · ${limitName(w.snapshot.window)} at ${w.snapshot.usedPercent.toInt()}% " +
                        "(threshold: ${w.threshold.toInt()}%) · ${formatResetsIn(w.snapshot.resetsAtInstant)}",
                    style = AM.type.bodyStrong, color = tone,
                )
            }
        }
    }
}

@Composable
private fun BreakdownList(items: List<Breakdown>, label: (String) -> String = { it }, dot: (String) -> Color) {
    val c = AM.colors
    if (items.isEmpty()) {
        EmptyNote("No usage in this range")
        return
    }
    val max = items.maxOf { it.cost }.takeIf { it > 0 } ?: 1.0
    val grow = animatedProgress(items)
    Column(verticalArrangement = Arrangement.spacedBy(AM.spacing.md)) {
        items.take(8).forEach { b ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(dot(b.name), 8.dp)
                    Spacer(Modifier.width(AM.spacing.sm))
                    Text(label(b.name), style = AM.type.body, color = c.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(usd(b.cost), style = AM.type.numericStrong, color = c.textPrimary, textAlign = TextAlign.End, modifier = Modifier.width(76.dp))
                    Text(tokens(b.tokens), style = AM.type.numeric.copy(fontSize = AM.type.caption.fontSize), color = c.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
                }
                Box(Modifier.fillMaxWidth().height(3.dp).background(c.track, AM.shapes.pill)) {
                    Box(
                        Modifier.fillMaxWidth(((b.cost / max).toFloat().coerceIn(0.01f, 1f)) * grow).height(3.dp)
                            .background(dot(b.name).copy(alpha = 0.85f), AM.shapes.pill),
                    )
                }
            }
        }
    }
}

@Composable
private fun Sessions(d: Dashboard) {
    val c = AM.colors
    if (d.topSessions.isEmpty()) {
        EmptyNote("No sessions in this range")
        return
    }
    val cols = listOf(1.3f, 1.3f, 1.5f, 2.5f, 0.8f, 0.8f)
    Column {
    Row(Modifier.padding(bottom = AM.spacing.sm)) {
        listOf("Started", "Agent", "Project", "Models", "Tokens", "Cost").forEachIndexed { i, h ->
            Text(h.uppercase(), style = AM.type.label, color = c.textMuted, modifier = Modifier.weight(cols[i]), textAlign = if (i >= 4) TextAlign.End else TextAlign.Start)
        }
    }
    d.topSessions.forEach { s ->
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tsFormat.format(Instant.ofEpochMilli(s.firstTs)), style = AM.type.numeric, color = c.textSecondary, modifier = Modifier.weight(cols[0]))
            Row(Modifier.weight(cols[1]), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(agentColor(s.agent))
                Text(agentLabel(s.agent), style = AM.type.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(s.project?.let(::projectName) ?: "—", style = AM.type.body, color = c.textSecondary, modifier = Modifier.weight(cols[2]), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.models.joinToString(), style = AM.type.body, color = c.textSecondary, modifier = Modifier.weight(cols[3]), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tokens(s.tokens), style = AM.type.numeric, color = c.textSecondary, modifier = Modifier.weight(cols[4]), textAlign = TextAlign.End)
            Text(usd(s.cost), style = AM.type.numericStrong, color = c.textPrimary, modifier = Modifier.weight(cols[5]), textAlign = TextAlign.End)
        }
    }
    }
}
