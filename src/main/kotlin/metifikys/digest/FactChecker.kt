package metifikys.digest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import metifikys.ai.LlmClient
import metifikys.ai.MeteredLlmClient
import metifikys.ai.PromptBuilder
import metifikys.config.CategoryConfig
import metifikys.config.FactCheckAlertConfig
import metifikys.config.FactCheckConfig
import metifikys.db.FactCheckRow
import metifikys.db.FactCheckStore
import metifikys.telegram.TelegramLinks
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * `factCheck`: judges delivered posts against the sources the render saw and stores the verdict
 * (see [FactCheckConfig]). Log-only and fire-and-forget: [afterDelivery] samples and queues, the
 * judge runs on its own thread, and every failure is a warning without a row.
 */
class FactChecker(
    private val config: FactCheckConfig,
    private val db: FactCheckStore,
    /** The judge of a category (`LlmClientsFactory.forJudge`, metered as JUDGE). */
    private val clientFor: (CategoryConfig) -> LlmClient?,
    /** Where a relative `promptFile` is resolved. */
    private val baseDir: File = File("."),
    private val random: () -> Double = { ThreadLocalRandom.current().nextDouble() },
    private val clock: () -> LocalDateTime = LocalDateTime::now,
    private val executor: ExecutorService = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(config.queueSize.coerceAtLeast(1)),
        { r -> Thread(r, "fact-check").also { it.isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
) {

    /** A delivered post: its first Telegram message, the clean topic (no footer) and the render's sources for it. */
    data class Post(
        val chatId: Long,
        val messageId: Long,
        val eventKey: String?,
        val topic: String,
        val sources: List<PromptBuilder.RenderSource>
    )

    data class Prompts(val system: String, val user: String)

    @Serializable
    data class Claim(val claim: String = "", val status: String = "", val note: String = "")

    @Serializable
    private data class Verdict(val verdict: String = "", val claims: List<Claim> = emptyList())

    private val prompts: Prompts by lazy { loadPrompts(config.promptFile, baseDir) ?: DEFAULT_PROMPTS }

    fun appliesTo(category: String): Boolean =
        config.enabled && (config.categories.isEmpty() || category in config.categories)

    /** Samples [posts] and queues their checks; returns at once. Never throws. */
    fun afterDelivery(category: String, categoryConfig: CategoryConfig, posts: List<Post>) {
        if (!appliesTo(category)) return
        for (post in posts) {
            if (post.sources.isEmpty()) continue
            if (config.sampleRate <= 0.0 || (config.sampleRate < 1.0 && random() >= config.sampleRate)) continue
            try {
                executor.execute { check(category, categoryConfig, post) }
            } catch (e: RejectedExecutionException) {
                logger.info { "[Category:$category][FactCheck] queue full — ${post.eventKey ?: post.messageId} not checked" }
            } catch (e: Exception) {
                logger.warn(e) { "[Category:$category][FactCheck] could not queue a check" }
            }
        }
    }

    /** One judge call and its row; null (logged) on any failure. Runs on the checker's thread. */
    internal fun check(category: String, categoryConfig: CategoryConfig, post: Post): FactCheckRow? {
        val label = "[Category:$category][FactCheck] ${post.eventKey ?: "msg ${post.messageId}"}"
        return try {
            val client = clientFor(categoryConfig) ?: return null
            val sourcesText = formatSources(post.sources)
            val vars = mapOf("CATEGORY" to category, "POST" to post.topic, "SOURCES" to sourcesText)
            val raw = client.completeJson(substitute(prompts.system, vars), substitute(prompts.user, vars), 1)
            val verdict = parseVerdict(raw)
            if (verdict == null) {
                logger.warn { "$label: the judge's answer is not a verdict — no row. Raw (truncated): ${raw.take(300)}" }
                return null
            }
            val row = FactCheckRow(
                category = category,
                chatId = post.chatId,
                messageId = post.messageId,
                eventKey = post.eventKey,
                postText = post.topic,
                sourceChars = sourcesText.length,
                verdict = verdict.verdict,
                claimsJson = json.encodeToString(verdict.claims),
                judgeProvider = MeteredLlmClient.providerKeyOf(client.endpoint),
                judgeModel = client.endpoint.model,
                checkedAt = clock()
            )
            db.insertFactCheck(row)
            val flagged = verdict.claims.filter { it.status == FactCheckRow.UNSUPPORTED }
            if (verdict.verdict == FactCheckRow.SUPPORTED) {
                logger.info { "$label: supported" }
            } else {
                logger.warn { "$label: ${verdict.verdict}" + flagged.joinToString("") { "\n  - ${it.claim} (${it.note})" } }
            }
            row
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            logger.warn { "$label: judge failed — ${e.message?.lineSequence()?.firstOrNull()}" }
            null
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun parseVerdict(raw: String): Verdict? {
        val v = try {
            json.decodeFromString<Verdict>(raw)
        } catch (e: Exception) {
            return null
        }
        val verdict = v.verdict.trim().lowercase()
        if (verdict !in FactCheckRow.VERDICTS) return null
        return v.copy(verdict = verdict, claims = v.claims.map { it.copy(status = it.status.trim().lowercase()) })
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
        private val yamlMapper = ObjectMapper(YAMLFactory())

        /** Placeholders substituted in both judge prompts. */
        val PLACEHOLDERS: Set<String> = setOf("CATEGORY", "POST", "SOURCES")

        val DEFAULT_PROMPTS = Prompts(
            system = """
                Ти перевіряєш факти в постах українського новинного Telegram-каналу. Тобі дають пост і джерела
                (часто англійською), з яких його написали. Знайди в пості фактичні твердження — цифри, дати, імена,
                назви, цитати, місця, причини й наслідки — і перевір кожне проти джерел.
                Статус твердження: "supported" — прямо є в джерелах; "minor" — дрібна неточність, округлення чи
                вільний переклад, що не змінює суті; "unsupported" — у джерелах цього немає або вони кажуть інше.
                Загальновідомий контекст (хто розробник гри, що це за компанія, коли вийшла попередня частина) не
                вважай unsupported, якщо він правдивий. Оцінки, стиль і заголовок як жанр не перевіряй.
                verdict: "unsupported", якщо хоч одне unsupported твердження стосується суті новини; "minor", якщо є
                лише дрібниці; інакше "supported".
                Відповідай лише JSON: {"verdict": "supported|minor|unsupported", "claims": [{"claim": "...",
                "status": "minor|unsupported", "note": "що саме кажуть джерела"}]}. У claims — лише minor і unsupported.
            """.trimIndent(),
            user = "Категорія: {{CATEGORY}}\n\nПост:\n{{POST}}\n\nДжерела:\n{{SOURCES}}"
        )

        /** Sources as the judge reads them: numbered, title, URL, then the text the render had. */
        fun formatSources(sources: List<PromptBuilder.RenderSource>): String =
            sources.withIndex().joinToString("\n\n") { (i, s) -> "[${i + 1}] ${s.title}\nURL: ${s.url}\n${s.text}" }

        /** `factCheck.system` / `factCheck.user` from [rawPath]; null (logged) when absent, unreadable or incomplete. */
        fun loadPrompts(rawPath: String?, baseDir: File): Prompts? {
            if (rawPath.isNullOrBlank()) return null
            val problem = promptFileProblem(rawPath, baseDir)
            if (problem != null) {
                logger.warn { "[FactCheck] $problem — the built-in prompt is used" }
                return null
            }
            val root = yamlMapper.readTree(fileOf(rawPath, baseDir))
            return Prompts(root.at("/factCheck/system").asText(), root.at("/factCheck/user").asText())
        }

        /** Why [rawPath] cannot serve as the judge's prompts, or null when it can (for [loadPrompts] and PromptValidator). */
        fun promptFileProblem(rawPath: String, baseDir: File): String? {
            if (rawPath.contains("..")) return "factCheck.promptFile '$rawPath' contains '..'"
            val file = fileOf(rawPath, baseDir)
            if (!file.isFile) return "factCheck.promptFile ${file.absolutePath} is missing"
            return try {
                val root = yamlMapper.readTree(file)
                val system = root?.at("/factCheck/system")?.asText(null)?.takeIf { it.isNotBlank() }
                val user = root?.at("/factCheck/user")?.asText(null)?.takeIf { it.isNotBlank() }
                when {
                    system == null || user == null -> "${file.absolutePath} lacks factCheck.system / factCheck.user"
                    "{{POST}}" !in system + user || "{{SOURCES}}" !in system + user ->
                        "${file.absolutePath}: the prompts need both {{POST}} and {{SOURCES}}"
                    else -> null
                }
            } catch (e: Exception) {
                "${file.absolutePath} is not readable YAML (${e.message?.lineSequence()?.firstOrNull()})"
            }
        }

        private fun fileOf(rawPath: String, baseDir: File) = if (File(rawPath).isAbsolute) File(rawPath) else File(baseDir, rawPath)

        private fun substitute(template: String, vars: Map<String, String>): String =
            vars.entries.fold(template) { acc, (k, v) -> acc.replace("{{$k}}", v) }

        /**
         * The `/status` lines over [rows] of the alert window: null below `minChecks`; else
         * `🧐 Fact-check 7d: 42 · unsupported 3 (7%) · minor 5`, plus a ⚠️ line with links to the 3
         * latest unsupported posts once their share passes `maxUnsupportedRate`. [channelOf] maps a
         * category to its channel id for the links.
         */
        fun statusLines(rows: List<FactCheckRow>, alert: FactCheckAlertConfig, channelOf: (String) -> String?): List<String>? {
            if (rows.size < alert.minChecks) return null
            val unsupported = rows.filter { it.verdict == FactCheckRow.UNSUPPORTED }
            val minor = rows.count { it.verdict == FactCheckRow.MINOR }
            val rate = unsupported.size.toDouble() / rows.size
            val lines = mutableListOf(
                "🧐 Fact-check ${alert.windowDays}d: ${rows.size} · unsupported ${unsupported.size} " +
                    "(${String.format(Locale.ROOT, "%.0f", rate * 100)}%) · minor $minor"
            )
            if (rate > alert.maxUnsupportedRate) {
                val links = unsupported.sortedByDescending { it.checkedAt }.take(3).mapNotNull { r ->
                    channelOf(r.category)?.let { TelegramLinks.messageUrl(it, r.chatId, r.messageId) }
                }
                lines += "⚠️ unsupported above ${String.format(Locale.ROOT, "%.0f", alert.maxUnsupportedRate * 100)}%" +
                    (if (links.isEmpty()) "" else ": ${links.joinToString(" ")}")
            }
            return lines
        }
    }
}
