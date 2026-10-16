package dev.agentmeter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agentmeter.Dashboard
import dev.agentmeter.ui.theme.AM
import dev.agentmeter.ui.theme.agentColor
import dev.agentmeter.ui.theme.agentLabel
import dev.agentmeter.ui.theme.animatedProgress
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CHART_HEIGHT = 190.dp
private val TOOLTIP_WIDTH = 210.dp

/** Stacked daily cost per agent: rounded bars that grow in, a hover tooltip with the day's cost per agent. */
@Composable
fun DailyChart(d: Dashboard) {
    val c = AM.colors
    val days = d.daily
    val max = days.maxOfOrNull { it.second.values.sum() }?.takeIf { it > 0 } ?: 1.0
    val agents = days.flatMap { it.second.keys }.toSortedSet()
    var hover by remember { mutableStateOf<Int?>(null) }
    var widthPx by remember { mutableStateOf(0) }
    val progress = animatedProgress(days)
    val density = LocalDensity.current

    Column(verticalArrangement = Arrangement.spacedBy(AM.spacing.sm)) {
        Row {
            Column(Modifier.height(CHART_HEIGHT).width(56.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(usd(max), style = AM.type.caption.copy(fontFeatureSettings = "tnum"), color = c.textMuted)
                Text(usd(max / 2), style = AM.type.caption.copy(fontFeatureSettings = "tnum"), color = c.textMuted)
                Text("$0", style = AM.type.caption.copy(fontFeatureSettings = "tnum"), color = c.textMuted)
            }
            Box(
                Modifier.weight(1f).height(CHART_HEIGHT).onSizeChanged { widthPx = it.width }
                    .pointerInput(days.size) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                val p = e.changes.firstOrNull()?.position
                                hover = if (e.type == PointerEventType.Exit || p == null || days.isEmpty()) null
                                else (p.x / (size.width.toFloat() / days.size)).toInt().coerceIn(0, days.size - 1)
                            }
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
                    for (f in listOf(0f, 0.5f, 1f)) {
                        val y = size.height * f
                        drawLine(c.grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = if (f == 1f) null else dash)
                    }
                    val slot = size.width / days.size
                    val barW = slot * 0.6f
                    val radius = CornerRadius(minOf(5.dp.toPx(), barW / 2))
                    days.forEachIndexed { i, (_, byAgent) ->
                        val x0 = i * slot + (slot - barW) / 2
                        if (hover == i) {
                            drawRoundRect(c.surfaceRaised, Offset(i * slot + 1f, 0f), Size(slot - 2f, size.height), CornerRadius(6.dp.toPx()))
                        }
                        val total = byAgent.values.sum()
                        val barH = (total / max * size.height).toFloat() * progress
                        if (barH <= 0f) return@forEachIndexed
                        val top = size.height - barH
                        val clip = Path().apply { addRoundRect(RoundRect(x0, top, x0 + barW, size.height, radius, radius, radius, radius)) }
                        clipPath(clip) {
                            var y = size.height
                            for (a in agents) {
                                val h = ((byAgent[a] ?: 0.0) / max * size.height).toFloat() * progress
                                if (h <= 0f) continue
                                drawRect(agentColor(a), Offset(x0, y - h), Size(barW, h))
                                y -= h
                            }
                        }
                    }
                }
                hover?.let { i ->
                    val (day, byAgent) = days[i]
                    val slotPx = widthPx.toFloat() / days.size
                    val tipPx = with(density) { TOOLTIP_WIDTH.roundToPx() }
                    val x = (i * slotPx + slotPx / 2 - tipPx / 2).toInt().coerceIn(0, (widthPx - tipPx).coerceAtLeast(0))
                    Tooltip(day, byAgent, Modifier.offset { IntOffset(x, 4) }.width(TOOLTIP_WIDTH))
                }
            }
        }
        Row(Modifier.padding(start = 56.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = listOf(0, days.size / 3, days.size * 2 / 3, days.size - 1).distinct().filter { it in days.indices }
            shown.forEach { Text(axisDate(days[it].first), style = AM.type.caption, color = c.textMuted) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AM.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            agents.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(agentColor(a))
                    Text(agentLabel(a), style = AM.type.caption, color = c.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun Tooltip(day: LocalDate, byAgent: Map<String, Double>, modifier: Modifier) {
    val c = AM.colors
    Column(
        modifier.background(c.surfaceRaised, AM.shapes.medium).border(1.dp, c.border, AM.shapes.medium).padding(AM.spacing.md),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row {
            Text(tooltipDate(day), style = AM.type.bodyStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
            Text(usd(byAgent.values.sum()), style = AM.type.numericStrong, color = c.textPrimary)
        }
        val rows = byAgent.filterValues { it > 0 }.entries.sortedByDescending { it.value }
        if (rows.isEmpty()) Text("No usage", style = AM.type.caption, color = c.textMuted)
        rows.forEach { (a, cost) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(agentColor(a))
                Text(agentLabel(a), style = AM.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
                Text(usd(cost), style = AM.type.numeric.copy(fontSize = 12.sp), color = c.textPrimary)
            }
        }
    }
}

private fun axisDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
private fun tooltipDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
