package dev.agentmeter

import dev.agentmeter.importers.Importer
import dev.agentmeter.importers.ParseResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

data class ScanReport(val filesRead: Int, val events: Int, val errors: List<String>)

/**
 * Finds agent log files and imports what changed since the last scan.
 * ponytail: polling instead of WatchService (not recursive on Linux, polls anyway on macOS);
 * fine for a few thousand files every few seconds.
 */
class Scanner(private val store: Store, private val importers: List<Importer>, private val home: Path) {
    private val log = Logger.getLogger("scanner")

    fun scanOnce(): ScanReport {
        var files = 0
        var events = 0
        val errors = mutableListOf<String>()
        for (imp in importers) for (root in imp.roots(home)) {
            if (!root.exists()) continue
            val paths = try {
                Files.walk(root, imp.maxDepth).use { s -> s.asSequence().filter { it.isRegularFile() && imp.accepts(it) }.toList() }
            } catch (e: Exception) {
                errors += "$root: ${e.message}"
                continue
            }
            for (file in paths) {
                try {
                    val n = importFile(imp, file, errors) ?: continue
                    files++
                    events += n
                } catch (e: Exception) {
                    errors += "$file: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
        errors.take(20).forEach { log.warning(it) }
        if (errors.size > 20) log.warning("... and ${errors.size - 20} more parse errors")
        return ScanReport(files, events, errors)
    }

    /** Returns number of events imported, or null if the file was unchanged. */
    private fun importFile(imp: Importer, file: Path, errors: MutableList<String>): Int? {
        val key = file.toAbsolutePath().toString()
        val watched = imp.watchedFiles(file).filter { it.exists() }
        val size = watched.sumOf { Files.size(it) }
        val mtime = watched.maxOf { it.getLastModifiedTime().toMillis() }
        val prev = store.fileState(key)
        if (prev != null && prev.size == size && prev.mtime == mtime) return null

        imp.parseFile(file)?.let { result ->
            commit(result, errors)
            store.saveFileState(key, FileState(size, mtime, size, 0))
            return result.events.size
        }

        val compressed = file.fileName.toString().let { it.endsWith(".zst") || it.endsWith(".zstd") }
        val incremental = imp.lineIndependent && !compressed && prev != null && size >= prev.offset
        val startOffset = if (incremental) prev!!.offset else 0L
        val startLine = if (incremental) prev!!.lines else 0

        val text: String
        val consumedBytes: Long
        if (compressed) {
            text = ZstdFrames.decodeText(Files.readAllBytes(file))
            consumedBytes = size
        } else {
            val bytes = RandomAccessFile(file.toFile(), "r").use { raf ->
                raf.seek(startOffset)
                ByteArray((size - startOffset).toInt()).also { raf.readFully(it) }
            }
            // Only consume complete lines; the agent may be mid-write on the last one.
            var end = bytes.lastIndexOf('\n'.code.toByte()) + 1
            // A last line that does not parse is treated as still being written: leave the offset before it.
            val lastStart = if (end > 1) bytes.copyOfRange(0, end - 1).lastIndexOf('\n'.code.toByte()) + 1 else 0
            if (end > 0 && !isJsonObject(String(bytes, lastStart, end - lastStart, Charsets.UTF_8))) end = lastStart
            text = String(bytes, 0, end, Charsets.UTF_8)
            consumedBytes = startOffset + end
        }
        var lines = if (compressed) text.lines() else text.split('\n').dropLast(1)
        if (compressed) lines = lines.dropLastWhile { it.isBlank() }.let { l -> if (l.isNotEmpty() && !isJsonObject(l.last())) l.dropLast(1) else l }
        val result = imp.parse(file, lines.asSequence(), startLine)
        commit(result, errors)

        store.saveFileState(key, FileState(size, mtime, consumedBytes, startLine + lines.size))
        return result.events.size
    }

    // All supported line-based logs are JSONL. Blank counts as fine (nothing to wait for).
    private fun isJsonObject(line: String) =
        line.isBlank() || runCatching { Json.parseToJsonElement(line) is JsonObject }.getOrDefault(false)

    private fun commit(result: ParseResult, errors: MutableList<String>) {
        // Zero-usage records (e.g. Codex before the first turn_context) only add noise.
        store.upsert(result.events.filter { it.inputTokens + it.cacheReadTokens + it.cacheWriteTokens + it.cacheWrite1hTokens + it.outputTokens > 0 })
        if (result.rateLimits.isNotEmpty()) store.saveRateLimits(result.rateLimits)
        errors += result.errors
    }
}
