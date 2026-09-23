package metifikys.telegram

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-chat pacing for Bot API calls, shared by every thread that writes through one
 * [TelegramSender]. Telegram's Bot FAQ asks for at most ~1 message per second in a single chat
 * and answers bursts with 429 + `retry_after`; the bot's own load is far below the global
 * 30 msg/s, and parallel sends go to different channels, so a per-chat gap plus a shared
 * cooldown is all the insurance needed (no global token bucket — see the 2026-09 engineering
 * plan for the criterion that would justify one: any `[TG429]` in prod logs, two categories on
 * one channel, or a fan-out feature).
 *
 *  - [acquire] spaces requests to the same chat at least [minIntervalMs] apart (the slot is
 *    reserved, so concurrent callers queue up instead of all firing after the same wait);
 *  - [cooldown] after a 429 makes every caller for that chat wait out `retry_after`, not only
 *    the thread that received it — a weekly post or a tracker edit racing a digest won't walk
 *    straight into the same limit.
 *
 * Chats that are written to rarely pay nothing. Clock and sleeper are injectable for tests.
 */
class ChatPacer(
    private val minIntervalMs: Long = 1_000L,
    private val nanos: () -> Long = System::nanoTime,
    private val sleeper: (Long) -> Unit = Thread::sleep
) {

    private class ChatState {
        /** Earliest nanoTime the next request to this chat may start (spacing reservation). */
        var nextFreeAtNanos = Long.MIN_VALUE
        /** NanoTime until which the chat is off-limits after a 429. */
        var cooldownUntilNanos = Long.MIN_VALUE
    }

    private val chats = ConcurrentHashMap<String, ChatState>()

    /**
     * Blocks until a request to [chatId] may start and reserves its slot. Throws
     * [InterruptedException] when interrupted while waiting — nothing is sent then.
     */
    fun acquire(chatId: String) {
        while (true) {
            val delayMs = acquireDelayMs(chatId)
            if (delayMs <= 0) return
            sleeper(delayMs)
        }
    }

    /**
     * Returns 0 and reserves the slot when a request to [chatId] may go now; otherwise the
     * milliseconds to wait (nothing reserved — call again after the delay).
     */
    fun acquireDelayMs(chatId: String): Long {
        val state = chats.computeIfAbsent(chatId) { ChatState() }
        synchronized(state) {
            val now = nanos()
            val earliest = maxOf(state.nextFreeAtNanos, state.cooldownUntilNanos)
            if (earliest > now) return (earliest - now) / 1_000_000 + 1
            state.nextFreeAtNanos = now + minIntervalMs * 1_000_000
            return 0
        }
    }

    /** Puts [chatId] off-limits for [ms] (a 429's `retry_after`); an active longer cooldown is kept. */
    fun cooldown(chatId: String, ms: Long) {
        val state = chats.computeIfAbsent(chatId) { ChatState() }
        synchronized(state) {
            state.cooldownUntilNanos = maxOf(state.cooldownUntilNanos, nanos() + ms * 1_000_000)
        }
    }
}
