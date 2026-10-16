package dev.agentmeter

import dev.agentmeter.importers.ClaudeCodeImporter
import dev.agentmeter.importers.CodexImporter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

fun fixture(name: String): Path = Path.of(ImporterTest::class.java.getResource("/fixtures/$name")!!.toURI())

class ImporterTest {
    private fun lines(name: String) = fixture(name).toFile().readLines().asSequence()

    @Test
    fun `claude - dedupes streamed chunks, keeps final usage, survives bad lines`() {
        val r = ClaudeCodeImporter.parse(fixture("claude-session.jsonl"), lines("claude-session.jsonl"))
        val byId = r.events.associateBy { it.eventKey }
        assertEquals(setOf("msg_A", "msg_B", "msg_C"), byId.keys)

        val a = byId.getValue("msg_A")
        assertEquals(300, a.outputTokens)
        assertEquals(2, a.inputTokens)
        assertEquals(50_000, a.cacheReadTokens)
        assertEquals(0, a.cacheWriteTokens)
        assertEquals(1_000, a.cacheWrite1hTokens)
        assertEquals("sess-1", a.sessionId)
        assertEquals("/home/dev/demo-project", a.project)

        assertEquals("claude-opus-5-5-fast", byId.getValue("msg_B").model)

        val c = byId.getValue("msg_C")
        assertEquals(200, c.cacheWriteTokens) // no 5m/1h split -> treated as 5m write
        assertEquals("claude-haiku-4-5-20251001", c.model)

        assertEquals(2, r.errors.size, r.errors.joinToString()) // invalid JSON + missing timestamp
    }

    @Test
    fun `codex - uses token_usage_record, dedupes response ids, tracks model`() {
        val r = CodexImporter.parse(fixture("codex-rollout.jsonl"), lines("codex-rollout.jsonl"))
        assertEquals(listOf("resp_1", "resp_2"), r.events.map { it.eventKey })

        val first = r.events[0]
        assertEquals("gpt-6.1-sol", first.model)
        assertEquals(18_000, first.inputTokens) // 30000 incl. 12000 cached
        assertEquals(12_000, first.cacheReadTokens)
        assertEquals(200, first.outputTokens)
        assertEquals("codex-sess-1", first.sessionId)
        assertEquals("/home/dev/demo-project", first.project)

        val second = r.events[1]
        assertEquals("gpt-6-luna", second.model)
        assertEquals(500, second.cacheWriteTokens)
        assertEquals(1, r.errors.size)

        assertEquals(1, r.rateLimits.size)
        assertEquals("5h", r.rateLimits[0].window)
        assertEquals(30.0, r.rateLimits[0].usedPercent)
        assertEquals(null, r.rateLimits[0].resetsAt)
    }


    @Test
    fun `codex legacy - token_count deltas, repeated totals skipped`() {
        val r = CodexImporter.parse(fixture("codex-rollout-legacy.jsonl"), lines("codex-rollout-legacy.jsonl"))
        assertEquals(2, r.events.size)
        assertEquals(4_000, r.events[0].inputTokens)
        assertEquals(4_000, r.events[1].inputTokens)
        assertEquals(3_000, r.events[1].cacheReadTokens)
        assertEquals(200, r.events[1].outputTokens)
        assertEquals("thread-old", r.events[1].sessionId)
        assertEquals("gpt-6-luna", r.events[1].model)
        assertEquals(0, r.errors.size)
    }

    @Test
    fun `importers never throw on arbitrary input`() {
        val junk = sequenceOf("", "null", "[]", "42", "\"str\"", "{", "{\"type\":\"assistant\",\"message\":7}",
            "{\"type\":\"token_usage_record\",\"payload\":{\"usage\":\"x\"}}", "{\"payload\":[]}")
        ClaudeCodeImporter.parse(Path.of("x.jsonl"), junk)
        CodexImporter.parse(Path.of("x.jsonl"), junk)
    }
}
