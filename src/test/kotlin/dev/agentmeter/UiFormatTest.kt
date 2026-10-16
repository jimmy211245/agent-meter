package dev.agentmeter

import dev.agentmeter.ui.Change
import dev.agentmeter.ui.changeBetween
import dev.agentmeter.ui.deltaPercent
import dev.agentmeter.ui.formatResetsIn
import dev.agentmeter.ui.isEmptyDashboard
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiFormatTest {
    private val now = 1_800_000_000_000L
    private fun at(plusMs: Long) = Instant.ofEpochMilli(now + plusMs)

    @Test
    fun `reset time is shown as time left`() {
        assertEquals("resets in 2h 14m", formatResetsIn(at(2 * 3_600_000L + 14 * 60_000L + 5_000), now))
        assertEquals("resets in 14m", formatResetsIn(at(14 * 60_000L + 1_000), now))
        assertEquals("resets in <1m", formatResetsIn(at(30_000), now))
        assertEquals("resets in 4d 3h", formatResetsIn(at(4 * 86_400_000L + 3 * 3_600_000L + 60_000), now))
        assertEquals("resetting now", formatResetsIn(at(-1_000), now))
        assertEquals("No reset time", formatResetsIn(null, now))
    }

    @Test
    fun `change against the previous period`() {
        assertEquals(12.0, deltaPercent(112.0, 100.0)!!, 1e-9)
        assertNull(deltaPercent(5.0, 0.0)) // nothing to compare with
        val up = changeBetween(112.0, 100.0, "vs. last week")
        assertEquals(Change.Direction.UP, up.direction)
        assertEquals("+12%", up.percentText)
        assertEquals("vs. last week", up.reference)
        val down = changeBetween(80.0, 100.0, "vs. yesterday")
        assertEquals(Change.Direction.DOWN, down.direction)
        assertEquals("−20%", down.percentText)
        assertEquals(Change.Direction.FLAT, changeBetween(100.2, 100.0, "x").direction)
        assertEquals("–", changeBetween(3.0, 0.0, "x").percentText)
    }

    @Test
    fun `dashboard compares with the same part of the previous period`() {
        val today = LocalDate.of(2026, 10, 15) // Thursday; the week started Monday 10-12
        fun row(day: LocalDate) = UsageRow("codex", "s-$day", "m", null, day, 0, 0, Tokens(output = 1_000_000))
        val prices = PriceTable.parse("""{"models":{"m":{"input":0,"output":1}}}""")
        val days = listOf(
            today, // 1.0 today
            today.minusDays(1), // yesterday
            LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 8), // last week up to Thursday: Mon 5 .. Thu 8
            LocalDate.of(2026, 10, 9), // last week, after Thursday: must not count
            LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 15), // last month up to the 15th
            LocalDate.of(2026, 9, 20), // after the 15th: must not count
        )
        val d = buildDashboard(days.map(::row), prices, today)
        assertEquals(1.0, d.comparison.yesterday, 1e-9)
        assertEquals(2.0, d.comparison.lastWeekSoFar, 1e-9)
        assertEquals(2.0, d.comparison.lastMonthSoFar, 1e-9)
    }

    @Test
    fun `empty dashboard is detected`() {
        val empty = buildDashboard(emptyList(), PriceTable(), LocalDate.of(2026, 10, 15))
        assertTrue(isEmptyDashboard(empty))
        val row = UsageRow("codex", "s", "m", null, LocalDate.of(2026, 10, 15), 0, 0, Tokens(output = 1))
        assertFalse(isEmptyDashboard(buildDashboard(listOf(row), PriceTable(), LocalDate.of(2026, 10, 15))))
    }
}
