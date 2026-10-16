package dev.agentmeter.importers

import dev.agentmeter.RateLimitSnapshot
import dev.agentmeter.UsageEvent
import dev.agentmeter.windowLabel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/**
 * Codex CLI writes ~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl, older ones compressed to
 * .jsonl.zst. Lines are `{timestamp, type, payload}`.
 *
 * Newer logs have `token_usage_record` lines (one per response, keyed by `response_id`,
 * which also dedupes history copied into subagent threads). Older logs only have
 * `event_msg/token_count` with `last_token_usage`, repeated while totals stay unchanged.
 * The model comes from the latest preceding `turn_context`.
 *
 * OpenAI semantics: `input_tokens` includes cached ones, `output_tokens` includes reasoning.
 */
object CodexImporter : Importer {
    override val agent = "codex"
    override val lineIndependent = false

    override fun roots(home: Path) = listOf(home.resolve(".codex").resolve("sessions"))

    override fun accepts(file: Path) = file.fileName.toString().let { it.endsWith(".jsonl") || it.endsWith(".jsonl.zst") }

    override fun parse(file: Path, lines: Sequence<String>, firstLine: Int): ParseResult {
        val errors = mutableListOf<String>()
        val fromRecords = LinkedHashMap<String, UsageEvent>()
        val fromTokenCount = mutableListOf<UsageEvent>()
        val rateLimits = mutableListOf<RateLimitSnapshot>()
        val lastByWindow = HashMap<String, Pair<Double, Long?>>()
        var model = "unknown"
        var sessionId = file.fileName.toString().removeSuffix(".zst").removeSuffix(".jsonl")
        var threadId = sessionId
        var project: String? = null
        var lastTotal: JsonElement? = null

        fun event(key: String, ts: Long, u: JsonObject): UsageEvent {
            val cached = u.long("cached_input_tokens")
            return UsageEvent(
                agent = agent, eventKey = key, sessionId = sessionId, timestampMs = ts, model = model,
                project = project,
                inputTokens = (u.long("input_tokens") - cached).coerceAtLeast(0),
                cacheReadTokens = cached,
                cacheWriteTokens = u.long("cache_write_input_tokens"),
                outputTokens = u.long("output_tokens"),
            )
        }

        /** [slot] ("primary"/"secondary") is only a fallback label when the log has no `window_minutes`. */
        fun parseRateLimit(slot: String, limitObj: JsonObject?, ts: Long): RateLimitSnapshot? {
            if (limitObj == null) return null
            val usedPercent = limitObj.double("used_percent") ?: return null
            val window = limitObj.longOrNull("window_minutes")?.takeIf { it > 0 }?.let(::windowLabel) ?: slot
            val rawResetsAt = limitObj.longOrNull("resets_at")
            val resetsAt = when {
                rawResetsAt != null -> if (rawResetsAt > 100_000_000_000L) rawResetsAt / 1000L else rawResetsAt
                else -> limitObj.str("resets_at")?.let { parseInstant(it)?.let { ms -> ms / 1000L } }
                    ?: limitObj.longOrNull("resets_in_seconds")?.let { (ts / 1000L) + it }
            }
            val key = usedPercent to resetsAt
            if (lastByWindow[window] == key) return null
            lastByWindow[window] = key
            return RateLimitSnapshot(agent = agent, window = window, usedPercent = usedPercent, resetsAt = resetsAt, timestampMs = ts)
        }

        lines.forEachIndexed { i, line ->
            if (line.isBlank()) return@forEachIndexed
            val lineNo = firstLine + i + 1
            val o = runCatching { lenientJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
            if (o == null) {
                errors += "$file:$lineNo: not a JSON object"
                return@forEachIndexed
            }
            val p = o.obj("payload") ?: return@forEachIndexed
            val ts by lazy { parseInstant(o.str("timestamp")) }
            when (o.str("type")) {
                "session_meta" -> {
                    threadId = p.str("id") ?: threadId
                    sessionId = p.str("session_id") ?: threadId
                    project = p.str("cwd") ?: project
                }
                "turn_context" -> model = p.str("model") ?: model
                "token_usage_record" -> {
                    val u = p.obj("usage") ?: return@forEachIndexed
                    val t = ts ?: run { errors += "$file:$lineNo: missing timestamp"; null } ?: return@forEachIndexed
                    val key = p.str("response_id") ?: "$threadId:$lineNo"
                    fromRecords[key] = event(key, t, u)
                }
                "event_msg" -> if (p.str("type") == "token_count") {
                    val t = ts ?: run { errors += "$file:$lineNo: missing timestamp"; null }
                    if (t != null) {
                        val rl = p.obj("rate_limits")
                        if (rl != null) {
                            parseRateLimit("primary", rl.obj("primary"), t)?.let { rateLimits += it }
                            parseRateLimit("secondary", rl.obj("secondary"), t)?.let { rateLimits += it }
                        }
                    }
                    val info = p.obj("info") ?: return@forEachIndexed
                    val total = info["total_token_usage"]
                    if (total == null || total == lastTotal) return@forEachIndexed
                    lastTotal = total
                    val u = info.obj("last_token_usage") ?: return@forEachIndexed
                    if (t != null) {
                        fromTokenCount += event("$threadId:$lineNo", t, u)
                    }
                }
            }
        }
        val events = if (fromRecords.isNotEmpty()) fromRecords.values.toList() else fromTokenCount
        return ParseResult(events, errors, rateLimits)
    }
}

