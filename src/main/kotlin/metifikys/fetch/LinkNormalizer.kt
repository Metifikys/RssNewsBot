package metifikys.fetch

import java.net.URI

/**
 * Canonicalizes a feed article link so the same story, re-emitted with different tracking
 * parameters, host casing, a trailing slash, or a `#fragment`, dedups to a single DB row
 * (BUG-020). Applied at ingestion in [RssFetcher] before the link becomes the dedup key.
 *
 * Best-effort: an unparseable or non-absolute URL is returned trimmed and otherwise unchanged,
 * so a weird link is never made worse. `www.` is deliberately NOT stripped — some hosts serve
 * different content with and without it.
 */
object LinkNormalizer {

    /** Query parameters that never identify a distinct article — pure click tracking. */
    private val TRACKING_PARAM = Regex(
        "^(utm_[a-z_]*|fbclid|gclid|dclid|gbraid|wbraid|msclkid|yclid|igshid|mc_cid|mc_eid|_hsenc|_hsmi|ref|ref_src)$",
        RegexOption.IGNORE_CASE
    )

    fun normalize(rawLink: String): String {
        val link = rawLink.trim()
        val uri = try {
            URI(link)
        } catch (e: Exception) {
            return link
        }
        val scheme = uri.scheme ?: return link          // relative URI — leave untouched
        val host = uri.host ?: return link               // opaque/mailto — leave untouched

        // Strip trailing slash(es); "/" and "" both canonicalize to "" so bare-domain variants match.
        val path = (uri.path ?: "").trimEnd('/')

        val keptQuery = uri.rawQuery
            ?.split("&")
            ?.filter { it.isNotBlank() }
            ?.filterNot { param -> TRACKING_PARAM.matches(param.substringBefore("=")) }
            ?.joinToString("&")
            ?.takeIf { it.isNotBlank() }

        return buildString {
            append(scheme.lowercase()).append("://").append(host.lowercase())
            if (uri.port != -1) append(":").append(uri.port)
            append(path)
            if (keptQuery != null) append("?").append(keptQuery)
            // Fragment intentionally dropped: #comments / #respond etc. are dedup noise.
        }
    }
}
