package dev.agentmeter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentmeter.Breakdown
import dev.agentmeter.Dashboard
import dev.agentmeter.LimitWarning
import dev.agentmeter.LimitWarningLevel
import dev.agentmeter.Range
import dev.agentmeter.RateLimitSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val agentColors = mapOf(
    "claude-code" to Color(0xFFD97757),
    "codex" to Color(0xFF10A37F),
    "opencode" to Color(0xFFC77DFF),
    "deepseek-harness" to Color(0xFF4D6BFE),
    "antigravity" to Color(0xFFF2B01E),
)
private fun agentColor(agent: String) = agentColors[agent] ?: Color(0xFF7C8DB5)

fun usd(v: Double): String = if (v < 0.01 && v > 0) "<$0.01" else String.format(Locale.US, "$%,.2f", v)

fun tokens(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1e9)
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1e3)
    else -> n.toString()
}

private val tsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun AgentMeterTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)

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

@Composable
fun DashboardScreen(d: Dashboard, range: Range, onRange: (Range) -> Unit, status: String) = Surface(Modifier.fillMaxSize()) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Agent Meter", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(16.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (d.limitWarnings.isNotEmpty()) {
            WarningBanner(d.limitWarnings)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("Today", d.today, Modifier.weight(1f))
            Stat("This week", d.thisWeek, Modifier.weight(1f))
            Stat("This month", d.thisMonth, Modifier.weight(1f))
        }
        if (d.unpricedModels.isNotEmpty()) {
            Text(
                "No price for: ${d.unpricedModels.sorted().joinToString()}. Their usage counts as \$0 — add them to prices.json.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (d.estimatedModels.isNotEmpty()) {
            Text(
                "Estimated cost for ${d.estimatedModels.joinToString()}: legacy prices that could not be verified against the official price page.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
            )
        }
        if (d.byAgent.any { it.name == "antigravity" }) {
            Text(
                "Antigravity runs on a subscription: its cost here is the API list price, not what you pay.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
            )
        }
        Panel("Limits") { LimitsCard(d.rateLimits) }
        Panel("Daily cost, last ${d.daily.size} days") { DailyChart(d) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Range.entries.forEach { r -> FilterChip(selected = r == range, onClick = { onRange(r) }, label = { Text(r.label) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel("By agent", Modifier.weight(1f)) { BreakdownList(d.byAgent) { agentColor(it) } }
            Panel("By model", Modifier.weight(1f)) {
                BreakdownList(d.byModel, label = { if (it in d.estimatedModels) "$it (estimated)" else it })
            }
            Panel("By project", Modifier.weight(1f)) { BreakdownList(d.byProject, label = ::projectName) }
        }
        Panel("Most expensive sessions") { Sessions(d) }
        Text(
            "Costs are API list prices from prices.json — subscription plans bill differently. All data stays on this machine.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WarningBanner(warnings: List<LimitWarning>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (w in warnings) {
            val isCritical = w.level == LimitWarningLevel.CRITICAL
            val bgColor = if (isCritical) MaterialTheme.colorScheme.errorContainer else Color(0xFFFEF3C7)
            val textColor = if (isCritical) MaterialTheme.colorScheme.onErrorContainer else Color(0xFF92400E)
            val icon = if (isCritical) "🚨" else "⚠️"
            val windowName = limitName(w.snapshot.window)
            val agentName = w.snapshot.agent.replaceFirstChar { it.uppercase() }
            val resetStr = formatResetTime(w.snapshot.resetsAtInstant)
            Card(
                colors = CardDefaults.cardColors(containerColor = bgColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(icon, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Limit warning: $agentName · $windowName at ${w.snapshot.usedPercent.toInt()}% (threshold: ${w.threshold.toInt()}%) · $resetStr",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun LimitsCard(rateLimits: List<RateLimitSnapshot>) {
    if (rateLimits.isEmpty()) {
        Text("No limit data yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rateLimits.forEach { s ->
            val used = s.usedPercent.coerceIn(0.0, 100.0)
            val barColor = when {
                used >= 95.0 -> MaterialTheme.colorScheme.error
                used >= 80.0 -> Color(0xFFF59E0B)
                else -> agentColor(s.agent)
            }
            val agentLabel = s.agent.replaceFirstChar { it.uppercase() }
            val windowLabel = limitName(s.window)
            val now = System.currentTimeMillis()
            val isReset = s.hasReset(now)
            val resetText = if (isReset) LIMIT_RESET_TEXT else formatResetTime(s.resetsAtInstant, now)
            val updatedText = formatUpdatedAgo(s.timestampMs, now)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$agentLabel · $windowLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (isReset) "—" else "${String.format(Locale.US, "%.1f", s.usedPercent)} %",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (!isReset && used >= 80.0) barColor else Color.Unspecified,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                ) {
                    if (!isReset) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((used / 100.0).toFloat().coerceIn(0.01f, 1f))
                                .height(8.dp)
                                .background(barColor, RoundedCornerShape(4.dp)),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        resetText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        updatedText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}


fun projectName(path: String) = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

@Composable
private fun Stat(label: String, value: Double, modifier: Modifier) = Card(modifier) {
    Column(Modifier.padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(usd(value), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }

@Composable
private fun DailyChart(d: Dashboard) {
    val max = d.daily.maxOfOrNull { it.second.values.sum() }?.takeIf { it > 0 } ?: 1.0
    val agents = d.daily.flatMap { it.second.keys }.toSortedSet()
    val grid = MaterialTheme.colorScheme.outlineVariant
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.height(160.dp).width(64.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(usd(max), style = MaterialTheme.typography.labelSmall)
            Text("$0", style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height))
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f))
            val slot = size.width / d.daily.size
            d.daily.forEachIndexed { i, (_, byAgent) ->
                var y = size.height
                for (a in agents) {
                    val h = ((byAgent[a] ?: 0.0) / max * size.height).toFloat()
                    if (h <= 0f) continue
                    drawRect(agentColor(a), Offset(i * slot + slot * 0.15f, y - h), Size(slot * 0.7f, h))
                    y -= h
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${d.daily.first().first} … ${d.daily.last().first}", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 64.dp).weight(1f))
        agents.forEach { a ->
            Box(Modifier.size(10.dp).background(agentColor(a), RoundedCornerShape(2.dp)))
            Text(a, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun BreakdownList(items: List<Breakdown>, label: (String) -> String = { it }, color: (String) -> Color? = { null }) {
    if (items.isEmpty()) {
        Text("No usage in this range", style = MaterialTheme.typography.bodySmall)
        return
    }
    val max = items.maxOf { it.cost }.takeIf { it > 0 } ?: 1.0
    val default = MaterialTheme.colorScheme.primary
    items.take(8).forEach { b ->
        Column {
            Row {
                Text(label(b.name), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${usd(b.cost)} · ${tokens(b.tokens)}", style = MaterialTheme.typography.bodySmall)
            }
            Box(
                Modifier.fillMaxWidth((b.cost / max).toFloat().coerceIn(0.01f, 1f)).height(4.dp)
                    .background(color(b.name) ?: default, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun Sessions(d: Dashboard) {
    if (d.topSessions.isEmpty()) {
        Text("No sessions in this range", style = MaterialTheme.typography.bodySmall)
        return
    }
    val style = MaterialTheme.typography.bodySmall
    Row {
        listOf("Started" to 1.2f, "Agent" to 1f, "Project" to 1.5f, "Models" to 2.5f, "Tokens" to 0.8f, "Cost" to 0.8f)
            .forEach { (h, w) -> Text(h, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(w)) }
    }
    HorizontalDivider()
    d.topSessions.forEach { s ->
        Row {
            Text(tsFormat.format(Instant.ofEpochMilli(s.firstTs)), style = style, modifier = Modifier.weight(1.2f))
            Text(s.agent, style = style, color = agentColor(s.agent), modifier = Modifier.weight(1f))
            Text(s.project?.let(::projectName) ?: "—", style = style, modifier = Modifier.weight(1.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.models.joinToString(), style = style, modifier = Modifier.weight(2.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tokens(s.tokens), style = style, modifier = Modifier.weight(0.8f))
            Text(usd(s.cost), style = style, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.8f))
        }
    }
}
