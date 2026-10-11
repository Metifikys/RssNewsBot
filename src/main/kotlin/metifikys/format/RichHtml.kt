package metifikys.format

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builders for the HTML of a Bot API 10.1 rich message (`sendRichMessage` / `editMessageText`
 * with `rich_message.html`): headings, paragraphs, a picture, a collage, a collapsible block,
 * lists, tables, a row of URL buttons and `<tg-time>` date-time entities.
 *
 * Every piece of text goes through [inline] = [TopicFormatter.toHtml] (HTML-escaped; `**bold**`,
 * `` `code` `` and `[label](url)` rendered), so LLM output can never turn into markup, and the HTML
 * mode — not rich Markdown, which reads a line starting with `#tag` as a heading — keeps
 * hashtags plain text. Media take only `http(s)://` URLs or a `tg://photo?id=` reference to a file
 * uploaded with the message (see [attached]); anything else is left out. Pure: no network.
 */
object RichHtml {

    /** Telegram's limit on a rich message's text (UTF-8 characters, formula sources included). */
    const val MAX_CHARS = 32768

    /** A list entry or a URL button. */
    data class Link(val label: String, val url: String)

    private val TIME_FORMAT = Regex("""r|w?[dD]?[tT]?""")
    private val NOON: LocalTime = LocalTime.NOON
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /** Inline Markdown (`**bold**`, `` `code` ``, `[label](url)`) as escaped HTML. */
    fun inline(markdown: String): String = TopicFormatter.toHtml(markdown)

    fun heading(level: Int, markdown: String): String {
        require(level in 1..6) { "heading level $level" }
        return "<h$level>${inline(markdown)}</h$level>"
    }

    fun paragraph(markdown: String): String = "<p>${inline(markdown)}</p>"

    /** A paragraph of ready HTML (e.g. text with a [time] entity in it). */
    fun paragraphHtml(html: String): String = "<p>$html</p>"

    /** A picture with an optional caption; empty when [src] is not a usable media URL. */
    fun figure(src: String, captionMarkdown: String? = null): String {
        if (!isMedia(src)) return ""
        val caption = captionMarkdown?.takeIf { it.isNotBlank() }?.let { "<figcaption>${inline(it)}</figcaption>" }.orEmpty()
        return "<figure><img src=\"${attr(src)}\"/>$caption</figure>"
    }

    /**
     * Several pictures as one collage (2+ usable URLs), a single [figure] for one, nothing for none.
     */
    fun collage(srcs: List<String>, captionMarkdown: String? = null): String {
        val usable = srcs.filter { isMedia(it) }.distinct()
        if (usable.isEmpty()) return ""
        if (usable.size == 1) return figure(usable.single(), captionMarkdown)
        val caption = captionMarkdown?.takeIf { it.isNotBlank() }?.let { "<figcaption>${inline(it)}</figcaption>" }.orEmpty()
        return "<tg-collage>" + usable.joinToString("") { "<img src=\"${attr(it)}\"/>" } + caption + "</tg-collage>"
    }

    /** A collapsible block: [summaryMarkdown] always shown, [innerHtml] on tap (or shown with [open]). */
    fun details(summaryMarkdown: String, innerHtml: String, open: Boolean = false): String =
        "<details${if (open) " open" else ""}><summary>${inline(summaryMarkdown)}</summary>$innerHtml</details>"

    /** A list of ready-HTML items; empty for none. */
    fun list(itemsHtml: List<String>, ordered: Boolean = true): String {
        if (itemsHtml.isEmpty()) return ""
        val tag = if (ordered) "ol" else "ul"
        return "<$tag>" + itemsHtml.joinToString("") { "<li>$it</li>" } + "</$tag>"
    }

    /** One `<a>` per [Link] with an http(s) URL, as list items. */
    fun linkList(links: List<Link>, ordered: Boolean = true): String =
        list(links.filter { isHttp(it.url) && it.label.isNotBlank() }.distinctBy { it.url }.map { link(it) }, ordered)

    fun link(link: Link): String = "<a href=\"${attr(link.url)}\">${TopicFormatter.escapeHtml(link.label)}</a>"

    /**
     * A table of ready-HTML cells ([header] may be null); Telegram allows inline formatting only in
     * a cell. Empty when there are no rows.
     */
    fun table(header: List<String>?, rows: List<List<String>>, bordered: Boolean = true, striped: Boolean = true, compact: Boolean = false): String {
        if (rows.isEmpty()) return ""
        val flags = listOfNotNull("bordered".takeIf { bordered }, "striped".takeIf { striped }, "compact".takeIf { compact })
        val open = if (flags.isEmpty()) "<table>" else "<table ${flags.joinToString(" ")}>"
        val head = header?.let { h -> "<tr>" + h.joinToString("") { "<th>$it</th>" } + "</tr>" }.orEmpty()
        val body = rows.joinToString("") { r -> "<tr>" + r.joinToString("") { "<td>$it</td>" } + "</tr>" }
        return "$open$head$body</table>"
    }

    /** A row of URL buttons (http(s) only); empty when none is usable. */
    fun buttonRow(links: List<Link>): String {
        val usable = links.filter { isHttp(it.url) && it.label.isNotBlank() }
        if (usable.isEmpty()) return ""
        return "<tg-button-row>" + usable.joinToString("") {
            "<tg-button type=\"url\" url=\"${attr(it.url)}\">${TopicFormatter.escapeHtml(it.label)}</tg-button>"
        } + "</tg-button-row>"
    }

    /**
     * A date-time entity: every reader sees [unix] in their own zone and language, as [format] says
     * (`r` relative and self-updating; `w` weekday, `d`/`D` short/long date, `t`/`T` short/long
     * time). [fallback] is what a client without the entity shows.
     */
    fun time(unix: Long, format: String, fallback: String): String {
        require(TIME_FORMAT.matches(format)) { "bad tg-time format '$format'" }
        return "<tg-time unix=\"$unix\" format=\"$format\">${TopicFormatter.escapeHtml(fallback)}</tg-time>"
    }

    /** [time] for a calendar day, anchored at noon in [zone] so the date is the same for every reader's zone. */
    fun time(date: LocalDate, format: String, zone: ZoneId = ZoneId.systemDefault()): String =
        time(date.atTime(NOON).atZone(zone).toEpochSecond(), format, date.format(DAY))

    /** [time] for a moment in [zone]. */
    fun time(at: LocalDateTime, format: String, zone: ZoneId = ZoneId.systemDefault()): String =
        time(at.atZone(zone).toEpochSecond(), format, at.format(DAY_TIME))

    /** The `src` of a picture uploaded with the message under [id] (`attachments` of `sendRichMessage`). */
    fun attached(id: String): String = "tg://photo?id=$id"

    private fun isHttp(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

    private fun isMedia(src: String): Boolean = isHttp(src) || src.startsWith("tg://photo?id=")

    private fun attr(s: String): String = TopicFormatter.escapeHtml(s).replace("\"", "&quot;")
}
