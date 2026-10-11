package metifikys.telegram

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * One-off operator alerts (broken prompts, an expired CLI login) to `admin.statusChatId`, as
 * their own plain-text messages — [StatusPoster] deletes only its own snapshot, so these stay.
 * Best-effort: no admin chat configured, or a failed send, only logs.
 */
class AdminAlerts(
    private val chatId: String?,
    private val sender: TelegramSender,
    /** The chat's thread for alerts (`admin.threads.status`); null = its main thread. */
    private val threadId: Long? = null
) {
    val enabled: Boolean get() = !chatId.isNullOrBlank()

    fun send(text: String, silent: Boolean = false): Boolean {
        val chat = chatId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return try {
            (sender.sendMessageWithKeyboard(chat, text, silent = silent, threadId = threadId) != null).also { ok ->
                if (!ok) logger.warn { "[Admin] alert to $chat not delivered: ${text.lineSequence().first()}" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "[Admin] alert to $chat failed: ${text.lineSequence().first()}" }
            false
        }
    }
}
