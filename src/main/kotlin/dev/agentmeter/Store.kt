package dev.agentmeter

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate

/**
 * Usage aggregated per (agent, session, model, project, local day, reported-cost flag), plus the UTC hour
 * for models with time-dependent prices ([hourStartMs]).
 */
data class UsageRow(
    val agent: String,
    val sessionId: String,
    val model: String,
    val project: String?,
    val day: LocalDate,
    val firstTs: Long,
    val lastTs: Long,
    val tokens: Tokens,
    /** Sum of costs reported by the agent; null if these events carry no cost and must be priced from [tokens]. */
    val reportedCost: Double? = null,
    /** Start of the UTC hour (epoch ms) when rows are split by hour for peak pricing, else null. */
    val hourStartMs: Long? = null,
)

data class FileState(val size: Long, val mtime: Long, val offset: Long, val lines: Int)

class Store(dbFile: Path) : AutoCloseable {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbFile")

    init {
        conn.createStatement().use {
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                    agent TEXT NOT NULL, event_key TEXT NOT NULL, session_id TEXT NOT NULL,
                    ts INTEGER NOT NULL, model TEXT NOT NULL, project TEXT,
                    input INTEGER NOT NULL, cache_read INTEGER NOT NULL, cache_write INTEGER NOT NULL,
                    cache_write_1h INTEGER NOT NULL, output INTEGER NOT NULL,
                    PRIMARY KEY(agent, event_key))""",
            )
            // Columns added after the first release.
            val have = it.executeQuery("PRAGMA table_info(events)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
            if ("cost" !in have) it.executeUpdate("ALTER TABLE events ADD COLUMN cost REAL")
            if ("provider" !in have) it.executeUpdate("ALTER TABLE events ADD COLUMN provider TEXT")
            it.executeUpdate("CREATE INDEX IF NOT EXISTS events_ts ON events(ts)")
            it.executeUpdate(
                "CREATE TABLE IF NOT EXISTS files(path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, byte_offset INTEGER, lines INTEGER)",
            )
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS rate_limit_snapshots(
                    agent TEXT NOT NULL,
                    window TEXT NOT NULL,
                    used_percent REAL NOT NULL,
                    resets_at INTEGER,
                    ts INTEGER NOT NULL,
                    PRIMARY KEY(agent, window, ts))""",
            )
            it.executeUpdate("CREATE INDEX IF NOT EXISTS rate_limit_snapshots_ts ON rate_limit_snapshots(ts)")
            // v1: windows used to be stored as "primary"/"secondary"; they are now named by length ("5h", "7d").
            // Drop the old rows once and forget file offsets so the logs are re-read (events upsert idempotently).
            val version = it.executeQuery("PRAGMA user_version").use { rs -> rs.getInt(1) }
            if (version < 1) {
                if (it.executeUpdate("DELETE FROM rate_limit_snapshots") > 0) it.executeUpdate("DELETE FROM files")
                it.executeUpdate("PRAGMA user_version = 1")
            }
        }
    }

    @Synchronized
    fun upsert(events: List<UsageEvent>) {
        if (events.isEmpty()) return
        conn.autoCommit = false
        try {
            conn.prepareStatement("INSERT OR REPLACE INTO events(agent,event_key,session_id,ts,model,project,input,cache_read,cache_write,cache_write_1h,output,cost,provider) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)").use { st ->
                for (e in events) {
                    st.setString(1, e.agent); st.setString(2, e.eventKey); st.setString(3, e.sessionId)
                    st.setLong(4, e.timestampMs); st.setString(5, e.model); st.setString(6, e.project)
                    st.setLong(7, e.inputTokens); st.setLong(8, e.cacheReadTokens); st.setLong(9, e.cacheWriteTokens)
                    st.setLong(10, e.cacheWrite1hTokens); st.setLong(11, e.outputTokens)
                    if (e.reportedCostUsd != null && e.reportedCostUsd > 0) st.setDouble(12, e.reportedCostUsd) else st.setNull(12, java.sql.Types.REAL)
                    st.setString(13, e.provider)
                    st.addBatch()
                }
                st.executeBatch()
            }
            conn.commit()
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    @Synchronized
    fun saveRateLimits(snapshots: List<RateLimitSnapshot>) {
        if (snapshots.isEmpty()) return
        val sorted = snapshots.sortedBy { it.timestampMs }
        val checkExists = conn.prepareStatement("SELECT 1 FROM rate_limit_snapshots WHERE agent = ? AND window = ? AND ts = ?")
        val selectPrev = conn.prepareStatement("SELECT used_percent, resets_at FROM rate_limit_snapshots WHERE agent = ? AND window = ? AND ts < ? ORDER BY ts DESC LIMIT 1")
        val insert = conn.prepareStatement("INSERT INTO rate_limit_snapshots VALUES(?, ?, ?, ?, ?)")
        val latestSeen = mutableMapOf<Pair<String, String>, Pair<Double, Long?>>()

        conn.autoCommit = false
        try {
            for (s in sorted) {
                val key = s.agent to s.window

                checkExists.setString(1, s.agent)
                checkExists.setString(2, s.window)
                checkExists.setLong(3, s.timestampMs)
                val exists = checkExists.executeQuery().use { it.next() }
                if (exists) {
                    latestSeen[key] = s.usedPercent to s.resetsAt
                    continue
                }

                val prev = latestSeen[key] ?: run {
                    selectPrev.setString(1, s.agent)
                    selectPrev.setString(2, s.window)
                    selectPrev.setLong(3, s.timestampMs)
                    selectPrev.executeQuery().use { rs ->
                        if (rs.next()) {
                            val rAt = rs.getObject(2)?.let { rs.getLong(2) }
                            rs.getDouble(1) to rAt
                        } else null
                    }
                }

                if (prev != null && prev.first == s.usedPercent && prev.second == s.resetsAt) {
                    continue
                }

                insert.setString(1, s.agent)
                insert.setString(2, s.window)
                insert.setDouble(3, s.usedPercent)
                if (s.resetsAt != null) insert.setLong(4, s.resetsAt) else insert.setNull(4, java.sql.Types.INTEGER)
                insert.setLong(5, s.timestampMs)
                insert.executeUpdate()

                latestSeen[key] = s.usedPercent to s.resetsAt
            }
            conn.commit()
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            checkExists.close()
            selectPrev.close()
            insert.close()
            conn.autoCommit = true
        }
    }

    @Synchronized
    fun latestRateLimits(agent: String? = null): List<RateLimitSnapshot> {
        val sql = if (agent != null) {
            """SELECT r.agent, r.window, r.used_percent, r.resets_at, r.ts
               FROM rate_limit_snapshots r
               INNER JOIN (
                   SELECT agent, window, MAX(ts) AS max_ts
                   FROM rate_limit_snapshots
                   WHERE agent = ?
                   GROUP BY agent, window
               ) m ON r.agent = m.agent AND r.window = m.window AND r.ts = m.max_ts
               ORDER BY r.agent, r.window"""
        } else {
            """SELECT r.agent, r.window, r.used_percent, r.resets_at, r.ts
               FROM rate_limit_snapshots r
               INNER JOIN (
                   SELECT agent, window, MAX(ts) AS max_ts
                   FROM rate_limit_snapshots
                   GROUP BY agent, window
               ) m ON r.agent = m.agent AND r.window = m.window AND r.ts = m.max_ts
               ORDER BY r.agent, r.window"""
        }
        return conn.prepareStatement(sql).use { st ->
            if (agent != null) st.setString(1, agent)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            RateLimitSnapshot(
                                agent = rs.getString(1),
                                window = rs.getString(2),
                                usedPercent = rs.getDouble(3),
                                resetsAt = rs.getObject(4)?.let { rs.getLong(4) },
                                timestampMs = rs.getLong(5),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Synchronized
    fun rateLimitSnapshots(agent: String? = null, window: String? = null): List<RateLimitSnapshot> {
        val conditions = mutableListOf<String>()
        if (agent != null) conditions += "agent = ?"
        if (window != null) conditions += "window = ?"
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        val sql = "SELECT agent, window, used_percent, resets_at, ts FROM rate_limit_snapshots $where ORDER BY ts ASC"
        return conn.prepareStatement(sql).use { st ->
            var idx = 1
            if (agent != null) st.setString(idx++, agent)
            if (window != null) st.setString(idx++, window)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            RateLimitSnapshot(
                                agent = rs.getString(1),
                                window = rs.getString(2),
                                usedPercent = rs.getDouble(3),
                                resetsAt = rs.getObject(4)?.let { rs.getLong(4) },
                                timestampMs = rs.getLong(5),
                            ),
                        )
                    }
                }
            }
        }
    }


    @Synchronized
    fun fileState(path: String): FileState? =
        conn.prepareStatement("SELECT size, mtime, byte_offset, lines FROM files WHERE path=?").use { st ->
            st.setString(1, path)
            st.executeQuery().use { if (it.next()) FileState(it.getLong(1), it.getLong(2), it.getLong(3), it.getInt(4)) else null }
        }

    @Synchronized
    fun saveFileState(path: String, s: FileState) {
        conn.prepareStatement("INSERT OR REPLACE INTO files VALUES(?,?,?,?,?)").use { st ->
            st.setString(1, path); st.setLong(2, s.size); st.setLong(3, s.mtime); st.setLong(4, s.offset); st.setInt(5, s.lines)
            st.executeUpdate()
        }
    }

    /** Distinct model names as they appear in the logs. */
    @Synchronized
    fun distinctModels(): List<String> =
        conn.createStatement().use { st -> st.executeQuery("SELECT DISTINCT model FROM events").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    /**
     * [hourlyModels]: models whose price depends on the time of day; their rows are split per UTC hour
     * so each hour can be priced as peak or off-peak.
     */
    @Synchronized
    fun rows(sinceMs: Long = 0, hourlyModels: Set<String> = emptySet()): List<UsageRow> {
        val hourExpr = if (hourlyModels.isEmpty()) "-1" else "CASE WHEN model IN (${hourlyModels.joinToString(",") { "?" }}) THEN ts/3600000 ELSE -1 END"
        return conn.prepareStatement(
            """SELECT agent, session_id, model, project, date(ts/1000,'unixepoch','localtime'), min(ts), max(ts),
                      sum(input), sum(cache_read), sum(cache_write), sum(cache_write_1h), sum(output),
                      CASE WHEN cost > 0 THEN 1 ELSE 0 END AS rep, sum(cost), $hourExpr AS hb
               FROM events WHERE ts >= ?
               GROUP BY agent, session_id, model, project, date(ts/1000,'unixepoch','localtime'), rep, hb""",
        ).use { st ->
            var i = 1
            hourlyModels.forEach { st.setString(i++, it) }
            st.setLong(i, sinceMs)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val hb = rs.getLong(15)
                        add(
                            UsageRow(
                                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                LocalDate.parse(rs.getString(5)), rs.getLong(6), rs.getLong(7),
                                Tokens(rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getLong(11), rs.getLong(12)),
                                reportedCost = if (rs.getInt(13) == 1) rs.getDouble(14) else null,
                                hourStartMs = if (hb >= 0) hb * 3_600_000L else null,
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun close() = conn.close()
}
