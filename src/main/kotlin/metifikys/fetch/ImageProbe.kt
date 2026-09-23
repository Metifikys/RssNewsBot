package metifikys.fetch

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Reads an image's pixel size from its first bytes (`post.images.probe`): one `Range` GET of at
 * most [maxBytes] through the [UrlGuard] (every redirect hop re-checked), then the header only —
 * `ImageIO` for JPEG / PNG / GIF / BMP, a few bytes of RIFF for WebP. Null when the size cannot be
 * told (a server that ignores `Range` is read no further than [maxBytes] either); never throws.
 */
class ImageProbe(
    private val guard: UrlGuard = UrlGuard(),
    timeoutSeconds: Long = 5,
    private val maxBytes: Int = 64 * 1024
) {
    private val client = guard.install(OkHttpClient.Builder())
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun dimensions(url: String): Pair<Int, Int>? = try {
        guard.check(url)
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; RssNewsBot/1.0)")
            .header("Range", "bytes=0-${maxBytes - 1}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val head = response.body?.byteStream()?.use { it.readNBytes(maxBytes) } ?: return null
            dimensionsOf(head)
        }
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        null
    }

    /**
     * A HEAD preflight for a picture Telegram is about to fetch (the weekly album, where one bad
     * photo fails the whole group): 2xx, an `image/…` content type and — when the server gives a
     * length — at most [maxBytes]. False on anything else, a guard refusal or a transport error.
     */
    fun isPostable(url: String, maxBytes: Long): Boolean = try {
        guard.check(url)
        val request = Request.Builder().url(url).head()
            .header("User-Agent", "Mozilla/5.0 (compatible; RssNewsBot/1.0)")
            .build()
        client.newCall(request).execute().use { response ->
            val type = response.header("Content-Type").orEmpty().lowercase()
            val length = response.header("Content-Length")?.toLongOrNull()
            response.isSuccessful && type.startsWith("image/") && (length == null || length <= maxBytes)
        }
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        false
    }

    companion object {
        /** Width × height from an image's leading bytes, or null. Pure. */
        fun dimensionsOf(head: ByteArray): Pair<Int, Int>? {
            webp(head)?.let { return it }
            return try {
                ImageIO.createImageInputStream(ByteArrayInputStream(head)).use { input ->
                    input ?: return null
                    val readers = ImageIO.getImageReaders(input)
                    if (!readers.hasNext()) return null
                    val reader = readers.next()
                    try {
                        reader.input = input
                        reader.getWidth(0) to reader.getHeight(0)
                    } finally {
                        reader.dispose()
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
        private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
        private fun le24(b: ByteArray, i: Int) = le16(b, i) or (u8(b, i + 2) shl 16)

        /** RIFF/WEBP: `VP8 ` (lossy), `VP8L` (lossless) or `VP8X` (extended) header. */
        internal fun webp(b: ByteArray): Pair<Int, Int>? {
            if (b.size < 30 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WEBP") return null
            return when (String(b, 12, 4, Charsets.US_ASCII)) {
                "VP8 " -> (le16(b, 26) and 0x3fff) to (le16(b, 28) and 0x3fff)
                "VP8L" -> {
                    val b1 = u8(b, 22)
                    val w = 1 + (((b1 and 0x3f) shl 8) or u8(b, 21))
                    val h = 1 + (((u8(b, 24) and 0x0f) shl 10) or (u8(b, 23) shl 2) or ((b1 and 0xc0) shr 6))
                    w to h
                }
                "VP8X" -> (1 + le24(b, 24)) to (1 + le24(b, 27))
                else -> null
            }
        }
    }
}
