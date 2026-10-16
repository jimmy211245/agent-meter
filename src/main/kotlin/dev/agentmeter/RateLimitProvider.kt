package dev.agentmeter

/**
 * Interface for rate limit providers.
 *
 * Designed to allow multiple agents to supply their rate limit status:
 * - [CodexRateLimitProvider]: extracts rate limits from session log token_count events (already stored in DB).
 * - Future Claude rate limit provider: can query Claude's local OAuth/API usage endpoint or status file.
 */
interface RateLimitProvider {
    /** Stable identifier of the agent, e.g. "codex" or "claude-code". */
    val agent: String

    /** Returns the latest rate limit snapshots available for this agent. */
    fun getLimits(): List<RateLimitSnapshot>
}

/**
 * Codex rate limit provider backed by SQLite rate limit snapshots recorded by CodexImporter.
 */
class CodexRateLimitProvider(private val store: Store) : RateLimitProvider {
    override val agent: String = "codex"

    override fun getLimits(): List<RateLimitSnapshot> = store.latestRateLimits(agent)
}

/**
 * Registry of active rate limit providers.
 */
object RateLimitProviders {
    fun all(store: Store): List<RateLimitProvider> = listOf(
        CodexRateLimitProvider(store),
        // Future Claude rate limit provider will be registered here:
        // ClaudeRateLimitProvider(...)
    )
}
