package dev.agentmeter.importers

import dev.agentmeter.RateLimitSnapshot
import dev.agentmeter.UsageEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path
import java.time.Instant

/**
 * A source of usage data (one per coding agent). Add a new agent by implementing this
 * and registering it in [Importers.all].
 */
interface Importer {
    /** Stable id stored in the DB, e.g. "claude-code". */
    val agent: String

    /** Directories to scan, relative to the user's home directory. */
    fun roots(home: Path): List<Path>

    fun accepts(file: Path): Boolean

    /**
     * True if every line can be parsed on its own, so the scanner may feed only newly
     * appended lines. False means the whole file is re-parsed when it changes.
     */
    val lineIndependent: Boolean

    /**
     * Parses log lines. [firstLine] is the 0-based line number of the first element in [lines].
     * Must never throw on malformed or unknown input: bad lines go to [ParseResult.errors].
     */
    fun parse(file: Path, lines: Sequence<String>, firstLine: Int = 0): ParseResult

    /** How deep below a root to look for files (1 = only direct children). */
    val maxDepth: Int get() = Int.MAX_VALUE

    /** Files whose size/mtime say whether [file] changed, e.g. a SQLite database plus its -wal file. */
    fun watchedFiles(file: Path): List<Path> = listOf(file)

    /**
     * Importers for non-text sources (databases) read the file themselves and return the result here;
     * the scanner then skips line reading. Must never throw. The default (null) means "read lines".
     */
    fun parseFile(file: Path): ParseResult? = null
}

data class ParseResult(
    val events: List<UsageEvent>,
    val errors: List<String>,
    val rateLimits: List<RateLimitSnapshot> = emptyList(),
)

object Importers {
    val all: List<Importer> = listOf(ClaudeCodeImporter, CodexImporter, OpenCodeImporter, DeepSeekHarnessImporter, AntigravityImporter)
}

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
internal fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonElement?.long(key: String): Long =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.longOrNull ?: 0L
internal fun JsonElement?.longOrNull(key: String): Long? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.longOrNull
internal fun JsonElement?.double(key: String): Double? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.doubleOrNull
internal fun parseInstant(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

