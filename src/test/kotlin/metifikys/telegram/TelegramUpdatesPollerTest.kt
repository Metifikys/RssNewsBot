package metifikys.telegram

import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import metifikys.config.AdminConfig
import metifikys.config.AdminCommandsConfig
import metifikys.db.NewsDatabase
import metifikys.db.DigestMessageRow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The poller against a local fake Bot API: the first `getUpdates` returns [updatesJson], later
 * ones an empty list. Routers run their work inline (direct executor).
 */
class TelegramUpdatesPollerTest {

    private lateinit var dbFile: File
    private lateinit var db: NewsDatabase
    private var server: HttpServer? = null
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("poller-test", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun teardown() {
        server?.stop(0)
        dbFile.delete()
    }

    private val admin = AdminConfig(statusChatId = "555", userIds = listOf(7L), commands = AdminCommandsConfig(enabled = true))
    private val direct = Executor { it.run() }

    private fun fakeApi(updatesJson: String): String {
        var served = false
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            requests += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val body = synchronized(this) {
                if (!served) { served = true; """{"ok":true,"result":$updatesJson}""" } else null
            } ?: run { Thread.sleep(50); """{"ok":true,"result":[]}""" }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun msg(updateId: Long, fromId: Long, text: String, chatId: Long = fromId, type: String = "private") =
        """{"update_id":$updateId,"message":{"message_id":1,"date":${System.currentTimeMillis() / 1000},""" +
            """"chat":{"id":$chatId,"type":"$type"},"from":{"id":$fromId},"text":"$text"}}"""

    @Test
    fun `allowed_updates follows the routers`() {
        val sender = mockk<TelegramSender>(relaxed = true)
        val auth = AdminAuth(admin)
        val plain = TelegramUpdatesPoller("t", db)
        assertEquals(listOf("message_reaction_count"), plain.allowedUpdates)
        val full = TelegramUpdatesPoller(
            "t", db, AdminCommandRouter(auth, sender, direct), AdminCallbackRouter(auth, sender, direct)
        )
        assertEquals(listOf("message_reaction_count", "message", "callback_query"), full.allowedUpdates)
    }

    @Test
    fun `a command's offset is persisted before its handler runs, strangers are not routed, reactions still land`() {
        val sender = mockk<TelegramSender>(relaxed = true)
        val router = AdminCommandRouter(AdminAuth(admin), sender, direct)
        val offsetsSeen = Collections.synchronizedList(mutableListOf<String?>())
        val done = CountDownLatch(1)
        router.register("ping", "/ping", "test") { _, _ ->
            offsetsSeen += db.getState("updates_offset")
            done.countDown()
        }
        router.register("stranger", "/stranger", "test") { _, _ -> offsetsSeen += "STRANGER" }
        db.insertDigestMessages(listOf(DigestMessageRow(-100L, 5L, "tech", "e", "", LocalDateTime.now())))

        val reaction = """{"update_id":10,"message_reaction_count":{"chat":{"id":-100},"message_id":5,""" +
            """"reactions":[{"type":{"type":"emoji","emoji":"👍"},"total_count":2}]}}"""
        val base = fakeApi("[$reaction,${msg(11, 99, "/stranger")},${msg(12, 7, "/ping")}]")
        val poller = TelegramUpdatesPoller(
            "t", db, router, AdminCallbackRouter(AdminAuth(admin), sender, direct), apiBaseUrl = base, longPollSeconds = 1
        )
        poller.start()
        try {
            assertTrue(done.await(10, TimeUnit.SECONDS), "the admin's /ping should be routed")
        } finally {
            poller.stop()
        }
        assertEquals(listOf<String?>("13"), offsetsSeen.toList(), "offset past /ping saved before the handler; /stranger never ran")
        assertTrue(requests.first().contains(""""allowed_updates":["message_reaction_count","message","callback_query"]"""), requests.first())
        val reactions = db.fetchReactionSummary(24)
        assertEquals(2L, reactions["tech"]?.totalReactions)
    }

    @Test
    fun `a button press from an admin is acknowledged and routed by namespace`() {
        val sender = mockk<TelegramSender>(relaxed = true)
        val callbacks = AdminCallbackRouter(AdminAuth(admin), sender, direct)
        val seen = Collections.synchronizedList(mutableListOf<List<String>>())
        val done = CountDownLatch(1)
        callbacks.register("ms") { cb, parts ->
            seen += parts + listOf(cb.chatId.toString(), cb.messageId.toString())
            done.countDown()
        }
        val press = """{"update_id":20,"callback_query":{"id":"cb1","from":{"id":7},"data":"ms:ok:17",""" +
            """"message":{"message_id":44,"date":0,"chat":{"id":555,"type":"private"}}}}"""
        val base = fakeApi("[$press]")
        val poller = TelegramUpdatesPoller("t", db, null, callbacks, apiBaseUrl = base, longPollSeconds = 1)
        poller.start()
        try {
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            poller.stop()
        }
        assertEquals(listOf(listOf("ok", "17", "555", "44")), seen.toList())
        io.mockk.verify { sender.answerCallbackQuery("cb1", null) }
    }

    @Test
    fun `a forwarded channel post reaches the message routes with its origin, old and new forward fields`() {
        val sender = mockk<TelegramSender>(relaxed = true)
        val router = AdminCommandRouter(AdminAuth(admin), sender, direct)
        val seen = Collections.synchronizedList(mutableListOf<Pair<Long?, Long?>>())
        val done = CountDownLatch(2)
        router.registerMessageHandler("fwd", { it.forwardChatId != null }) { m, _ ->
            seen += m.forwardChatId to m.forwardMessageId
            done.countDown()
        }
        val now = System.currentTimeMillis() / 1000
        val origin = """{"update_id":30,"message":{"message_id":2,"date":$now,"chat":{"id":7,"type":"private"},"from":{"id":7},""" +
            """"forward_origin":{"type":"channel","chat":{"id":-1009876543210,"type":"channel"},"message_id":15205,"date":1}}}"""
        val legacy = """{"update_id":31,"message":{"message_id":3,"date":$now,"chat":{"id":7,"type":"private"},"from":{"id":7},""" +
            """"forward_from_chat":{"id":-1009876543210,"type":"channel"},"forward_from_message_id":15206,"caption":"x"}}"""
        val user = """{"update_id":32,"message":{"message_id":4,"date":$now,"chat":{"id":7,"type":"private"},"from":{"id":7},""" +
            """"forward_origin":{"type":"user","sender_user":{"id":5},"date":1},"text":"hi"}}"""
        val base = fakeApi("[$origin,$legacy,$user]")
        val poller = TelegramUpdatesPoller("t", db, router, null, apiBaseUrl = base, longPollSeconds = 1)
        poller.start()
        try {
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            poller.stop()
        }
        assertEquals(listOf<Pair<Long?, Long?>>(-1009876543210L to 15205L, -1009876543210L to 15206L), seen.toList())
    }
}
