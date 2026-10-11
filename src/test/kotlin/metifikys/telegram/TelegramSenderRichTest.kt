package metifikys.telegram

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramSenderRichTest {

    private class Call(val path: String, val contentType: String, val body: String)

    /**
     * A fake Bot API: records every call and answers with [status] / [response] (mutable per test),
     * or — when set — with [script] by call index.
     */
    private class FakeApi : AutoCloseable {
        val calls = mutableListOf<Call>()
        var status = 200
        var response = """{"ok":true,"result":{"message_id":42,"chat":{"id":-100123}}}"""
        var script: ((Int) -> Pair<Int, String>)? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                calls += Call(ex.requestURI.path, ex.requestHeaders.getFirst("Content-Type").orEmpty(), ex.requestBody.readBytes().toString(Charsets.UTF_8))
                val (code, body) = script?.invoke(calls.size - 1) ?: (status to response)
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val sender = TelegramSender("tok", apiBaseUrl = "http://127.0.0.1:${server.address.port}", pacer = ChatPacer(minIntervalMs = 0))
        override fun close() = server.stop(0)
    }

    @Test
    fun `a rich message without attachments is one JSON sendRichMessage call`() = FakeApi().use { api ->
        val ref = api.sender.sendRichMessage("@ch", "<h2>Hi</h2><p>a</p>", replyToMessageId = 11L, silent = true)
        assertEquals(SentRef(-100123L, 42L), ref)
        val call = api.calls.single()
        assertEquals("/bottok/sendRichMessage", call.path)
        assertTrue(call.contentType.startsWith("application/json"), call.contentType)
        assertTrue(""""rich_message":{"html":"<h2>Hi</h2><p>a</p>"}""" in call.body, call.body)
        assertTrue(""""reply_parameters":{"message_id":11,"allow_sending_without_reply":true}""" in call.body, call.body)
        assertTrue(""""disable_notification":true""" in call.body, call.body)
    }

    @Test
    fun `attachments go multipart, named in the media list as attach references`() = FakeApi().use { api ->
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val ref = api.sender.sendRichMessage("@ch", "<figure><img src=\"tg://photo?id=barometer\"/></figure>", attachments = mapOf("barometer" to png))
        assertEquals(42L, ref?.messageId)
        val call = api.calls.single()
        assertTrue(call.contentType.startsWith("multipart/form-data"), call.contentType)
        assertTrue("""name="rich_message"""" in call.body, call.body)
        assertTrue("""{"id":"barometer","media":{"type":"photo","media":"attach://barometer"}}""" in call.body, call.body)
        assertTrue("""name="barometer"; filename="barometer.png"""" in call.body, call.body)
        assertFalse("reply_parameters" in call.body, call.body)
    }

    @Test
    fun `a refused rich message returns null and is not retried as plain text`() = FakeApi().use { api ->
        api.status = 400
        api.response = """{"ok":false,"error_code":400,"description":"Bad Request: can't parse rich message"}"""
        assertNull(api.sender.sendRichMessage("@ch", "<h2>x</h2>"))
        assertEquals(1, api.calls.size)
    }

    @Test
    fun `a time token is a tg-time entity in an ordinary post, and the plain-text retry keeps its text`() = FakeApi().use { api ->
        api.script = { i ->
            if (i == 0) 400 to """{"ok":false,"error_code":400,"description":"Bad Request: can't parse entities"}"""
            else 200 to """{"ok":true,"result":{"message_id":42,"chat":{"id":-100123}}}"""
        }
        api.sender.sendToChannel("@ch", "**MMX**\n\n📅 ⟦t:1792400400:wD|19.10.2026⟧")
        assertEquals(2, api.calls.size)
        assertTrue("""📅 <tg-time unix=\"1792400400\" format=\"wD\">19.10.2026</tg-time>""" in api.calls[0].body, api.calls[0].body)
        assertTrue(""""text":"MMX\n\n📅 19.10.2026"""" in api.calls[1].body, api.calls[1].body)
    }

    @Test
    fun `editRichMessage tells a gone message from refused content`() = FakeApi().use { api ->
        assertEquals(TelegramSender.EditResult.OK, api.sender.editRichMessage("@ch", 7L, "<p>x</p>"))
        assertTrue(""""message_id":7""" in api.calls[0].body && """"rich_message":{"html":"<p>x</p>"}""" in api.calls[0].body, api.calls[0].body)
        assertEquals("/bottok/editMessageText", api.calls[0].path)

        api.status = 400
        api.response = """{"ok":false,"error_code":400,"description":"Bad Request: message is not modified"}"""
        assertEquals(TelegramSender.EditResult.OK, api.sender.editRichMessage("@ch", 7L, "<p>x</p>"))
        api.response = """{"ok":false,"error_code":400,"description":"Bad Request: message to edit not found"}"""
        assertEquals(TelegramSender.EditResult.NOT_FOUND, api.sender.editRichMessage("@ch", 7L, "<p>x</p>"))
        api.response = """{"ok":false,"error_code":400,"description":"Bad Request: can't parse rich message"}"""
        assertEquals(TelegramSender.EditResult.REJECTED, api.sender.editRichMessage("@ch", 7L, "<p>x</p>"))
        api.status = 502
        api.response = """{"ok":false,"error_code":502,"description":"Bad Gateway"}"""
        assertEquals(TelegramSender.EditResult.FAILED, api.sender.editRichMessage("@ch", 7L, "<p>x</p>"))
    }
}
