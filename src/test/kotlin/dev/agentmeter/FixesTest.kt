package dev.agentmeter

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixesTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 3_600_000L
    private val today = LocalDate.of(2026, 10, 16)

    // --- obsolete limit windows ---

    private fun snap(window: String, ts: Long, resets: Long?) = RateLimitSnapshot("codex", window, 40.0, resets, ts)

    @Test
    fun `reset window older than 24h is obsolete, everything else stays`() {
        val oldReset = snap("30d", now - 30 * day, (now - 20 * day) / 1000)
        val freshReset = snap("5h", now - 2 * 3_600_000L, (now - 3_600_000L) / 1000)
        val oldButCurrent = snap("7d", now - 3 * day, (now + day) / 1000)
        val oldNoReset = snap("1d", now - 3 * day, null)
        assertTrue(oldReset.isObsolete(now))
        assertFalse(freshReset.isObsolete(now))
        assertFalse(oldButCurrent.isObsolete(now))
        assertFalse(oldNoReset.isObsolete(now))
        // boundary: exactly 24h old is not yet obsolete
        assertFalse(snap("5h", now - day, (now - 1000) / 1000).isObsolete(now))
    }

    @Test
    fun `dashboard and tray hide obsolete windows`() {
        val all = listOf(
            snap("30d", now - 30 * day, (now - 20 * day) / 1000),
            snap("5h", now - 2 * 3_600_000L, (now - 3_600_000L) / 1000),
            snap("7d", now - 60_000, (now + day) / 1000),
        )
        val d = buildDashboard(emptyList(), PriceTable(), today, rateLimits = all, nowMs = now)
        assertEquals(listOf("5h", "7d"), d.rateLimits.map { it.window })
        val lines = trayLines(d, now)
        assertTrue(lines.none { "30d" in it }, lines.toString())
        // the freshly reset 5h window is still reported, but the 7d one is the "highest current" one
        assertTrue(lines.single { it.startsWith("Codex") }.contains("7d limit"))

        val onlyOld = buildDashboard(emptyList(), PriceTable(), today, rateLimits = all.take(1), nowMs = now)
        assertTrue(onlyOld.rateLimits.isEmpty())
        assertEquals("No limit data yet", trayLines(onlyOld, now)[1])
    }

    // --- legacy prices ---

    private val legacy = listOf("gpt-6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5")

    @Test
    fun `legacy models are priced and flagged, unknown ones stay unpriced`() {
        val t = PriceTable.bundled()
        legacy.forEach {
            assertTrue(t.isEstimated(it), it)
            assertNotNull(t.cost(it, Tokens(output = 1_000_000)), it)
        }
        assertEquals(30.0, t.cost("gpt-5.5", Tokens(output = 1_000_000))!!, 1e-9)
        assertFalse(t.isEstimated("gpt-6.1-sol"))
        assertFalse(t.isEstimated("claude-opus-5-5"))
        assertNull(t.find("codex-auto-review"))
        assertNull(t.find("gpt-reserve"))
    }

    @Test
    fun `dashboard lists estimated models with usage only, and still counts their cost`() {
        val rows = listOf(
            UsageRow("codex", "s1", "gpt-5.5", null, today, 0, 0, Tokens(output = 1_000_000)),
            UsageRow("codex", "s2", "gpt-6.1-sol", null, today, 0, 0, Tokens(output = 1_000_000)),
            UsageRow("codex", "s3", "codex-auto-review", null, today, 0, 0, Tokens(output = 5)),
        )
        val d = buildDashboard(rows, PriceTable.bundled(), today, nowMs = now)
        assertEquals(setOf("gpt-5.5"), d.estimatedModels)
        assertEquals(setOf("codex-auto-review"), d.unpricedModels)
        assertEquals(40.0, d.today, 1e-9) // 30 (estimated) + 10
    }

    @Test
    fun `user prices win and missing bundled models are added without touching the file`() {
        val file = Files.createTempDirectory("prices-merge").resolve("prices.json")
        val custom = """{"models":{"gpt-5.5":{"input":1.0,"output":2.0},"my-model":{"input":3.0,"output":4.0}}}"""
        Files.writeString(file, custom)

        val t = PriceTable.load(file)
        assertEquals(2.0, t.find("gpt-5.5")!!.output) // edited by the user, kept
        assertFalse(t.isEstimated("gpt-5.5")) // user entry has no legacy status
        assertEquals(4.0, t.find("my-model")!!.output)
        assertNotNull(t.find("gpt-5.6-terra"))
        assertNotNull(t.find("claude-opus-5-5"))
        assertEquals(custom, Files.readString(file))
    }

    @Test
    fun `invalid user file falls back to bundled prices`() {
        val file = Files.createTempDirectory("prices-bad").resolve("prices.json")
        Files.writeString(file, "{ not json")
        assertNotNull(PriceTable.load(file).find("gpt-5.5"))
    }

    // --- header path ---

    @Test
    fun `data path under home is shown with a tilde`() {
        val home = Files.createTempDirectory("home-x")
        val sep = File.separator
        assertEquals("~${sep}.agent-meter", tildePath(home.resolve(".agent-meter"), home))
        assertEquals("~${sep}a${sep}b", tildePath(home.resolve("a").resolve("b"), home))
        assertEquals("~", tildePath(home, home))
        val other = Files.createTempDirectory("other-x")
        assertEquals(other.toAbsolutePath().normalize().toString(), tildePath(other, home))
        assertFalse(tildePath(home.resolve(".agent-meter"), home).contains(home.fileName.toString()))
    }
}
