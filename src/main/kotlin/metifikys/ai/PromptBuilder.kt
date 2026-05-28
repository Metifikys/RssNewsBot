package metifikys.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import metifikys.model.Article
import metifikys.model.ShortlistItem

/**
 * Shared prompt construction for the sync (`OpenAI`) and async (`OpenAIBatch`) routes.
 * Both routes must produce IDENTICAL prompts so the choice of route never changes
 * the editorial output — see `telegram_posting_improvement_plan.md` and the older
 * `p2-digest-quality-plan.md` for context.
 */
object PromptBuilder {

    private val json = Json { encodeDefaults = false }

    @Serializable
    private data class RenderSourceArticle(
        val articleIndex: Int,
        val title: String,
        val description: String,
        val url: String
    )

    @Serializable
    private data class RenderShortlistItem(
        val eventKey: String,
        val subject: String = "",
        val franchise: String = "",
        val eventType: String = "",
        val coreFact: String,
        val importance: Int = 0,
        val newsworthiness: Int = 0,
        val digestFit: Int = 0,
        val relatedPreviousEventKey: String? = null,
        val url: String,
        val status: String,
        val articleIndices: List<Int> = emptyList(),
        val sourceArticles: List<RenderSourceArticle> = emptyList()
    )

    /**
     * Default Ukrainian editor system prompt for the legacy single-step flow.
     * Categories override this via `CategoryConfig.systemPrompt`.
     */
    const val LEGACY_SYSTEM_PROMPT: String =
        "Ти асистент для аналізу новин. Створюй стислі, інформативні дайджести українською мовою. " +
            "Виділяй найважливіші події та тенденції. Використовуй markdown для форматування. " +
            "Кожну тему починай із заголовку жирним шрифтом і відокремлюй теми рядком ---. " +
            "Для кожної теми обов'язково вказуй посилання на джерела у форматі [назва](url) — лише з наданого списку. " +
            "Якщо надано попередні дайджести, уникай повторення вже висвітленої інформації — " +
            "згадуй попередні теми лише якщо є суттєво нові деталі або розвиток подій."

    /**
     * Builds the user message for the legacy single-step flow (no dedup shortlist).
     * The optional [maxArticles] cap matches the old sync default; pass `Int.MAX_VALUE`
     * (or `articles.size`) to disable capping (the batch path historically didn't cap).
     */
    fun buildLegacyUserPrompt(
        category: String,
        articles: List<Article>,
        userPromptOverride: String? = null,
        previousSummaries: List<String> = emptyList(),
        maxArticles: Int = Int.MAX_VALUE
    ): String {
        val capped = if (maxArticles >= articles.size) articles else articles.take(maxArticles)
        val sb = StringBuilder()
        if (userPromptOverride != null) {
            sb.appendLine(userPromptOverride)
        } else {
            sb.appendLine("Проаналізуй наступні новини з категорії '$category' та створи стислий дайджест українською мовою.")
            sb.appendLine("Згрупуй пов'язані новини в окремі теми. Кожна тема має починатися з короткого заголовку жирним шрифтом (*заголовок*).")
            sb.appendLine("Між темами постав рівно один рядок-роздільник: ---")
            sb.appendLine("Для кожної теми вказуй посилання на відповідні джерела у форматі Markdown: [назва](url).")
            sb.appendLine("Використовуй лише посилання зі списку нижче — не вигадуй інших URL.")
        }
        sb.appendLine()
        if (previousSummaries.isNotEmpty()) {
            sb.appendLine("=== ПОПЕРЕДНІ ДАЙДЖЕСТИ (для контексту — НЕ повторюй цю інформацію) ===")
            sb.appendLine("Нижче наведено останні дайджести цієї категорії. Уникай повторення вже висвітлених тем,")
            sb.appendLine("якщо немає суттєво нової інформації. Зосередься на нових подіях та оновленнях.")
            sb.appendLine()
            for ((i, summary) in previousSummaries.withIndex()) {
                sb.appendLine("--- Дайджест ${i + 1} ---")
                sb.appendLine(summary)
                sb.appendLine()
            }
            sb.appendLine("=== КІНЕЦЬ ПОПЕРЕДНІХ ДАЙДЖЕСТІВ ===")
            sb.appendLine()
        }
        for ((i, article) in capped.withIndex()) {
            sb.appendLine("${i + 1}. ${article.title}")
            sb.appendLine("   URL: ${article.link}")
            val desc = article.promptText()
            if (desc.isNotBlank()) sb.appendLine("   $desc")
        }
        return sb.toString()
    }

    /**
     * Builds the user message for the two-step dedup render flow. The template comes from
     * `prompts/<category>-dedup.yaml` and supports `{{SHORTLIST_JSON}}`, `{{CATEGORY}}`,
     * `{{EMOJI}}` placeholders. The JSON sent to Step 2 is enriched with prompt-only
     * `sourceArticles` data derived from [articles]; persisted shortlist storage is unchanged.
     */
    fun buildRenderUserPrompt(
        category: String,
        emoji: String,
        renderUserPromptTemplate: String,
        shortlist: List<ShortlistItem>,
        articles: List<Article>
    ): String {
        val shortlistJson = json.encodeToString(shortlist.map { it.toRenderPromptItem(articles) })
        return renderUserPromptTemplate
            .replace("{{SHORTLIST_JSON}}", shortlistJson)
            .replace("{{CATEGORY}}", category)
            .replace("{{EMOJI}}", emoji)
    }

    private fun ShortlistItem.toRenderPromptItem(articles: List<Article>): RenderShortlistItem {
        val sourceArticles = resolveSourceArticles(articles).map { (index, article) ->
            RenderSourceArticle(
                articleIndex = index,
                title = article.title,
                description = article.promptText(),
                url = article.link
            )
        }
        return RenderShortlistItem(
            eventKey = eventKey,
            subject = subject,
            franchise = franchise,
            eventType = eventType,
            coreFact = coreFact,
            importance = importance,
            newsworthiness = newsworthiness,
            digestFit = digestFit,
            relatedPreviousEventKey = relatedPreviousEventKey,
            url = url,
            status = status,
            articleIndices = articleIndices,
            sourceArticles = sourceArticles
        )
    }

    private fun ShortlistItem.resolveSourceArticles(articles: List<Article>): List<Pair<Int, Article>> {
        val resolvedIndices = normalizeArticleIndices(articles).ifEmpty {
            articles.indexOfFirst { it.link == url }
                .takeIf { it >= 0 }
                ?.let(::listOf)
                ?: emptyList()
        }
        return resolvedIndices.mapNotNull { index ->
            articles.getOrNull(index)?.let { article -> index to article }
        }
    }

    /**
     * Step 1 is instructed to emit 0-based indices, but older prompts/logged behavior sometimes
     * drifted into 1-based values. Prefer the interpretation that matches the shortlist URL.
     */
    private fun ShortlistItem.normalizeArticleIndices(articles: List<Article>): List<Int> {
        val raw = articleIndices.distinct()
        if (raw.isEmpty()) return emptyList()

        val zeroBased = raw.filter { it in articles.indices }
        val oneBased = if (raw.all { it in 1..articles.size }) raw.map { it - 1 } else emptyList()
        val urlIndex = articles.indexOfFirst { it.link == url }.takeIf { it >= 0 }

        return when {
            urlIndex != null && oneBased.contains(urlIndex) && !zeroBased.contains(urlIndex) -> oneBased
            zeroBased.size == raw.size -> zeroBased
            oneBased.isNotEmpty() && zeroBased.isEmpty() -> oneBased
            else -> zeroBased
        }.distinct()
    }
}
