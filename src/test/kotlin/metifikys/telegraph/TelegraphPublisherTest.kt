package metifikys.telegraph

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import metifikys.config.TelegraphConfig
import metifikys.db.NewsDatabase
import metifikys.fetch.UrlGuard
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** [TelegraphClient] and [TelegraphPublisher] against a local fake of api.telegra.ph. */
class TelegraphPublisherTest {

    private lateinit var db: NewsDatabase
    private lateinit var dbFile: File
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    /** method → request form params, in call order. */
    private val calls = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()

    /** method → response body. */
    private val responses = mutableMapOf(
        "createAccount" to """{"ok":true,"result":{"short_name":"RssNewsBot","author_name":"RSS News","access_token":"tok-new"}}""",
        "createPage" to """{"ok":true,"result":{"path":"Story-09-23","url":"https://telegra.ph/Story-09-23","title":"Story","views":0}}"""
    )

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-telegraph", ".db")
        db = NewsDatabase(dbFile.absolutePath)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val method = ex.requestURI.path.trim('/')
            val form = ex.requestBody.readBytes().toString(Charsets.UTF_8).split('&').filter { it.isNotEmpty() }.associate {
                val (k, v) = it.split('=', limit = 2)
                URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }
            calls += method to form
            val body = (responses[method] ?: """{"ok":false,"error":"METHOD_NOT_FOUND"}""").toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun teardown() {
        server.stop(0)
        dbFile.delete()
    }

    private val content = JsonArray(listOf(JsonPrimitive("text")))

    private fun publisher(cfg: TelegraphConfig = TelegraphConfig()) =
        TelegraphPublisher(cfg, db, TelegraphClient(5, baseUrl, UrlGuard.OFF))

    @Test
    fun `the account is created once, its token stored and reused`() {
        val p = publisher()
        assertEquals("https://telegra.ph/Story-09-23", p.publish("gaming", "evt-1", "Story", content, "https://t.me/gaming"))
        assertEquals("https://telegra.ph/Story-09-23", p.publish("gaming", "evt-2", "Story", content, null))
        assertEquals(listOf("createAccount", "createPage", "createPage"), calls.map { it.first })
        assertEquals("tok-new", db.getState(TelegraphPublisher.STATE_KEY))

        val account = calls[0].second
        assertEquals(mapOf("short_name" to "RssNewsBot", "author_name" to "RSS News", "author_url" to "https://t.me/gaming"), account)
        val page = calls[1].second
        assertEquals("tok-new", page["access_token"])
        assertEquals("Story", page["title"])
        assertEquals("https://t.me/gaming", page["author_url"])
        assertEquals("[\"text\"]", page["content"])

        val row = db.fetchLatestTelegraphPage("gaming", "evt-1")!!
        assertEquals("Story-09-23", row.path)
        assertEquals("https://telegra.ph/Story-09-23", row.url)
    }

    @Test
    fun `a configured token skips createAccount, and authorUrl in config wins`() {
        publisher(TelegraphConfig(accessToken = "cfg-tok", authorUrl = "https://example.com/me"))
            .publish("gaming", "evt-1", "Story", content, "https://t.me/gaming")
        assertEquals(listOf("createPage"), calls.map { it.first })
        assertEquals("cfg-tok", calls[0].second["access_token"])
        assertEquals("https://example.com/me", calls[0].second["author_url"])
        assertNull(db.getState(TelegraphPublisher.STATE_KEY))
    }

    @Test
    fun `ok false or a flood wait yields no page`() {
        responses["createPage"] = """{"ok":false,"error":"FLOOD_WAIT_7"}"""
        assertNull(publisher().publish("gaming", "evt-1", "Story", content, null))
        assertNull(db.fetchLatestTelegraphPage("gaming", "evt-1"))
        assertFailsWith<TelegraphClient.TelegraphException> {
            TelegraphClient(5, baseUrl, UrlGuard.OFF).createPage("t", "Story", "a", null, content)
        }.also { assertEquals("FLOOD_WAIT_7", it.error) }
    }

    @Test
    fun `a rejected stored token is forgotten so the next page makes a new account`() {
        db.setState(TelegraphPublisher.STATE_KEY, "old-tok")
        responses["createPage"] = """{"ok":false,"error":"ACCESS_TOKEN_INVALID"}"""
        assertNull(publisher().publish("gaming", "evt-1", "Story", content, null))
        assertNull(db.getState(TelegraphPublisher.STATE_KEY))
    }

    @Test
    fun `an unreachable API yields no page`() {
        server.stop(0)
        assertNull(publisher().publish("gaming", "evt-1", "Story", content, null))
    }

    @Test
    fun `the default guard lets the public API host through`() {
        // api.telegra.ph must not be mistaken for a private address; resolution is stubbed.
        val guard = UrlGuard(resolve = { arrayOf(java.net.InetAddress.getByAddress("api.telegra.ph", byteArrayOf(149.toByte(), 154.toByte(), 164.toByte(), 13))) })
        guard.check("https://api.telegra.ph/createPage")
    }
}
