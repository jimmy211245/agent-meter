package dev.agentmeter

import dev.agentmeter.importers.AntigravityImporter
import dev.agentmeter.importers.Importers
import dev.agentmeter.importers.Protobuf
import dev.agentmeter.importers.message
import dev.agentmeter.importers.string
import dev.agentmeter.importers.varint
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hand-built protobuf, numbers only. */
private object Pb {
    private fun varintBytes(v: Long): ByteArray {
        val o = ByteArrayOutputStream()
        var x = v
        while (x and 0x7f.inv().toLong() != 0L) { o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
        o.write(x.toInt())
        return o.toByteArray()
    }
    fun v(field: Int, value: Long) = varintBytes((field.toLong() shl 3) or 0) + varintBytes(value)
    fun bytes(field: Int, payload: ByteArray) = varintBytes((field.toLong() shl 3) or 2) + varintBytes(payload.size.toLong()) + payload
    fun s(field: Int, text: String) = bytes(field, text.toByteArray())
    fun fixed32(field: Int, value: Int) = varintBytes((field.toLong() shl 3) or 5) + ByteArray(4) { (value ushr (8 * it)).toByte() }
    fun fixed64(field: Int, value: Long) = varintBytes((field.toLong() shl 3) or 1) + ByteArray(8) { (value ushr (8 * it)).toByte() }
    fun cat(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }
}

class AntigravityTest {
    private val uuid = "00000000-0000-4000-8000-000000000001"
    private val t0 = 1_792_000_000L // seconds

    // ---------- decoder ----------

    @Test
    fun `decoder reads varints, fixed32, fixed64, strings and nested messages and skips nothing it is not asked for`() {
        val msg = Pb.cat(
            Pb.v(1, 300), Pb.v(2, -1L), Pb.v(3, 0),
            Pb.fixed32(4, 0x01020304), Pb.fixed64(5, 0x0102030405060708L),
            Pb.s(6, "abc"), Pb.bytes(7, Pb.v(1, 42)), Pb.v(200, 7),
        )
        val f = Protobuf.decode(msg)!!
        assertEquals(8, f.size)
        assertEquals(300, f.varint(1))
        assertEquals(-1L, f.varint(2)) // 10-byte varint, all bits set
        assertEquals(0x01020304L, f.varint(4))
        assertEquals(0x0102030405060708L, f.varint(5))
        assertEquals("abc", f.string(6))
        assertEquals(42, f.message(7)!!.varint(1))
        assertEquals(7, f.varint(200))
        assertEquals(0, f.varint(99)) // absent -> proto3 default
        assertNull(f.message(99))
        assertEquals(emptyList(), Protobuf.decode(ByteArray(0)))
    }

    @Test
    fun `decoder rejects malformed input instead of throwing`() {
        val good = Pb.cat(Pb.v(1, 5), Pb.s(2, "hello"))
        assertNull(Protobuf.decode(good.copyOf(good.size - 1))) // truncated string
        assertNull(Protobuf.decode(byteArrayOf(0x08))) // key without value
        assertNull(Protobuf.decode(byteArrayOf(0x08, 0x80.toByte()))) // unterminated varint
        assertNull(Protobuf.decode(ByteArray(12) { 0xff.toByte() })) // varint longer than 10 bytes
        assertNull(Protobuf.decode(byteArrayOf(0x00, 0x01))) // field number 0
        assertNull(Protobuf.decode(byteArrayOf(0x0b))) // start-group wire type
        assertNull(Protobuf.decode(byteArrayOf(0x12, 0x7f, 0x01))) // length larger than the data
        assertNull(Protobuf.decode(byteArrayOf(0x0d, 1, 2, 3))) // truncated fixed32
    }

    // ---------- fixture database ----------

    private fun usage(enum: Long, responseId: String?, input: Long, output: Long, thinking: Long, cacheRead: Long, cacheWrite: Long = 0) =
        Pb.cat(
            Pb.v(1, enum), Pb.v(2, input), Pb.v(3, output), Pb.v(4, cacheWrite), Pb.v(5, cacheRead),
            Pb.v(6, 24), Pb.v(9, thinking), Pb.v(10, output - thinking),
            if (responseId != null) Pb.s(11, responseId) else ByteArray(0),
        )

    private fun step(atSec: Long, usage: ByteArray?, withRetry: Boolean = false) = Pb.cat(
        Pb.bytes(1, Pb.cat(Pb.v(1, atSec), Pb.v(2, 250_000_000))),
        Pb.v(3, 1),
        if (usage != null) Pb.bytes(9, usage) else ByteArray(0),
        // CortexStepMetadata.retry_infos[].usage repeats the same numbers; it must not be counted again
        if (withRetry && usage != null) Pb.bytes(28, Pb.bytes(2, usage)) else ByteArray(0),
    )

    private fun gen(name: String, enum: Long, responseId: String) =
        Pb.bytes(1, Pb.cat(Pb.v(3, enum), Pb.bytes(4, Pb.cat(Pb.v(1, enum), Pb.s(11, responseId))), Pb.s(19, name)))

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.insert(sql: String, idx: Int, blob: ByteArray) =
        prepareStatement(sql).use { it.setInt(1, idx); it.setBytes(2, blob); it.executeUpdate() }

    /** home/.gemini/<dir>/conversations/<uuid>.db with a still-open WAL writer, like a running Antigravity. */
    private fun newConversation(home: Path, dir: String = "antigravity"): Pair<Path, Connection> {
        val conv = home.resolve(".gemini").resolve(dir).resolve("conversations").createDirectories()
        val db = conv.resolve("$uuid.db")
        val c = DriverManager.getConnection("jdbc:sqlite:$db")
        c.exec("PRAGMA journal_mode=WAL")
        c.exec("PRAGMA wal_autocheckpoint=0")
        c.exec("CREATE TABLE steps(idx integer PRIMARY KEY, step_type integer NOT NULL DEFAULT 0, metadata blob)")
        c.exec("CREATE TABLE gen_metadata(idx integer PRIMARY KEY, data blob, size integer NOT NULL DEFAULT 0)")
        c.exec("CREATE TABLE trajectory_metadata_blob(id text PRIMARY KEY, data blob)")

        c.insert("INSERT INTO gen_metadata(idx, data) VALUES(?, ?)", 0, gen("gemini-3.8-flash", 1318, "r1"))
        c.insert("INSERT INTO gen_metadata(idx, data) VALUES(?, ?)", 1, gen("gemini-3.8-flash-n", 1318, "r2"))
        val steps = listOf(
            0 to step(t0, null), // user step: no usage
            1 to step(t0 + 10, usage(1318, "r1", input = 2893, output = 61, thinking = 17, cacheRead = 22960), withRetry = true),
            2 to step(t0 + 20, usage(1318, "r1", input = 2893, output = 61, thinking = 17, cacheRead = 22960)), // same response again
            3 to step(t0 + 30, usage(1318, "r2", input = 100, output = 10, thinking = 0, cacheRead = 5, cacheWrite = 7)),
            4 to step(t0 + 40, usage(1318, "r3", input = 1, output = 2, thinking = 1, cacheRead = 3)), // unknown response id -> enum name
            5 to step(t0 + 50, usage(99, null, input = 4, output = 5, thinking = 0, cacheRead = 6)), // no response id, unknown enum
            6 to byteArrayOf(0x0a, 0x7f), // not a protobuf message
        )
        for ((i, m) in steps) c.insert("INSERT INTO steps(idx, metadata) VALUES(?, ?)", i, m)
        c.prepareStatement("INSERT INTO trajectory_metadata_blob(id, data) VALUES('main', ?)").use {
            it.setBytes(1, Pb.bytes(1, Pb.s(1, "file:///c%3A/work/My%20Project"))); it.executeUpdate()
        }
        return db to c
    }

    @Test
    fun `reads usage from steps, names models, dedupes and ignores retry copies`() {
        val home = Files.createTempDirectory("ag-test")
        val (db, writer) = newConversation(home)
        writer.use {
            val r = AntigravityImporter.parseFile(db)
            val byKey = r.events.associateBy { it.eventKey }
            assertEquals(setOf("r1", "r2", "r3", "$uuid:5"), byKey.keys)

            val a = byKey.getValue("r1")
            assertEquals("antigravity", a.agent)
            assertEquals(uuid, a.sessionId)
            assertEquals("gemini-3.8-flash", a.model)
            assertEquals("antigravity", a.provider)
            assertEquals((t0 + 10) * 1000 + 250, a.timestampMs)
            assertEquals(2893, a.inputTokens)
            assertEquals(22960, a.cacheReadTokens)
            assertEquals(61, a.outputTokens) // thinking (17) is already inside; field 9 is not added again
            assertEquals(0, a.cacheWriteTokens)
            assertEquals("C:\\work\\My Project", a.project) // from trajectory_metadata_blob (no summaries db here)

            val b = byKey.getValue("r2")
            assertEquals("gemini-3.8-flash-n", b.model) // matched by response id
            assertEquals(7, b.cacheWriteTokens)
            assertEquals("gemini-3.8-flash-n", byKey.getValue("r3").model) // matched by enum (latest name)
            assertEquals("antigravity-model-99", byKey.getValue("$uuid:5").model)

            // r1 appears twice, once with a retry copy: counted once
            assertEquals(2893L + 100 + 1 + 4, r.events.sumOf { it.inputTokens })
            assertEquals(61L + 10 + 2 + 5, r.events.sumOf { it.outputTokens })
            assertEquals(1, r.errors.size)
            assertTrue("undecodable" in r.errors.single())
        }
    }

    @Test
    fun `project comes from conversation_summaries workspace_uris, first uri`() {
        val home = Files.createTempDirectory("ag-sum")
        val (db, writer) = newConversation(home)
        writer.use {
            val summaries = home.resolve(".gemini/antigravity/conversation_summaries.db")
            DriverManager.getConnection("jdbc:sqlite:$summaries").use { c ->
                c.exec("CREATE TABLE conversation_summaries(conversation_id text PRIMARY KEY, workspace_uris text NOT NULL)")
                c.prepareStatement("INSERT INTO conversation_summaries VALUES(?, ?)").use {
                    it.setString(1, uuid); it.setString(2, """["file:///c%3A/claude/Agent%20meter","file:///d%3A/other"]"""); it.executeUpdate()
                }
            }
            assertEquals("C:\\claude\\Agent meter", AntigravityImporter.parseFile(db).events.first().project)
        }
        assertEquals("/home/dev/p", AntigravityImporter.uriToPath("file:///home/dev/p/"))
        assertEquals("not a uri", AntigravityImporter.uriToPath("not a uri"))
    }

    @Test
    fun `ide folder is the same agent and tells the source apart`() {
        val home = Files.createTempDirectory("ag-ide")
        val (db, writer) = newConversation(home, dir = "antigravity-ide")
        writer.use {
            assertEquals("antigravity-ide", AntigravityImporter.parseFile(db).events.first().provider)
            assertEquals(
                listOf(home.resolve(".gemini/antigravity/conversations"), home.resolve(".gemini/antigravity-ide/conversations")),
                AntigravityImporter.roots(home),
            )
            assertTrue(AntigravityImporter.accepts(db))
            assertTrue(!AntigravityImporter.accepts(db.resolveSibling("$uuid.db-wal")))
            assertTrue(!AntigravityImporter.accepts(Path.of("conversation_summaries.db")))
        }
    }

    @Test
    fun `database and wal file stay byte-identical, writes are impossible`() {
        val home = Files.createTempDirectory("ag-ro")
        val (db, writer) = newConversation(home)
        writer.use {
            val wal = db.resolveSibling("$uuid.db-wal")
            assertTrue(Files.size(wal) > 0, "test needs uncheckpointed WAL data")
            fun digest(p: Path) = MessageDigest.getInstance("SHA-256").digest(p.readBytes()).toList()
            val before = listOf(digest(db), digest(wal))
            assertTrue(AntigravityImporter.parseFile(db).events.isNotEmpty()) // sees WAL-only rows
            assertEquals(before, listOf(digest(db), digest(wal)))
            val count = writer.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM steps").use { it.getInt(1) } }
            assertEquals(7, count)
        }
    }

    @Test
    fun `foreign or broken databases never throw`() {
        val dir = Files.createTempDirectory("ag-bad")
        val notDb = dir.resolve("$uuid.db").also { Files.writeString(it, "this is not a database") }
        assertTrue(AntigravityImporter.parseFile(notDb).let { it.events.isEmpty() && it.errors.isNotEmpty() })
        val other = dir.resolve("11111111-2222-3333-4444-555555555555.db")
        DriverManager.getConnection("jdbc:sqlite:$other").use { it.exec("CREATE TABLE unrelated(a)") }
        assertTrue(AntigravityImporter.parseFile(other).errors.isNotEmpty())
    }

    @Test
    fun `scanner imports app and ide, prices follow the model names`() {
        val home = Files.createTempDirectory("ag-scan")
        val (_, w1) = newConversation(home)
        w1.use {
            Store(home.resolve("am.db")).use { store ->
                val scanner = Scanner(store, Importers.all, home)
                assertEquals(1, scanner.scanOnce().filesRead)
                assertEquals(3, store.rows().size) // gemini-3.8-flash, gemini-3.8-flash-n, antigravity-model-99
                val prices = PriceTable.parse(
                    """{"models":{"gemini-3.8-flash":{"input":0.75,"cachedInput":0.075,"output":3.75},
                       "claude-opus-5-5":{"input":4,"output":20,"aliases":["claude-opus-5-5-medium"]}}}""",
                )
                assertNotNull(prices.find("claude-opus-5-5-medium"))
                assertNull(prices.find("gemini-3.8-flash-n")) // not on the official page: stays unpriced
                assertEquals(0, scanner.scanOnce().filesRead) // unchanged
            }
        }
    }
}
