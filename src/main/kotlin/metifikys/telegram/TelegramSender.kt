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
    private data class SendMessageResponse(val ok: Boolean = false, val result: SentMessage? = null)

    @Serializable
    private data class SentMessage(val message_id: Long, val chat: ChatRef? = null)

    @Serializable
    private data class ChatRef(val id: Long)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private companion object {
        /** Telegram's hard limit for sendPhoto caption length. */
        const val MAX_CAPTION_LEN = 1024

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
            val ref = sendMessage(channelId, html, parseMode = "HTML", disablePreview = disablePreview)
                ?: sendMessage(channelId, html.htmlToPlainText(), parseMode = null, disablePreview = disablePreview)
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
        return sendPhoto(channelId, photoUrl, htmlCaption, parseMode = "HTML")
            ?: sendPhoto(channelId, photoUrl, htmlCaption?.htmlToPlainText(), parseMode = null)
    }

    private fun sendPhoto(chatId: String, photoUrl: String, caption: String?, parseMode: String?): SentRef? {
        val payload = json.encodeToString(SendPhotoRequest(chatId, photoUrl, caption, parseMode))
        return postForRef("sendPhoto", payload, chatId)
    }

    private fun truncateForCaption(text: String): String =
        if (text.length <= MAX_CAPTION_LEN) text
        else text.substring(0, MAX_CAPTION_LEN - 1).trimEnd() + "…"

    private fun sendMessage(chatId: String, text: String, parseMode: String?, disablePreview: Boolean = false): SentRef? {
        val payload = json.encodeToString(
            SendMessageRequest(chatId, text, parseMode, if (disablePreview) true else null)
        )
        return postForRef("sendMessage", payload, chatId)
    }

    /**
     * POSTs [payload] to the given Bot API [endpoint] and parses the sent message's ids.
     * Returns null on any transport/parse failure or a non-`ok` response. The numeric chat id
     * comes from the response `chat.id`; if absent it falls back to [chatId] when that is already
     * numeric (a `@username` target cannot be resolved to a number here and yields 0).
     */
    private fun postForRef(endpoint: String, payload: String, chatId: String): SentRef? {
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/$endpoint")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn { "Telegram $endpoint HTTP ${response.code} for $chatId" }
                    return null
                }
                val responseBody = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(SendMessageResponse.serializer(), responseBody)
                val result = if (parsed.ok) parsed.result else null
                if (result == null) return null
                val resolvedChatId = result.chat?.id ?: chatId.toLongOrNull() ?: 0L
                SentRef(resolvedChatId, result.message_id)
            }
        } catch (e: Exception) {
            logger.error(e) { "Telegram $endpoint failed for $chatId" }
            null
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
        return sendMessageInternal(chatId, text, parseMode = "Markdown")
            ?: sendMessageInternal(chatId, text.stripMarkdown(), parseMode = null)
    }

    private fun sendMessageInternal(chatId: String, text: String, parseMode: String?): Long? {
        val payload = json.encodeToString(SendMessageRequest(chatId, text, parseMode, disable_web_page_preview = true))
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/sendMessage")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn { "sendMessageReturningId HTTP ${response.code} for $chatId" }
                    return null
                }
                val responseBody = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(SendMessageResponse.serializer(), responseBody)
                if (parsed.ok) parsed.result?.message_id else null
            }
        } catch (e: Exception) {
            logger.error(e) { "sendMessageReturningId failed for $chatId" }
            null
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
