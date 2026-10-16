package dev.agentmeter

import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrayTest {
    private val now = 1_800_000_000_000L
    private val resetAt = now / 1000 + 3600

    private fun snap(window: String, pct: Double, resets: Long? = resetAt, agent: String = "codex", ts: Long = now - 60_000) =
        RateLimitSnapshot(agent, window, pct, resets, ts)

    private fun warnings(vararg s: RateLimitSnapshot, thresholds: List<Double> = listOf(80.0, 95.0), at: Long = now) =
        evaluateLimitWarnings(s.toList(), thresholds, at)

    // --- icon state ---

    @Test
    fun `icon level follows thresholds from settings`() {
        assertEquals(TrayLevel.NORMAL, trayLevel(warnings(snap("5h", 50.0))))
        assertEquals(TrayLevel.NORMAL, trayLevel(emptyList()))
        assertEquals(TrayLevel.WARNING, trayLevel(warnings(snap("5h", 80.0))))
        assertEquals(TrayLevel.CRITICAL, trayLevel(warnings(snap("5h", 95.0))))
        // worst window wins
        assertEquals(TrayLevel.CRITICAL, trayLevel(warnings(snap("5h", 85.0), snap("7d", 99.0))))
        // custom thresholds: first = yellow, second = red, regardless of their value
        assertEquals(TrayLevel.WARNING, trayLevel(warnings(snap("5h", 60.0), thresholds = listOf(50.0, 70.0))))
        assertEquals(TrayLevel.CRITICAL, trayLevel(warnings(snap("5h", 72.0), thresholds = listOf(70.0, 50.0))))
        // a single threshold can only be yellow; unsorted/duplicate values are handled
        assertEquals(TrayLevel.WARNING, trayLevel(warnings(snap("5h", 99.0), thresholds = listOf(90.0, 90.0))))
        assertEquals(TrayLevel.NORMAL, trayLevel(warnings(snap("5h", 99.0), thresholds = emptyList())))
    }

    @Test
    fun `a window that already reset does not colour the icon`() {
        val stale = snap("5h", 99.0, resets = now / 1000 - 10, ts = now - 3_600_000)
        assertEquals(TrayLevel.NORMAL, trayLevel(warnings(stale)))
    }

    @Test
    fun `icon image uses the level colour`() {
        for (level in TrayLevel.entries) {
            val img = trayIconImage(level)
            assertEquals(level.rgb, img.getRGB(10, 32) and 0xFFFFFF)
            assertEquals(0, img.getRGB(0, 0) ushr 24) // transparent corner
        }
    }

    // --- notifications ---

    private fun w(pct: Double, window: String = "5h", resets: Long? = resetAt) = warnings(snap(window, pct, resets)).single()

    @Test
    fun `existing overshoot at startup is silent, new crossing notifies once`() {
        val t = NotificationTracker()
        assertTrue(t.update(warnings(snap("5h", 85.0))).isEmpty()) // primed
        assertTrue(t.update(warnings(snap("5h", 86.0))).isEmpty())
        assertTrue(t.update(warnings(snap("7d", 50.0))).isEmpty())
        val n = t.update(warnings(snap("5h", 86.0), snap("7d", 81.0)))
        assertEquals(listOf("7d"), n.map { it.snapshot.window })
        assertTrue(t.update(warnings(snap("7d", 82.0))).isEmpty()) // same window, same cycle, same threshold
    }

    @Test
    fun `crossing the next threshold in the same cycle notifies again`() {
        val t = NotificationTracker()
        t.update(emptyList())
        assertEquals(80.0, t.update(listOf(w(81.0))).single().threshold)
        assertTrue(t.update(listOf(w(90.0))).isEmpty())
        val critical = t.update(listOf(w(96.0))).single()
        assertEquals(LimitWarningLevel.CRITICAL, critical.level)
        assertTrue(t.update(listOf(w(97.0))).isEmpty())
    }

    @Test
    fun `new reset cycle notifies again, resets_at jitter does not`() {
        val t = NotificationTracker()
        t.update(emptyList())
        assertEquals(1, t.update(listOf(w(85.0))).size)
        assertTrue(t.update(listOf(w(85.0, resets = resetAt + 5))).isEmpty())
        assertEquals(1, t.update(listOf(w(85.0, resets = resetAt + 5 * 3600))).size)
        assertTrue(t.update(listOf(w(86.0, resets = resetAt + 5 * 3600))).isEmpty())
    }

    @Test
    fun `windows are tracked independently`() {
        val t = NotificationTracker()
        t.update(emptyList())
        assertEquals(2, t.update(listOf(w(85.0, "5h"), w(85.0, "7d"))).size)
        assertTrue(t.update(listOf(w(85.0, "5h"), w(85.0, "7d"))).isEmpty())
    }

    @Test
    fun `snapshots that already reset never notify`() {
        val t = NotificationTracker()
        t.update(emptyList())
        val stale = snap("5h", 99.0, resets = now / 1000 - 10, ts = now - 3_600_000)
        assertTrue(t.update(warnings(stale)).isEmpty())
    }

    @Test
    fun `notification text is english and carries the reset time`() {
        val (title, body) = notificationText(w(96.4), now, ZoneId.of("UTC"), Locale.US)
        assertEquals("Codex 5h limit at 96%", title)
        assertTrue(body.startsWith("Threshold 95% reached. Resets at "), body)
    }

    // --- menu / tooltip ---

    @Test
    fun `lines show cost today and the highest current limit per agent`() {
        val limits = listOf(
            snap("5h", 32.0), snap("7d", 84.0),
            snap("5h", 10.0, agent = "claude-code"),
        )
        val rows = listOf(UsageRow("codex", "s", "gpt-6-luna", null, LocalDate.of(2026, 10, 16), 0, 0, Tokens(output = 1_000_000)))
        val d = buildDashboard(rows, PriceTable.parse("""{"models":{"gpt-6-luna":{"input":1,"output":0.5}}}"""), LocalDate.of(2026, 10, 16), rateLimits = limits, nowMs = now)
        val lines = trayLines(d, now, ZoneId.of("UTC"), Locale.US)
        assertEquals("Today: $0.50", lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("Claude-code · 5h limit: 10% · Resets at "), lines[1])
        assertTrue(lines[2].startsWith("Codex · 7d limit: 84% · Resets at "), lines[2])
    }

    @Test
    fun `reset windows are skipped unless nothing else is left`() {
        val reset = snap("5h", 99.0, resets = now / 1000 - 10, ts = now - 3_600_000)
        val current = snap("7d", 20.0)
        assertEquals("7d", highestLimitPerAgent(listOf(reset, current), now).single().window)
        assertEquals("5h", highestLimitPerAgent(listOf(reset), now).single().window)

        val d = buildDashboard(emptyList(), PriceTable(), LocalDate.of(2026, 10, 16), rateLimits = listOf(reset), nowMs = now)
        assertEquals("Codex · 5h limit: reset, waiting for new data", trayLines(d, now)[1])
    }

    @Test
    fun `no limit data and tooltip length`() {
        val d = buildDashboard(emptyList(), PriceTable(), LocalDate.of(2026, 10, 16), nowMs = now)
        assertEquals(listOf("Today: $0.00", "No limit data yet"), trayLines(d, now))
        val long = trayTooltip(List(10) { "x".repeat(40) })
        assertEquals(127, long.length)
        assertEquals("Agent Meter\nToday: $0.00", trayTooltip(listOf("Today: $0.00")))
    }

    // --- settings / window behaviour ---

    @Test
    fun `settings defaults and partial files`() {
        val d = Settings.default()
        assertEquals(listOf(80.0, 95.0), d.warningThresholds)
        assertTrue(d.minimizeToTray)
        assertFalse(d.startMinimized)

        val partial = Settings.parse("""{"startMinimized": true, "unknownFutureKey": 1}""")
        assertTrue(partial.startMinimized)
        assertTrue(partial.minimizeToTray)
        assertEquals(listOf(80.0, 95.0), partial.warningThresholds)

        val off = Settings.parse("""{"minimizeToTray": false, "warningThresholds": [70.0]}""")
        assertFalse(off.minimizeToTray)
        assertEquals(listOf(70.0), off.warningThresholds)
    }

    @Test
    fun `first start writes a settings file with every option`() {
        val file = Files.createTempDirectory("settings-tray").resolve("settings.json")
        assertEquals(Settings.default(), Settings.load(file))
        val text = Files.readString(file)
        listOf("warningThresholds", "minimizeToTray", "startMinimized").forEach { assertTrue(it in text, it) }
        assertEquals(Settings.default(), Settings.load(file))
    }

    @Test
    fun `window policy depends on tray support`() {
        assertEquals(WindowPolicy(closeToTray = true, startVisible = true), windowPolicy(Settings(), traySupported = true))
        assertEquals(WindowPolicy(closeToTray = false, startVisible = true), windowPolicy(Settings(minimizeToTray = false), true))
        assertEquals(WindowPolicy(closeToTray = true, startVisible = false), windowPolicy(Settings(startMinimized = true), true))
        // no tray: never hide the only way to reach the app
        assertEquals(WindowPolicy(closeToTray = false, startVisible = true), windowPolicy(Settings(startMinimized = true), false))
    }
}
