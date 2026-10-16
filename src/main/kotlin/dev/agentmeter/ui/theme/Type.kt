package dev.agentmeter.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Type scale: big numbers, small muted labels. Numeric styles use tabular figures so columns line up. */
@Immutable
class AgentMeterType(
    val kpi: TextStyle,
    val title: TextStyle,
    val subtitle: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val numeric: TextStyle,
    val numericStrong: TextStyle,
)

private const val TABULAR = "tnum"

val DefaultType: AgentMeterType = run {
    val base = TextStyle(fontFamily = FontFamily.Default)
    AgentMeterType(
        kpi = base.copy(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, fontFeatureSettings = TABULAR),
        title = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        subtitle = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        body = base.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodyStrong = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        label = base.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
        caption = base.copy(fontSize = 11.sp, lineHeight = 15.sp),
        numeric = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TABULAR),
        numericStrong = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR),
    )
}
