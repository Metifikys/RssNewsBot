package metifikys.telegraph

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import metifikys.fetch.UrlGuard
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The two telegra.ph API calls the long-form pages need (https://telegra.ph/api): `createAccount`
 * and `createPage`. Form-encoded POSTs (the token travels in the body, never in a URL that could
 * reach a log), `content` as a JSON array of Nodes. Every call is capped at [timeoutSeconds] and
 * goes through the [UrlGuard] (api.telegra.ph is a public host). A `{"ok":false}` answer throws
 * [TelegraphException] with Telegraph's error code (`FLOOD_WAIT_5`, `ACCESS_TOKEN_INVALID`, …);
 * transport failures throw [IOException] as they are.
 */
class TelegraphClient(
    timeoutSeconds: Long = 10,
    /** API origin; tests point it at a local server (with [UrlGuard.OFF]). */
    private val baseUrl: String = "https://api.telegra.ph",
    guard: UrlGuard = UrlGuard()
) {
    /** Telegraph answered `ok: false`; [error] is its error string. */
    class TelegraphException(val error: String) : IOException("Telegraph error: $error")

    /** A created page: its path (`Title-09-23`) and full URL. */
    data class Page(val path: String, val url: String)

    private val client = guard.install(OkHttpClient.Builder())
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Creates an account and returns its access token. */
    fun createAccount(shortName: String, authorName: String, authorUrl: String?): String {
        val result = call("createAccount", buildMap {
            put("short_name", shortName)
            put("author_name", authorName)
            if (!authorUrl.isNullOrBlank()) put("author_url", authorUrl)
        })
        return result["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw TelegraphException("NO_ACCESS_TOKEN")
    }

    /** Publishes a page of [content] (an array of Telegraph Nodes, see [TelegraphPage]). */
    fun createPage(token: String, title: String, authorName: String, authorUrl: String?, content: JsonArray): Page {
        val result = call("createPage", buildMap {
            put("access_token", token)
            put("title", title)
            put("author_name", authorName)
            if (!authorUrl.isNullOrBlank()) put("author_url", authorUrl)
            put("content", content.toString())
            put("return_content", "false")
        })
        val path = result["path"]?.jsonPrimitive?.contentOrNull
        val url = result["url"]?.jsonPrimitive?.contentOrNull
        if (path.isNullOrBlank() || url.isNullOrBlank() || !url.startsWith("https://")) throw TelegraphException("NO_PAGE_URL")
        return Page(path, url)
    }

    private fun call(method: String, params: Map<String, String>): JsonObject {
        val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url("$baseUrl/$method").post(body).build()
        val text = client.newCall(request).execute().use { response ->
            // Telegraph reports its errors with HTTP 200 and ok:false; anything else is transport.
            if (!response.isSuccessful) throw IOException("Telegraph $method: HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IOException("Telegraph $method: not a JSON object")
        if ((root["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            throw TelegraphException((root["error"] as? JsonPrimitive)?.contentOrNull ?: "UNKNOWN")
        }
        return root["result"] as? JsonObject ?: throw IOException("Telegraph $method: no result")
    }
}
