package metifikys.digest

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import metifikys.ai.LlmClient
import metifikys.config.CategoryConfig
import metifikys.config.FeedConfig
import metifikys.config.PersonaConfig
import metifikys.db.DigestMessageRow
import metifikys.format.PersonaRemark
import metifikys.model.ShortlistItem
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonaWriterTest {

    private val dir: File = Files.createTempDirectory("persona-test").toFile()
    private val client = mockk<LlmClient>()
    private val writer = PersonaWriter({ client }, dir)
    private val cat = CategoryConfig(emoji = "🎮", feeds = listOf(FeedConfig("https://example.com/rss")), channelId = "@g")
    private val topic = "**Silksong виходить 4 вересня.**\n\n[IGN](https://www.ign.com/a)"

    init {
        File(dir, "persona.yaml").writeText(
            "persona:\n  system: \"sys {{CATEGORY}} {{MAX_CHARS}}\"\n  user: \"fact={{CORE_FACT}} topic={{TOPIC}}\"\n"
        )
    }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** First keys that land in each arm at share 0.5. */
    private val personaKey = (1..100).map { "evt-$it" }.first { PersonaRemark.inPersonaArm(it, 0.5) }
    private val controlKey = (1..100).map { "evt-$it" }.first { !PersonaRemark.inPersonaArm(it, 0.5) }

    private fun item(key: String) = ShortlistItem(eventKey = key, coreFact = "Silksong 4 вересня", url = "https://www.ign.com/a", status = "new")
    private fun cfg(file: String = "persona.yaml", timeout: Long = 5) = PersonaConfig(promptFile = file, timeoutSeconds = timeout)

    @Test
    fun `the persona arm gets the checked remark, built from the owner's prompt`() {
        val system = slot<String>()
        val user = slot<String>()
        every { client.complete(capture(system), capture(user)) } returns "«Сім років — і ось 4 вересня.»"
        val out = writer.write("gaming", cat, cfg(), item(personaKey), topic)
        assertEquals(PersonaWriter.Outcome(DigestMessageRow.VARIANT_PERSONA, "💬 Сім років — і ось 4 вересня."), out)
        assertEquals("sys gaming 160", system.captured)
        assertEquals("fact=Silksong 4 вересня topic=$topic", user.captured)
    }

    @Test
    fun `the control arm is tagged without any call`() {
        assertEquals(PersonaWriter.Outcome(DigestMessageRow.VARIANT_CONTROL, null), writer.write("gaming", cat, cfg(), item(controlKey), topic))
        verify(exactly = 0) { client.complete(any(), any()) }
    }

    @Test
    fun `a rejected remark, a failed call or a slow one leave the post untagged`() {
        every { client.complete(any(), any()) } returns "Через 5 років — сиквел."
        assertEquals(PersonaWriter.Outcome.NONE, writer.write("gaming", cat, cfg(), item(personaKey), topic))

        every { client.complete(any(), any()) } throws RuntimeException("503")
        assertEquals(PersonaWriter.Outcome.NONE, writer.write("gaming", cat, cfg(), item(personaKey), topic))

        every { client.complete(any(), any()) } answers { Thread.sleep(5_000); "Пізно." }
        val start = System.nanoTime()
        assertEquals(PersonaWriter.Outcome.NONE, writer.write("gaming", cat, cfg(timeout = 1), item(personaKey), topic))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 4_000)
    }

    @Test
    fun `without a readable prompt file neither arm is tagged`() {
        assertEquals(PersonaWriter.Outcome.NONE, writer.write("gaming", cat, cfg("missing.yaml"), item(controlKey), topic))
        File(dir, "half.yaml").writeText("persona:\n  system: \"only system\"\n")
        assertEquals(PersonaWriter.Outcome.NONE, writer.write("gaming", cat, cfg("half.yaml"), item(controlKey), topic))
        verify(exactly = 0) { client.complete(any(), any()) }
    }
}
