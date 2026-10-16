package dev.agentmeter

import dev.agentmeter.importers.lenientJson
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.logging.Logger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** USD per 1M tokens. */
@Serializable
data class Price(
    val input: Double,
    val cachedInput: Double? = null,
    val cacheWrite: Double? = null,
    val cacheWrite1h: Double? = null,
    val output: Double,
    val asOf: String? = null,
    val source: String? = null,
    /** null = from an official price page; "legacy-unverified" = last known price, no longer listed officially. */
    val status: String? = null,
    /** Other model names that are billed at this price (e.g. retired names that are served by this model). */
    val aliases: List<String> = emptyList(),
    /** Optional higher price during [PeakPrice.windows]; the fields above are then the off-peak price. */
    val peak: PeakPrice? = null,
    /**
     * Optional price changes over time. The plain fields apply unless a period contains the response date (UTC);
     * the first matching period wins. No periods = the plain fields always apply.
     */
    val periods: List<PricePeriod> = emptyList(),
)

/** A price valid from [validFrom] to [validUntil], both ISO dates (UTC) and inclusive; a missing bound is open. */
@Serializable
data class PricePeriod(
    val validFrom: String? = null,
    val validUntil: String? = null,
    val input: Double,
    val cachedInput: Double? = null,
    val cacheWrite: Double? = null,
    val cacheWrite1h: Double? = null,
    val output: Double,
) {
    /** A bound that is present but not a valid date makes the period match nothing rather than everything. */
    fun contains(day: LocalDate): Boolean {
        val from = validFrom?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return false }
        val until = validUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return false }
        return (from == null || !day.isBefore(from)) && (until == null || !day.isAfter(until))
    }
}

/** Prices during peak hours. Rates left out fall back to the base price. */
@Serializable
data class PeakPrice(
    val input: Double,
    val cachedInput: Double? = null,
    val cacheWrite: Double? = null,
    val cacheWrite1h: Double? = null,
    val output: Double,
    val windows: List<PeakWindow>,
)

/** Peak when the UTC weekday is in [days] (MON..SUN) and fromHourUtc <= hour < toHourUtc. */
@Serializable
data class PeakWindow(val days: List<String>, val fromHourUtc: Int, val toHourUtc: Int)

fun PeakPrice.isPeak(epochMs: Long): Boolean {
    val t = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC)
    return windows.any { w ->
        t.hour >= w.fromHourUtc && t.hour < w.toHourUtc &&
            w.days.any { it.uppercase().take(3) == t.dayOfWeek.name.take(3) }
    }
}

@Serializable
data class PriceTable(val models: Map<String, Price> = emptyMap()) {
    private val aliasIndex: Map<String, Price> by lazy {
        buildMap { models.values.forEach { p -> p.aliases.forEach { put(it, p) } } }
    }

    /** Exact id, a listed alias, or either with a date snapshot suffix (claude-haiku-4-5-20251001). No fuzzy guessing. */
    fun find(model: String): Price? {
        val bare = model.replace(DATE_SUFFIX, "")
        return models[model] ?: aliasIndex[model] ?: models[bare] ?: aliasIndex[bare]
    }

    /** Which of [rawModelNames] (as they appear in the logs) have prices that depend on the time (peak hours or date periods). */
    fun timeDependentModels(rawModelNames: Collection<String>): Set<String> =
        rawModelNames.filter { name -> find(name)?.let { it.peak != null || it.periods.isNotEmpty() } == true }.toSet()

    fun isEstimated(model: String): Boolean = find(model)?.status == LEGACY_UNVERIFIED

    /**
     * Null if the model has no price entry. [atMs] (epoch ms of the response) selects the peak price
     * if the model has one; without it the base price applies.
     */
    fun cost(model: String, t: Tokens, atMs: Long? = null): Double? {
        val found = find(model) ?: return null
        val base = atMs?.let { at ->
            val day = Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate()
            found.periods.firstOrNull { it.contains(day) }?.let { found.copy(input = it.input, cachedInput = it.cachedInput, cacheWrite = it.cacheWrite, cacheWrite1h = it.cacheWrite1h, output = it.output) }
        } ?: found
        val p = base.peak?.takeIf { atMs != null && it.isPeak(atMs) }?.let { pk ->
            base.copy(input = pk.input, cachedInput = pk.cachedInput ?: base.cachedInput,
                cacheWrite = pk.cacheWrite ?: base.cacheWrite, cacheWrite1h = pk.cacheWrite1h ?: base.cacheWrite1h, output = pk.output)
        } ?: base
        val write = p.cacheWrite ?: p.input
        return (t.input * p.input +
            t.cacheRead * (p.cachedInput ?: p.input) +
            t.cacheWrite * write +
            t.cacheWrite1h * (p.cacheWrite1h ?: write) +
            t.output * p.output) / 1_000_000.0
    }

    companion object {
        const val LEGACY_UNVERIFIED = "legacy-unverified"
        private val DATE_SUFFIX = Regex("-\\d{8}$")
        private val log = Logger.getLogger("pricing")

        fun parse(json: String): PriceTable = lenientJson.decodeFromString(serializer(), json)

        fun bundled(): PriceTable =
            parse(PriceTable::class.java.getResource("/prices.json")!!.readText())

        /** Loads the user's editable copy, creating it from the bundled defaults on first run. */
        fun load(file: Path): PriceTable {
            if (!file.exists()) {
                file.parent.createDirectories()
                file.writeText(PriceTable::class.java.getResource("/prices.json")!!.readText())
            }
            return runCatching { parse(file.readText()) }
                // The user's file always wins; models it lacks (added in newer versions) come from the defaults.
                // ponytail: merged in memory only, the user's file is never rewritten.
                .map { user -> PriceTable(bundled().models + user.models) }
                .getOrElse {
                    log.warning("Invalid $file (${it.message}); using bundled prices")
                    bundled()
                }
        }
    }
}

data class Tokens(
    val input: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val cacheWrite1h: Long = 0,
    val output: Long = 0,
) {
    operator fun plus(o: Tokens) = Tokens(
        input + o.input, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite,
        cacheWrite1h + o.cacheWrite1h, output + o.output,
    )
    val total get() = input + cacheRead + cacheWrite + cacheWrite1h + output
}
