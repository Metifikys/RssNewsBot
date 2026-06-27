package metifikys.telegram

import metifikys.config.AppConfig
import metifikys.db.CategoryArticleCounts
import metifikys.db.NewsDatabase
import metifikys.db.ProviderLatency
import metifikys.digest.CycleErrorLog
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds a one-shot snapshot for the `/status` Telegram command:
 *  - per category: most recent digest's article count vs `dedup.digest.maxDigestItems` cap
 *  - per category: currently pending OpenAI batches with their start times
 *  - any cycle errors recorded since the last successful status post
 */
class StatusCommand(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val errorLog: CycleErrorLog = CycleErrorLog()
) {

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val stampFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val zoneId: ZoneId = ZoneId.systemDefault()

    /** Renders status text and a token identifying the highest error seq consumed. */
    data class BuiltStatus(val text: String, val errorCommitToken: Long)

    /** Read-only snapshot used by the `/status` command. Does not clear errors. */
    fun build(): String = buildSnapshot().text

    fun buildSnapshot(): BuiltStatus {
        val readyByCategory = db.fetchReadyForDigestByCategory(config.processing.staleTimeoutHours)
        val pendingByCategory = db.fetchPendingBatchesByCategory()
        val articleCounts = safeFetchArticleCounts(24)
        val publishedCounts = safeFetchPublishedCounts(24)
        val errors = errorLog.list()
        val sb = StringBuilder()
        sb.append("📊 *Status* — ${LocalDateTime.now().format(stampFmt)}\n")
        for ((name, category) in config.categories) {
            val latest = db.fetchLatestSummary(name)
            val whenCell = if (latest != null) {
                val age = formatAge(Duration.between(latest.createdAt, LocalDateTime.now()))
                "${latest.createdAt.format(timeFmt)} ($age)"
            } else {
                "never"
            }
            val readyCount = readyByCategory[name].orEmpty().size
            sb.append('\n')
            sb.append("*${escapeMarkdown(name)}* ${category.emoji}\n")
            sb.append("  last digest: $whenCell\n")
            sb.append("  ready for next batch: $readyCount\n")
            sb.append("  ").append(formatArticleCounts(articleCounts[name], publishedCounts[name] ?: 0L)).append('\n')
            val pending = pendingByCategory[name].orEmpty()
            for (batch in pending) {
                sb.append("  pending: `${escapeMarkdown(batch.batchId)}` started ${batch.createdAt.format(timeFmt)}\n")
            }
        }

        appendLatencySection(sb)

        val errorCommitToken = if (errors.isEmpty()) {
            -1L
        } else {
            sb.append('\n').append("⚠️ *Recent errors*\n")
            val visible = errors.takeLast(10)
            for (entry in visible) {
                val time = entry.at.atZone(zoneId).toLocalTime().format(timeFmt)
                val cat = entry.category?.let { escapeMarkdown(it) } ?: "-"
                val ctx = escapeMarkdown(entry.context)
                val msg = escapeMarkdown(entry.message)
                sb.append("  $time · $cat · $ctx · $msg\n")
            }
            errors.last().seq
        }

        return BuiltStatus(sb.toString().trimEnd(), errorCommitToken)
    }

    /**
     * Per-provider LLM latency, two windows side by side: `avg/p95 (calls)` for 24h
     * then 7d. Providers are ordered by 24h call volume (falling back to 7d). Batch
     * jobs are excluded upstream (they record no `duration_ms`).
     */
    private fun appendLatencySection(sb: StringBuilder) {
        val day = safeFetchLatency(24).associateBy { it.provider }
        val week = safeFetchLatency(7 * 24).associateBy { it.provider }
        sb.append('\n').append("⏱ *LLM latency* (avg / p95 · req · 24h / 7d)\n")
        if (day.isEmpty() && week.isEmpty()) {
            sb.append("  no timed calls recorded\n")
            return
        }
        val providers = (day.keys + week.keys).distinct()
            .sortedByDescending { day[it]?.callCount ?: week[it]?.callCount ?: 0L }
        for (p in providers) {
            sb.append(escapeMarkdown(p)).append(": ").append(latencyCell(day[p])).append('\n')
            sb.append("  ").append(latencyCell(week[p])).append('\n')
        }
    }

    private fun latencyCell(l: ProviderLatency?): String =
        if (l == null) "—" else "${formatMs(l.avgMs)} / ${formatMs(l.p95Ms.toDouble())} · ${l.callCount}"

    /** `✅ P (x%) · 📰 A · ⛔ B (y%)` — percentages over (articles+blocked), omitted when that's 0. */
    private fun formatArticleCounts(c: CategoryArticleCounts?, published: Long): String {
        val articles = c?.articles ?: 0L
        val blocked = c?.blocked ?: 0L
        val denom = articles + blocked
        val pubPct = if (denom > 0L) " (${published * 100 / denom}%)" else ""
        val blkPct = if (denom > 0L) " (${blocked * 100 / denom}%)" else ""
        return "✅ $published$pubPct · 📰 $articles · ⛔ $blocked$blkPct"
    }

    private fun safeFetchLatency(sinceHours: Long): List<ProviderLatency> = try {
        db.fetchProviderLatency(sinceHours)
    } catch (e: Exception) {
        emptyList()
    }

    private fun safeFetchArticleCounts(sinceHours: Long): Map<String, CategoryArticleCounts> = try {
        db.fetchArticleStatusCounts(sinceHours)
    } catch (e: Exception) {
        emptyMap()
    }

    private fun safeFetchPublishedCounts(sinceHours: Long): Map<String, Long> = try {
        db.fetchPublishedTopicCounts(sinceHours)
    } catch (e: Exception) {
        emptyMap()
    }

    private fun formatMs(ms: Double): String =
        if (ms < 1000.0) "${ms.toLong()}ms" else String.format(Locale.ROOT, "%.1fs", ms / 1000.0)

    private fun escapeMarkdown(text: String): String =
        text.replace("*", "").replace("_", "").replace("`", "").replace("[", "").replace("]", "")

    internal fun formatAge(d: Duration): String {
        val totalMin = d.toMinutes().coerceAtLeast(0)
        return when {
            totalMin < 1L        -> "<1 min"
            totalMin < 60L       -> "$totalMin min"
            totalMin < 24L * 60L -> {
                val h = totalMin / 60
                val m = totalMin % 60
                if (m == 0L) "${h}h" else "${h}h ${m}m"
            }
            else                 -> "${totalMin / (24L * 60L)}d"
        }
    }
}
