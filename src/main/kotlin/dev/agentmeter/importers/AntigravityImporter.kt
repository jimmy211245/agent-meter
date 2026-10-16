package dev.agentmeter.importers

import dev.agentmeter.UsageEvent
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * Google Antigravity (desktop app and IDE) keeps one SQLite database per conversation:
 * `~/.gemini/antigravity/conversations/<uuid>.db` and `~/.gemini/antigravity-ide/conversations/<uuid>.db`.
 *
 * Usage comes from `steps.metadata` (protobuf, read with [Protobuf]); all field numbers were checked against
 * the schema embedded in Antigravity's language server and against the conversation transcripts:
 *  - 1 = created_at {1 = seconds, 2 = nanos}
 *  - 9 = ModelUsageStats {1 model enum, 2 input_tokens (without cache), 3 output_tokens (thinking included),
 *        4 cache_write_tokens, 5 cache_read_tokens, 9 thinking, 10 response output, 11 response_id}
 * Field 9 inside 9 is a part of field 3 and is not added again. The copy of the usage in `retry_infos` is not read.
 *
 * The model name as text is only in `gen_metadata` (field 1.19, matched by response id, else by model enum).
 * The project is the first workspace URI of `conversation_summaries.db` (app only), or the first file URI in
 * `trajectory_metadata_blob`. The databases are opened strictly read-only; only numbers and ids are read.
 * The agent id is the same for app and IDE; the folder name ("antigravity" / "antigravity-ide") is stored as provider.
 */
object AntigravityImporter : Importer {
    override val agent = "antigravity"
    override val lineIndependent = false
    override val maxDepth = 1

    private val DB_NAME = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\\.db")

    override fun roots(home: Path) = listOf("antigravity", "antigravity-ide").map {
        home.resolve(".gemini").resolve(it).resolve("conversations")
    }

    override fun accepts(file: Path) = DB_NAME.matches(file.fileName.toString())

    override fun watchedFiles(file: Path) = OpenCodeImporter.watchedFiles(file)

    override fun parse(file: Path, lines: Sequence<String>, firstLine: Int) = ParseResult(emptyList(), emptyList())

    override fun parseFile(file: Path): ParseResult {
        val errors = mutableListOf<String>()
        val events = LinkedHashMap<String, UsageEvent>()
        val conversation = file.fileName.toString().removeSuffix(".db")
        val source = file.parent?.parent?.fileName?.toString()
        try {
            OpenCodeImporter.withReadOnlyConnection(file) { conn ->
                val names = ModelNames.read(conn)
                val project = project(file, conversation, conn)
                var undecodable = 0
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT idx, metadata FROM steps WHERE metadata IS NOT NULL").use { rs ->
                        while (rs.next()) {
                            val idx = rs.getLong(1)
                            val meta = rs.getBytes(2)?.let { Protobuf.decode(it) }
                            if (meta == null) { undecodable++; continue }
                            val usage = meta.message(9) ?: continue
                            val responseId = usage.string(11)?.takeIf { it.isNotBlank() }
                            val key = responseId ?: "$conversation:$idx"
                            val enum = usage.varint(1)
                            val created = meta.message(1)
                            events.putIfAbsent(key, UsageEvent( // the same response on a later step: keep the first
                                agent = agent,
                                eventKey = key,
                                sessionId = conversation,
                                timestampMs = (created?.varint(1) ?: 0L) * 1000 + (created?.varint(2) ?: 0L) / 1_000_000,
                                model = names.name(responseId, enum),
                                project = project,
                                inputTokens = usage.varint(2),
                                cacheReadTokens = usage.varint(5),
                                cacheWriteTokens = usage.varint(4),
                                outputTokens = usage.varint(3),
                                provider = source,
                            ))
                        }
                    }
                }
                if (undecodable > 0) errors += "$file: $undecodable steps with undecodable metadata skipped"
            }
        } catch (e: Exception) {
            errors += "$file: ${e.javaClass.simpleName}: ${e.message}"
        }
        return ParseResult(events.values.filter { it.timestampMs > 0 }.toList(), errors)
    }

    /** Model name per response id, and the latest name per model enum as fallback. */
    internal class ModelNames(private val byResponse: Map<String, String>, private val byEnum: Map<Long, String>) {
        fun name(responseId: String?, enum: Long) =
            responseId?.let { byResponse[it] } ?: byEnum[enum] ?: "antigravity-model-$enum"

        companion object {
            fun read(conn: Connection): ModelNames {
                val byResponse = HashMap<String, String>()
                val byEnum = HashMap<Long, String>()
                runCatching {
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT data FROM gen_metadata ORDER BY idx").use { rs ->
                            while (rs.next()) {
                                val outer = rs.getBytes(1)?.let { Protobuf.decode(it) }?.message(1) ?: continue
                                val name = outer.string(19)?.takeIf { it.isNotBlank() } ?: continue
                                val usage = outer.message(4)
                                usage?.string(11)?.let { byResponse[it] = name }
                                usage?.varint(1)?.takeIf { it != 0L }?.let { byEnum[it] = name }
                            }
                        }
                    }
                }
                return ModelNames(byResponse, byEnum)
            }
        }
    }

    private fun project(file: Path, conversation: String, conn: Connection): String? =
        workspaceFromSummaries(file.parent?.parent?.resolve("conversation_summaries.db"), conversation)
            ?: runCatching {
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT data FROM trajectory_metadata_blob LIMIT 1").use { rs ->
                        if (rs.next()) rs.getBytes(1)?.let { Protobuf.decode(it) }?.findString("file://") else null
                    }
                }
            }.getOrNull()?.let { uriToPath(it) }

    private fun workspaceFromSummaries(db: Path?, conversation: String): String? {
        if (db == null || !Files.exists(db)) return null
        return runCatching {
            OpenCodeImporter.withReadOnlyConnection(db) { conn ->
                conn.prepareStatement("SELECT workspace_uris FROM conversation_summaries WHERE conversation_id = ?").use { ps ->
                    ps.setString(1, conversation)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }
        }.getOrNull()?.let { firstUri(it) }?.let { uriToPath(it) }
    }

    /** First string of a JSON array like `["file:///c%3A/x"]`; no JSON library needed for this shape. */
    internal fun firstUri(json: String): String? = Regex("\"(file:[^\"]+)\"").find(json)?.groupValues?.get(1)

    /**
     * `file:///c%3A/dir/My%20Project` -> `C:\dir\My Project` (same spelling the other agents log for Windows paths);
     * malformed input is returned unchanged.
     */
    internal fun uriToPath(uri: String): String {
        val path = runCatching { URI(uri).path }.getOrNull() ?: return uri
        val drive = Regex("^/([A-Za-z]):(.*)$").find(path) ?: return path.trimEnd('/')
        return (drive.groupValues[1].uppercase() + ":" + drive.groupValues[2].replace('/', '\\')).trimEnd('\\')
    }
}
