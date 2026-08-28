package metifikys.model

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup

private val logger = KotlinLogging.logger {}

/**
 * HTML-to-plain-text for feed-supplied descriptions.
 *
 * Many feeds ship raw HTML in `<description>` (telegram RSSHub mirrors, WordPress excerpts,
 * reddit cards): in a production DB snapshot, markup ate 40–75% of the first-1000-char prompt
 * budget on the worst hosts — and Step-1's `extractMaxPromptChars` cap means those wasted
 * chars directly reduce how many articles fit into one extract call. Stripping happens once
 * at ingestion ([metifikys.fetch.RssFetcher]) so the DB carries clean text, and defensively
 * in [Article.promptText] for rows written before the change.
 *
 * Jsoup's `text()` inserts whitespace at block boundaries, collapses runs of whitespace, and
 * decodes entities (`&#8217;`, `&amp;`, …), so paragraphs don't glue together.
 */
object HtmlText {

    /**
     * Cheap gate so plain-text descriptions skip the parse: a tag opener followed by a
     * letter/`!`/`/`, or an HTML entity. Bare `<` and `&` in prose ("5 < 6", "a & b") pass.
     */
    private val MARKUP = Regex("""<[a-zA-Z!/]|&#?[a-zA-Z0-9]{2,8};""")

    /** Returns [text] with tags removed and entities decoded; the input itself when it carries no markup. */
    fun strip(text: String): String {
        if (text.isEmpty() || !MARKUP.containsMatchIn(text)) return text
        return try {
            Jsoup.parseBodyFragment(text).text().trim()
        } catch (e: Exception) {
            logger.debug(e) { "[HtmlText] parse failed — keeping raw text" }
            text
        }
    }
}
