package metifikys.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.format.TopicFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Chat + message id of a message the bot actually delivered. Captured from the send response
 * so digest topics can later be joined back to the reactions Telegram reports per message
 * (see [metifikys.telegram.TelegramUpdatesPoller]). [chatId] is the numeric id from the
 * response (channels report reactions under the `-100…` id, not the `@username` form).
 */
data class SentRef(val chatId: Long, val messageId: Long)

/**
 * One inline-keyboard button of an admin-chat message. Pressing it delivers a `callback_query`
 * carrying [callbackData] to the updates poller; Telegram caps that at 64 bytes.
 */
data class InlineButton(val text: String, val callbackData: String) {
    init {
        require(callbackData.toByteArray(Charsets.UTF_8).size <= 64) { "callback_data over 64 bytes: $callbackData" }
    }
}

/** One photo of a [TelegramSender.sendMediaGroup] album: a URL Telegram fetches, and an optional Markdown caption. */
data class AlbumPhoto(val url: String, val caption: String? = null)

class TelegramSender(
    private val botToken: String,
    /** Bot API origin; overridable so tests can point the sender at a local HTTP server. */
    private val apiBaseUrl: String = "https://api.telegram.org",
    /** Per-chat spacing + shared 429 cooldown for every call this sender makes (see [ChatPacer]). */
    private val pacer: ChatPacer = ChatPacer()
) {

    /** Epoch millis of every 429 Telegram returned, for the `/status` 24-hour counter. */
    private val rateLimitHits = ConcurrentLinkedDeque<Long>()

    /** How many 429s Telegram returned in the last 24 hours (in-process; resets on restart). */
    fun rateLimitsLast24h(): Int {
        val cutoff = System.currentTimeMillis() - 24 * 3_600_000L
        while (rateLimitHits.peekFirst()?.let { it < cutoff } == true) rateLimitHits.pollFirst()
        return rateLimitHits.size
    }

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
        val link_preview_options: LinkPreviewOptions? = null,
        /** Forum topic / private-chat thread (Bot API 9.3); null = the chat's main thread. */
        val message_thread_id: Long? = null,
        val disable_notification: Boolean? = null
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

    /** Bot API `InputMediaPhoto`. [type] has no default on purpose: the encoder skips defaults. */
    @Serializable
    private data class InputMediaPhotoDto(
        val type: String,
        val media: String,
        val caption: String? = null,
        val parse_mode: String? = null
    )

    @Serializable
    private data class SendMediaGroupRequest(val chat_id: String, val media: List<InputMediaPhotoDto>)

    @Serializable
    private data class SendMediaGroupResponse(val ok: Boolean = false, val result: List<SentMessage>? = null)

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

    @Serializable
    private data class InlineKeyboardButtonDto(val text: String, val callback_data: String)

    @Serializable
    private data class InlineKeyboardMarkup(val inline_keyboard: List<List<InlineKeyboardButtonDto>>)

    /** `sendMessage` for the admin chat: optional inline keyboard, optionally silent. */
    @Serializable
    private data class KeyboardMessageRequest(
        val chat_id: String,
        val text: String,
        val parse_mode: String? = null,
        val disable_web_page_preview: Boolean? = null,
        val disable_notification: Boolean? = null,
        val reply_markup: InlineKeyboardMarkup? = null,
        val message_thread_id: Long? = null
    )

    /** `editMessageText` for the admin chat; a null [reply_markup] removes the keyboard. */
    @Serializable
    private data class EditKeyboardMessageRequest(
        val chat_id: String,
        val message_id: Long,
        val text: String,
        val parse_mode: String? = null,
        val disable_web_page_preview: Boolean? = null,
        val reply_markup: InlineKeyboardMarkup? = null
    )

    @Serializable
    private data class EditReplyMarkupRequest(
        val chat_id: String,
        val message_id: Long,
        val reply_markup: InlineKeyboardMarkup? = null
    )

    @Serializable
    private data class AnswerCallbackRequest(val callback_query_id: String, val text: String? = null)

    @Serializable
    private data class BotCommandDto(val command: String, val description: String)

    @Serializable
    private data class BotCommandScopeDto(val type: String, val chat_id: String)

    @Serializable
    private data class SetMyCommandsRequest(val commands: List<BotCommandDto>, val scope: BotCommandScopeDto)

    private fun List<List<InlineButton>>.toMarkup(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(map { row -> row.map { InlineKeyboardButtonDto(it.text, it.callbackData) } })

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

        /** Admin-chat messages are cut to this many chars (Telegram's limit is 4096). */
        const val ADMIN_TEXT_MAX = 4000

        /** Fallback wait when Telegram returns 429 without a `parameters.retry_after`. */
        const val DEFAULT_RETRY_AFTER_SEC = 3L

        /** Upper bound on the honored `retry_after`, so an extreme value can't stall the caller. */
        const val MAX_RETRY_AFTER_SEC = 60L

        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        val PNG_TYPE = "image/png".toMediaType()

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
        previewImageUrl: String? = null,
        /** Where the [previewImageUrl] card sits: above the text (a picture) or below it (a telegra.ph page). */
        previewAboveText: Boolean = true,
        /** `disable_notification`: the message arrives without a sound (a reply under a post). */
        silent: Boolean = false
    ): List<SentRef> {
        val chunks = chunkMessage(text)
        val refs = ArrayList<SentRef>(chunks.size)
        for ((index, chunk) in chunks.withIndex()) {
            val replyTo = if (index == 0) replyToMessageId else null
            // The image preview belongs to the first chunk; later chunks keep [disablePreview].
            val preview = if (index == 0) previewImageUrl else null
            val html = TopicFormatter.toHtml(chunk)
            val ref = when (val res = sendMessage(channelId, html, parseMode = "HTML", disablePreview = disablePreview, replyTo = replyTo, previewImageUrl = preview, previewAboveText = previewAboveText, silent = silent)) {
                is SendResult.Ok -> res.ref
                // BUG-007: fall back to plain text only on a 400 markup error.
                is SendResult.BadRequest ->
                    (sendMessage(channelId, html.htmlToPlainText(), parseMode = null, disablePreview = disablePreview, replyTo = replyTo, previewImageUrl = preview, previewAboveText = previewAboveText, silent = silent) as? SendResult.Ok)?.ref
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

    /**
     * Uploads [png] as a photo post (multipart `sendPhoto`) — for images the bot draws itself, such
     * as the weekly barometer chart. Caption handling as in [sendPhotoToChannel] (truncated, HTML,
     * plain-text retry on a 400). Same pacer and 429 handling as every other call; the body is
     * rebuilt from the bytes for the 429 retry. Returns the delivered message's [SentRef], or null.
     */
    fun sendPhotoBytes(channelId: String, png: ByteArray, caption: String? = null, fileName: String = "chart.png"): SentRef? {
        val htmlCaption = caption?.let { TopicFormatter.toHtml(truncateForCaption(it)) }
        fun body(text: String?, parseMode: String?): RequestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", channelId)
            .apply {
                if (text != null) addFormDataPart("caption", text)
                if (parseMode != null) addFormDataPart("parse_mode", parseMode)
            }
            .addFormDataPart("photo", fileName, png.toRequestBody(PNG_TYPE))
            .build()
        return when (val res = post("sendPhoto", body(htmlCaption, "HTML"), channelId)) {
            is SendResult.Ok -> res.ref
            is SendResult.BadRequest ->
                (post("sendPhoto", body(htmlCaption?.htmlToPlainText(), null), channelId) as? SendResult.Ok)?.ref
            is SendResult.Transient -> null
        }
    }

    /**
     * Sends [photos] (2–10) as one album (`sendMediaGroup`) — Telegram shows them as a grid and
     * the first photo's caption under it. Captions are truncated and rendered as in
     * [sendPhotoToChannel]. Same pacer and 429 handling as every other call. Returns one
     * [SentRef] per photo message, or an empty list on any failure — a 400 (Telegram could not
     * fetch one of the photos, a caption it cannot parse) fails the whole album; the caller
     * falls back to text.
     */
    fun sendMediaGroup(channelId: String, photos: List<AlbumPhoto>): List<SentRef> {
        require(photos.size in 2..10) { "An album takes 2..10 photos (got ${photos.size})" }
        val media = photos.map { p ->
            val caption = p.caption?.takeIf { it.isNotBlank() }?.let { TopicFormatter.toHtml(truncateForCaption(it)) }
            InputMediaPhotoDto("photo", p.url, caption, if (caption != null) "HTML" else null)
        }
        return try {
            val response = execute("sendMediaGroup", json.encodeToString(SendMediaGroupRequest(channelId, media)).toJsonBody(), channelId)
            if (!response.isSuccessful) {
                val err = response.error()
                logger.warn { "Telegram sendMediaGroup HTTP ${err?.error_code ?: response.httpCode} for $channelId — ${err?.description ?: "failed"}" }
                return emptyList()
            }
            val parsed = json.decodeFromString(SendMediaGroupResponse.serializer(), response.body)
            if (!parsed.ok) return emptyList()
            parsed.result.orEmpty().map { SentRef(it.chat?.id ?: channelId.toLongOrNull() ?: 0L, it.message_id) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn { "Telegram sendMediaGroup for $channelId interrupted during a rate-limit wait — not sent" }
            emptyList()
        } catch (e: Exception) {
            logger.error(e) { "Telegram sendMediaGroup failed for $channelId" }
            emptyList()
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
        previewImageUrl: String? = null,
        previewAboveText: Boolean = true,
        silent: Boolean = false
    ): SendResult {
        val quiet = if (silent) true else null
        // An explicit image preview replaces the legacy on/off flag (the two must not conflict).
        val request = if (previewImageUrl != null) {
            SendMessageRequest(
                chatId, text, parseMode, reply_parameters = replyParams(replyTo),
                link_preview_options = LinkPreviewOptions(previewImageUrl, prefer_large_media = true, show_above_text = previewAboveText),
                disable_notification = quiet
            )
        } else {
            SendMessageRequest(chatId, text, parseMode, if (disablePreview) true else null, replyParams(replyTo), disable_notification = quiet)
        }
        val payload = json.encodeToString(request)
        return post("sendMessage", payload, chatId)
    }

    /** HTTP status and body of one Bot API call. */
    private class RawResponse(val httpCode: Int, val body: String) {
        val isSuccessful: Boolean get() = httpCode in 200..299
    }

    /** The error envelope of a non-2xx response, or null when the body is not one. */
    private fun RawResponse.error(): SendMessageResponse? =
        runCatching { json.decodeFromString(SendMessageResponse.serializer(), body) }.getOrNull()

    /**
     * POSTs [payload] to the given Bot API [endpoint] — the transport every Bot API method goes
     * through. Every call first waits for its chat's [ChatPacer] slot (≥ 1 s between calls to one
     * chat). BUG-007: on 429 the chat goes into a shared cooldown of `parameters.retry_after`
     * seconds and the SAME request is retried exactly once — the retry's pacer wait is that
     * cooldown; a second 429 is returned to the caller. Every 429 is logged as `[TG429]` and
     * counted for `/status`. Network errors, and an interrupt during a wait, propagate.
     */
    private fun execute(endpoint: String, payload: RequestBody, chatId: String, retryOn429: Boolean = true, paced: Boolean = true): RawResponse {
        if (paced) pacer.acquire(chatId)
        val request = Request.Builder()
            .url("$apiBaseUrl/bot$botToken/$endpoint")
            .post(payload)
            .build()
        val response = client.newCall(request).execute().use { RawResponse(it.code, it.body?.string().orEmpty()) }
        if (response.isSuccessful) return response
        val err = response.error()
        if ((err?.error_code ?: response.httpCode) != 429) return response
        val retryAfter = (err?.parameters?.retry_after ?: DEFAULT_RETRY_AFTER_SEC).coerceIn(1, MAX_RETRY_AFTER_SEC)
        rateLimitHits.addLast(System.currentTimeMillis())
        pacer.cooldown(chatId, retryAfter * 1000)
        // Greppable marker: the criterion for a full token bucket is any of these in prod logs.
        logger.warn { "[TG429] $endpoint $chatId retry_after=$retryAfter" + if (retryOn429) " — retrying once" else " — giving up" }
        if (!retryOn429) return response
        return execute(endpoint, payload, chatId, retryOn429 = false, paced = paced)
    }

    /**
     * POSTs [payload] to the given Bot API [endpoint] (via [execute], so 429 is honored) and
     * classifies the outcome as a [SendResult]. The numeric chat id comes from the response
     * `chat.id`; if absent it falls back to [chatId] when that is already numeric (a `@username`
     * target yields 0).
     *
     * A 400 maps to [SendResult.BadRequest] (caller may retry as plain text); everything else
     * (429-after-retry, 5xx, network) maps to [SendResult.Transient] (no plain-text fallback — the
     * markup was valid).
     */
    private fun post(endpoint: String, payload: String, chatId: String): SendResult = post(endpoint, payload.toJsonBody(), chatId)

    private fun post(endpoint: String, payload: RequestBody, chatId: String): SendResult {
        return try {
            val response = execute(endpoint, payload, chatId)
            if (response.isSuccessful) {
                val parsed = json.decodeFromString(SendMessageResponse.serializer(), response.body)
                val result = parsed.result?.takeIf { parsed.ok }
                return if (result != null) {
                    val resolvedChatId = result.chat?.id ?: chatId.toLongOrNull() ?: 0L
                    SendResult.Ok(SentRef(resolvedChatId, result.message_id))
                } else {
                    SendResult.Transient
                }
            }
            val err = response.error()
            when (val code = err?.error_code ?: response.httpCode) {
                400 -> {
                    logger.warn { "Telegram $endpoint HTTP 400 for $chatId — ${err?.description ?: "bad request"}" }
                    SendResult.BadRequest(err?.description)
                }
                else -> {
                    logger.warn { "Telegram $endpoint HTTP $code for $chatId" }
                    SendResult.Transient
                }
            }
        } catch (e: InterruptedException) {
            // Interrupted while waiting for the chat's slot or a 429 cooldown: keep the flag for the
            // caller's cancellation handling and stop — no (further) attempt is sent.
            Thread.currentThread().interrupt()
            logger.warn { "Telegram $endpoint for $chatId interrupted during a rate-limit wait — not sent" }
            SendResult.Transient
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
     * [threadId] targets a thread of the chat (`admin.threads.status`); null = its main thread.
     */
    fun sendMessageReturningId(chatId: String, text: String, threadId: Long? = null): Long? {
        val markdownPayload = json.encodeToString(
            SendMessageRequest(chatId, text, parse_mode = "Markdown", disable_web_page_preview = true, message_thread_id = threadId)
        )
        return when (val res = post("sendMessage", markdownPayload, chatId)) {
            is SendResult.Ok -> res.ref.messageId
            // Same policy as the channel path: only retry as plain text on a 400 markup error.
            is SendResult.BadRequest -> {
                val plainPayload = json.encodeToString(
                    SendMessageRequest(chatId, text.stripMarkdown(), parse_mode = null, disable_web_page_preview = true, message_thread_id = threadId)
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

    /** For Bot API methods whose `result` is a bare `true`. Goes through [execute], so 429 is honored. */
    private fun postBoolean(endpoint: String, payload: String, chatId: String, paced: Boolean = true): Boolean {
        return try {
            val response = execute(endpoint, payload.toJsonBody(), chatId, paced = paced)
            val ok = response.isSuccessful && response.body.contains("\"ok\":true")
            if (!ok) logger.warn { "Telegram $endpoint failed for $chatId (HTTP ${response.httpCode})" }
            ok
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn { "Telegram $endpoint for $chatId interrupted during a rate-limit wait — not sent" }
            false
        } catch (e: Exception) {
            logger.warn(e) { "Telegram $endpoint failed for $chatId" }
            false
        }
    }

    /** Deletes a previously sent message. Returns true on success. */
    fun deleteMessage(chatId: String, messageId: Long): Boolean =
        postBoolean("deleteMessage", json.encodeToString(DeleteMessageRequest(chatId, messageId)), chatId)

    // ── Admin chat (commands, suggestions, moderation) ───────────────────────

    /**
     * Sends one admin-chat message, optionally with an inline [keyboard] and [silent]
     * (`disable_notification`). The text is plain unless [html] (then a 400 markup error is
     * retried once as plain text, as on the channel path). Over-long text is cut to one message.
     * Returns the message id, or null when nothing was sent. Goes through the per-chat pacer
     * like every other call, so a burst of admin messages spaces itself out in the admin chat
     * without touching the channels' slots. [threadId] targets a thread of the chat (a private
     * chat's topic, `admin.threads`); null = its main thread.
     */
    fun sendMessageWithKeyboard(
        chatId: String,
        text: String,
        keyboard: List<List<InlineButton>>? = null,
        silent: Boolean = false,
        html: Boolean = false,
        threadId: Long? = null
    ): Long? {
        val body = cutForAdmin(text, html)
        fun attempt(t: String, parseMode: String?): SendResult = post(
            "sendMessage",
            json.encodeToString(
                KeyboardMessageRequest(
                    chatId, t, parseMode, disable_web_page_preview = true,
                    disable_notification = if (silent) true else null,
                    reply_markup = keyboard?.toMarkup(),
                    message_thread_id = threadId
                )
            ),
            chatId
        )
        return when (val res = attempt(body, if (html) "HTML" else null)) {
            is SendResult.Ok -> res.ref.messageId
            is SendResult.BadRequest -> if (html) (attempt(cutForAdmin(body.htmlToPlainText()), null) as? SendResult.Ok)?.ref?.messageId else null
            is SendResult.Transient -> null
        }
    }

    /**
     * Replaces an admin-chat message's text and keyboard in one call (a null [keyboard] removes
     * it). "Message is not modified" counts as [EditResult.OK].
     */
    fun editMessageWithKeyboard(
        chatId: String,
        messageId: Long,
        text: String,
        keyboard: List<List<InlineButton>>? = null,
        html: Boolean = false
    ): EditResult {
        val body = cutForAdmin(text, html)
        fun attempt(t: String, parseMode: String?): SendResult = post(
            "editMessageText",
            json.encodeToString(EditKeyboardMessageRequest(chatId, messageId, t, parseMode, true, keyboard?.toMarkup())),
            chatId
        )
        fun classify(res: SendResult): EditResult? = when (res) {
            is SendResult.Ok -> EditResult.OK
            is SendResult.Transient -> EditResult.FAILED
            is SendResult.BadRequest -> if (res.description?.contains("not modified", ignoreCase = true) == true) EditResult.OK else null
        }
        classify(attempt(body, if (html) "HTML" else null))?.let { return it }
        if (!html) return EditResult.NOT_FOUND
        return classify(attempt(cutForAdmin(body.htmlToPlainText()), null)) ?: EditResult.NOT_FOUND
    }

    /** Swaps (or with a null [keyboard] removes) the inline keyboard of a message, text untouched. */
    fun editMessageReplyMarkup(chatId: String, messageId: Long, keyboard: List<List<InlineButton>>?): Boolean =
        postBoolean(
            "editMessageReplyMarkup",
            json.encodeToString(EditReplyMarkupRequest(chatId, messageId, keyboard?.toMarkup())),
            chatId
        )

    /**
     * Stops the client's spinner on a pressed button, optionally with a short toast. Not paced:
     * it belongs to no chat, and Telegram expects it within seconds of the press.
     */
    fun answerCallbackQuery(callbackQueryId: String, text: String? = null): Boolean =
        postBoolean(
            "answerCallbackQuery",
            json.encodeToString(AnswerCallbackRequest(callbackQueryId, text)),
            "callback",
            paced = false
        )

    /**
     * Sets the "/" command menu for ONE chat (`BotCommandScopeChat`) — the admin chat or an
     * admin's private chat — so no other chat sees the bot's commands. [commands] are
     * `command` (without the slash) → description pairs.
     */
    fun setMyCommands(commands: List<Pair<String, String>>, chatId: String): Boolean =
        postBoolean(
            "setMyCommands",
            json.encodeToString(
                SetMyCommandsRequest(commands.map { BotCommandDto(it.first, it.second) }, BotCommandScopeDto("chat", chatId))
            ),
            chatId
        )

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
                    // surrogate pair (same invalid-UTF-16 → 400 failure as BUG-009's captions),
                    // and never inside a [label](url) link that fits in one message: the cut backs
                    // up to the link's start, as truncateForCaption does (BUG-010).
                    val links = TopicFormatter.MARKDOWN_LINK.findAll(line).map { it.range }.toList()
                    var start = 0
                    while (start < line.length) {
                        var end = minOf(start + maxLen, line.length)
                        links.firstOrNull { start < it.first && it.first < end && end <= it.last }?.let { end = it.first }
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

    /**
     * One admin message's worth of [text], never cutting an astral character's surrogate pair.
     * [html] is measured as Telegram counts it (tags and `href`s are free), so a card that fits
     * on screen is never cut mid-tag.
     */
    private fun cutForAdmin(text: String, html: Boolean = false): String {
        if (text.length <= ADMIN_TEXT_MAX) return text
        if (html && TopicFormatter.htmlVisibleLength(text) <= ADMIN_TEXT_MAX) return text
        var end = ADMIN_TEXT_MAX - 1
        if (text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end) + "…"
    }

    private fun String.toJsonBody(): RequestBody = toRequestBody(JSON_TYPE)

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
            .replace("<blockquote expandable>", "").replace("<blockquote>", "").replace("</blockquote>", "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}
