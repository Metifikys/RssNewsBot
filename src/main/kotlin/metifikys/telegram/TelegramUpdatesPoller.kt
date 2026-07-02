package metifikys.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import metifikys.db.NewsDatabase
import metifikys.db.ReactionCount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Long-polls Telegram `getUpdates` on its own daemon thread and persists channel-post reaction
 * counts. We only subscribe to `message_reaction_count` — the anonymous, aggregated reaction
 * update Telegram emits for channels (per-user `message_reaction` exists only in groups). Each
 * update carries a message's complete reaction set, so it maps to a replace-all write via
 * [NewsDatabase.replaceReactionCounts].
 *
 * The `getUpdates` offset is persisted in `bot_state` after every non-empty batch, so a restart
 * resumes without reprocessing or dropping updates. Telegram allows only ONE `getUpdates`
 * consumer per bot token; a second one gets HTTP 409 (logged loudly) — never run two pollers on
 * the same token (e.g. a dev box and the server). Gated behind `telegram.updatesPolling`, off by
 * default, precisely so a shared-token dev instance stays silent.
 */
class TelegramUpdatesPoller(
    private val botToken: String,
    private val db: NewsDatabase
) {
    private companion object {
        const val OFFSET_KEY = "updates_offset"
        const val LONG_POLL_SECONDS = 50
        val ALLOWED_UPDATES = listOf("message_reaction_count")
        const val MAX_BACKOFF_MS = 60_000L
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Read timeout must exceed the long-poll timeout so a quiet poll isn't killed as a timeout.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout((LONG_POLL_SECONDS + 20).toLong(), TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Volatile
    private var running = false
    private var offset: Long = 0
    private var thread: Thread? = null

    fun start() {
        if (running) return
        offset = db.getState(OFFSET_KEY)?.toLongOrNull() ?: 0L
        running = true
        thread = Thread({ runLoop() }, "telegram-updates-poll").apply {
            isDaemon = true
            start()
        }
        logger.info { "[Updates] Reaction poller started (offset=$offset, allowed=$ALLOWED_UPDATES)." }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun runLoop() {
        var backoffMs = 1_000L
        while (running) {
            try {
                val updates = fetchUpdates(offset)
                backoffMs = 1_000L
                for (u in updates) {
                    handleUpdate(u)
                    offset = u.updateId + 1
                }
                if (updates.isNotEmpty()) db.setState(OFFSET_KEY, offset.toString())
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running) break
                logger.warn(e) { "[Updates] poll error; retrying in ${backoffMs}ms." }
                try {
                    Thread.sleep(backoffMs)
                } catch (ie: InterruptedException) {
                    break
                }
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
        logger.info { "[Updates] Reaction poller stopped." }
    }

    private fun handleUpdate(u: UpdateDto) {
        val mrc = u.messageReactionCount ?: return
        val counts = mrc.reactions.mapNotNull { r ->
            val key = emojiKey(r.type) ?: return@mapNotNull null
            ReactionCount(key, r.totalCount)
        }
        db.replaceReactionCounts(mrc.chat.id, mrc.messageId, counts)
        logger.debug { "[Updates] reactions for ${mrc.chat.id}/${mrc.messageId}: $counts" }
    }

    /** Resolves a Telegram reaction type to a storage key, or null for shapes we ignore. */
    private fun emojiKey(t: ReactionTypeDto): String? = when (t.type) {
        "emoji" -> t.emoji?.takeIf { it.isNotBlank() }
        "custom_emoji" -> t.customEmojiId?.let { "custom:$it" }
        "paid" -> "paid"
        else -> null
    }

    private fun fetchUpdates(offset: Long): List<UpdateDto> {
        val payload = json.encodeToString(
            GetUpdatesRequest(offset = offset, timeout = LONG_POLL_SECONDS, allowedUpdates = ALLOWED_UPDATES)
        )
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/getUpdates")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 409) {
                logger.error {
                    "[Updates] HTTP 409 Conflict — another getUpdates consumer is running for this bot " +
                        "token. Only one instance may poll; check for a second bot process (e.g. a dev box " +
                        "and the server sharing the same TELEGRAM_BOT_TOKEN)."
                }
                throw IOException("getUpdates 409 conflict")
            }
            if (!response.isSuccessful) throw IOException("getUpdates HTTP ${response.code}")
            val bodyStr = response.body?.string().orEmpty()
            val parsed = json.decodeFromString(GetUpdatesResponse.serializer(), bodyStr)
            if (!parsed.ok) throw IOException("getUpdates returned ok=false")
            return parsed.result
        }
    }

    // ── Telegram getUpdates DTOs (only the fields we consume) ─────────────────

    @Serializable
    private data class GetUpdatesRequest(
        val offset: Long,
        val timeout: Int,
        @SerialName("allowed_updates") val allowedUpdates: List<String>
    )

    @Serializable
    private data class GetUpdatesResponse(
        val ok: Boolean = false,
        val result: List<UpdateDto> = emptyList()
    )

    @Serializable
    private data class UpdateDto(
        @SerialName("update_id") val updateId: Long = 0,
        @SerialName("message_reaction_count") val messageReactionCount: MessageReactionCountDto? = null
    )

    @Serializable
    private data class MessageReactionCountDto(
        val chat: ChatDto = ChatDto(),
        @SerialName("message_id") val messageId: Long = 0,
        val reactions: List<ReactionCountDto> = emptyList()
    )

    @Serializable
    private data class ChatDto(val id: Long = 0)

    @Serializable
    private data class ReactionCountDto(
        val type: ReactionTypeDto = ReactionTypeDto(),
        @SerialName("total_count") val totalCount: Int = 0
    )

    @Serializable
    private data class ReactionTypeDto(
        val type: String = "",
        val emoji: String? = null,
        @SerialName("custom_emoji_id") val customEmojiId: String? = null
    )
}
