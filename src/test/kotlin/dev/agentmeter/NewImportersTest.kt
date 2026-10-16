package dev.agentmeter

import dev.agentmeter.importers.DeepSeekHarnessImporter
import dev.agentmeter.importers.Importers
import dev.agentmeter.importers.OpenCodeImporter
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

class NewImportersTest {
    private val monPeak = ms("2026-10-12T02:30:00Z") // Monday, inside 01-04 UTC
    private val deepseekFixture = "deepseek-session.v4.jsonl.zstd"

    // ---------- DeepSeek Harness ----------

    private fun deepseekLines(): Sequence<String> =
        ZstdFrames.decodeText(fixture(deepseekFixture).readBytes()).lines().asSequence()

    @Test
    fun `deepseek - every zstd frame is read, not only the first`() {
        val text = ZstdFrames.decodeText(fixture(deepseekFixture).readBytes())
        // 11 lines in 9 frames (the first frame holds three lines)
        assertEquals(11, text.lines().count { it.isNotBlank() })
    }

    @Test
    fun `deepseek - parses usage, cwd, model and dedupes by session and seq`() {
        val r = DeepSeekHarnessImporter.parse(fixture(deepseekFixture), deepseekLines())
        val byKey = r.events.associateBy { it.eventKey }
        assertEquals(listOf("demo-session-1:5", "demo-session-1:9", "demo-session-1:12", "demo-session-1:15"), r.events.map { it.eventKey })

        val a = byKey.getValue("demo-session-1:5")
        assertEquals("deepseek-harness", a.agent)
        assertEquals("demo-session-1", a.sessionId)
        assertEquals("/home/dev/demo-project", a.project)
        assertEquals("deepseek-flash", a.model)
        assertEquals("deepseek-account", a.provider)
        assertEquals(monPeak, a.timestampMs)
        assertEquals(1000, a.inputTokens)
        assertEquals(9000, a.cacheReadTokens)
        assertEquals(200, a.outputTokens)
        assertNull(a.reportedCostUsd)

        assertEquals("deepseek-v4.1-flash", byKey.getValue("demo-session-1:15").model)
        assertEquals(1, r.errors.size, r.errors.toString()) // the damaged line only; usage-less assistant lines are not errors
    }

    @Test
    fun `deepseek - half-written last frame is ignored until it is complete`() {
        val whole = fixture(deepseekFixture).readBytes()
        val cut = whole.copyOf(whole.size - 7)
        val text = ZstdFrames.decodeText(cut)
        assertTrue(text.endsWith("\n"))
        val r = DeepSeekHarnessImporter.parse(Path.of("x"), text.lines().asSequence())
        assertEquals(3, r.events.size) // the last response (seq 15) is not complete yet
        assertEquals("", ZstdFrames.decodeText(ByteArray(0)))
        assertEquals("", ZstdFrames.decodeText(byteArrayOf(1, 2, 3, 4, 5))) // garbage must not throw
    }

    // ---------- OpenCode ----------

    private fun newDb(dir: Path): Pair<Path, Connection> {
        val db = dir.resolve("opencode.db")
        val c = DriverManager.getConnection("jdbc:sqlite:$db")
        c.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA wal_autocheckpoint=0") // keep new rows in the -wal file like a running OpenCode
            it.execute("CREATE TABLE session(id TEXT PRIMARY KEY, directory TEXT)")
            it.execute("CREATE TABLE message(id TEXT PRIMARY KEY, session_id TEXT, time_created INTEGER, time_updated INTEGER, data TEXT)")
            it.execute("INSERT INTO session VALUES('ses_1','/home/dev/other')")
        }
        return db to c
    }

    private fun Connection.msg(id: String, data: String, time: Long = monPeak) =
        prepareStatement("INSERT INTO message VALUES(?, 'ses_1', ?, ?, ?)").use { st ->
            st.setString(1, id); st.setLong(2, time); st.setLong(3, time); st.setString(4, data); st.executeUpdate()
        }

    private fun assistant(model: String, provider: String, cost: Double, input: Long, output: Long, reasoning: Long, read: Long, write: Long, cwd: String?, time: Long = monPeak) =
        """{"role":"assistant","modelID":"$model","providerID":"$provider","cost":$cost,
            "tokens":{"total":1,"input":$input,"output":$output,"reasoning":$reasoning,"cache":{"read":$read,"write":$write}},
            ${if (cwd != null) """"path":{"cwd":"$cwd","root":"$cwd"},""" else ""}
            "time":{"created":$time,"completed":${time + 5}},"text":"<redacted>"}"""

    private fun populate(c: Connection) {
        c.msg("msg_1", assistant("model-a", "prov", 0.5, 100, 20, 30, 1000, 5, "/home/dev/proj"))
        c.msg("msg_2", assistant("deepseek-flash", "prov", 0.0, 1000, 200, 0, 9000, 0, null))
        c.msg("msg_3", """{"role":"user","text":"<redacted>"}""")
        c.msg("msg_4", """{"role":"assistant","modelID":"model-a"}""") // no tokens
        c.msg("msg_5", "{ not json")
    }

    @Test
    fun `opencode - tokens, model, provider, cwd, cost and dedupe by message id`() {
        val dir = Files.createTempDirectory("oc-test")
        val (db, writer) = newDb(dir)
        writer.use { c ->
            populate(c)
            val r = OpenCodeImporter.parseFile(db)
            val byKey = r.events.associateBy { it.eventKey }
            assertEquals(setOf("msg_1", "msg_2"), byKey.keys)

            val m1 = byKey.getValue("msg_1")
            assertEquals("opencode", m1.agent)
            assertEquals("ses_1", m1.sessionId)
            assertEquals("model-a", m1.model)
            assertEquals("prov", m1.provider)
            assertEquals("/home/dev/proj", m1.project)
            assertEquals(monPeak, m1.timestampMs)
            assertEquals(100, m1.inputTokens)
            assertEquals(50, m1.outputTokens) // output + reasoning are billed as output
            assertEquals(1000, m1.cacheReadTokens)
            assertEquals(5, m1.cacheWriteTokens)
            assertEquals(0.5, m1.reportedCostUsd)

            val m2 = byKey.getValue("msg_2")
            assertNull(m2.reportedCostUsd) // cost 0 -> computed from prices
            assertEquals("/home/dev/other", m2.project) // falls back to the session directory

            assertEquals(1, r.errors.size)
            assertTrue("invalid JSON" in r.errors.single())

            // importing twice does not double count
            Store(dir.resolve("am.db")).use { store ->
                store.upsert(r.events)
                store.upsert(OpenCodeImporter.parseFile(db).events)
                assertEquals(2, store.rows().size)
                assertEquals(1000L + 100, store.rows().sumOf { it.tokens.input })
            }
        }
    }

    @Test
    fun `opencode - database and wal file are never modified, writes are impossible`() {
        val dir = Files.createTempDirectory("oc-ro")
        val (db, writer) = newDb(dir)
        writer.use { c ->
            populate(c)
            val wal = dir.resolve("opencode.db-wal")
            assertTrue(Files.size(wal) > 0, "test needs uncheckpointed WAL data")
            fun digest(p: Path) = MessageDigest.getInstance("SHA-256").digest(p.readBytes()).toList()
            val before = listOf(digest(db), digest(wal))

            assertEquals(2, OpenCodeImporter.parseFile(db).events.size) // sees WAL-only rows while a writer is open
            OpenCodeImporter.open(db).use { ro ->
                assertFailsWith<Exception> { ro.createStatement().use { it.executeUpdate("INSERT INTO session VALUES('x','y')") } }
                assertFailsWith<Exception> { ro.createStatement().use { it.executeUpdate("CREATE TABLE t(a)") } }
            }

            assertEquals(before, listOf(digest(db), digest(wal)))
            assertEquals(0, c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM session WHERE id='x'").use { rs -> rs.getInt(1) } })
        }
    }

    @Test
    fun `opencode - unreadable or foreign databases never throw`() {
        val dir = Files.createTempDirectory("oc-bad")
        val notDb = dir.resolve("opencode.db").also { Files.writeString(it, "this is not a database") }
        assertTrue(OpenCodeImporter.parseFile(notDb).let { it.events.isEmpty() && it.errors.isNotEmpty() })

        val other = dir.resolve("opencode-dev.db")
        DriverManager.getConnection("jdbc:sqlite:$other").use { it.createStatement().use { st -> st.execute("CREATE TABLE unrelated(a)") } }
        assertTrue(OpenCodeImporter.parseFile(other).errors.isNotEmpty()) // missing message table is reported, not thrown

        assertTrue(OpenCodeImporter.accepts(Path.of("opencode.db")))
        assertTrue(OpenCodeImporter.accepts(Path.of("opencode-dev.db")))
        assertFalse(OpenCodeImporter.accepts(Path.of("opencode.db-wal")))
        assertFalse(OpenCodeImporter.accepts(Path.of("auth.json")))
    }

    @Test
    fun `opencode - data dir honours XDG_DATA_HOME and the default location`() {
        val home = Path.of("home-x")
        val def = home.resolve(".local").resolve("share").resolve("opencode")
        assertEquals(listOf(def), OpenCodeImporter.rootsFor(home, null))
        assertEquals(listOf(def), OpenCodeImporter.rootsFor(home, "  "))
        assertEquals(listOf(Path.of("xdg-x").resolve("opencode"), def), OpenCodeImporter.rootsFor(home, "xdg-x"))
        assertEquals(listOf(def), OpenCodeImporter.rootsFor(home, def.parent.toString()))
    }

    // ---------- scanner / watcher ----------

    @Test
    fun `scanner imports both agents, notices wal-only changes and prices peak hours per response`() {
        val home = Files.createTempDirectory("agent-home")
        val ocDir = home.resolve(".local/share/opencode").createDirectories()
        val (db, writer) = newDb(ocDir)
        val dsDir = home.resolve(".dsh/sessions/--demo--/session-demo-session-1").createDirectories()
        fixture(deepseekFixture).copyTo(dsDir.resolve("session.v4.jsonl.zstd"))

        writer.use { c ->
            populate(c)
            Store(home.resolve("am.db")).use { store ->
                val scanner = Scanner(store, Importers.all, home)
                assertEquals(2, scanner.scanOnce().filesRead)
                assertEquals(0, scanner.scanOnce().filesRead) // nothing changed

                val prices = PriceTable.bundled()
                val today = LocalDate.of(2026, 10, 20)
                fun dash() = buildDashboard(store.rows(0, prices.timeDependentModels(store.distinctModels())), prices, today, Range.ALL, nowMs = monPeak)
                val first = dash()
                val per = first.byAgent.associate { it.name to it.cost }
                // opencode: reported 0.5 + deepseek-flash at peak: (1000*0.3 + 9000*0.006 + 200*1.2) / 1e6
                assertEquals(0.5 + 594e-6, per.getValue("opencode"), 1e-9)
                // deepseek: peak 594e-6, off-peak 360e-6, 09:59:59 peak 210e-6, Saturday via alias 210e-6
                assertEquals(594e-6 + 360e-6 + 210e-6 + 210e-6, per.getValue("deepseek-harness"), 1e-9)
                assertTrue(first.unpricedModels.isEmpty(), first.unpricedModels.toString())

                // a row that only lands in the -wal file is picked up by the next scan
                c.msg("msg_6", assistant("model-a", "prov", 0.25, 10, 5, 0, 0, 0, "/home/dev/proj", monPeak + 60_000))
                assertEquals(1, scanner.scanOnce().filesRead)
                assertEquals(0.5 + 594e-6 + 0.25, dash().byAgent.single { it.name == "opencode" }.cost, 1e-9)
            }
        }
    }

    // ---------- prices: aliases and peak windows ----------

    @Test
    fun `peak windows are Mon-Fri 01-04 and 06-10 UTC, end hour exclusive`() {
        val peak = PriceTable.bundled().find("deepseek-flash")!!.peak!!
        fun p(iso: String) = peak.isPeak(ms(iso))
        assertFalse(p("2026-10-12T00:59:59Z")) // Mon
        assertTrue(p("2026-10-12T01:00:00Z"))
        assertTrue(p("2026-10-12T03:59:59Z"))
        assertFalse(p("2026-10-12T04:00:00Z"))
        assertFalse(p("2026-10-12T05:59:59Z"))
        assertTrue(p("2026-10-12T06:00:00Z"))
        assertTrue(p("2026-10-12T09:59:59Z"))
        assertFalse(p("2026-10-12T10:00:00Z"))
        assertTrue(p("2026-10-16T02:00:00Z")) // Friday
        assertFalse(p("2026-10-17T02:00:00Z")) // Saturday
        assertFalse(p("2026-10-18T07:00:00Z")) // Sunday
    }

    @Test
    fun `deepseek prices, aliases and per-response peak costing`() {
        val t = PriceTable.bundled()
        listOf("deepseek-v4.1-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp").forEach {
            assertEquals(t.find("deepseek-flash"), t.find(it), it)
        }
        assertFalse(t.isEstimated("deepseek-flash"))
        val tokens = Tokens(input = 1_000_000, cacheRead = 1_000_000, output = 1_000_000)
        val off = ms("2026-10-17T02:00:00Z")
        assertEquals(0.15 + 0.003 + 0.6, t.cost("deepseek-flash", tokens, off)!!, 1e-9)
        assertEquals(0.3 + 0.006 + 1.2, t.cost("deepseek-flash", tokens, monPeak)!!, 1e-9)
        assertEquals(0.66 + 0.022 + 1.98, t.cost("deepseek-v4-pro", tokens, off)!!, 1e-9)
        assertEquals(1.32 + 0.044 + 3.96, t.cost("deepseek-v4-pro", tokens, monPeak)!!, 1e-9)
        assertEquals(0.15 + 0.003 + 0.6, t.cost("deepseek-flash", tokens)!!, 1e-9) // no timestamp -> base price
        assertEquals(setOf("deepseek-v4.1-flash", "deepseek-flash"), t.timeDependentModels(listOf("deepseek-v4.1-flash", "deepseek-flash", "claude-opus-5-5")))
    }
}
