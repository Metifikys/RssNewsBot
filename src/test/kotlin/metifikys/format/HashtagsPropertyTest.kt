package metifikys.format

import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Invariants of [Hashtags.normalize] — also the shared entity-key normalizer for intros and trackers. */
class HashtagsPropertyTest {

    @Test
    fun `normalize yields null or a well-formed, bounded tag without stray underscores`() = runBlocking<Unit> {
        checkAll(1000, FormatterArbs.entityName()) { raw ->
            val tag = Hashtags.normalize(raw) ?: return@checkAll
            assertTrue(Hashtags.isWellFormed(tag), "not well-formed: '$tag' from '$raw'")
            assertTrue(tag.length <= Hashtags.MAX_LEN + 1, "too long: '$tag'")
            val key = tag.drop(1)
            assertFalse("__" in key || key.startsWith("_") || key.endsWith("_"), "stray underscore: '$tag'")
        }
    }

    @Test
    fun `normalize is idempotent`() = runBlocking<Unit> {
        checkAll(1000, FormatterArbs.entityName()) { raw ->
            val tag = Hashtags.normalize(raw) ?: return@checkAll
            assertEquals(tag, Hashtags.normalize(tag.drop(1)), "from '$raw'")
        }
    }

    @Test
    fun `a Cyrillic word only changes case`() = runBlocking<Unit> {
        checkAll(1000, FormatterArbs.cyrillicWord()) { word ->
            assertEquals("#" + word.lowercase(), Hashtags.normalize(word))
        }
    }
}
