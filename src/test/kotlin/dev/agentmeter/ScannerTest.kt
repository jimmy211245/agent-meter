package dev.agentmeter

import dev.agentmeter.importers.Importers
import java.nio.file.Files
import kotlin.io.path.appendText
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ScannerTest {
    @Test
    fun `imports both agents, skips unchanged files, reads appended lines incrementally`() {
        val home = Files.createTempDirectory("agent-meter-home")
        val claudeDir = home.resolve(".claude/projects/demo").createDirectories()
        val claudeFile = claudeDir.resolve("sess-1.jsonl")
        fixture("claude-session.jsonl").copyTo(claudeFile)
        val codexDir = home.resolve(".codex/sessions/2026/05/14").createDirectories()
        fixture("codex-rollout.jsonl").copyTo(codexDir.resolve("rollout-a.jsonl"))
        // Same legacy session plain and compressed: must not double count.
        fixture("codex-rollout-legacy.jsonl").copyTo(codexDir.resolve("rollout-b.jsonl"))
        fixture("codex-rollout-legacy.jsonl.zst").copyTo(codexDir.resolve("rollout-b-archived.jsonl.zst"))

        Store(home.resolve("test.db")).use { store ->
            val scanner = Scanner(store, Importers.all, home)
            val first = scanner.scanOnce()
            assertEquals(4, first.filesRead)
            val rows = store.rows()
            // One row per (session, model, day): claude 3 models, codex 2 models, legacy 1.
            assertEquals(6, rows.size)
            assertEquals(8_000, rows.single { it.sessionId == "thread-old" }.tokens.input)
            val limits = store.latestRateLimits()
            assertEquals(1, limits.size)
            assertEquals("codex", limits[0].agent)
            assertEquals("5h", limits[0].window)
            assertEquals(30.0, limits[0].usedPercent)

            assertEquals(0, scanner.scanOnce().filesRead)


            // A half-written line is not consumed until it is complete.
            claudeFile.appendText("""{"type":"assistant","sessionId":"sess-1","timestamp":"2026-10-01T11:00:00.000Z","message":{"id":"msg_D","model":"claude-opus-5-5","usage":{"input_tokens":1,""")
            val partial = scanner.scanOnce()
            assertEquals(0, partial.events)
            assertEquals(0, partial.errors.size)
            claudeFile.appendText("\"output_tokens\":42}}}\n")
            val appended = scanner.scanOnce()
            assertEquals(1, appended.events) // only the new line was parsed
            assertEquals(300L + 42, store.rows().filter { it.model == "claude-opus-5-5" }.sumOf { it.tokens.output })
        }
    }

    private fun claudeLine(id: String, output: Int) =
        """{"type":"assistant","sessionId":"s2","timestamp":"2026-10-01T12:00:00.000Z","message":{"id":"$id","model":"claude-opus-5-5","usage":{"input_tokens":1,"output_tokens":$output}}}"""

    @Test
    fun `unparsable last line is retried, broken middle lines are logged and skipped`() {
        val home = Files.createTempDirectory("agent-meter-home")
        val file = home.resolve(".claude/projects/demo").createDirectories().resolve("s2.jsonl")
        // Step 1: good line, then a line cut off mid-JSON that already ends in \n.
        file.writeText(claudeLine("m1", 10) + "\n" + claudeLine("m2", 20).dropLast(30) + "\n")
        Store(home.resolve("test.db")).use { store ->
            val scanner = Scanner(store, Importers.all, home)
            val first = scanner.scanOnce()
            assertEquals(1, first.events)
            assertEquals(0, first.errors.size) // not an error, just incomplete
            // Step 2: the writer finishes the line.
            file.writeText(claudeLine("m1", 10) + "\n" + claudeLine("m2", 20) + "\n")
            assertEquals(1, scanner.scanOnce().events) // only m2 is new
            assertEquals(30L, store.rows().sumOf { it.tokens.output })

            // A broken line in the middle is reported once and skipped; later lines still count.
            file.appendText("garbage{\n" + claudeLine("m3", 5) + "\n")
            val mid = scanner.scanOnce()
            assertEquals(1, mid.events)
            assertEquals(1, mid.errors.size)
            assertEquals(35L, store.rows().sumOf { it.tokens.output })
        }
    }
}
