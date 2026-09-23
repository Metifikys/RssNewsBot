package metifikys.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.util.concurrent.Executor

private val logger = KotlinLogging.logger {}

/**
 * Routes admin commands (`/help`, `/pause gaming 6h`, …) that the updates poller hands over as
 * `message` updates. The poller thread only authorizes and parses; every command then runs on
 * the single-thread `admin-commands` [executor], so a slow command never stalls reaction
 * polling, and commands never run concurrently with each other.
 *
 * Handlers are registered by name ([register]); `/help` is built in and also answers an
 * unknown command. Replies are separate plain-text messages to the chat the command came from
 * (the rolling status snapshot is untouched — [StatusPoster] deletes only its own message).
 *
 * Telegram keeps undelivered updates for 24 h, so a command sent during a long outage would run
 * hours late; commands older than [maxAgeSeconds] are refused with a "stale" reply instead.
 */
class AdminCommandRouter(
    private val auth: AdminAuth,
    private val sender: TelegramSender,
    private val executor: Executor,
    private val clock: () -> Instant = Instant::now,
    private val maxAgeSeconds: Long = 600
) {

    /** A parsed command: [name] lowercased without the slash and `@BotName`, [args] tokenized. */
    data class Command(val name: String, val args: List<String>)

    /** Where a command came from, and how to answer it. */
    class Context(val chatId: String, val fromId: Long, val updateId: Long, private val sender: TelegramSender) {
        fun reply(text: String) {
            if (sender.sendMessageWithKeyboard(chatId, text) == null) {
                logger.warn { "[Admin] reply to $chatId failed" }
            }
        }
    }

    fun interface Handler {
        fun handle(cmd: Command, ctx: Context)
    }

    private class Registered(val usage: String, val description: String, val handler: Handler)

    private val commands = LinkedHashMap<String, Registered>()

    init {
        register("help", "/help", "список команд") { _, ctx -> ctx.reply(helpText()) }
    }

    /** Adds a command; [usage] is the `/help` line (`/pause <cat> [6h|2d]`), [description] its gloss. */
    fun register(name: String, usage: String, description: String, handler: Handler) {
        synchronized(commands) { commands[name.lowercase()] = Registered(usage, description, handler) }
    }

    /** `command → description` pairs for `setMyCommands` (the "/" menu). */
    fun menu(): List<Pair<String, String>> = synchronized(commands) {
        commands.map { (name, r) -> name to r.description }
    }

    fun helpText(): String = synchronized(commands) {
        "Команди:\n" + commands.values.joinToString("\n") { "${it.usage} — ${it.description}" }
    }

    /** Called on the poller thread. Plain text (no leading `/`) is ignored. */
    fun onMessage(m: IncomingMessage) {
        val cmd = parse(m.text ?: return) ?: return
        if (!auth.allowsCommand(m)) return
        val ctx = Context(m.chatId.toString(), m.fromId ?: return, m.updateId, sender)
        executor.execute { run(cmd, m, ctx) }
    }

    private fun run(cmd: Command, m: IncomingMessage, ctx: Context) {
        val age = clock().epochSecond - m.date
        if (age > maxAgeSeconds) {
            logger.warn { "[Admin] /${cmd.name} from ${ctx.fromId} is ${age}s old — refused as stale" }
            ctx.reply("⌛ /${cmd.name} застаріла (надіслана ${age / 60} хв тому, бот був недоступний) — не виконую. Надішли ще раз.")
            return
        }
        val registered = synchronized(commands) { commands[cmd.name] }
        if (registered == null) {
            ctx.reply("Невідома команда /${cmd.name}.\n\n${helpText()}")
            return
        }
        logger.info { "[Admin] /${cmd.name} ${cmd.args} by ${ctx.fromId} (update ${ctx.updateId})" }
        try {
            registered.handler.handle(cmd, ctx)
        } catch (e: Exception) {
            logger.error(e) { "[Admin] /${cmd.name} failed" }
            ctx.reply("⚠️ /${cmd.name}: помилка — ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        /**
         * `/cmd@BotName arg1 "a phrase" arg3` → `Command("cmd", [arg1, a phrase, arg3])`. Null when
         * [text] is not a command. Quotes (`"…"`, `“…”`, `«…»`) keep a phrase in one argument.
         */
        fun parse(text: String): Command? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("/") || trimmed.length < 2) return null
            val head = trimmed.substring(1).takeWhile { !it.isWhitespace() }
            val name = head.substringBefore('@').lowercase()
            if (name.isEmpty()) return null
            return Command(name, tokenize(trimmed.substring(1 + head.length)))
        }

        private val OPEN_QUOTES = mapOf('"' to '"', '“' to '”', '«' to '»', '„' to '“')

        fun tokenize(s: String): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c in OPEN_QUOTES -> {
                        val close = OPEN_QUOTES.getValue(c)
                        val end = s.indexOf(close, i + 1).let { if (it < 0) s.length else it }
                        val phrase = s.substring(i + 1, end).trim()
                        if (phrase.isNotEmpty()) out += phrase
                        i = end + 1
                    }
                    else -> {
                        val start = i
                        while (i < s.length && !s[i].isWhitespace()) i++
                        out += s.substring(start, i)
                    }
                }
            }
            return out
        }
    }
}
