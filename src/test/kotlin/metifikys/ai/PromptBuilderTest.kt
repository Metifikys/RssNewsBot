package metifikys.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import metifikys.model.Article
import metifikys.model.ShortlistItem
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptBuilderTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `buildRenderUserPrompt enriches shortlist with source article titles and descriptions`() {
        val articles = listOf(
            Article(
                category = "tech",
                title = "Source title 0",
                link = "https://a.com/0",
                description = "alpha"
            ),
            Article(
                category = "tech",
                title = "Source title 1",
                link = "https://a.com/1",
                description = "b".repeat(1200)
            )
        )
        val shortlist = listOf(
            ShortlistItem(
                eventKey = "k1",
                coreFact = "fact1",
                url = "https://a.com/1",
                status = "new",
                articleIndices = listOf(1, 0)
            )
        )

        val prompt = PromptBuilder.buildRenderUserPrompt(
            category = "tech",
            emoji = ":)",
            renderUserPromptTemplate = "{{SHORTLIST_JSON}}",
            shortlist = shortlist,
            articles = articles
        )

        val payload = json.parseToJsonElement(prompt).jsonArray
        val item = payload.single().jsonObject
        val sourceArticles = item.getValue("sourceArticles").jsonArray

        assertEquals(2, sourceArticles.size)
        assertEquals(1, sourceArticles[0].jsonObject.getValue("articleIndex").jsonPrimitive.content.toInt())
        assertEquals("Source title 1", sourceArticles[0].jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(1000, sourceArticles[0].jsonObject.getValue("description").jsonPrimitive.content.length)
        assertEquals(0, sourceArticles[1].jsonObject.getValue("articleIndex").jsonPrimitive.content.toInt())
        assertEquals("alpha", sourceArticles[1].jsonObject.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun `buildRenderUserPrompt falls back to shortlist url when articleIndices are absent`() {
        val articles = listOf(
            Article(
                category = "tech",
                title = "Matched source",
                link = "https://a.com/42",
                description = "matched description"
            )
        )
        val shortlist = listOf(
            ShortlistItem(
                eventKey = "k42",
                coreFact = "fact42",
                url = "https://a.com/42",
                status = "new"
            )
        )

        val prompt = PromptBuilder.buildRenderUserPrompt(
            category = "tech",
            emoji = ":)",
            renderUserPromptTemplate = "{{SHORTLIST_JSON}}",
            shortlist = shortlist,
            articles = articles
        )

        val payload = json.parseToJsonElement(prompt).jsonArray
        val item = payload.single().jsonObject
        val sourceArticles = item.getValue("sourceArticles").jsonArray

        assertEquals(1, sourceArticles.size)
        assertEquals("Matched source", sourceArticles.single().jsonObject.getValue("title").jsonPrimitive.content)
        assertTrue(prompt.contains("matched description"))
    }

    @Test
    fun `buildLegacyUserPrompt prefers article summary over description when present`() {
        val articles = listOf(
            Article(
                category = "tech",
                title = "Title A",
                link = "https://a.com/0",
                description = "raw rss body should not appear",
                summary = "llm condensed summary text"
            ),
            Article(
                category = "tech",
                title = "Title B",
                link = "https://a.com/1",
                description = "raw fallback when summary is null"
            )
        )

        val prompt = PromptBuilder.buildLegacyUserPrompt(
            category = "tech",
            articles = articles
        )

        assertTrue(prompt.contains("llm condensed summary text"))
        assertTrue(!prompt.contains("raw rss body should not appear"))
        assertTrue(prompt.contains("raw fallback when summary is null"))
    }

    @Test
    fun `buildRenderUserPrompt prefers article summary over description in sourceArticles`() {
        val articles = listOf(
            Article(
                category = "tech",
                title = "Title A",
                link = "https://a.com/0",
                description = "raw rss body",
                summary = "llm summary wins"
            )
        )
        val shortlist = listOf(
            ShortlistItem(
                eventKey = "k0",
                coreFact = "fact0",
                url = "https://a.com/0",
                status = "new",
                articleIndices = listOf(0)
            )
        )

        val prompt = PromptBuilder.buildRenderUserPrompt(
            category = "tech",
            emoji = ":)",
            renderUserPromptTemplate = "{{SHORTLIST_JSON}}",
            shortlist = shortlist,
            articles = articles
        )

        val sourceArticle = json.parseToJsonElement(prompt).jsonArray.single()
            .jsonObject.getValue("sourceArticles").jsonArray.single().jsonObject

        assertEquals("llm summary wins", sourceArticle.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun `buildRenderUserPrompt infers one based articleIndices from shortlist url`() {
        val articles = listOf(
            Article(
                category = "tech",
                title = "Source title 0",
                link = "https://a.com/0",
                description = "alpha"
            ),
            Article(
                category = "tech",
                title = "Source title 1",
                link = "https://a.com/1",
                description = "beta"
            )
        )
        val shortlist = listOf(
            ShortlistItem(
                eventKey = "k1",
                coreFact = "fact1",
                url = "https://a.com/0",
                status = "new",
                articleIndices = listOf(1)
            )
        )

        val prompt = PromptBuilder.buildRenderUserPrompt(
            category = "tech",
            emoji = ":)",
            renderUserPromptTemplate = "{{SHORTLIST_JSON}}",
            shortlist = shortlist,
            articles = articles
        )

        val payload = json.parseToJsonElement(prompt).jsonArray
        val item = payload.single().jsonObject
        val sourceArticle = item.getValue("sourceArticles").jsonArray.single().jsonObject

        assertEquals(0, sourceArticle.getValue("articleIndex").jsonPrimitive.content.toInt())
        assertEquals("Source title 0", sourceArticle.getValue("title").jsonPrimitive.content)
    }
}
