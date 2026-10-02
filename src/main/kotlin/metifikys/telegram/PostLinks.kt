package metifikys.telegram

/**
 * A channel post a link or a forward points at: by numeric [chatId] (`t.me/c/…` links and
 * forwards) or by channel [username] (`t.me/<username>/…`). Exactly one of the two is set.
 */
data class PostRef(val chatId: Long?, val username: String?, val messageId: Long) {
    /** How the admin wrote it, for "not found" replies. */
    fun display(): String = when {
        username != null -> "t.me/$username/$messageId"
        chatId != null -> chatId.toString().removePrefix("-100").let { "t.me/c/$it/$messageId" }
        else -> messageId.toString()
    }
}

/** Finds links to channel posts in an admin's message. Pure. */
object PostLinks {

    /** At most this many links of one message get a card each. */
    const val MAX_LINKS = 5

    /**
     * `https://t.me/c/<internal id>/<msg>` (also with a thread id in between), `https://t.me/<username>/<msg>`
     * and `t.me/s/<username>/<msg>` (the web preview); `telegram.me` too, scheme optional, query and
     * fragment ignored. Not preceded by a letter, digit, `.` or `/`, so `abt.me/x/1` is no link.
     */
    private val LINK = Regex(
        """(?<![\w./])(?:https?://)?(?:www\.)?(?:t|telegram)\.me/(?:s/)?(?:c/(\d{1,20})(?:/\d+)?|([A-Za-z][A-Za-z0-9_]{3,31}))/(\d{1,12})(?![\w])""",
        RegexOption.IGNORE_CASE
    )

    /** `t.me/<word>/…` paths that are Telegram features, never a channel. */
    private val RESERVED = setOf("joinchat", "addstickers", "addemoji", "addlist", "share", "proxy", "socks", "boost", "invoice", "setlanguage", "login", "confirmphone", "contact", "iv")

    /** Up to [MAX_LINKS] distinct post links in [text], in order. */
    fun parse(text: String?): List<PostRef> {
        if (text.isNullOrBlank()) return emptyList()
        return LINK.findAll(text).mapNotNull { m ->
            val messageId = m.groupValues[3].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val internal = m.groupValues[1]
            if (internal.isNotEmpty()) {
                val chatId = ("-100$internal").toLongOrNull() ?: return@mapNotNull null
                PostRef(chatId, null, messageId)
            } else {
                val username = m.groupValues[2]
                if (username.lowercase() in RESERVED) null else PostRef(null, username, messageId)
            }
        }.distinct().take(MAX_LINKS).toList()
    }

    /** The post a forward came from, when the forward is of a channel post. */
    fun forwarded(m: IncomingMessage): PostRef? {
        val chat = m.forwardChatId ?: return null
        val message = m.forwardMessageId ?: return null
        return PostRef(chat, null, message)
    }
}
