package metifikys.telegram

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramSenderTest {

    private val sender = TelegramSender("token")

    /** A fake Bot API that records every request body and answers a fixed `ok` envelope. */
    private fun withFakeApi(block: (sender: TelegramSender, bodies: MutableList<String>) -> Unit) {
        val bodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            bodies += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val resp = """{"ok":true,"result":{"message_id":42,"chat":{"id":-100123}}}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        try {
            block(TelegramSender("tok", apiBaseUrl = "http://127.0.0.1:${server.address.port}"), bodies)
        } finally {
            server.stop(0)
        }
    }

    private val replyJson = """"reply_parameters":{"message_id":11,"allow_sending_without_reply":true}"""

    @Test
    fun `reply target is sent as reply_parameters with allow_sending_without_reply`() = withFakeApi { s, bodies ->
        val refs = s.sendToChannel("@ch", "hello", disablePreview = true, replyToMessageId = 11L)
        assertEquals(listOf(SentRef(-100123L, 42L)), refs)
        assertEquals(1, bodies.size)
        assertTrue(replyJson in bodies[0], bodies[0])

        bodies.clear()
        s.sendToChannel("@ch", "hello")
        assertFalse("reply_parameters" in bodies[0], bodies[0])
    }

    @Test
    fun `an image preview replaces the disable flag and rides on the first chunk only`() = withFakeApi { s, bodies ->
        val text = "a".repeat(3000) + "\n" + "b".repeat(3000)
        s.sendToChannel("@ch", text, disablePreview = true, previewImageUrl = "https://img/1.jpg")
        assertEquals(2, bodies.size)
        val preview = """"link_preview_options":{"url":"https://img/1.jpg","prefer_large_media":true,"show_above_text":true}"""
        assertTrue(preview in bodies[0], bodies[0])
        assertFalse("disable_web_page_preview" in bodies[0], bodies[0])
        assertFalse("link_preview_options" in bodies[1], bodies[1])
        assertTrue(""""disable_web_page_preview":true""" in bodies[1], bodies[1])
    }

    @Test
    fun `only the first chunk of a long message carries the reply`() = withFakeApi { s, bodies ->
        val text = "a".repeat(3000) + "\n" + "b".repeat(3000)
        s.sendToChannel("@ch", text, replyToMessageId = 11L)
        assertEquals(2, bodies.size)
        assertTrue(replyJson in bodies[0])
        assertFalse("reply_parameters" in bodies[1])
    }

    @Test
    fun `editMessageText renders HTML and reports OK, pin and unpin post their ids`() = withFakeApi { s, bodies ->
        assertEquals(TelegramSender.EditResult.OK, s.editMessageText("@ch", 7L, "**bold** [l](https://x.y)"))
        assertTrue(""""message_id":7""" in bodies[0], bodies[0])
        assertTrue("<b>bold</b>" in bodies[0], bodies[0])
        assertTrue(""""disable_web_page_preview":true""" in bodies[0], bodies[0])

        bodies.clear()
        assertTrue(s.pinChatMessage("@ch", 7L))
        assertTrue(""""message_id":7""" in bodies[0] && """"disable_notification":true""" in bodies[0], bodies[0])
        assertTrue(s.unpinChatMessage("@ch", 7L))
        assertFalse("disable_notification" in bodies[1], bodies[1])
        assertEquals(listOf("/bottok/pinChatMessage", "/bottok/unpinChatMessage").size, bodies.size)
    }

    @Test
    fun `editMessageText maps a 400 to NOT_FOUND unless the text was merely unchanged`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var description = "Bad Request: message to edit not found"
        server.createContext("/") { ex ->
            ex.requestBody.readBytes()
            val resp = """{"ok":false,"error_code":400,"description":"$description"}""".toByteArray()
            ex.sendResponseHeaders(400, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        try {
            val s = TelegramSender("tok", apiBaseUrl = "http://127.0.0.1:${server.address.port}")
            assertEquals(TelegramSender.EditResult.NOT_FOUND, s.editMessageText("@ch", 7L, "x"))
            description = "Bad Request: message is not modified"
            assertEquals(TelegramSender.EditResult.OK, s.editMessageText("@ch", 7L, "x"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `plain-text fallback after a 400 shows the URL with its real quotes`() {
        val bodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            bodies += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val (code, resp) = if (bodies.size == 1) {
                400 to """{"ok":false,"error_code":400,"description":"Bad Request: can't parse entities"}"""
            } else {
                200 to """{"ok":true,"result":{"message_id":42,"chat":{"id":-100123}}}"""
            }
            val bytes = resp.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val s = TelegramSender("tok", apiBaseUrl = "http://127.0.0.1:${server.address.port}")
            s.sendToChannel("@ch", "[Foo](https://x.com/s?q=\"a\")")
            assertEquals(2, bodies.size)
            assertTrue("""Foo: https://x.com/s?q=\"a\"""" in bodies[1], bodies[1])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `pin, unpin and delete honour a 429 retry_after and retry once`() {
        val paths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestBody.readBytes()
            paths += ex.requestURI.path
            // Every odd request is rate-limited, the retry that follows succeeds.
            val (code, resp) = if (paths.size % 2 == 1) {
                429 to """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":1}}"""
            } else {
                200 to """{"ok":true,"result":true}"""
            }
            val bytes = resp.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val s = TelegramSender("tok", apiBaseUrl = "http://127.0.0.1:${server.address.port}")
            assertTrue(s.pinChatMessage("@ch", 7L))
            assertTrue(s.unpinChatMessage("@ch", 7L))
            assertTrue(s.deleteMessage("@ch", 7L))
            assertEquals(
                listOf("pinChatMessage", "pinChatMessage", "unpinChatMessage", "unpinChatMessage", "deleteMessage", "deleteMessage"),
                paths.map { it.substringAfterLast('/') }
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `photo posts reply too`() = withFakeApi { s, bodies ->
        val ref = s.sendPhotoToChannel("@ch", "https://img.example/1.jpg", caption = "cap", replyToMessageId = 11L)
        assertEquals(SentRef(-100123L, 42L), ref)
        assertTrue(replyJson in bodies.single(), bodies.single())
    }

    @Test
    fun `chunkMessage returns single chunk when under limit`() {
        val text = "Hello world"
        val chunks = sender.chunkMessage(text, maxLen = 4096)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }

    @Test
    fun `chunkMessage returns single chunk when exactly at limit`() {
        val text = "a".repeat(4096)
        val chunks = sender.chunkMessage(text, maxLen = 4096)
        assertEquals(1, chunks.size)
        assertEquals(4096, chunks[0].length)
    }

    @Test
    fun `chunkMessage splits at newlines when over limit`() {
        val line1 = "a".repeat(3000)
        val line2 = "b".repeat(3000)
        val text = "$line1\n$line2"
        val chunks = sender.chunkMessage(text, maxLen = 4096)
        assertEquals(2, chunks.size)
        assertEquals(line1, chunks[0])
        assertEquals(line2, chunks[1])
    }

    @Test
    fun `chunkMessage hard-splits single line longer than maxLen`() {
        val text = "x".repeat(5000)
        val chunks = sender.chunkMessage(text, maxLen = 4096)
        assertEquals(2, chunks.size)
        assertEquals(4096, chunks[0].length)
        assertEquals(904, chunks[1].length)
    }

    @Test
    fun `chunkMessage hard split does not cut an emoji surrogate pair`() {
        // 🔥 straddles the 4096 boundary of a single over-long line.
        val text = "a".repeat(4095) + "🔥" + "b".repeat(10)
        val chunks = sender.chunkMessage(text, maxLen = 4096)
        assertEquals(text, chunks.joinToString(""))
        for (chunk in chunks) {
            assertTrue(chunk.length <= 4096)
            assertFalse(chunk.last().isHighSurrogate(), "chunk ends with an unpaired high surrogate")
            assertFalse(chunk.first().isLowSurrogate(), "chunk starts with an unpaired low surrogate")
        }
    }

    @Test
    fun `chunkMessage hard split never cuts a link in half`() {
        // Found by TelegramSenderPropertyTest: a single over-long line was cut at exactly maxLen,
        // even inside a [label](url) — both halves then went out as raw Markdown.
        val link = "[Doom](https://en.wikipedia.org/wiki/Doom_(2016_video_game))"
        val text = "a".repeat(4080) + link + "b".repeat(100)
        val chunks = sender.chunkMessage(text, maxLen = 4096)
        assertEquals(listOf("a".repeat(4080), link + "b".repeat(100)), chunks)

        // A link longer than a whole message cannot be kept; the plain cut still applies.
        val huge = "[x](https://e/${"y".repeat(5000)})"
        assertEquals("a$huge", sender.chunkMessage("a$huge", maxLen = 4096).joinToString(""))
    }

    @Test
    fun `chunkMessage preserves all content`() {
        val lines = (1..100).map { "Line $it: ${"x".repeat(60)}" }
        val text = lines.joinToString("\n")
        val chunks = sender.chunkMessage(text, maxLen = 4096)
        val rejoined = chunks.joinToString("\n")
        assertEquals(text, rejoined)
    }

    @Test
    fun `truncateForCaption leaves text under the limit unchanged`() {
        val text = "a short caption"
        assertEquals(text, sender.truncateForCaption(text))
    }

    @Test
    fun `truncateForCaption does not split an emoji surrogate pair (BUG-009)`() {
        // Place 🔥 (a surrogate pair) straddling the 1023 cut boundary.
        val text = "a".repeat(1022) + "🔥" + "b".repeat(50)
        val out = sender.truncateForCaption(text)
        val body = out.removeSuffix("…")
        assertTrue(
            body.isEmpty() || !body.last().isHighSurrogate(),
            "truncation left an unpaired high surrogate: invalid UTF-16"
        )
        assertTrue(out.length <= 1024)
    }

    @Test
    fun `truncateForCaption does not cut inside a markdown link (BUG-010)`() {
        val head = "x".repeat(1000)
        val link = "[Some very long article title](https://example.com/some/really/long/path?a=b)"
        val text = "$head $link tail"
        val out = sender.truncateForCaption(text)
        val body = out.removeSuffix("…")
        val open = body.lastIndexOf('[')
        if (open >= 0) {
            assertTrue(body.indexOf(')', open) > open, "left a half-open markdown link: $body")
        }
        assertTrue(out.length <= 1024)
    }

    @Test
    fun `truncateForCaption does not cut inside a link whose url has parentheses`() {
        val head = "x".repeat(1000)
        val link = "[Doom](https://en.wikipedia.org/wiki/Doom_(2016_video_game)_and_a_long_tail)"
        val out = sender.truncateForCaption("$head $link tail")
        assertEquals("$head…", out)
    }

    @Test
    fun `truncateForCaption does not cut inside a link whose label has brackets`() {
        val head = "x".repeat(1000)
        val link = "[I built a thing [P] with a long title](https://example.com/some/long/path)"
        val out = sender.truncateForCaption("$head $link tail")
        assertEquals("$head…", out)
    }

    @Test
    fun `truncateForCaption keeps a 1200-char topic within the caption limit`() {
        val text = "т".repeat(1150) + " [дж](https://ex.com/a)"
        val out = sender.truncateForCaption(text)
        assertTrue(out.length <= 1024)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `sendToChannel returns no refs when send fails`() {
        // Sender with a bogus token will get network/connection errors → no delivered refs
        val unreachableSender = TelegramSender("invalid-token")
        val result = unreachableSender.sendToChannel("@test_channel", "test message")
        assertTrue(result.isEmpty(), "Expected no refs when network call fails")
    }
}
