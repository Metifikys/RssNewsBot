package metifikys.enrich

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * A local HTTP server for the badge provider tests: routes are matched by path prefix and
 * answer with a fixed status + body; every hit is counted so tests can assert on caching.
 */
class FakeApi : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val routes = mutableListOf<Triple<String, Int, String>>()
    val hits = AtomicInteger(0)
    val requests = mutableListOf<String>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun route(pathPrefix: String, body: String, status: Int = 200): FakeApi {
        routes += Triple(pathPrefix, status, body)
        return this
    }

    fun start(): FakeApi {
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            val path = ex.requestURI.path + (ex.requestURI.query?.let { "?$it" } ?: "")
            synchronized(requests) { requests += path }
            val match = routes.firstOrNull { ex.requestURI.path.startsWith(it.first) }
            val status = match?.second ?: 404
            val bytes = (match?.third ?: "").toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.start()
        return this
    }

    override fun close() = server.stop(0)
}
