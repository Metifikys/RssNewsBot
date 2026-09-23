package metifikys.enrich

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import metifikys.fetch.UrlGuard
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Small GET client for the badge providers: short timeouts, a descriptive User-Agent (the
 * Wikimedia and GitHub APIs ask for one), redirects followed (no credentials travel here; OkHttp
 * drops `Authorization` on a cross-host hop). Every hop goes through the [UrlGuard]'s network
 * interceptor, so an API redirect can never land on a private address.
 * A 404 is a normal "not found" (null); any other non-2xx or a transport error throws, so the
 * provider can tell "no such thing" (cacheable) from "the API is down" (not cacheable).
 */
class BadgeHttp(
    timeoutSeconds: Long = 5,
    private val userAgent: String = "RssNewsBot/1.0 (+https://github.com/Metifikys/RssNewsBot)",
    /** Public APIs only: the default trusts no private host. Tests pass [UrlGuard.OFF]. */
    guard: UrlGuard = UrlGuard()
) {
    private val client = guard.install(OkHttpClient.Builder())
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }

    fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement? =
        get(url, headers)?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) }
}
