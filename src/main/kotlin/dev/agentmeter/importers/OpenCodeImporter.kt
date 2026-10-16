package dev.agentmeter.importers

import dev.agentmeter.UsageEvent
import kotlinx.serialization.json.JsonObject
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.logging.Logger

/**
 * OpenCode keeps everything in one SQLite database (`opencode.db`, WAL mode) under
 * `$XDG_DATA_HOME/opencode` or `~/.local/share/opencode` (also on Windows and macOS).
 * Each assistant row of `message` has a JSON `data` with `tokens {input, output, reasoning, cache {read, write}}`,
 * `modelID`, `providerID`, `cost`, `time.created` and `path.cwd`; the message id is the dedupe key.
 *
 * The database is opened strictly read-only and only the numbers and ids above are read, never prompt or answer text.
 * OpenCode counts reasoning separately from output (output + reasoning is what providers bill as output) and
 * `input` excludes cached tokens.
 */
object OpenCodeImporter : Importer {
    override val agent = "opencode"
    override val lineIndependent = false
    override val maxDepth = 1 // the data dir also holds git checkouts that are not worth walking

    private val log = Logger.getLogger("opencode")
    private val DB_NAME = Regex("opencode(-[A-Za-z0-9._-]+)?\\.db")

    override fun roots(home: Path) = rootsFor(home, System.getenv("XDG_DATA_HOME"))

    internal fun rootsFor(home: Path, xdgDataHome: String?): List<Path> =
        listOfNotNull(
            xdgDataHome?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve("opencode") },
            home.resolve(".local").resolve("share").resolve("opencode"),
        ).distinct()

    override fun accepts(file: Path) = DB_NAME.matches(file.fileName.toString())

    override fun watchedFiles(file: Path): List<Path> =
        listOf(file, file.resolveSibling(file.fileName.toString() + "-wal"))

    override fun parse(file: Path, lines: Sequence<String>, firstLine: Int) = ParseResult(emptyList(), emptyList())

    // ponytail: re-reads all assistant messages when the db changes (milliseconds for thousands of rows);
    // filter on time_created > last seen if databases ever get huge.
    override fun parseFile(file: Path): ParseResult {
        val errors = mutableListOf<String>()
        val events = LinkedHashMap<String, UsageEvent>()
        try {
            withReadOnlyConnection(file) { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(
                        """SELECT m.id, m.session_id, m.time_created, m.data, s.directory
                           FROM message m LEFT JOIN session s ON s.id = m.session_id
                           WHERE CASE WHEN json_valid(m.data) THEN json_extract(m.data, '${'$'}.role') END = 'assistant'""",
                    ).use { rs ->
                        while (rs.next()) {
                            val id = rs.getString(1)
                            val data = runCatching { lenientJson.parseToJsonElement(rs.getString(4)) as? JsonObject }.getOrNull()
                            if (data == null) {
                                errors += "$file: message $id: data is not a JSON object"
                                continue
                            }
                            toEvent(id, rs.getString(2), rs.getLong(3), data, rs.getString(5))?.let { events[id] = it }
                        }
                    }
                }
                val bad = conn.createStatement().use { st ->
                    st.executeQuery("SELECT count(*) FROM message WHERE NOT json_valid(data)").use { rs -> rs.getInt(1) }
                }
                if (bad > 0) errors += "$file: $bad messages with invalid JSON data skipped"
            }
        } catch (e: Exception) {
            errors += "$file: ${e.javaClass.simpleName}: ${e.message}"
        }
        return ParseResult(events.values.toList(), errors)
    }

    private fun toEvent(id: String, sessionId: String?, createdFallback: Long, data: JsonObject, sessionDir: String?): UsageEvent? {
        val t = data.obj("tokens") ?: return null
        val cache = t.obj("cache")
        return UsageEvent(
            agent = agent,
            eventKey = id,
            sessionId = sessionId ?: "unknown",
            timestampMs = data.obj("time").longOrNull("created") ?: createdFallback,
            model = data.str("modelID") ?: "unknown",
            project = data.obj("path").str("cwd") ?: sessionDir,
            inputTokens = t.long("input"),
            cacheReadTokens = cache.long("read"),
            cacheWriteTokens = cache.long("write"),
            outputTokens = t.long("output") + t.long("reasoning"),
            provider = data.str("providerID"),
            reportedCostUsd = data.double("cost")?.takeIf { it > 0 },
        )
    }

    /**
     * Opens [file] with SQLITE_OPEN_READONLY and `query_only`. If that is impossible (e.g. the WAL index cannot be
     * mapped), a temporary copy of the database files is read instead; the originals are never written either way.
     */
    internal fun <T> withReadOnlyConnection(file: Path, block: (Connection) -> T): T {
        try {
            return open(file).use(block)
        } catch (e: Exception) {
            log.info("Read-only open of $file failed (${e.message}); reading a temporary copy")
        }
        val tmp = Files.createTempDirectory("agent-meter-opencode")
        try {
            for (f in watchedFiles(file) + file.resolveSibling(file.fileName.toString() + "-shm")) {
                if (Files.exists(f)) Files.copy(f, tmp.resolve(f.fileName.toString()))
            }
            return open(tmp.resolve(file.fileName.toString())).use(block)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    internal fun open(file: Path): Connection {
        val cfg = SQLiteConfig().apply { setReadOnly(true) }
        val conn = DriverManager.getConnection("jdbc:sqlite:file:${file.toUri().rawPath}?mode=ro", cfg.toProperties())
        conn.createStatement().use { it.execute("PRAGMA query_only = ON") }
        return conn
    }
}
