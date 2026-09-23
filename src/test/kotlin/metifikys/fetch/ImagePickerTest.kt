package metifikys.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImagePickerTest {

    private val picker = ImagePicker(minWidth = 400, ignore = listOf("example.com/default-share.png"))

    private fun rejected(url: String, w: Int? = null, h: Int? = null) = picker.rejectReason(ImageCandidate(url, w, h))

    @Test
    fun `logos, avatars, pixels and icons are rejected by path token`() {
        for (url in listOf(
            "https://cdn.site.com/assets/logo.png",
            "https://cdn.site.com/img/site-logo-dark.jpg",
            "https://cdn.site.com/authors/avatar_123.jpg",
            "https://secure.gravatar.com/avatar/abc",
            "https://feeds.feedburner.com/~r/x/~4/abc",
            "https://cdn.site.com/1x1.gif",
            "https://cdn.site.com/t/pixel.gif",
            "https://cdn.site.com/static/favicon.png",
            "https://cdn.site.com/emoji/72x72/1f525.png",
            "https://cdn.site.com/ui/placeholder.jpg",
            "https://cdn.site.com/brand.svg",
            "https://cdn.site.com/favicon.ico",
            "data:image/png;base64,AAAA"
        )) {
            assertNotNull(rejected(url), url)
        }
    }

    @Test
    fun `whole tokens only — a word that merely contains one passes`() {
        for (url in listOf(
            "https://cdn.site.com/2026/09/iconic-scene.jpg",
            "https://cdn.site.com/2026/09/pixel-10-review.jpg",
            "https://cdn.site.com/2026/09/logotype-history-hero.jpg",
            "https://cdn.site.com/2026/09/silksong.webp"
        )) {
            assertNull(rejected(url), url)
        }
    }

    @Test
    fun `known sizes are checked, unknown sizes pass`() {
        assertEquals("width 150 < 400", rejected("https://cdn.site.com/a.jpg", 150, 150))
        assertNotNull(rejected("https://cdn.site.com/banner.jpg", 2000, 300))    // 6.7:1
        assertNotNull(rejected("https://cdn.site.com/tall.jpg", 500, 1600))      // 1:3.2
        assertNull(rejected("https://cdn.site.com/a.jpg", 1200, 675))
        assertNull(rejected("https://cdn.site.com/a.jpg"))
        assertNull(rejected("https://cdn.site.com/a.jpg", 1200, null))
    }

    @Test
    fun `ignore entries are case-insensitive substrings`() {
        assertNotNull(rejected("https://EXAMPLE.com/default-share.png"))
    }

    @Test
    fun `pick returns the first acceptable candidate in order`() {
        val picked = picker.pick(listOf(
            ImageCandidate("https://cdn.site.com/logo.png"),
            ImageCandidate("https://cdn.site.com/thumb.jpg", 150, 150),
            ImageCandidate("https://cdn.site.com/hero.jpg", 1200, 630),
            ImageCandidate("https://cdn.site.com/other.jpg")
        ))
        assertEquals("https://cdn.site.com/hero.jpg", picked?.url)
        assertNull(picker.pick(listOf(ImageCandidate("https://cdn.site.com/logo.png"))))
    }
}
