package dev.agentmeter.importers

import dev.agentmeter.UsageEvent
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * Claude Code writes one JSONL file per session under ~/.claude/projects/<project>/
 * (subagents in <session>/subagents/). Assistant lines carry `message.usage`.
 * One API response is split into several lines (one per content block) sharing
 * `message.id`; later lines have the final output count, so the last one wins.
 */
object ClaudeCodeImporter : Importer {
    override val agent = "claude-code"
    override val lineIndependent = true

    override fun roots(home: Path) = listOf(home.resolve(".claude").resolve("projects"))

    override fun accepts(file: Path) = file.extension == "jsonl"

    override fun parse(file: Path, lines: Sequence<String>, firstLine: Int): ParseResult {
        val events = LinkedHashMap<String, UsageEvent>()
        val errors = mutableListOf<String>()
        lines.forEachIndexed { i, line ->
            if (line.isBlank()) return@forEachIndexed
            val o = runCatching { lenientJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
            if (o == null) {
                errors += "$file:${firstLine + i + 1}: not a JSON object"
                return@forEachIndexed
            }
            if (o.str("type") != "assistant") return@forEachIndexed
            val msg = o.obj("message") ?: return@forEachIndexed
            val usage = msg.obj("usage") ?: return@forEachIndexed
            val id = msg.str("id") ?: return@forEachIndexed
            val model = msg.str("model") ?: "unknown"
            if (model == "<synthetic>") return@forEachIndexed // locally generated, never billed
            val ts = parseInstant(o.str("timestamp"))
            if (ts == null) {
                errors += "$file:${firstLine + i + 1}: missing timestamp"
                return@forEachIndexed
            }
            val split = usage.obj("cache_creation")
            val write5m = if (split != null) split.long("ephemeral_5m_input_tokens") else usage.long("cache_creation_input_tokens")
            val write1h = split?.long("ephemeral_1h_input_tokens") ?: 0
            events[id] = UsageEvent(
                agent = agent,
                eventKey = id,
                sessionId = o.str("sessionId") ?: file.nameWithoutExtension,
                timestampMs = ts,
                // Fast mode is billed at a premium; priced via a "<model>-fast" entry.
                model = if (usage.str("speed") == "fast") "$model-fast" else model,
                project = o.str("cwd"),
                inputTokens = usage.long("input_tokens"),
                cacheReadTokens = usage.long("cache_read_input_tokens"),
                cacheWriteTokens = write5m,
                cacheWrite1hTokens = write1h,
                outputTokens = usage.long("output_tokens"),
            )
        }
        return ParseResult(events.values.toList(), errors)
    }
}
