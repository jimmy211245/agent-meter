package dev.agentmeter

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CostTest {
    private val prices = PriceTable.parse(
        """{"models":{
            "m":  {"input":3.0,"cachedInput":0.3,"cacheWrite":3.75,"cacheWrite1h":6.0,"output":15.0},
            "m2": {"input":1.0,"output":2.0}
        }}""",
    )

    @Test
    fun `cost sums every token class at its own rate`() {
        val t = Tokens(input = 1_000_000, cacheRead = 2_000_000, cacheWrite = 1_000_000, cacheWrite1h = 1_000_000, output = 100_000)
        // 3 + 0.6 + 3.75 + 6 + 1.5
        assertEquals(14.85, prices.cost("m", t)!!, 1e-9)
    }

    @Test
    fun `missing cache rates fall back to input price`() {
        assertEquals(4.0 + 2.0, prices.cost("m2", Tokens(cacheRead = 1_000_000, cacheWrite = 2_000_000, cacheWrite1h = 1_000_000, output = 1_000_000))!!, 1e-9)
    }

    @Test
    fun `model matching is exact or date snapshot only`() {
        assertNotNull(prices.find("m-20251001"))
        assertNull(prices.find("m-7"))
        assertNull(prices.cost("unknown", Tokens(input = 1)))
    }

    @Test
    fun `bundled price table parses and documents every entry`() {
        val t = PriceTable.bundled()
        assertTrue(t.models.isNotEmpty())
        t.models.forEach { (k, p) ->
            assertNotNull(p.asOf, k)
            assertNotNull(p.source, k)
        }
        assertEquals(20.0, t.find("claude-opus-5-5")!!.output)
    }

    @Test
    fun `dashboard aggregates periods, breakdowns and unpriced models`() {
        val today = LocalDate.of(2026, 10, 15) // Thursday
        fun row(agent: String, session: String, model: String, day: LocalDate, output: Long) =
            UsageRow(agent, session, model, "/p/$agent", day, 0, 0, Tokens(output = output))
        val rows = listOf(
            row("claude-code", "s1", "m", today, 1_000_000), // $15
            row("claude-code", "s1", "m2", today.minusDays(1), 1_000_000), // $2, same week
            row("codex", "s2", "m", LocalDate.of(2026, 10, 1), 1_000_000), // $15, same month, other week
            row("codex", "s3", "mystery", today, 5), // unpriced
            row("codex", "s4", "m", LocalDate.of(2026, 9, 30), 2_000_000), // $30, last month
        )
        val d = buildDashboard(rows, prices, today, Range.ALL, historyDays = 30)
        assertEquals(15.0, d.today, 1e-9)
        assertEquals(17.0, d.thisWeek, 1e-9)
        assertEquals(32.0, d.thisMonth, 1e-9)
        assertEquals(listOf("codex" to 45.0, "claude-code" to 17.0), d.byAgent.map { it.name to it.cost })
        assertEquals(setOf("mystery"), d.unpricedModels)
        assertEquals("s4", d.topSessions.first().sessionId)
        assertEquals(setOf("m", "m2"), d.topSessions.first { it.sessionId == "s1" }.models)
        assertEquals(30, d.daily.size)
        assertEquals(15.0, d.daily.last().second["claude-code"]!!, 1e-9)

        val week = buildDashboard(rows, prices, today, Range.WEEK)
        assertEquals(listOf("claude-code"), week.byAgent.filter { it.cost > 0 }.map { it.name })
    }
}
