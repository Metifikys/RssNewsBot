package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import kotlin.math.ceil

/**
 * Per-LLM-call observability log: token estimates and an estimated USD cost,
 * tagged with provider, model, category, and use-case. Append-only; aggregated
 * by `/status` (last-24h / last-7d totals) and pruned periodically.
 */
object LlmCallsTable : Table("llm_calls") {
    val id = integer("id").autoIncrement()
    val provider = varchar("provider", 50).index()
    val model = varchar("model", 200)
    val category = varchar("category", 100).nullable().index()
    val useCase = varchar("use_case", 50).nullable()
    val promptTokens = integer("prompt_tokens").default(0)
    val completionTokens = integer("completion_tokens").default(0)
    val estCostUsd = double("est_cost_usd").default(0.0)
    val ts = datetime("ts").index()

    /**
     * Wall-clock latency of the LLM call in milliseconds. Nullable: only the
     * synchronous code paths in [metifikys.ai.MeteredLlmClient] record it; batch
     * jobs leave it null (their wall-clock is poll-wait, not model latency).
     */
    val durationMs = long("duration_ms").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Aggregate row produced by [NewsDatabase.fetchLlmCallStats]: one entry per
 * (provider, category) bucket within the requested time window.
 */
data class LlmCostStat(
    val provider: String,
    val category: String?,
    val totalCostUsd: Double,
    val promptTokens: Long,
    val completionTokens: Long,
    val callCount: Long
)

/**
 * Per-provider latency aggregate produced by [NewsDatabase.fetchProviderLatency]
 * over the rows that recorded a `duration_ms` (synchronous calls only). [p95Ms]
 * is a nearest-rank percentile computed in-process.
 */
data class ProviderLatency(
    val provider: String,
    val callCount: Long,
    val avgMs: Double,
    val p95Ms: Long
)

interface LlmCallStore {
    fun insertLlmCall(
        provider: String,
        model: String,
        category: String?,
        useCase: String?,
        promptTokens: Int,
        completionTokens: Int,
        estCostUsd: Double,
        ts: LocalDateTime = LocalDateTime.now(),
        durationMs: Long? = null
    )

    /**
     * Aggregates LLM calls within the last [sinceHours] hours, grouped by (provider, category).
     * Empty when no rows fall in the window.
     */
    fun fetchLlmCallStats(sinceHours: Long): List<LlmCostStat>

    /**
     * Per-provider latency over the last [sinceHours] hours, computed from rows that
     * recorded a `duration_ms` (synchronous calls; batch jobs are excluded). Returns
     * avg and nearest-rank p95, sorted by call count descending. Empty when no timed
     * rows fall in the window.
     */
    fun fetchProviderLatency(sinceHours: Long): List<ProviderLatency>

    /** Deletes LLM-call records older than [days] days. */
    fun deleteOldLlmCalls(days: Long = 30)
}

internal class LlmCallRepository : LlmCallStore {

    override fun insertLlmCall(
        provider: String,
        model: String,
        category: String?,
        useCase: String?,
        promptTokens: Int,
        completionTokens: Int,
        estCostUsd: Double,
        ts: LocalDateTime,
        durationMs: Long?
    ) {
        transaction {
            LlmCallsTable.insert {
                it[LlmCallsTable.provider] = provider
                it[LlmCallsTable.model] = model
                it[LlmCallsTable.category] = category
                it[LlmCallsTable.useCase] = useCase
                it[LlmCallsTable.promptTokens] = promptTokens
                it[LlmCallsTable.completionTokens] = completionTokens
                it[LlmCallsTable.estCostUsd] = estCostUsd
                it[LlmCallsTable.ts] = ts
                it[LlmCallsTable.durationMs] = durationMs
            }
        }
    }

    override fun fetchLlmCallStats(sinceHours: Long): List<LlmCostStat> {
        val cutoff = LocalDateTime.now().minusHours(sinceHours)
        val costSum = LlmCallsTable.estCostUsd.sum()
        val promptSum = LlmCallsTable.promptTokens.sum()
        val completionSum = LlmCallsTable.completionTokens.sum()
        val callCount = LlmCallsTable.id.count()
        return transaction {
            LlmCallsTable
                .select(
                    LlmCallsTable.provider, LlmCallsTable.category,
                    costSum, promptSum, completionSum, callCount
                )
                .where { LlmCallsTable.ts greaterEq cutoff }
                .groupBy(LlmCallsTable.provider, LlmCallsTable.category)
                .map {
                    LlmCostStat(
                        provider = it[LlmCallsTable.provider],
                        category = it[LlmCallsTable.category],
                        totalCostUsd = it[costSum] ?: 0.0,
                        promptTokens = (it[promptSum] ?: 0).toLong(),
                        completionTokens = (it[completionSum] ?: 0).toLong(),
                        callCount = it[callCount]
                    )
                }
        }
    }

    override fun fetchProviderLatency(sinceHours: Long): List<ProviderLatency> {
        val cutoff = LocalDateTime.now().minusHours(sinceHours)
        val byProvider = transaction {
            LlmCallsTable
                .select(LlmCallsTable.provider, LlmCallsTable.durationMs)
                .where { LlmCallsTable.ts greaterEq cutoff }
                .mapNotNull { row ->
                    val d = row[LlmCallsTable.durationMs] ?: return@mapNotNull null
                    row[LlmCallsTable.provider] to d
                }
        }.groupBy({ it.first }, { it.second })

        return byProvider.map { (provider, durations) ->
            val sorted = durations.sorted()
            ProviderLatency(
                provider = provider,
                callCount = sorted.size.toLong(),
                avgMs = sorted.average(),
                p95Ms = nearestRankPercentile(sorted, 95)
            )
        }.sortedByDescending { it.callCount }
    }

    /** Nearest-rank percentile [p] (1..100) over an ascending-sorted list; 0 when empty. */
    private fun nearestRankPercentile(sortedAsc: List<Long>, p: Int): Long {
        if (sortedAsc.isEmpty()) return 0L
        val rank = ceil(p / 100.0 * sortedAsc.size).toInt().coerceIn(1, sortedAsc.size)
        return sortedAsc[rank - 1]
    }

    override fun deleteOldLlmCalls(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            LlmCallsTable.deleteWhere { ts less cutoff }
        }
    }
}
