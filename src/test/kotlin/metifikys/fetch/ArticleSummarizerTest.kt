package metifikys.fetch

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import metifikys.ai.LlmClient
import metifikys.ai.LlmClientsFactory
import metifikys.ai.LlmEndpoint
import metifikys.model.Article
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArticleSummarizerTest {

    private val prompt = slot<String>()
    private val client = mockk<LlmClient> {
        every { endpoint } returns LlmEndpoint("https://openrouter.ai/api/v1", "k", "m:free")
        every { complete(any(), capture(prompt)) } returns "Summary."
    }
    private val factory = mockk<LlmClientsFactory> { every { forSummarize(any(), any()) } returns client }
    private val summarizer = ArticleSummarizer(mockk(relaxed = true), emptyMap(), factory)

    private fun article(description: String, pageText: String?) =
        Article("tech", "Title", "https://example.com/a", description, summarize = "openrouter", pageText = pageText)

    @Test
    fun `the summarizer reads the page text, not the 1500-char description`() {
        val out = summarizer.summarize(listOf(article("d".repeat(1500), "start " + "x".repeat(8000) + " the ending")))
        assertEquals("Summary.", out.single().summary)
        assertTrue(prompt.captured.endsWith("the ending"), "the end of the article reaches the model")
        assertFalse("ddd" in prompt.captured)
    }

    @Test
    fun `without page text (no enrichment) the description is the input`() {
        summarizer.summarize(listOf(article("RSS full-content body", null)))
        assertTrue(prompt.captured.endsWith("Content:\nRSS full-content body"), prompt.captured)
    }
}
