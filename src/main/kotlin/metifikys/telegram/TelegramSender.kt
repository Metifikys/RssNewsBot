package metifikys.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.format.TopicFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Chat + message id of a message the bot actually delivered. Captured from the send response
 * so digest topics can later be joined back to the reactions Telegram reports per message
 * (see [metifikys.telegram.TelegramUpdatesPoller]). [chatId] is the numeric id from the
 * response (channels report reactions under the `-100…` id, not the `@username` form).
 */
data class SentRef(val chatId: Long, val messageId: Long)

class TelegramSender(private val botToken: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Do not follow redirects to avoid leaking bot token to a third-party host
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Request payload serialized safely via kotlinx-serialization (no manual JSON). */
    @Serializable
    private data class SendMessageRequest(
        val chat_id: String,
        val text: String,
        val parse_mode: String? = null,
        val disable_web_page_preview: Boolean? = null
    )

    @Serializable
    private data class SendPhotoRequest(
        val chat_id: String,
        val photo: String,
        val caption: String? = null,
        val parse_mode: String? = null
    )

    @Serializable
    private data class DeleteMessageRequest(
        val chat_id: String,
        val message_id: Long
    )

    @Serializable
    private data class SendMessageResponse(
        val ok: Boolean = false,
        val result: SentMessage? = null,
        val error_code: Int? = null,
        val description: String? = null,
        val parameters: ResponseParameters? = null
    )

    @Serializable
    private data class ResponseParameters(val retry_after: Long? = null)

    @Serializable
    private data class SentMessage(val message_id: Long, val chat: ChatRef? = null)

    @Serializable
    private data class ChatRef(val id: Long)

    /**
     * Outcome of a single Bot API POST. Distinguishes the failure classes so callers can decide
     * whether a plain-text retry is warranted (BUG-007): only [BadRequest] (HTTP 400, a markup/parse
     * error) is — on [Transient] (429 after its own retry, 5xx, network) the HTML was fine and a
     * plain-text resend would risk a duplicate and hammer a rate limit.
     */
    private sealed interface SendResult {
        data class Ok(val ref: SentRef) : SendResult
        object BadRequest : SendResult
        object Transient : SendResult
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private companion object {
        /** Telegram's hard limit for sendPhoto caption length. */
        const val MAX_CAPTION_LEN = 1024

        /** Fallback wait when Telegram returns 429 without a `parameters.retry_after`. */
        const val DEFAULT_RETRY_AFTER_SEC = 3L

        /** Upper bound on the honored `retry_after`, so an extreme value can't stall the caller. */
        const val MAX_RETRY_AFTER_SEC = 60L

        /** Matches the `<a href="url">label</a>` anchors emitted by [TopicFormatter.toHtml]. */
        val HTML_ANCHOR = Regex("""<a href="([^"]*)">(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    }

    /**
     * Sends [text] to [channelId], chunking if needed. Returns a [SentRef] for every message
     * actually delivered (usually one; long text may chunk into several). An empty list means
     * nothing was sent — the caller treats that as the old `false`. Non-empty refs feed the
     * reaction-tracking join in [metifikys.digest.DigestDeliverer].
     */
    fun sendToChannel(channelId: String, text: String, disablePreview: Boolean = false): List<SentRef> {
        val chunks = chunkMessage(text)
        val refs = ArrayList<SentRef>(chunks.size)
        for (chunk in chunks) {
            val html = TopicFormatter.toHtml(chunk)
            val ref = when (val res = sendMessage(channelId, html, parseMode = "HTML", disablePreview = disablePreview)) {
                is SendResult.Ok -> res.ref
                // BUG-007: fall back to plain text only on a 400 markup error.
                is SendResult.BadRequest ->
                    (sendMessage(channelId, html.htmlToPlainText(), parseMode = null, disablePreview = disablePreview) as? SendResult.Ok)?.ref
                is SendResult.Transient -> null
            }
            if (ref != null) refs += ref
        }
        return refs
    }

    /**
     * Sends a photo with optional caption. Tries HTML parse mode first, falls back to plain text
     * (mirroring [sendToChannel]). Caption is truncated to [MAX_CAPTION_LEN] with an ellipsis if needed.
     * Returns the delivered message's [SentRef], or null if both attempts fail.
     */
    fun sendPhotoToChannel(channelId: String, photoUrl: String, caption: String? = null): SentRef? {
        // Truncate the Markdown caption first (so we never cut inside an <a> tag), then render HTML.
        val htmlCaption = caption?.let { TopicFormatter.toHtml(truncateForCaption(it)) }
        return when (val res = sendPhoto(channelId, photoUrl, htmlCaption, parseMode = "HTML")) {
            is SendResult.Ok -> res.ref
            // BUG-007: fall back to plain text only on a 400 markup error.
            is SendResult.BadRequest ->
                (sendPhoto(channelId, photoUrl, htmlCaption?.htmlToPlainText(), parseMode = null) as? SendResult.Ok)?.ref
            is SendResult.Transient -> null
        }
    }

    private fun sendPhoto(chatId: String, photoUrl: String, caption: String?, parseMode: String?): SendResult {
        val payload = json.encodeToString(SendPhotoRequest(chatId, photoUrl, caption, parseMode))
        return post("sendPhoto", payload, chatId)
    }

    private fun truncateForCaption(text: String): String =
        if (text.length <= MAX_CAPTION_LEN) text
        else text.substring(0, MAX_CAPTION_LEN - 1).trimEnd() + "…"

    private fun sendMessage(chatId: String, text: String, parseMode: String?, disablePreview: Boolean = false): SendResult {
        val payload = json.encodeToString(
            SendMessageRequest(chatId, text, parseMode, if (disablePreview) true else null)
        )
        return post("sendMessage", payload, chatId)
    }

    /**
     * POSTs [payload] to the given Bot API [endpoint] and classifies the outcome as a [SendResult].
     * The numeric chat id comes from the response `chat.id`; if absent it falls back to [chatId]
     * when that is already numeric (a `@username` target yields 0).
     *
     * BUG-007: on HTTP 429 the `parameters.retry_after` seconds are honored and the SAME request is
     * retried exactly once ([retryOn429] guards against a second retry). A 400 maps to [SendResult.BadRequest]
     * (caller may retry as plain text); everything else (429-after-retry, 5xx, network) maps to
     * [SendResult.Transient] (no plain-text fallback — the markup was valid).
     */
    private fun post(endpoint: String, payload: String, chatId: String, retryOn429: Boolean = true): SendResult {
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/$endpoint")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val parsed = json.decodeFromString(SendMessageResponse.serializer(), responseBody)
                    val result = parsed.result?.takeIf { parsed.ok }
                    return@use if (result != null) {
                        val resolvedChatId = result.chat?.id ?: chatId.toLongOrNull() ?: 0L
                        SendResult.Ok(SentRef(resolvedChatId, result.message_id))
                    } else {
                        SendResult.Transient
                    }
                }
                val err = runCatching { json.decodeFromString(SendMessageResponse.serializer(), responseBody) }.getOrNull()
                val code = err?.error_code ?: response.code
                when {
                    code == 429 && retryOn429 -> {
                        val retryAfter = (err?.parameters?.retry_after ?: DEFAULT_RETRY_AFTER_SEC)
                            .coerceIn(1, MAX_RETRY_AFTER_SEC)
                        logger.warn { "Telegram $endpoint 429 for $chatId — retrying once after ${retryAfter}s" }
                        Thread.sleep(retryAfter * 1000)
                        post(endpoint, payload, chatId, retryOn429 = false)
                    }
                    code == 400 -> {
                        logger.warn { "Telegram $endpoint HTTP 400 for $chatId — ${err?.description ?: "bad request"}" }
                        SendResult.BadRequest
                    }
                    else -> {
                        logger.warn { "Telegram $endpoint HTTP $code for $chatId" }
                        SendResult.Transient
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Telegram $endpoint failed for $chatId" }
            SendResult.Transient
        }
    }

    /**
     * Sends a single message and returns its `message_id` for later edits or deletion.
     * Markdown is attempted first; falls back to plain text. Returns null if both fail.
     * Used by the status-poster path; not for fan-out channel messages (which may chunk).
     *
     * Intentionally stays on legacy Markdown (its input is controlled status text, not LLM output),
     * unlike the digest channel path which renders HTML. See [sendToChannel].
     */
    fun sendMessageReturningId(chatId: String, text: String): Long? {
        val markdownPayload = json.encodeToString(
            SendMessageRequest(chatId, text, parse_mode = "Markdown", disable_web_page_preview = true)
        )
        return when (val res = post("sendMessage", markdownPayload, chatId)) {
            is SendResult.Ok -> res.ref.messageId
            // Same policy as the channel path: only retry as plain text on a 400 markup error.
            is SendResult.BadRequest -> {
                val plainPayload = json.encodeToString(
                    SendMessageRequest(chatId, text.stripMarkdown(), parse_mode = null, disable_web_page_preview = true)
                )
                (post("sendMessage", plainPayload, chatId) as? SendResult.Ok)?.ref?.messageId
            }
            is SendResult.Transient -> null
        }
    }

    /** Deletes a previously sent message. Returns true on success. */
    fun deleteMessage(chatId: String, messageId: Long): Boolean {
        val payload = json.encodeToString(DeleteMessageRequest(chatId, messageId))
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/deleteMessage")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            logger.warn(e) { "deleteMessage failed for $chatId/$messageId" }
            false
        }
    }

    fun chunkMessage(text: String, maxLen: Int = 4096): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val chunks = mutableListOf<String>()
        val lines = text.split("\n")
        val current = StringBuilder()
        for (line in lines) {
            val toAdd = if (current.isEmpty()) line else "\n$line"
            if (current.length + toAdd.length > maxLen) {
                if (current.isNotEmpty()) chunks.add(current.toString())
                current.clear()
                if (line.length > maxLen) {
                    // single line too long — hard split
                    var start = 0
                    while (start < line.length) {
                        chunks.add(line.substring(start, minOf(start + maxLen, line.length)))
                        start += maxLen
                    }
                } else {
                    current.append(line)
                }
            } else {
                current.append(toAdd)
            }
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    /** Plain-text fallback for the status path (legacy Markdown). */
    private fun String.stripMarkdown(): String =
        replace("*", "").replace("_", "").replace("`", "").replace("[", "").replace("]", "")

    /**
     * Plain-text fallback for the HTML digest path, used when Telegram rejects the HTML.
     * Unlike [stripMarkdown], it keeps the URL visible: `<a href="url">label</a>` -> `label: url`,
     * so a parse failure degrades to a readable link instead of a corrupted one.
     */
    private fun String.htmlToPlainText(): String =
        HTML_ANCHOR.replace(this) { "${it.groupValues[2]}: ${it.groupValues[1]}" }
            .replace("<b>", "").replace("</b>", "")
            .replace("<i>", "").replace("</i>", "")
            .replace("<code>", "").replace("</code>", "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
