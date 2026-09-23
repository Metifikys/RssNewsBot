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

class TelegramSender(
    private val botToken: String,
    /** Bot API origin; overridable so tests can point the sender at a local HTTP server. */
    private val apiBaseUrl: String = "https://api.telegram.org"
) {

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
        val disable_web_page_preview: Boolean? = null,
        val reply_parameters: ReplyParameters? = null,
        val link_preview_options: LinkPreviewOptions? = null
    )

    /**
     * Bot API 7.0 `LinkPreviewOptions`: preview [url] (not the text's first link), large and above
     * the text — how a long-form post carries its image without the 1024-char caption cap. Both
     * flags have no default on purpose: the JSON encoder skips defaults and they must be sent.
     */
    @Serializable
    private data class LinkPreviewOptions(
        val url: String,
        val prefer_large_media: Boolean,
        val show_above_text: Boolean
    )

    @Serializable
    private data class SendPhotoRequest(
        val chat_id: String,
        val photo: String,
        val caption: String? = null,
        val parse_mode: String? = null,
        val reply_parameters: ReplyParameters? = null
    )

    /**
     * Bot API 7.0 `ReplyParameters`. `allow_sending_without_reply` has no default on purpose:
     * the JSON encoder skips defaults, and the flag MUST reach Telegram — with it, a deleted
     * target just drops the quote header instead of failing the whole send with a 400.
     */
    @Serializable
    private data class ReplyParameters(
        val message_id: Long,
        val allow_sending_without_reply: Boolean
    )

    @Serializable
    private data class DeleteMessageRequest(
        val chat_id: String,
        val message_id: Long
    )

    @Serializable
    private data class EditMessageTextRequest(
        val chat_id: String,
        val message_id: Long,
        val text: String,
        val parse_mode: String? = null,
        val disable_web_page_preview: Boolean? = null
    )

    @Serializable
    private data class PinMessageRequest(
        val chat_id: String,
        val message_id: Long,
        val disable_notification: Boolean? = null
    )

    /** Outcome of [editMessageText]. */
    enum class EditResult {
        /** Edited (or Telegram reported the text unchanged). */
        OK,
        /** The message is gone or cannot be edited — the caller should post a fresh one. */
        NOT_FOUND,
        /** Transient failure (rate limit, network); worth retrying later. */
        FAILED
    }

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
        data class BadRequest(val description: String? = null) : SendResult
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
     *
     * [replyToMessageId] makes the (first chunk of the) message a reply to that message in the
     * same chat — Telegram shows a quote header that jumps to it. Sent with
     * `allow_sending_without_reply`, so a deleted target never fails the send.
     */
    fun sendToChannel(
        channelId: String,
        text: String,
        disablePreview: Boolean = false,
        replyToMessageId: Long? = null,
        previewImageUrl: String? = null
    ): List<SentRef> {
        val chunks = chunkMessage(text)
        val refs = ArrayList<SentRef>(chunks.size)
        for ((index, chunk) in chunks.withIndex()) {
            val replyTo = if (index == 0) replyToMessageId else null
            // The image preview belongs to the first chunk; later chunks keep [disablePreview].
            val preview = if (index == 0) previewImageUrl else null
            val html = TopicFormatter.toHtml(chunk)
            val ref = when (val res = sendMessage(channelId, html, parseMode = "HTML", disablePreview = disablePreview, replyTo = replyTo, previewImageUrl = preview)) {
                is SendResult.Ok -> res.ref
                // BUG-007: fall back to plain text only on a 400 markup error.
                is SendResult.BadRequest ->
                    (sendMessage(channelId, html.htmlToPlainText(), parseMode = null, disablePreview = disablePreview, replyTo = replyTo, previewImageUrl = preview) as? SendResult.Ok)?.ref
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
     * [replyToMessageId] as in [sendToChannel].
     */
    fun sendPhotoToChannel(
        channelId: String,
        photoUrl: String,
        caption: String? = null,
        replyToMessageId: Long? = null
    ): SentRef? {
        // Truncate the Markdown caption first (so we never cut inside an <a> tag), then render HTML.
        val htmlCaption = caption?.let { TopicFormatter.toHtml(truncateForCaption(it)) }
        return when (val res = sendPhoto(channelId, photoUrl, htmlCaption, parseMode = "HTML", replyTo = replyToMessageId)) {
            is SendResult.Ok -> res.ref
            // BUG-007: fall back to plain text only on a 400 markup error.
            is SendResult.BadRequest ->
                (sendPhoto(channelId, photoUrl, htmlCaption?.htmlToPlainText(), parseMode = null, replyTo = replyToMessageId) as? SendResult.Ok)?.ref
            is SendResult.Transient -> null
        }
    }

    private fun replyParams(replyTo: Long?): ReplyParameters? =
        replyTo?.let { ReplyParameters(message_id = it, allow_sending_without_reply = true) }

    private fun sendPhoto(chatId: String, photoUrl: String, caption: String?, parseMode: String?, replyTo: Long? = null): SendResult {
        val payload = json.encodeToString(SendPhotoRequest(chatId, photoUrl, caption, parseMode, replyParams(replyTo)))
        return post("sendPhoto", payload, chatId)
    }

    internal fun truncateForCaption(text: String): String {
        if (text.length <= MAX_CAPTION_LEN) return text
        var end = MAX_CAPTION_LEN - 1  // leave room for the ellipsis
        // BUG-010: if the cut lands inside a [label](url) link, back up to the link's start so we
        // never emit half a link (toHtml can't render it and it reads as gibberish in plain text).
        // Uses toHtml's own matcher, so a `[P]` label or a `Foo_(bar)` URL counts as a link too.
        for (m in TopicFormatter.MARKDOWN_LINK.findAll(text)) {
            if (m.range.first < end && end <= m.range.last) { end = m.range.first; break }
        }
        // BUG-009: never split an astral-plane emoji's UTF-16 surrogate pair.
        if (end > 0 && text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    private fun sendMessage(
        chatId: String,
        text: String,
        parseMode: String?,
        disablePreview: Boolean = false,
        replyTo: Long? = null,
        previewImageUrl: String? = null
    ): SendResult {
        // An explicit image preview replaces the legacy on/off flag (the two must not conflict).
        val request = if (previewImageUrl != null) {
            SendMessageRequest(
                chatId, text, parseMode, reply_parameters = replyParams(replyTo),
                link_preview_options = LinkPreviewOptions(previewImageUrl, prefer_large_media = true, show_above_text = true)
            )
        } else {
            SendMessageRequest(chatId, text, parseMode, if (disablePreview) true else null, replyParams(replyTo))
        }
        val payload = json.encodeToString(request)
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
            .url("$apiBaseUrl/bot$botToken/$endpoint")
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
                        SendResult.BadRequest(err?.description)
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

    /**
     * Edits the text of a message the bot sent (channel posts included; there is no time limit
     * on a bot's own messages). Same Markdown → HTML rendering and plain-text fallback as
     * [sendToChannel]. "Message is not modified" counts as [EditResult.OK]; any other 400 after the
     * plain-text retry means the message is gone → [EditResult.NOT_FOUND].
     */
    fun editMessageText(chatId: String, messageId: Long, text: String, disablePreview: Boolean = true): EditResult {
        val html = TopicFormatter.toHtml(text)
        val preview = if (disablePreview) true else null
        fun attempt(body: String, parseMode: String?): SendResult =
            post("editMessageText", json.encodeToString(EditMessageTextRequest(chatId, messageId, body, parseMode, preview)), chatId)
        return when (val first = attempt(html, "HTML")) {
            is SendResult.Ok -> EditResult.OK
            is SendResult.Transient -> EditResult.FAILED
            is SendResult.BadRequest -> {
                if (first.description?.contains("not modified", ignoreCase = true) == true) return EditResult.OK
                when (val second = attempt(html.htmlToPlainText(), null)) {
                    is SendResult.Ok -> EditResult.OK
                    is SendResult.Transient -> EditResult.FAILED
                    is SendResult.BadRequest ->
                        if (second.description?.contains("not modified", ignoreCase = true) == true) EditResult.OK else EditResult.NOT_FOUND
                }
            }
        }
    }

    /** Pins a message in the chat (silently by default). The bot needs the channel's edit-messages right. */
    fun pinChatMessage(chatId: String, messageId: Long, disableNotification: Boolean = true): Boolean =
        postBoolean("pinChatMessage", json.encodeToString(PinMessageRequest(chatId, messageId, if (disableNotification) true else null)), chatId)

    fun unpinChatMessage(chatId: String, messageId: Long): Boolean =
        postBoolean("unpinChatMessage", json.encodeToString(PinMessageRequest(chatId, messageId)), chatId)

    /** For Bot API methods whose `result` is a bare `true`. */
    private fun postBoolean(endpoint: String, payload: String, chatId: String): Boolean {
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$apiBaseUrl/bot$botToken/$endpoint")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val ok = response.isSuccessful && response.body?.string().orEmpty().contains("\"ok\":true")
                if (!ok) logger.warn { "Telegram $endpoint failed for $chatId (HTTP ${response.code})" }
                ok
            }
        } catch (e: Exception) {
            logger.warn(e) { "Telegram $endpoint failed for $chatId" }
            false
        }
    }

    /** Deletes a previously sent message. Returns true on success. */
    fun deleteMessage(chatId: String, messageId: Long): Boolean {
        val payload = json.encodeToString(DeleteMessageRequest(chatId, messageId))
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$apiBaseUrl/bot$botToken/deleteMessage")
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
                    // single line too long — hard split, never between the halves of a
                    // surrogate pair (same invalid-UTF-16 → 400 failure as BUG-009's captions)
                    var start = 0
                    while (start < line.length) {
                        var end = minOf(start + maxLen, line.length)
                        if (end < line.length && end - start > 1 && line[end - 1].isHighSurrogate()) end--
                        chunks.add(line.substring(start, end))
                        start = end
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
