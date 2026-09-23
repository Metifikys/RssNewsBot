package metifikys.telegram

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import metifikys.config.AdminCommandsConfig
import metifikys.config.AdminConfig
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminCommandRouterTest {

    private val admin = AdminConfig(statusChatId = "-500", userIds = listOf(7L), commands = AdminCommandsConfig(enabled = true))
    private val now = Instant.parse("2026-09-23T12:00:00Z")

    private class Harness(admin: AdminConfig, now: Instant) {
        val replies = mutableListOf<Pair<String, String>>()
        val sender = mockk<TelegramSender>().also { s ->
            val chat = slot<String>()
            val text = slot<String>()
            every { s.sendMessageWithKeyboard(capture(chat), capture(text), any(), any(), any()) } answers {
                replies += chat.captured to text.captured
                1L
            }
        }
        val router = AdminCommandRouter(AdminAuth(admin), sender, Executor { it.run() }, clock = { now })
    }

    private fun message(text: String, from: Long? = 7L, chatId: Long = 7L, type: String = "private", date: Long = now.epochSecond) =
        IncomingMessage(updateId = 1, chatId = chatId, chatType = type, fromId = from, messageId = 3, date = date, text = text)

    @Test
    fun `parse drops the bot-name suffix and keeps quoted phrases together`() {
        assertEquals(AdminCommandRouter.Command("mute", listOf("call of duty", "gaming")),
            AdminCommandRouter.parse("/Mute@RssNewsBot \"call of duty\" gaming"))
        assertEquals(AdminCommandRouter.Command("mute", listOf("star wars")), AdminCommandRouter.parse("/mute «star wars»"))
        assertEquals(AdminCommandRouter.Command("mute", listOf("a b")), AdminCommandRouter.parse("/mute “a b”"))
        assertEquals(AdminCommandRouter.Command("help", emptyList()), AdminCommandRouter.parse("  /help  "))
        assertNull(AdminCommandRouter.parse("hello"))
        assertNull(AdminCommandRouter.parse("/"))
    }

    @Test
    fun `an unknown command answers with the help text`() {
        val h = Harness(admin, now)
        h.router.register("pause", "/pause <cat>", "призупинити")
        { _, _ -> error("not called") }
        h.router.onMessage(message("/nope"))
        assertEquals(1, h.replies.size)
        val (chat, text) = h.replies.single()
        assertEquals("7", chat)
        assertTrue(text.startsWith("Невідома команда /nope"), text)
        assertTrue("/pause <cat> — призупинити" in text && "/help" in text, text)
    }

    @Test
    fun `commands count only from an admin in the admin chat or a private chat`() {
        val h = Harness(admin, now)
        val ran = mutableListOf<String>()
        h.router.register("ping", "/ping", "t") { _, ctx -> ran += ctx.chatId }
        h.router.onMessage(message("/ping", from = 99))                                  // stranger
        h.router.onMessage(message("/ping", from = 7, chatId = -42, type = "group"))       // admin, foreign group
        h.router.onMessage(message("/ping", from = null, chatId = -500, type = "channel")) // channel post, no sender
        h.router.onMessage(message("/ping", from = 7, chatId = -500, type = "group"))      // admin chat
        h.router.onMessage(message("/ping"))                                               // private chat
        assertEquals(listOf("-500", "7"), ran)
        assertTrue(h.replies.isEmpty(), "nobody is answered on a refused command")
    }

    @Test
    fun `a stale command is refused, a failing handler reports the error`() {
        val h = Harness(admin, now)
        var ran = false
        h.router.register("run", "/run", "t") { _, _ -> ran = true }
        h.router.register("boom", "/boom", "t") { _, _ -> error("kaput") }
        h.router.onMessage(message("/run", date = now.epochSecond - 11 * 60))
        assertTrue(!ran)
        assertTrue(h.replies.single().second.startsWith("⌛ /run застаріла"), h.replies.single().second)
        h.router.onMessage(message("/boom"))
        assertTrue("kaput" in h.replies.last().second)
    }

    @Test
    fun `menu lists help first, then registered commands`() {
        val h = Harness(admin, now)
        h.router.register("status", "/status", "знімок") { _, _ -> }
        assertEquals(listOf("help", "status"), h.router.menu().map { it.first })
    }
}
