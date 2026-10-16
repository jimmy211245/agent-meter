package dev.agentmeter

import dev.agentmeter.importers.CodexImporter
import dev.agentmeter.importers.parseInstant
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import dev.agentmeter.ui.formatResetTime
import dev.agentmeter.ui.formatUpdatedAgo
import dev.agentmeter.ui.limitName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimitTest {
    private fun lines(name: String) = fixture(name).toFile().readLines().asSequence()

    @Test
    fun `codex importer parses primary and secondary rate limits with reset times`() {
        val r = CodexImporter.parse(fixture("codex-rollout-rate-limits.jsonl"), lines("codex-rollout-rate-limits.jsonl"))

        // Primary:
        // 1st token_count (ts 08:00:06): 30.0%, resets_at = 1792180422
        // 2nd token_count (ts 08:05:00): identical 30.0% -> SKIPPED!
        // 3rd token_count (ts 08:10:00): 85.0%, resets_at = 1792180422
        // 4th token_count (ts 08:15:00): 96.0%, resets_in_seconds = 1800 -> resets_at = 08:15:00 in sec + 1800
        val primary = r.rateLimits.filter { it.window == "5h" }
        assertEquals(3, primary.size, "Consecutive identical 30.0% snapshot must be skipped")

        assertEquals(30.0, primary[0].usedPercent)
        assertEquals(1792180422L, primary[0].resetsAt)
        assertEquals(parseInstant("2026-10-02T08:00:06.000Z"), primary[0].timestampMs)

        assertEquals(85.0, primary[1].usedPercent)
        assertEquals(1792180422L, primary[1].resetsAt)
        assertEquals(parseInstant("2026-10-02T08:10:00.000Z"), primary[1].timestampMs)

        assertEquals(96.0, primary[2].usedPercent)
        val expectedResetsAt = (parseInstant("2026-10-02T08:15:00.000Z")!! / 1000L) + 1800L
        assertEquals(expectedResetsAt, primary[2].resetsAt)

        // Secondary:
        // 1st token_count: 25.0%, resets_at = 1792662911
        // 2nd token_count: 25.0% -> SKIPPED
        // 3rd token_count: 25.0% -> SKIPPED
        // 4th token_count: 90.0%, resets_at = 1792662911
        val secondary = r.rateLimits.filter { it.window == "7d" }
        assertEquals(2, secondary.size, "Consecutive identical 25.0% snapshots must be skipped")
        assertEquals(25.0, secondary[0].usedPercent)
        assertEquals(90.0, secondary[1].usedPercent)
    }

    @Test
    fun `store skips consecutive duplicates and persists history`() {
        val tmpDir = Files.createTempDirectory("store-rate-limit-test")
        val dbFile = tmpDir.resolve("test.db")

        Store(dbFile).use { store ->
            val baseTs = 1760000000000L
            val snapshots = listOf(
                RateLimitSnapshot("codex", "primary", 30.0, 1800000000L, baseTs),
                RateLimitSnapshot("codex", "primary", 30.0, 1800000000L, baseTs + 5_000L), // duplicate -> skip
                RateLimitSnapshot("codex", "primary", 35.0, 1800000000L, baseTs + 10_000L), // changed -> keep
                RateLimitSnapshot("codex", "primary", 35.0, 1800000000L, baseTs + 15_000L), // duplicate -> skip
                RateLimitSnapshot("codex", "primary", 30.0, 1800000000L, baseTs + 20_000L), // changed back -> keep
            )

            store.saveRateLimits(snapshots)

            val stored = store.rateLimitSnapshots("codex", "primary")
            assertEquals(3, stored.size)
            assertEquals(listOf(30.0, 35.0, 30.0), stored.map { it.usedPercent })
            assertEquals(listOf(baseTs, baseTs + 10_000L, baseTs + 20_000L), stored.map { it.timestampMs })

            // Re-saving exact same batch should not add any duplicates
            store.saveRateLimits(snapshots)
            assertEquals(3, store.rateLimitSnapshots("codex", "primary").size)

            // Saving another snapshot with identical value to the latest in DB must be skipped
            store.saveRateLimits(listOf(RateLimitSnapshot("codex", "primary", 30.0, 1800000000L, baseTs + 30_000L)))
            assertEquals(3, store.rateLimitSnapshots("codex", "primary").size)

            // Latest rate limit query returns the most recent snapshot per window
            val latest = store.latestRateLimits("codex")
            assertEquals(1, latest.size)
            assertEquals(30.0, latest[0].usedPercent)
            assertEquals(baseTs + 20_000L, latest[0].timestampMs)
        }
    }

    @Test
    fun `settings loads defaults and parses custom thresholds`() {
        val defaultSettings = Settings.default()
        assertEquals(listOf(80.0, 95.0), defaultSettings.warningThresholds)

        val parsed = Settings.parse("""{"warningThresholds": [75.0, 90.0, 99.0]}""")
        assertEquals(listOf(75.0, 90.0, 99.0), parsed.warningThresholds)

        val tmpDir = Files.createTempDirectory("settings-test")
        val settingsFile = tmpDir.resolve("settings.json")
        val loaded = Settings.load(settingsFile)
        assertTrue(Files.exists(settingsFile))
        assertEquals(listOf(80.0, 95.0), loaded.warningThresholds)
    }

    @Test
    fun `threshold logic triggers warning and critical levels appropriately`() {
        val now = 1760000000000L
        val thresholds = listOf(80.0, 95.0)

        // Snapshot under threshold: no warning
        val sNormal = listOf(RateLimitSnapshot("codex", "primary", 50.0, null, now))
        assertTrue(evaluateLimitWarnings(sNormal, thresholds).isEmpty())

        // Snapshot reaching 80%: WARNING
        val sWarning = listOf(RateLimitSnapshot("codex", "primary", 82.0, 1800000000L, now))
        val w1 = evaluateLimitWarnings(sWarning, thresholds)
        assertEquals(1, w1.size)
        assertEquals(80.0, w1[0].threshold)
        assertEquals(LimitWarningLevel.WARNING, w1[0].level)

        // Snapshot reaching 95%: CRITICAL
        val sCritical = listOf(RateLimitSnapshot("codex", "primary", 96.5, 1800000000L, now))
        val w2 = evaluateLimitWarnings(sCritical, thresholds)
        assertEquals(1, w2.size)
        assertEquals(95.0, w2[0].threshold)
        assertEquals(LimitWarningLevel.CRITICAL, w2[0].level)

        // Multiple windows simultaneously
        val sMulti = listOf(
            RateLimitSnapshot("codex", "primary", 96.0, null, now),
            RateLimitSnapshot("codex", "secondary", 85.0, null, now),
        )
        val wMulti = evaluateLimitWarnings(sMulti, thresholds)
        assertEquals(2, wMulti.size)
        assertEquals(LimitWarningLevel.CRITICAL, wMulti.first { it.snapshot.window == "primary" }.level)
        assertEquals(LimitWarningLevel.WARNING, wMulti.first { it.snapshot.window == "secondary" }.level)
    }

    @Test
    fun `rate limit provider interface and codex implementation`() {
        val tmpDir = Files.createTempDirectory("provider-test")
        val dbFile = tmpDir.resolve("test.db")

        Store(dbFile).use { store ->
            val now = System.currentTimeMillis()
            store.saveRateLimits(
                listOf(
                    RateLimitSnapshot("codex", "primary", 42.0, now / 1000L + 3600L, now),
                    RateLimitSnapshot("codex", "secondary", 60.0, now / 1000L + 7200L, now),
                )
            )

            val provider: RateLimitProvider = CodexRateLimitProvider(store)
            assertEquals("codex", provider.agent)
            val limits = provider.getLimits()
            assertEquals(2, limits.size)
            assertEquals(setOf("primary", "secondary"), limits.map { it.window }.toSet())
        }
    }

    @Test
    fun `window labels come from window length`() {
        assertEquals("5h", windowLabel(300))
        assertEquals("7d", windowLabel(10080))
        assertEquals("1d", windowLabel(1440))
        assertEquals("2h", windowLabel(120))
        assertEquals("90m", windowLabel(90))
        assertEquals("30d", windowLabel(43200))
    }

    @Test
    fun `old logs with the 7-day window in primary get the same label as new ones`() {
        val r = CodexImporter.parse(fixture("codex-rollout-old-windows.jsonl"), lines("codex-rollout-old-windows.jsonl"))
        val windows = r.rateLimits.map { it.window to it.usedPercent }
        // primary(10080) -> "7d" (not "5h"); 12 then 14 kept; no window_minutes -> slot name; 90 min -> "90m"
        assertEquals(listOf("7d" to 12.0, "7d" to 14.0, "primary" to 5.0, "90m" to 7.0), windows)
    }

    @Test
    fun `old and new logs share one window key in the store`() {
        Files.createTempDirectory("store-windows").let { dir ->
            Store(dir.resolve("t.db")).use { store ->
                val old = CodexImporter.parse(fixture("codex-rollout-old-windows.jsonl"), lines("codex-rollout-old-windows.jsonl"))
                val new = CodexImporter.parse(fixture("codex-rollout-rate-limits.jsonl"), lines("codex-rollout-rate-limits.jsonl"))
                store.saveRateLimits(old.rateLimits + new.rateLimits)
                val sevenDay = store.latestRateLimits("codex").single { it.window == "7d" }
                assertEquals(90.0, sevenDay.usedPercent) // newer secondary(10080) log wins over old primary(10080)
            }
        }
    }

    @Test
    fun `legacy primary-secondary rows are dropped once and files rescanned`() {
        val db = Files.createTempDirectory("store-migrate").resolve("t.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use {
                it.executeUpdate("CREATE TABLE rate_limit_snapshots(agent TEXT NOT NULL, window TEXT NOT NULL, used_percent REAL NOT NULL, resets_at INTEGER, ts INTEGER NOT NULL, PRIMARY KEY(agent, window, ts))")
                it.executeUpdate("CREATE TABLE files(path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, byte_offset INTEGER, lines INTEGER)")
                it.executeUpdate("INSERT INTO rate_limit_snapshots VALUES('codex','primary',50.0,1,1)")
                it.executeUpdate("INSERT INTO files VALUES('x',1,1,1,1)")
            }
        }
        Store(db).use { store ->
            assertTrue(store.latestRateLimits().isEmpty())
            assertNull(store.fileState("x"))
            store.saveRateLimits(listOf(RateLimitSnapshot("codex", "primary", 5.0, null, 10))) // fallback label survives
            store.saveFileState("y", FileState(1, 1, 1, 1))
        }
        Store(db).use { store ->
            assertEquals(1, store.latestRateLimits().size)
            assertNotNull(store.fileState("y"))
        }
    }

    @Test
    fun `snapshot is reset once resets_at passed after it was taken`() {
        val reset = 1_800_000_000L
        val before = RateLimitSnapshot("codex", "5h", 97.0, reset, (reset - 600) * 1000)
        assertFalse(before.hasReset((reset - 1) * 1000))
        assertTrue(before.hasReset(reset * 1000))
        assertTrue(before.hasReset((reset + 3600) * 1000))
        // taken after the reset time (log already shows the new window) or unknown reset -> not reset
        assertFalse(RateLimitSnapshot("codex", "5h", 3.0, reset, (reset + 5) * 1000).hasReset((reset + 3600) * 1000))
        assertFalse(RateLimitSnapshot("codex", "5h", 3.0, null, 0).hasReset(Long.MAX_VALUE))
    }

    @Test
    fun `reset snapshots raise no warning`() {
        val reset = 1_800_000_000L
        val s = listOf(RateLimitSnapshot("codex", "5h", 97.0, reset, (reset - 600) * 1000))
        assertEquals(1, evaluateLimitWarnings(s, listOf(80.0, 95.0), (reset - 60) * 1000).size)
        assertTrue(evaluateLimitWarnings(s, listOf(80.0, 95.0), (reset + 60) * 1000).isEmpty())
        val d = buildDashboard(emptyList(), PriceTable(), java.time.LocalDate.of(2027, 1, 1), rateLimits = s, nowMs = (reset + 60) * 1000)
        assertTrue(d.limitWarnings.isEmpty())
    }

    @Test
    fun `ui texts are english and follow locale`() {
        val zone = java.time.ZoneId.of("UTC")
        val now = Instant.parse("2026-10-16T10:00:00Z")
        val today = Instant.parse("2026-10-16T15:45:00Z")
        val later = Instant.parse("2026-10-21T15:45:00Z")
        assertEquals("Resets at 15:45", formatResetTime(today, now.toEpochMilli(), zone, java.util.Locale.GERMANY))
        assertEquals("Resets at 3:45 PM", formatResetTime(today, now.toEpochMilli(), zone, java.util.Locale.US))
        assertEquals("Resets 21.10.26 15:45", formatResetTime(later, now.toEpochMilli(), zone, java.util.Locale.GERMANY))
        assertEquals("Resets 10/21/26 3:45 PM", formatResetTime(later, now.toEpochMilli(), zone, java.util.Locale.US))
        assertEquals("No reset time", formatResetTime(null))

        assertEquals("updated <1 min ago", formatUpdatedAgo(1_000, 30_000))
        assertEquals("updated 5 min ago", formatUpdatedAgo(0, 5 * 60_000L))
        assertEquals("updated 3 h ago", formatUpdatedAgo(0, 3 * 3_600_000L))
        assertEquals("updated 2 d ago", formatUpdatedAgo(0, 2 * 86_400_000L))
        assertEquals("5h limit", limitName("5h"))
    }
}
