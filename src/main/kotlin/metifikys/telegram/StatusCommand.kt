package metifikys.telegram

import metifikys.config.AppConfig
import metifikys.db.LlmCostStat
import metifikys.db.NewsDatabase
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
            val pending = pendingByCategory[name].orEmpty()
            for (batch in pending) {
                sb.append("  pending: `${escapeMarkdown(batch.batchId)}` started ${batch.createdAt.format(timeFmt)}\n")
            }
        }

        appendCostSection(sb)

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

    private fun appendCostSection(sb: StringBuilder) {
        val day = safeFetchStats(24)
        val week = safeFetchStats(7 * 24)
        sb.append('\n').append("💰 *LLM cost* (24h / 7d)\n")
        val dayTotal = day.sumOf { it.totalCostUsd }
        val weekTotal = week.sumOf { it.totalCostUsd }
        sb.append("  total: ").append(formatUsd(dayTotal)).append(" / ").append(formatUsd(weekTotal)).append('\n')
        if (day.isEmpty() && week.isEmpty()) return

        val weekIndex: Map<Pair<String, String?>, LlmCostStat> =
            week.associateBy { it.provider to it.category }
        val rows = day.sortedByDescending { it.totalCostUsd }.take(10)
        for (row in rows) {
            val w = weekIndex[row.provider to row.category]?.totalCostUsd ?: 0.0
            val cat = row.category?.let { escapeMarkdown(it) } ?: "-"
            sb.append("  ").append(escapeMarkdown(row.provider)).append(" · ").append(cat).append(": ")
            val showCost = row.totalCostUsd > 0.0 || w > 0.0
            if (showCost) {
                sb.append(formatUsd(row.totalCostUsd)).append(" / ").append(formatUsd(w)).append(' ')
            }
            sb.append('(')
                .append(formatTokens(row.promptTokens)).append(" in / ")
                .append(formatTokens(row.completionTokens)).append(" out, ")
                .append(row.callCount).append(" calls)\n")
        }
    }

    private fun safeFetchStats(sinceHours: Long): List<LlmCostStat> = try {
        db.fetchLlmCallStats(sinceHours)
    } catch (e: Exception) {
        emptyList()
    }

    private fun formatUsd(v: Double): String = "$" + String.format(Locale.ROOT, "%.2f", v)

    private fun formatTokens(n: Long): String = when {
        n < 1_000L -> n.toString()
        n < 1_000_000L -> String.format(Locale.ROOT, "%.1fk", n / 1_000.0)
        else -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0)
    }

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
