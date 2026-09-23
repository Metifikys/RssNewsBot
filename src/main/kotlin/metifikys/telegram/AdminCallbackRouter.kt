package metifikys.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.Executor

private val logger = KotlinLogging.logger {}

/**
 * Routes inline-button presses (`callback_query`) from admin-chat messages. `callback_data` is
 * `<namespace>:<action>:<id…>` (≤ 64 bytes, e.g. `ms:cat:17` or `mod:del:-1001234567890:4812`);
 * the namespace picks the registered [Handler], which gets the remaining parts.
 *
 * Like commands, presses run on the `admin-commands` executor. The press is acknowledged
 * (`answerCallbackQuery`) first thing there, before the handler — otherwise the client keeps
 * a spinner on the button — so handlers report their outcome by editing the message.
 * A press by anyone outside `admin.userIds` is dropped unanswered.
 */
class AdminCallbackRouter(
    private val auth: AdminAuth,
    private val sender: TelegramSender,
    private val executor: Executor
) {

    fun interface Handler {
        /** [parts] is `callback_data` split on `:` without the namespace: `[action, id…]`. */
        fun handle(cb: IncomingCallback, parts: List<String>)
    }

    private val handlers = java.util.concurrent.ConcurrentHashMap<String, Handler>()

    fun register(namespace: String, handler: Handler) {
        handlers[namespace] = handler
    }

    /** Called on the poller thread. */
    fun onCallback(cb: IncomingCallback) {
        if (!auth.allowsCallback(cb)) return
        executor.execute { run(cb) }
    }

    private fun run(cb: IncomingCallback) {
        sender.answerCallbackQuery(cb.id)
        val parts = cb.data.split(':')
        val handler = handlers[parts.first()]
        if (handler == null) {
            logger.warn { "[Admin] button '${cb.data}' has no handler (feature off?) — ignored" }
            return
        }
        logger.info { "[Admin] button '${cb.data}' by ${cb.fromId}" }
        try {
            handler.handle(cb, parts.drop(1))
        } catch (e: Exception) {
            logger.error(e) { "[Admin] button '${cb.data}' failed" }
        }
    }
}
