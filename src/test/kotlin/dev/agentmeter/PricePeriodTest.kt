package dev.agentmeter

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PricePeriodTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private val million = Tokens(input = 1_000_000, cacheRead = 1_000_000, output = 1_000_000)

    private val table = PriceTable.parse(
        """{"models":{
          "promo":{"input":4,"cachedInput":0.4,"output":20,"periods":[
              {"validFrom":"2026-11-22","input":5,"cachedInput":0.5,"output":30}]},
          "intro":{"input":2,"cachedInput":0.1,"output":10,"aliases":["intro-trial"],"periods":[
              {"validUntil":"2026-11-30","input":1,"cachedInput":0.05,"output":5},
              {"validFrom":"2026-12-01","input":4,"cachedInput":0.2,"output":20}]},
          "plain":{"input":1,"output":2}}}""",
    )

    @Test
    fun `price follows the response date, bounds are inclusive days in UTC`() {
        assertEquals(24.4, table.cost("promo", million, ms("2026-10-16T12:00:00Z"))!!, 1e-9)
        assertEquals(24.4, table.cost("promo", million, ms("2026-11-21T23:59:59Z"))!!, 1e-9) // last promo day
        assertEquals(35.5, table.cost("promo", million, ms("2026-11-22T00:00:00Z"))!!, 1e-9) // first day of the old price
        assertEquals(35.5, table.cost("promo", million, ms("2030-01-01T00:00:00Z"))!!, 1e-9)

        assertEquals(6.05, table.cost("intro", million, ms("2026-11-30T23:59:59Z"))!!, 1e-9) // validUntil is inclusive
        assertEquals(24.2, table.cost("intro", million, ms("2026-12-01T00:00:00Z"))!!, 1e-9)
        assertEquals(6.05, table.cost("intro", million, ms("2020-01-01T00:00:00Z"))!!, 1e-9) // open lower bound
    }

    @Test
    fun `without a timestamp or periods the plain price applies, old files stay valid`() {
        assertEquals(24.4, table.cost("promo", million)!!, 1e-9)
        assertEquals(2.0 + 1.0, table.cost("plain", Tokens(input = 1_000_000, output = 1_000_000), ms("2030-01-01T00:00:00Z"))!!, 1e-9)
        assertEquals(24.2, table.cost("intro-trial", million, ms("2027-01-01T00:00:00Z"))!!, 1e-9) // aliases share the periods
    }

    @Test
    fun `only models with periods or peak hours are split per hour`() {
        assertEquals(setOf("promo", "intro", "intro-trial"), table.timeDependentModels(listOf("promo", "intro", "intro-trial", "plain", "unknown")))
    }

    @Test
    fun `a malformed date never throws and matches nothing`() {
        val t = PriceTable.parse("""{"models":{"m":{"input":1,"output":1,"periods":[{"validFrom":"soon","input":9,"output":9}]}}}""")
        assertEquals(1.0, t.cost("m", Tokens(input = 1_000_000), ms("2030-01-01T00:00:00Z"))!!, 1e-9)
    }

    @Test
    fun `bundled prices carry the dated changes and every period date parses`() {
        val b = PriceTable.bundled()
        for ((name, m) in b.models) for (p in m.periods) {
            p.validFrom?.let { LocalDate.parse(it) }; p.validUntil?.let { LocalDate.parse(it) }
            assertTrue(p.validFrom != null || p.validUntil != null, name)
        }
        val m = Tokens(input = 1_000_000)
        assertEquals(4.0, b.cost("gpt-5.6-sol", m, ms("2026-11-21T10:00:00Z")))
        assertEquals(5.0, b.cost("gpt-5.6-sol", m, ms("2026-11-22T00:00:00Z")))
        assertEquals(2.0, b.cost("gemini-4-argon", m, ms("2026-11-30T10:00:00Z")))
        assertEquals(4.0, b.cost("gemini-4-argon", m, ms("2026-12-01T00:00:00Z")))
        assertEquals(0.75, b.cost("gemini-3.8-flash", m, ms("2026-12-31T23:00:00Z")))
        assertEquals(1.5, b.cost("gemini-3.6-flash", m, ms("2027-01-01T00:00:00Z")))
    }

    @Test
    fun `rows of date-dependent models are split per hour so each response gets its own day's price`() {
        val dir = java.nio.file.Files.createTempDirectory("periods")
        Store(dir.resolve("t.db")).use { store ->
            fun ev(key: String, at: String) = dev.agentmeter.UsageEvent("codex", key, "s", ms(at), "promo", null, inputTokens = 1_000_000)
            store.upsert(listOf(ev("a", "2026-11-21T23:30:00Z"), ev("b", "2026-11-22T00:30:00Z")))
            val rows = store.rows(0, table.timeDependentModels(store.distinctModels()))
            val cost = rows.sumOf { table.cost(it.model, it.tokens, it.hourStartMs)!! }
            assertEquals(4.0 + 5.0, cost, 1e-9) // 4/M before the change, 5/M after, even if both fall on one local day
        }
    }
}
