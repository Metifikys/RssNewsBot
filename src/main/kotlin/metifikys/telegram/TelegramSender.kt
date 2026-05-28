package metifikys.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

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
    private data class SentMessage(val message_id: Long)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private companion object {
        /** Telegram's hard limit for sendPhoto caption length. */
        const val MAX_CAPTION_LEN = 1024
    }

    fun sendToChannel(channelId: String, text: String, disablePreview: Boolean = false): Boolean {
        val chunks = chunkMessage(text)
        var allSuccess = true
        for (chunk in chunks) {
            val success = sendMessage(channelId, chunk, parseMode = "Markdown", disablePreview = disablePreview)
                || sendMessage(channelId, chunk.stripMarkdown(), parseMode = null, disablePreview = disablePreview)
            if (!success) allSuccess = false
        }
        return allSuccess
    }

    /**
     * Sends a photo with optional caption. Tries Markdown parse mode first, falls back to plain text
     * (mirroring [sendToChannel]). Caption is truncated to [MAX_CAPTION_LEN] with an ellipsis if needed.
     * Returns false if both attempts fail.
     */
    fun sendPhotoToChannel(channelId: String, photoUrl: String, caption: String? = null): Boolean {
        val safeCaption = caption?.let { truncateForCaption(it) }
        return sendPhoto(channelId, photoUrl, safeCaption, parseMode = "Markdown")
            || sendPhoto(channelId, photoUrl, safeCaption?.stripMarkdown(), parseMode = null)
    }

    private fun sendPhoto(chatId: String, photoUrl: String, caption: String?, parseMode: String?): Boolean {
        val payload = json.encodeToString(SendPhotoRequest(chatId, photoUrl, caption, parseMode))
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/sendPhoto")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            logger.error(e) { "Telegram sendPhoto failed for $chatId" }
            false
        }
    }

    private fun truncateForCaption(text: String): String =
        if (text.length <= MAX_CAPTION_LEN) text
        else text.substring(0, MAX_CAPTION_LEN - 1).trimEnd() + "…"

    private fun sendMessage(chatId: String, text: String, parseMode: String?, disablePreview: Boolean = false): Boolean {
        val payload = json.encodeToString(
            SendMessageRequest(chatId, text, parseMode, if (disablePreview) true else null)
        )
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/sendMessage")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            logger.error(e) { "Telegram send failed for $chatId" }
            false
        }
    }

    /**
     * Sends a single message and returns its `message_id` for later edits or deletion.
     * Markdown is attempted first; falls back to plain text. Returns null if both fail.
     * Used by the status-poster path; not for fan-out channel messages (which may chunk).
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

    private fun String.stripMarkdown(): String =
        replace("*", "").replace("_", "").replace("`", "").replace("[", "").replace("]", "")
}
