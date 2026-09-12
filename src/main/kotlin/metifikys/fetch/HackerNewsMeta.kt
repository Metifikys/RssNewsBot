package metifikys.fetch

import metifikys.model.HnMeta

/**
 * Reads the Hacker News signals hnrss.org embeds in every item description:
 *
 *     <p>Article URL: <a href="…">…</a></p>
 *     <p>Comments URL: <a href="https://news.ycombinator.com/item?id=12345">…</a></p>
 *     <p>Points: 512</p>
 *     <p># Comments: 340</p>
 *
 * Must run on the RAW description, before [metifikys.model.HtmlText.strip] and before a
 * full-content fetch replaces it. Any description without a `news.ycombinator.com/item?id=`
 * comments link is not an HN item and yields null.
 */
object HackerNewsMeta {

    private val ITEM_ID = Regex("""news\.ycombinator\.com/item\?id=(\d+)""")
    private val POINTS = Regex("""Points:\s*(\d+)""")
    private val COMMENTS = Regex("""#\s*Comments:\s*(\d+)""")

    fun fromDescription(description: String?): HnMeta? {
        if (description.isNullOrBlank()) return null
        val id = ITEM_ID.find(description)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return HnMeta(
            id = id,
            points = POINTS.find(description)?.groupValues?.get(1)?.toIntOrNull(),
            comments = COMMENTS.find(description)?.groupValues?.get(1)?.toIntOrNull()
        )
    }
}
