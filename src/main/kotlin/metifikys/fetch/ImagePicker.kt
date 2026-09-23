package metifikys.fetch

/** One image a post could carry; [width] / [height] as declared by the feed or page (or probed), null when unknown. */
data class ImageCandidate(val url: String, val width: Int? = null, val height: Int? = null)

/**
 * Tells a real article picture from a publisher logo, an author avatar, a tracking pixel or a
 * thumbnail too small for the channel (`post.images`). Pure: judges the URL and whatever size is
 * known, fetches nothing. An unknown size passes — only a declared or probed one can fail.
 *
 * Rejected:
 * - non-http(s) URLs, `.svg` / `.ico`;
 * - a path token naming chrome: `logo`, `avatar`, `gravatar`, `icon`, `favicon`, `sprite`,
 *   `placeholder`, `spacer`, `1x1`, `feedburner`, an `/emoji/` directory, or a file named
 *   `pixel` / `blank` / `transparent` (a whole token, so `iconic-scene.jpg` or
 *   `pixel-10-review.jpg` pass); `gravatar.com` / `feedburner` hosts;
 * - any URL containing one of [ignore] (case-insensitive substrings: a site's branded fallback);
 * - a known width below [minWidth], or a known aspect ratio beyond 4:1 / 1:3 (banners, strips).
 */
class ImagePicker(
    private val minWidth: Int = 0,
    private val ignore: List<String> = emptyList()
) {

    /** Why [c] is not a post picture, or null when it may be one. */
    fun rejectReason(c: ImageCandidate): String? {
        val url = c.url.trim()
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return "not http(s)"
        val uri = try {
            java.net.URI(url)
        } catch (e: Exception) {
            return "malformed"
        }
        val host = uri.host?.lowercase().orEmpty()
        val path = (uri.rawPath ?: "").lowercase()
        if (path.endsWith(".svg") || path.endsWith(".ico")) return "svg/ico"
        if (host.endsWith("gravatar.com") || "feedburner" in host) return "host $host"
        val segments = path.split('/').filter { it.isNotEmpty() }
        if ("emoji" in segments.dropLast(1)) return "emoji"
        val tokens = TOKEN_SPLIT.split(path).filter { it.isNotEmpty() }
        tokens.firstOrNull { it in CHROME_TOKENS }?.let { return "'$it' in path" }
        val fileStem = segments.lastOrNull()?.substringBeforeLast('.').orEmpty()
        if (fileStem in PIXEL_FILES) return "'$fileStem' file"
        ignore.firstOrNull { it.isNotBlank() && it.lowercase() in lower }?.let { return "ignored '$it'" }
        val w = c.width?.takeIf { it > 0 }
        val h = c.height?.takeIf { it > 0 }
        if (w != null && w < minWidth) return "width $w < $minWidth"
        if (w != null && h != null) {
            val ratio = w.toDouble() / h
            if (ratio > MAX_RATIO || ratio < MIN_RATIO) return "aspect ${w}x$h"
        }
        return null
    }

    fun accepts(c: ImageCandidate): Boolean = rejectReason(c) == null

    /** The first candidate that [accepts], in the given (priority) order. */
    fun pick(candidates: List<ImageCandidate>): ImageCandidate? = candidates.firstOrNull(::accepts)

    companion object {
        const val MAX_RATIO = 4.0
        const val MIN_RATIO = 1.0 / 3.0

        private val TOKEN_SPLIT = Regex("""[^a-z0-9]+""")
        private val CHROME_TOKENS = setOf(
            "logo", "logos", "avatar", "avatars", "gravatar", "icon", "icons", "favicon", "sprite", "sprites",
            "placeholder", "spacer", "1x1", "feedburner"
        )
        private val PIXEL_FILES = setOf("pixel", "blank", "transparent", "spacer")
    }
}
