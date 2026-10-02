package metifikys.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AdminConfig
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * A `message` update reduced to what command routing needs. [date] is Telegram's epoch seconds.
 * [forwardChatId] / [forwardMessageId] are set when the message is a forward of a CHANNEL post
 * (`forward_origin.type = channel`, or the older `forward_from_chat`): the original post's ids.
 */
data class IncomingMessage(
    val updateId: Long,
    val chatId: Long,
    val chatType: String,
    val fromId: Long?,
    val messageId: Long,
    val date: Long,
    val text: String?,
    val forwardChatId: Long? = null,
    val forwardMessageId: Long? = null
)

/**
 * A `callback_query` update (an inline button press). [chatId]/[messageId] identify the message
 * that carried the keyboard; they are null when Telegram no longer has it.
 */
data class IncomingCallback(
    val updateId: Long,
    val id: String,
    val fromId: Long,
    val data: String,
    val chatId: Long?,
    val messageId: Long?
)

/**
 * Who may drive the bot from Telegram. A command counts only from a user in `admin.userIds`
 * AND either in the admin chat (`admin.statusChatId`) or in a private chat with the bot; a button
 * press only from a user in `admin.userIds` (its keyboard lives in the admin chat anyway).
 * Everything else is dropped without a reply — answering strangers would advertise the bot —
 * and logged once per sender id per run.
 */
class AdminAuth(private val admin: AdminConfig) {

    private val warned = ConcurrentHashMap.newKeySet<Long>()

    fun allowsCommand(m: IncomingMessage): Boolean {
        val from = m.fromId
        val inAdminChat = admin.statusChatId?.trim() == m.chatId.toString()
        val ok = from != null && from in admin.userIds && (inAdminChat || m.chatType == "private")
        if (!ok) warnOnce(from, "command in chat ${m.chatId} (${m.chatType})")
        return ok
    }

    fun allowsCallback(c: IncomingCallback): Boolean {
        val ok = c.fromId in admin.userIds
        if (!ok) warnOnce(c.fromId, "button press '${c.data}'")
        return ok
    }

    private fun warnOnce(fromId: Long?, what: String) {
        if (warned.add(fromId ?: 0L)) {
            logger.warn { "[Admin] ignoring $what from user ${fromId ?: "unknown"} — not in admin.userIds, or not the admin or a private chat (logged once per user)" }
        }
    }
}
