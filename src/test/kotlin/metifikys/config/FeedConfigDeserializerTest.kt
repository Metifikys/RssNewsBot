package metifikys.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedConfigDeserializerTest {

    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    private fun parse(yaml: String): FeedConfig =
        mapper.readValue(yaml.trimIndent(), FeedConfig::class.java)

    @Test
    fun `plain string url returns FeedConfig with defaults`() {
        val feed = parse("https://example.com/feed")
        assertEquals("https://example.com/feed", feed.url)
        assertEquals(false, feed.fetchFullContent)
        assertNull(feed.summarize)
    }

    @Test
    fun `plain string blank url throws`() {
        val ex = assertThrows<Exception> { parse("\"\"") }
        // Wrapped in JsonMappingException → root cause is IllegalArgumentException
        val root = generateSequence<Throwable>(ex) { it.cause }.last()
        assertTrue(root.message!!.contains("Empty feed URL"))
    }

    @Test
    fun `object with url only applies defaults`() {
        val feed = parse(
            """
            url: "https://example.com/rss"
            """
        )
        assertEquals("https://example.com/rss", feed.url)
        assertEquals(false, feed.fetchFullContent)
        assertNull(feed.summarize)
    }

    @Test
    fun `object with fetchFullContent true sets flag`() {
        val feed = parse(
            """
            url: "https://example.com/rss"
            fetchFullContent: true
            """
        )
        assertEquals(true, feed.fetchFullContent)
    }

    @Test
    fun `object with fetchFullContent false sets flag`() {
        val feed = parse(
            """
            url: "https://example.com/rss"
            fetchFullContent: false
            """
        )
        assertEquals(false, feed.fetchFullContent)
    }

    @Test
    fun `object with valid summarize openai sets provider`() {
        val feed = parse(
            """
            url: "https://example.com/rss"
            summarize: "openai"
            """
        )
        assertEquals("openai", feed.summarize)
    }

    @Test
    fun `object with valid summarize openrouter sets provider`() {
        val feed = parse(
            """
            url: "https://example.com/rss"
            summarize: "openrouter"
            """
        )
        assertEquals("openrouter", feed.summarize)
    }

    @Test
    fun `object with valid summarize anthropic sets provider`() {
        val feed = parse(
            """
            url: "https://example.com/rss"
            summarize: "anthropic"
            """
        )
        assertEquals("anthropic", feed.summarize)
    }

    @Test
    fun `object with blank summarize collapses to null`() {
        val feed = parse(
            """
            url: "https://example.com/rss"
            summarize: ""
            """
        )
        assertNull(feed.summarize)
    }

    @Test
    fun `object with invalid summarize provider throws`() {
        val ex = assertThrows<Exception> {
            parse(
                """
                url: "https://example.com/rss"
                summarize: "deepseek"
                """
            )
        }
        val root = generateSequence<Throwable>(ex) { it.cause }.last()
        assertTrue(root.message!!.contains("Invalid 'summarize' value"))
    }

    @Test
    fun `object missing url throws`() {
        val ex = assertThrows<Exception> {
            parse(
                """
                fetchFullContent: true
                """
            )
        }
        val root = generateSequence<Throwable>(ex) { it.cause }.last()
        assertTrue(root.message!!.contains("missing 'url'"))
    }

    @Test
    fun `object with blank url throws`() {
        val ex = assertThrows<Exception> {
            parse(
                """
                url: ""
                """
            )
        }
        val root = generateSequence<Throwable>(ex) { it.cause }.last()
        assertTrue(root.message!!.contains("missing 'url'"))
    }
}
