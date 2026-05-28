package metifikys.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.digest.CycleErrorLog

private val logger = KotlinLogging.logger {}

/**
 * One-way status reporter. On each [post] call:
 *   1. Deletes the previously posted status message (if any).
 *   2. Sends a fresh status message and remembers its message_id.
 *   3. On successful send, clears the cycle-error log up to the rendered seq.
 *
 * Result: the status chat always shows exactly one current snapshot, no polling required.
 * Last message_id is held in memory only — on bot restart the prior message stays orphaned
 * until manually cleared (a single stale message is acceptable; persisting through restarts
 * would add a DB column for marginal value).
 */
class StatusPoster(
    private val chatId: String,
    private val sender: TelegramSender,
    private val statusCommand: StatusCommand,
    private val errorLog: CycleErrorLog
) {
    @Volatile
    private var lastMessageId: Long? = null

    fun post() {
        val built = try {
            statusCommand.buildSnapshot()
        } catch (e: Exception) {
            logger.error(e) { "Failed to build status text; skipping post." }
            return
        }
        val previous = lastMessageId
        if (previous != null) {
            // Best-effort: a 400 here usually means the message was already deleted manually —
            // not a reason to skip the new post.
            sender.deleteMessage(chatId, previous)
        }
        val newId = sender.sendMessageReturningId(chatId, built.text)
        if (newId != null) {
            lastMessageId = newId
            errorLog.clearUpTo(built.errorCommitToken)
        } else {
            logger.warn { "Status post to $chatId failed; will retry next cycle." }
        }
    }
}
