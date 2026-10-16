package dev.agentmeter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import dev.agentmeter.RateLimitSnapshot
import dev.agentmeter.ui.theme.AM
import dev.agentmeter.ui.theme.agentColor
import dev.agentmeter.ui.theme.agentLabel
import dev.agentmeter.ui.theme.animatedValue
import java.util.Locale

@Composable
fun LimitsCard(rateLimits: List<RateLimitSnapshot>) {
    val c = AM.colors
    if (rateLimits.isEmpty()) {
        EmptyNote("No limit data yet. Limits appear after your first Codex request.")
        return
    }
    val now = System.currentTimeMillis()
    Column(verticalArrangement = Arrangement.spacedBy(AM.spacing.xl - 4.dp)) {
        rateLimits.forEach { s ->
            val used = s.usedPercent.coerceIn(0.0, 100.0)
            val isReset = s.hasReset(now)
            val resetText = if (isReset) LIMIT_RESET_TEXT else formatResetsIn(s.resetsAtInstant, now)
            val valueColor = when {
                isReset -> c.textMuted
                used >= 95.0 -> c.negative
                used >= 80.0 -> c.warning
                else -> c.textPrimary
            }
            val fraction = animatedValue(if (isReset) 0f else (used / 100.0).toFloat())

            Column(verticalArrangement = Arrangement.spacedBy(AM.spacing.sm)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Dot(agentColor(s.agent))
                    Spacer(Modifier.width(AM.spacing.sm))
                    Text("${agentLabel(s.agent)} · ${limitName(s.window)}", style = AM.type.bodyStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
                    Text(if (isReset) "—" else "${String.format(Locale.US, "%.1f", s.usedPercent)}%", style = AM.type.numericStrong, color = valueColor)
                }
                // The gradient spans the whole track, so the colour at the end of the fill shows how close the limit is.
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(c.track, cornerRadius = r)
                    val w = size.width * fraction
                    if (w > 0f) {
                        drawRoundRect(
                            brush = Brush.horizontalGradient(0f to c.limitLow, 0.6f to c.limitMid, 1f to c.limitHigh, startX = 0f, endX = size.width),
                            size = Size(maxOf(w, size.height), size.height),
                            cornerRadius = r,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(resetText, style = AM.type.caption, color = c.textMuted)
                    Text(formatUpdatedAgo(s.timestampMs, now), style = AM.type.caption, color = c.textMuted)
                }
            }
        }
    }
}
