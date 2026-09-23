package metifikys.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

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
