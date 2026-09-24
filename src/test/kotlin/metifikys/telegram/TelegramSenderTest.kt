package metifikys.telegram

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramSenderTest {

    private val sender = TelegramSender("token")

    /** Tests drive many calls into one chat; the 1 s per-chat spacing is covered by ChatPacerTest. */
    private fun noPacing() = ChatPacer(minIntervalMs = 0)

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
            block(TelegramSender("tok", apiBaseUrl = "http://127.0.0.1:${server.address.port}", pacer = noPacing()), bodies)
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
    fun `a page preview can sit below the text`() = withFakeApi { s, bodies ->
        s.sendToChannel("@ch", "teaser", disablePreview = true, previewImageUrl = "https://telegra.ph/p", previewAboveText = false)
        val preview = """"link_preview_options":{"url":"https://telegra.ph/p","prefer_large_media":true,"show_above_text":false}"""
        assertTrue(preview in bodies[0], bodies[0])
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
    fun `admin messages carry an inline keyboard, silence, and the admin-chat helper calls`() = withFakeApi { s, bodies ->
        val kb = listOf(listOf(InlineButton("🔇 У gaming", "ms:cat:17"), InlineButton("✖ Ні", "ms:no:17")))
        assertEquals(42L, s.sendMessageWithKeyboard("555", "hi", kb, silent = true))
        val keyboardJson = """"reply_markup":{"inline_keyboard":[[{"text":"🔇 У gaming","callback_data":"ms:cat:17"},""" +
            """{"text":"✖ Ні","callback_data":"ms:no:17"}]]}"""
        assertTrue(keyboardJson in bodies[0], bodies[0])
        assertTrue(""""disable_notification":true""" in bodies[0] && "parse_mode" !in bodies[0], bodies[0])

        assertEquals(TelegramSender.EditResult.OK, s.editMessageWithKeyboard("555", 42L, "done", keyboard = null))
        assertFalse("reply_markup" in bodies[1], "a null keyboard removes it: ${bodies[1]}")

        assertTrue(s.editMessageReplyMarkup("555", 42L, kb))
        assertTrue(s.answerCallbackQuery("cbq-1"))
        assertTrue(""""callback_query_id":"cbq-1"""" in bodies[3], bodies[3])
        assertTrue(s.setMyCommands(listOf("help" to "список"), "555"))
        assertTrue(""""scope":{"type":"chat","chat_id":"555"}""" in bodies[4], bodies[4])
    }

    @Test
    fun `callback data over 64 bytes is rejected up front`() {
        val ex = runCatching { InlineButton("x", "mod:del:" + "9".repeat(60)) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
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
            val s = TelegramSender("tok", apiBaseUrl = "http://127.0.0.1:${server.address.port}", pacer = noPacing())
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
            val s = TelegramSender("tok", apiBaseUrl = "http://127.0.0.1:${server.address.port}", pacer = noPacing())
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
            val s = TelegramSender("tok", apiBaseUrl = "http://127.0.0.1:${server.address.port}", pacer = noPacing())
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

    /** A fake Bot API that answers every call with 429 + [retryAfter]; returns the server and the hit paths. */
    private fun alwaysRateLimited(retryAfter: Int): Pair<HttpServer, MutableList<String>> {
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestBody.readBytes()
            paths += ex.requestURI.path.substringAfterLast('/')
            val bytes = """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":$retryAfter}}"""
                .toByteArray()
            ex.sendResponseHeaders(429, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to paths
    }

    @Test
    fun `a second 429 gives up and both count for status`() {
        val (server, paths) = alwaysRateLimited(retryAfter = 1)
        try {
            val s = TelegramSender("tok", apiBaseUrl = "http://127.0.0.1:${server.address.port}", pacer = noPacing())
            assertFalse(s.deleteMessage("@ch", 7L))
            assertEquals(listOf("deleteMessage", "deleteMessage"), paths.toList())
            assertEquals(2, s.rateLimitsLast24h())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an interrupt during the 429 wait keeps the flag and stops retrying`() {
        val (server, paths) = alwaysRateLimited(retryAfter = 30)
        val caller = Thread.currentThread()
        val interrupter = Thread {
            // Wait until the first 429 arrived, then interrupt the cooldown wait.
            while (paths.isEmpty()) Thread.sleep(10)
            Thread.sleep(200)
            caller.interrupt()
        }
        try {
            val s = TelegramSender("tok", apiBaseUrl = "http://127.0.0.1:${server.address.port}", pacer = noPacing())
            interrupter.start()
            val started = System.nanoTime()
            assertTrue(s.sendToChannel("@ch", "hello").isEmpty())
            assertTrue(Thread.interrupted(), "the interrupt flag must survive the rate-limit wait")
            assertTrue(System.nanoTime() - started < 10_000_000_000L, "gave up promptly instead of waiting 30 s")
            assertEquals(listOf("sendMessage"), paths.toList(), "no retry after the interrupt")
        } finally {
            interrupter.join()
            Thread.interrupted()
            server.stop(0)
        }
    }

    @Test
    fun `a PNG goes up as a multipart photo with an HTML caption`() = withFakeApi { s, bodies ->
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        val ref = s.sendPhotoBytes("@ch", png, caption = "**Барометр** за тиждень", fileName = "barometer.png")
        assertEquals(SentRef(-100123L, 42L), ref)
        val body = bodies.single()
        assertTrue("name=\"chat_id\"" in body && "@ch" in body, body)
        assertTrue("name=\"caption\"" in body && "<b>Барометр</b> за тиждень" in body, body)
        assertTrue("name=\"parse_mode\"" in body && "HTML" in body, body)
        assertTrue("name=\"photo\"; filename=\"barometer.png\"" in body, body)
        assertTrue("Content-Type: image/png" in body, body)
    }

    @Test
    fun `a rate-limited PNG upload waits and resends the whole body once`() {
        val bodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            bodies += ex.requestBody.readBytes().toString(Charsets.ISO_8859_1)
            val (code, resp) = if (bodies.size == 1) {
                429 to """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":1}}"""
            } else {
                200 to """{"ok":true,"result":{"message_id":43,"chat":{"id":-100123}}}"""
            }
            val bytes = resp.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val s = TelegramSender("tok", apiBaseUrl = "http://127.0.0.1:${server.address.port}", pacer = noPacing())
            val png = ByteArray(2048) { (it % 251).toByte() }
            assertEquals(SentRef(-100123L, 43L), s.sendPhotoBytes("@ch", png, caption = "c"))
            assertEquals(2, bodies.size)
            assertEquals(bodies[0].length, bodies[1].length)
            assertTrue(png.toString(Charsets.ISO_8859_1) in bodies[1])
            assertEquals(1, s.rateLimitsLast24h())
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

    @Test
    fun `an album is one sendMediaGroup with the caption on the first photo only`() {
        val bodies = mutableListOf<String>()
        val paths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            paths += ex.requestURI.path.substringAfterLast('/')
            bodies += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val resp = """{"ok":true,"result":[{"message_id":50,"chat":{"id":-100123}},{"message_id":51,"chat":{"id":-100123}}]}""".toByteArray()
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        try {
            val s = TelegramSender("tok", apiBaseUrl = "http://127.0.0.1:${server.address.port}", pacer = noPacing())
            val refs = s.sendMediaGroup("@ch", listOf(AlbumPhoto("https://img/1.jpg", "**Тиждень** & more"), AlbumPhoto("https://img/2.jpg")))
            assertEquals(listOf(SentRef(-100123L, 50L), SentRef(-100123L, 51L)), refs)
            assertEquals(listOf("sendMediaGroup"), paths)
            assertEquals(
                """{"chat_id":"@ch","media":[""" +
                    """{"type":"photo","media":"https://img/1.jpg","caption":"<b>Тиждень</b> &amp; more","parse_mode":"HTML"},""" +
                    """{"type":"photo","media":"https://img/2.jpg"}]}""",
                bodies.single()
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a rejected album returns nothing`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestBody.readBytes()
            val resp = """{"ok":false,"error_code":400,"description":"Bad Request: failed to get HTTP URL content"}""".toByteArray()
            ex.sendResponseHeaders(400, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        try {
            val s = TelegramSender("tok", apiBaseUrl = "http://127.0.0.1:${server.address.port}", pacer = noPacing())
            assertTrue(s.sendMediaGroup("@ch", listOf(AlbumPhoto("https://img/1.jpg"), AlbumPhoto("https://img/2.jpg"))).isEmpty())
        } finally {
            server.stop(0)
        }
    }
}
