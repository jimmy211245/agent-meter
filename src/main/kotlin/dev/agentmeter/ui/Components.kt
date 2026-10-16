package dev.agentmeter.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import dev.agentmeter.ui.theme.AM
import dev.agentmeter.ui.theme.LocalMotion
import dev.agentmeter.ui.theme.animatedValue

/** A bordered surface with a small title; replaces heavy elevated cards. */
@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = AM.colors
    Column(
        modifier.background(c.surface, AM.shapes.large).border(1.dp, c.border, AM.shapes.large).padding(AM.spacing.xl - 4.dp),
        verticalArrangement = Arrangement.spacedBy(AM.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AM.type.subtitle, color = c.textPrimary, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        content()
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).background(color, CircleShape))

/** Big number with a muted label above and the change versus the previous period below. */
@Composable
fun KpiCard(label: String, value: Double, change: Change, modifier: Modifier = Modifier) {
    val c = AM.colors
    Column(
        modifier.background(c.surface, AM.shapes.large).border(1.dp, c.border, AM.shapes.large).padding(AM.spacing.xl - 4.dp),
        verticalArrangement = Arrangement.spacedBy(AM.spacing.sm),
    ) {
        Text(label.uppercase(), style = AM.type.label, color = c.textMuted)
        Text(usd(animatedValue(value.toFloat()).toDouble()), style = AM.type.kpi, color = c.textPrimary)
        val color = when (change.direction) {
            Change.Direction.UP -> c.negative // spending more is the "bad" direction
            Change.Direction.DOWN -> c.positive
            Change.Direction.FLAT -> c.textMuted
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(change.percentText, style = AM.type.numericStrong, color = color)
            Text(change.reference, style = AM.type.caption, color = c.textMuted)
        }
    }
}

@Composable
fun <T> SegmentedControl(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val c = AM.colors
    Row(
        Modifier.background(c.surface, AM.shapes.medium).border(1.dp, c.border, AM.shapes.medium).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            Text(
                label(o),
                style = if (on) AM.type.bodyStrong else AM.type.body,
                color = if (on) c.textPrimary else c.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.clip(AM.shapes.small)
                    .background(if (on) c.surfaceRaised else Color.Transparent, AM.shapes.small)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** A pulsing placeholder block for the loading state. */
@Composable
fun Skeleton(modifier: Modifier) {
    val c = AM.colors
    val a = if (LocalMotion.current) {
        rememberInfiniteTransition().animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse)).value
    } else 0.7f
    Box(modifier.alpha(a).background(c.track, AM.shapes.small))
}

@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) =
    Text(text, style = AM.type.body, color = AM.colors.textMuted, modifier = modifier.padding(vertical = AM.spacing.sm))

/** Whole-screen empty state: nothing has been imported yet. */
@Composable
fun EmptyState(modifier: Modifier = Modifier) {
    val c = AM.colors
    Column(
        modifier.fillMaxWidth().background(c.surface, AM.shapes.large).border(1.dp, c.border, AM.shapes.large).padding(vertical = 56.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AM.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(AM.spacing.sm)) {
            listOf("claude-code", "codex", "opencode", "deepseek-harness", "antigravity").forEach { Dot(dev.agentmeter.ui.theme.agentColor(it, c.dark), 10.dp) }
        }
        Text("No data yet", style = AM.type.title, color = c.textPrimary)
        Text(
            "Start a session in Claude Code, Codex, OpenCode, DeepSeek Harness or Antigravity.\nAgent Meter picks it up within a few seconds — nothing to configure.",
            style = AM.type.body, color = c.textSecondary, textAlign = TextAlign.Center,
        )
    }
}

/** Loading state shown until the first scan has finished. */
@Composable
fun LoadingState() {
    Column(verticalArrangement = Arrangement.spacedBy(AM.spacing.lg)) {
        Row(horizontalArrangement = Arrangement.spacedBy(AM.spacing.lg)) {
            repeat(3) { Skeleton(Modifier.weight(1f).height(112.dp)) }
        }
        Skeleton(Modifier.fillMaxWidth().height(120.dp))
        Skeleton(Modifier.fillMaxWidth().height(240.dp))
        Text("Scanning your agent logs…", style = AM.type.body, color = AM.colors.textMuted)
    }
}
