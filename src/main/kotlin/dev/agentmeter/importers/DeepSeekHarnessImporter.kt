package dev.agentmeter.importers

import dev.agentmeter.UsageEvent
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/**
 * DeepSeek Harness writes `~/.dsh/sessions/<cwd-slug>/session-<id>/session.v4.jsonl.zstd`, one zstd frame
 * per appended line (see [dev.agentmeter.ZstdFrames]). The first line is `{"type":"session", id, cwd, ...}`;
 * every model response is an `assistant/message` line with `time` (epoch ms), `seq`,
 * `data.message.source.{provider, model}` and `data.usage.{inputTokens, outputTokens, cacheReadTokens,
 * cacheWriteTokens}`. `inputTokens` excludes cache reads (totalTokens = input + output + cacheRead).
 * The log has no cost; it is computed from prices.json. Dedupe key: session id + seq.
 */
object DeepSeekHarnessImporter : Importer {
    override val agent = "deepseek-harness"
    override val lineIndependent = false

    override fun roots(home: Path) = listOf(home.resolve(".dsh").resolve("sessions"))

    override fun accepts(file: Path) = file.fileName.toString().let { it.startsWith("session.v") && it.endsWith(".jsonl.zstd") }

    override fun parse(file: Path, lines: Sequence<String>, firstLine: Int): ParseResult {
        val errors = mutableListOf<String>()
        val events = LinkedHashMap<String, UsageEvent>()
        var sessionId = file.parent?.fileName?.toString() ?: file.fileName.toString()
        var cwd: String? = null

        lines.forEachIndexed { i, line ->
            if (line.isBlank()) return@forEachIndexed
            val lineNo = firstLine + i + 1
            val o = runCatching { lenientJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
            if (o == null) {
                errors += "$file:$lineNo: not a JSON object"
                return@forEachIndexed
            }
            when (o.str("type")) {
                "session" -> {
                    o.str("id")?.let { sessionId = it }
                    cwd = o.str("cwd") ?: cwd
                }
                "assistant/message" -> {
                    val data = o.obj("data") ?: return@forEachIndexed
                    val u = data.obj("usage") ?: return@forEachIndexed
                    val time = o.longOrNull("time")
                    if (time == null) {
                        errors += "$file:$lineNo: missing time"
                        return@forEachIndexed
                    }
                    val source = data.obj("message").obj("source")
                    val key = "$sessionId:${o.longOrNull("seq") ?: lineNo}"
                    events[key] = UsageEvent(
                        agent = agent,
                        eventKey = key,
                        sessionId = sessionId,
                        timestampMs = time,
                        model = source.str("model") ?: "unknown",
                        project = cwd,
                        inputTokens = u.long("inputTokens"),
                        cacheReadTokens = u.long("cacheReadTokens"),
                        cacheWriteTokens = u.long("cacheWriteTokens"),
                        outputTokens = u.long("outputTokens"),
                        provider = source.str("provider"),
                    )
                }
            }
        }
        return ParseResult(events.values.toList(), errors)
    }
}
