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
 * Long-polls Telegram `getUpdates` on its own daemon thread. Always subscribed to
 * `message_reaction_count` — the anonymous, aggregated reaction update Telegram emits for
 * channels (per-user `message_reaction` exists only in groups). Each such update carries a
 * message's complete reaction set, so it maps to a replace-all write via
 * [NewsDatabase.replaceReactionCounts].
 *
 * With admin commands on (a [commandRouter] / [callbackRouter] is passed) it also subscribes to
 * `message` and `callback_query` and hands them to the routers, which authorize and execute them
 * off this thread. `channel_post` is never subscribed, so the bot does not receive its own posts.
 *
 * The `getUpdates` offset is persisted in `bot_state`. Reactions: after every non-empty batch,
 * so a restart resumes without reprocessing or dropping them. Commands and button presses:
 * BEFORE they are routed (at-most-once) — after a crash-restart, a lost command is better than
 * a second `/run` or a second "delete". Telegram allows only ONE `getUpdates` consumer per bot
 * token; a second one gets HTTP 409 (logged loudly) — never run two pollers on the same token
 * (e.g. a dev box and the server). Gated behind `telegram.updatesPolling`, off by default,
 * precisely so a shared-token dev instance stays silent.
 */
class TelegramUpdatesPoller(
    private val botToken: String,
    private val db: NewsDatabase,
    private val commandRouter: AdminCommandRouter? = null,
    private val callbackRouter: AdminCallbackRouter? = null,
    /** Bot API origin; overridable so tests can point the poller at a local HTTP server. */
    private val apiBaseUrl: String = "https://api.telegram.org",
    private val longPollSeconds: Int = LONG_POLL_SECONDS
) {
    private companion object {
        const val OFFSET_KEY = "updates_offset"
        const val LONG_POLL_SECONDS = 50
        const val MAX_BACKOFF_MS = 60_000L
    }

    /** What `getUpdates` subscribes to: reactions, plus messages and button presses when routed. */
    internal val allowedUpdates: List<String> = buildList {
        add("message_reaction_count")
        if (commandRouter != null) add("message")
        if (callbackRouter != null) add("callback_query")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Read timeout must exceed the long-poll timeout so a quiet poll isn't killed as a timeout.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout((longPollSeconds + 20).toLong(), TimeUnit.SECONDS)
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
        logger.info { "[Updates] Poller started (offset=$offset, allowed=$allowedUpdates)." }
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
                    if (u.message != null || u.callbackQuery != null) {
                        // At-most-once: the offset moves past a command before it is routed.
                        offset = u.updateId + 1
                        db.setState(OFFSET_KEY, offset.toString())
                    }
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
        logger.info { "[Updates] Poller stopped." }
    }

    private fun handleUpdate(u: UpdateDto) {
        u.messageReactionCount?.let { handleReactions(it) }
        u.message?.let { m ->
            val router = commandRouter ?: return@let
            routeSafely("message") {
                router.onMessage(
                    IncomingMessage(
                        updateId = u.updateId, chatId = m.chat.id, chatType = m.chat.type, fromId = m.from?.id,
                        messageId = m.messageId, date = m.date, text = m.text
                    )
                )
            }
        }
        u.callbackQuery?.let { c ->
            val router = callbackRouter ?: return@let
            routeSafely("callback_query") {
                router.onCallback(
                    IncomingCallback(
                        updateId = u.updateId, id = c.id, fromId = c.from.id, data = c.data.orEmpty(),
                        chatId = c.message?.chat?.id, messageId = c.message?.messageId
                    )
                )
            }
        }
    }

    /** A routing failure (e.g. the executor is shutting down) must never stall the poll loop. */
    private inline fun routeSafely(kind: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.warn(e) { "[Updates] routing a $kind update failed — dropped" }
        }
    }

    private fun handleReactions(mrc: MessageReactionCountDto) {
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
            GetUpdatesRequest(offset = offset, timeout = longPollSeconds, allowedUpdates = allowedUpdates)
        )
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$apiBaseUrl/bot$botToken/getUpdates")
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
        @SerialName("message_reaction_count") val messageReactionCount: MessageReactionCountDto? = null,
        val message: MessageDto? = null,
        @SerialName("callback_query") val callbackQuery: CallbackQueryDto? = null
    )

    @Serializable
    private data class MessageReactionCountDto(
        val chat: ChatDto = ChatDto(),
        @SerialName("message_id") val messageId: Long = 0,
        val reactions: List<ReactionCountDto> = emptyList()
    )

    @Serializable
    private data class ChatDto(val id: Long = 0, val type: String = "")

    @Serializable
    private data class UserDto(val id: Long = 0)

    @Serializable
    private data class MessageDto(
        @SerialName("message_id") val messageId: Long = 0,
        val chat: ChatDto = ChatDto(),
        val from: UserDto? = null,
        val date: Long = 0,
        val text: String? = null
    )

    @Serializable
    private data class CallbackQueryDto(
        val id: String = "",
        val from: UserDto = UserDto(),
        val data: String? = null,
        val message: MessageDto? = null
    )

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
