package metifikys.format

import metifikys.config.HashtagsConfig
import metifikys.model.ShortlistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HashtagsTest {

    private fun item(franchise: String = "", subject: String = "", eventType: String = "") = ShortlistItem(
        eventKey = "evt",
        subject = subject,
        franchise = franchise,
        eventType = eventType,
        coreFact = "fact",
        url = "https://example.com/1",
        status = "new"
    )

    @Test
    fun `normalize lowercases, joins words with underscores and folds latin diacritics`() {
        assertEquals("#marvel_tokon_fighting_souls", Hashtags.normalize("Marvel Tōkon: Fighting Souls"))
        assertEquals("#hollow_knight_silksong", Hashtags.normalize("Hollow Knight: Silksong"))
        assertEquals("#pokemon", Hashtags.normalize("Pokémon"))
        assertEquals("#steam", Hashtags.normalize("  Steam "))
        assertEquals("#cyberpunk_2077", Hashtags.normalize("Cyberpunk 2077"))
    }

    @Test
    fun `normalize keeps cyrillic intact (NFD would break й and ї)`() {
        assertEquals("#верховна_рада", Hashtags.normalize("Верховна Рада"))
        assertEquals("#київ", Hashtags.normalize("Київ"))
        assertEquals("#їжак_йорж", Hashtags.normalize("Їжак Йорж"))
    }

    @Test
    fun `normalize rejects blanks, stop words and letterless values`() {
        assertNull(Hashtags.normalize(null))
        assertNull(Hashtags.normalize(""))
        assertNull(Hashtags.normalize("   "))
        assertNull(Hashtags.normalize("n/a"))
        assertNull(Hashtags.normalize("None"))
        assertNull(Hashtags.normalize("інше"))
        assertNull(Hashtags.normalize("2077"))
        assertNull(Hashtags.normalize("---"))
    }

    @Test
    fun `normalize cuts long names at an underscore boundary`() {
        val tag = Hashtags.normalize("a very long franchise name that keeps going and going")!!
        assertEquals("#a_very_long_franchise_name_that", tag)
        assertTrue(tag.length <= Hashtags.MAX_LEN + 1)
        assertFalse(tag.endsWith("_"))
    }

    @Test
    fun `isWellFormed accepts hash plus word chars with at least one letter`() {
        assertTrue(Hashtags.isWellFormed("#gaming"))
        assertTrue(Hashtags.isWellFormed("#release_date"))
        assertTrue(Hashtags.isWellFormed("#головне"))
        assertTrue(Hashtags.isWellFormed("#ps5"))
        assertFalse(Hashtags.isWellFormed("gaming"))
        assertFalse(Hashtags.isWellFormed("#2024"))
        assertFalse(Hashtags.isWellFormed("#tag-x"))
        assertFalse(Hashtags.isWellFormed("#"))
        assertFalse(Hashtags.isWellFormed("#two words"))
    }

    @Test
    fun `forItem puts static tags first, then franchise by default`() {
        val cfg = HashtagsConfig(category = "#gaming")
        assertEquals(listOf("#gaming", "#silksong"), Hashtags.forItem(item(franchise = "Silksong", eventType = "release_date"), cfg))
    }

    @Test
    fun `forItem adds event type and subject only when enabled`() {
        val cfg = HashtagsConfig(category = "#gaming", fromEventType = true, fromSubject = true)
        val tags = Hashtags.forItem(item(franchise = "Silksong", subject = "Hollow Knight: Silksong", eventType = "release_date"), cfg)
        assertEquals(listOf("#gaming", "#silksong", "#release_date", "#hollow_knight_silksong"), tags)
    }

    @Test
    fun `forItem caps generated tags and never duplicates a static one`() {
        val cfg = HashtagsConfig(category = "#gaming #steam", fromEventType = true, max = 1)
        // franchise normalizes to the static #steam → skipped, the cap then admits the event type
        assertEquals(listOf("#gaming", "#steam", "#platform_policy"), Hashtags.forItem(item(franchise = "Steam", eventType = "platform_policy"), cfg))
        assertEquals(listOf("#gaming"), Hashtags.forItem(item(franchise = "n/a"), HashtagsConfig(category = "#gaming")))
    }

    @Test
    fun `forItem on a legacy bullet yields the static tags only, and nothing without config`() {
        assertEquals(listOf("#politics"), Hashtags.forItem(null, HashtagsConfig(category = "#politics")))
        assertEquals(emptyList(), Hashtags.forItem(null, HashtagsConfig()))
        assertEquals(emptyList(), Hashtags.forItem(item(franchise = "Silksong"), null))
        assertEquals(listOf("#silksong"), Hashtags.forItem(item(franchise = "Silksong"), HashtagsConfig()))
    }

    @Test
    fun `the length cap never keeps half of an astral letter`() {
        // Found by HashtagsPropertyTest: MAX_LEN cut a surrogate pair (𝐁 = U+1D401), leaving a
        // lone high surrogate that is not \p{L} — a malformed tag that also re-normalized differently.
        val raw = "a".repeat(Hashtags.MAX_LEN - 1) + "𝐁𝐨"
        val tag = Hashtags.normalize(raw)!!
        assertEquals("#" + "a".repeat(Hashtags.MAX_LEN - 1), tag)
        assertTrue(Hashtags.isWellFormed(tag))
        assertEquals(tag, Hashtags.normalize(tag.drop(1)))
    }

    @Test
    fun `splitStatic tolerates extra whitespace and blanks`() {
        assertEquals(listOf("#a", "#b"), Hashtags.splitStatic("  #a   #b "))
        assertEquals(emptyList(), Hashtags.splitStatic(null))
        assertEquals(emptyList(), Hashtags.splitStatic("   "))
    }
}
