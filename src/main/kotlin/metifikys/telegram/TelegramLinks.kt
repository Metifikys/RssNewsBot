package metifikys.telegram

/** Public URLs of channel posts, for links the bot writes into its own messages. */
object TelegramLinks {

    /**
     * `https://t.me/<username>/<id>` for a `@username` channel, `https://t.me/c/<internal id>/<id>`
     * for a `-100…` chat id (opens for channel members, i.e. every reader); null when neither
     * form is available.
     */
    fun messageUrl(channelId: String, chatId: Long?, messageId: Long): String? {
        val trimmed = channelId.trim()
        if (trimmed.startsWith("@") && trimmed.length > 1) return "https://t.me/${trimmed.drop(1)}/$messageId"
        val numeric = chatId ?: trimmed.toLongOrNull() ?: return null
        val s = numeric.toString()
        return if (s.startsWith("-100") && s.length > 4) "https://t.me/c/${s.removePrefix("-100")}/$messageId" else null
    }
}
