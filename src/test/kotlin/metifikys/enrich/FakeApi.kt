package metifikys.enrich

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A local HTTP server for the badge provider tests: routes are matched by path prefix (a prefix
 * with `?` is matched against path + query, for endpoints told apart by a parameter) and answer
 * with a fixed status + body, optionally after [route]'s `delayMs`; every hit is counted so tests
 * can assert on caching, and the request headers are kept.
 */
class FakeApi : AutoCloseable {
    private class Route(val prefix: String, val status: Int, val body: String, val delayMs: Long)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val routes = mutableListOf<Route>()
    val hits = AtomicInteger(0)
    val requests = mutableListOf<String>()
    /** Request headers, lower-case names, in request order. */
    val headers = mutableListOf<Map<String, String>>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun route(pathPrefix: String, body: String, status: Int = 200, delayMs: Long = 0): FakeApi {
        routes += Route(pathPrefix, status, body, delayMs)
        return this
    }

    /** Hits whose path + query starts with [prefix]. */
    fun hitsFor(prefix: String): Int = synchronized(requests) { requests.count { it.startsWith(prefix) } }

    fun start(): FakeApi {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "fake-api").also { it.isDaemon = true } }
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            val path = ex.requestURI.path + (ex.requestURI.query?.let { "?$it" } ?: "")
            synchronized(requests) {
                requests += path
                headers += ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            }
            val match = routes.firstOrNull { r ->
                if ('?' in r.prefix) path.startsWith(r.prefix) else ex.requestURI.path.startsWith(r.prefix)
            }
            if (match != null && match.delayMs > 0) Thread.sleep(match.delayMs)
            val status = match?.status ?: 404
            val bytes = (match?.body ?: "").toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.start()
        return this
    }

    override fun close() = server.stop(0)
}
