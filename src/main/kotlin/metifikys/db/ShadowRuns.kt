package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * `llm.shadow` runs: the live "A" output and the shadow "B" output of the same Step 1 batch or
 * render, side by side (see `metifikys.digest.ShadowRunner`). `*_output` is a compact JSON of what
 * the post-processing made of the answer (Step 1: shortlist + drops; render: prepared topics), or
 * B's raw answer when it did not parse; `*_urls` are the resulting links, one per line — the
 * stable key to compare on (event keys are the model's). Read by `/status` and
 * `bench/shadow-dump.mjs`; pruned after `processing.shadowRetentionDays`.
 */
object ShadowRunsTable : Table("shadow_runs") {
    val id = integer("id").autoIncrement()
    val category = varchar("category", 100).index()
    /** `EXTRACT` or `RENDER`. */
    val stage = varchar("stage", 20)
    val createdAt = datetime("created_at").index()
    /** Hash of the shared input (article links; plus the shortlist keys for a render). */
    val inputHash = varchar("input_hash", 64)
    val aProvider = varchar("a_provider", 50)
    val aModel = varchar("a_model", 200)
    val aPromptHash = varchar("a_prompt_hash", 64)
    val bProvider = varchar("b_provider", 50)
    val bModel = varchar("b_model", 200)
    val bPromptHash = varchar("b_prompt_hash", 64)
    /** Null when A itself failed (legacy fallback / render error). */
    val aOutput = text("a_output").nullable()
    val bOutput = text("b_output").nullable()
    /** Why B has no usable output (call failed, unparseable answer); null = B is fine. */
    val bError = text("b_error").nullable()
    val bDurationMs = long("b_duration_ms").nullable()
    val aUrls = text("a_urls").default("")
    val bUrls = text("b_urls").default("")

    override val primaryKey = PrimaryKey(id)
}

data class ShadowRunRow(
    val category: String,
    val stage: String,
    val createdAt: LocalDateTime,
    val inputHash: String,
    val aProvider: String,
    val aModel: String,
    val aPromptHash: String,
    val bProvider: String,
    val bModel: String,
    val bPromptHash: String,
    val aOutput: String?,
    val bOutput: String?,
    val bError: String?,
    val bDurationMs: Long?,
    val aUrls: List<String>,
    val bUrls: List<String>
) {
    companion object {
        const val STAGE_EXTRACT = "EXTRACT"
        const val STAGE_RENDER = "RENDER"
    }
}

interface ShadowRunStore {
    fun insertShadowRun(row: ShadowRunRow)

    /** Runs created at or after [since], oldest first. */
    fun fetchShadowRuns(since: LocalDateTime): List<ShadowRunRow>

    /** Deletes runs older than [days] days. */
    fun deleteOldShadowRuns(days: Long)
}

internal class ShadowRunRepository : ShadowRunStore {

    override fun insertShadowRun(row: ShadowRunRow) {
        transaction {
            ShadowRunsTable.insert {
                it[category] = row.category
                it[stage] = row.stage
                it[createdAt] = row.createdAt
                it[inputHash] = row.inputHash
                it[aProvider] = row.aProvider
                it[aModel] = row.aModel
                it[aPromptHash] = row.aPromptHash
                it[bProvider] = row.bProvider
                it[bModel] = row.bModel
                it[bPromptHash] = row.bPromptHash
                it[aOutput] = row.aOutput
                it[bOutput] = row.bOutput
                it[bError] = row.bError
                it[bDurationMs] = row.bDurationMs
                it[aUrls] = row.aUrls.joinToString("\n")
                it[bUrls] = row.bUrls.joinToString("\n")
            }
        }
    }

    override fun fetchShadowRuns(since: LocalDateTime): List<ShadowRunRow> = transaction {
        ShadowRunsTable.selectAll()
            .where { ShadowRunsTable.createdAt greaterEq since }
            .orderBy(ShadowRunsTable.createdAt to SortOrder.ASC)
            .map { r ->
                ShadowRunRow(
                    category = r[ShadowRunsTable.category],
                    stage = r[ShadowRunsTable.stage],
                    createdAt = r[ShadowRunsTable.createdAt],
                    inputHash = r[ShadowRunsTable.inputHash],
                    aProvider = r[ShadowRunsTable.aProvider],
                    aModel = r[ShadowRunsTable.aModel],
                    aPromptHash = r[ShadowRunsTable.aPromptHash],
                    bProvider = r[ShadowRunsTable.bProvider],
                    bModel = r[ShadowRunsTable.bModel],
                    bPromptHash = r[ShadowRunsTable.bPromptHash],
                    aOutput = r[ShadowRunsTable.aOutput],
                    bOutput = r[ShadowRunsTable.bOutput],
                    bError = r[ShadowRunsTable.bError],
                    bDurationMs = r[ShadowRunsTable.bDurationMs],
                    aUrls = r[ShadowRunsTable.aUrls].lines().filter { it.isNotBlank() },
                    bUrls = r[ShadowRunsTable.bUrls].lines().filter { it.isNotBlank() }
                )
            }
    }

    override fun deleteOldShadowRuns(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            ShadowRunsTable.deleteWhere { createdAt less cutoff }
        }
    }
}
