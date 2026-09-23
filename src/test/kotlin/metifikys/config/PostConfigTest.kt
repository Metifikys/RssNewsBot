package metifikys.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The per-category `post:` block: reply threading and the hashtag footer. */
class PostConfigTest {

    private fun load(yaml: String): AppConfig {
        val file = File.createTempFile("config-post-test", ".yaml")
        file.writeText(yaml.trimIndent())
        try {
            return ConfigLoader.load(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    private fun yaml(post: String) = buildString {
        appendLine("telegram:")
        appendLine("  botToken: \"tok\"")
        appendLine("openai:")
        appendLine("  apiKey: \"sk-test\"")
        appendLine("database:")
        appendLine("  path: \"test.db\"")
        appendLine("scheduler:")
        appendLine("  intervalMinutes: 30")
        appendLine("categories:")
        appendLine("  gaming:")
        appendLine("    emoji: \"🎮\"")
        appendLine("    channelId: \"@gaming\"")
        appendLine("    feeds:")
        appendLine("      - https://example.com/gaming")
        if (post.isNotBlank()) appendLine(post.trimIndent().prependIndent("    "))
        appendLine("  politics:")
        appendLine("    emoji: \"🏛️\"")
        appendLine("    channelId: \"@politics\"")
        appendLine("    feeds:")
        appendLine("      - https://example.com/politics")
    }

    @Test
    fun `post block is parsed with its defaults`() {
        val config = load(
            yaml(
                """
                post:
                  threads:
                    maxAgeDays: 10
                  hashtags:
                    category: "#gaming #ігри"
                    fromEventType: true
                """.trimIndent()
            )
        )
        val post = config.categories.getValue("gaming").post!!
        assertTrue(post.threads!!.enabled)
        assertEquals(10L, post.threads!!.maxAgeDays)
        assertEquals("#gaming #ігри", post.hashtags!!.category)
        assertTrue(post.hashtags!!.fromFranchise)
        assertTrue(post.hashtags!!.fromEventType)
        assertEquals(false, post.hashtags!!.fromSubject)
        assertEquals(3, post.hashtags!!.max)
        assertNull(config.categories.getValue("politics").post)
    }

    @Test
    fun `badges flags map to provider ids, and the global badges block is range-checked`() {
        val config = load(yaml("post:\n  badges:\n    steam: true\n    github: true\n    trailer: true"))
        assertEquals(setOf("steam", "github", "trailer"), config.categories.getValue("gaming").post!!.badges!!.enabledIds)
        assertEquals(listOf("major_announcement", "release_date", "major_dlc_expansion", "delay"), config.badges.trailerEventTypes)
        assertTrue(config.badges.trailerFetchOnDelivery)
        assertEquals(5L, config.badges.timeoutSeconds)
        assertEquals("UA", config.badges.steamCountry)

        val withGlobal = load("badges:\n  timeoutSeconds: 2\n  maxTotalSeconds: 8\n  steamCountry: \"PL\"\n" + yaml(""))
        assertEquals(2L, withGlobal.badges.timeoutSeconds)
        assertEquals(8L, withGlobal.badges.maxTotalSeconds)
        assertEquals("PL", withGlobal.badges.steamCountry)

        assertThrows<IllegalArgumentException> { load("badges:\n  timeoutSeconds: 0\n" + yaml("")) }
        assertThrows<IllegalArgumentException> { load("badges:\n  timeoutSeconds: 10\n  maxTotalSeconds: 5\n" + yaml("")) }
    }

    @Test
    fun `the newer badges map to their provider ids and are off by default`() {
        val plain = load(yaml("post:\n  badges:\n    steam: true"))
        assertEquals(setOf("steam"), plain.categories.getValue("gaming").post!!.badges!!.enabledIds)
        assertEquals(false, plain.badges.ukLocalizationShowMissing)

        val all = load(
            "badges:\n  ukLocalizationShowMissing: true\n" +
                yaml("post:\n  badges:\n    ukLocalization: true\n    deck: true\n    critic: true")
        )
        assertEquals(setOf("uk_lang", "deck", "critic"), all.categories.getValue("gaming").post!!.badges!!.enabledIds)
        assertTrue(all.badges.ukLocalizationShowMissing)
    }

    @Test
    fun `intro block is parsed with defaults and validated`() {
        val config = load(yaml("post:\n  intro:\n    languages: [uk, en]\n    maxPerDigest: 1"))
        val intro = config.categories.getValue("gaming").post!!.intro!!
        assertTrue(intro.enabled)
        assertEquals(listOf("uk", "en"), intro.languages)
        assertEquals(1, intro.maxPerDigest)
        assertEquals(365L, intro.repeatAfterDays)

        assertThrows<IllegalArgumentException> { load(yaml("post:\n  intro:\n    languages: []")) }
        assertThrows<IllegalArgumentException> { load(yaml("post:\n  intro:\n    languages: [Ukrainian]")) }
        assertThrows<IllegalArgumentException> { load(yaml("post:\n  intro:\n    maxPerDigest: 0")) }
        assertThrows<IllegalArgumentException> { load(yaml("post:\n  intro:\n    repeatAfterDays: 0")) }
    }

    @Test
    fun `absent post block leaves posts unchanged`() {
        val config = load(yaml(""))
        assertNull(config.categories.getValue("gaming").post)
    }

    @Test
    fun `static hashtags must be well-formed`() {
        for (bad in listOf("gaming", "#2024", "#tag-x", "#", "#two words")) {   // NBSP is not a word char either
            val ex = assertThrows<IllegalArgumentException> {
                load(yaml("post:\n  hashtags:\n    category: \"$bad\""))
            }
            assertTrue("post.hashtags.category" in ex.message.orEmpty(), "message for '$bad': ${ex.message}")
        }
    }

    @Test
    fun `threads maxAgeDays and hashtags max are range-checked`() {
        assertThrows<IllegalArgumentException> { load(yaml("post:\n  threads:\n    maxAgeDays: 0")) }
        assertThrows<IllegalArgumentException> { load(yaml("post:\n  hashtags:\n    max: -1")) }
    }

    @Test
    fun `also block is parsed with host names and validated`() {
        val config = load(yaml("post:\n  also:\n    max: 2\n    names: { ign.com: IGN, pcgamer.com: PC Gamer }"))
        val also = config.categories.getValue("gaming").post!!.also!!
        assertTrue(also.enabled)
        assertEquals(false, also.logOnly)
        assertEquals(2, also.max)
        assertEquals(mapOf("ign.com" to "IGN", "pcgamer.com" to "PC Gamer"), also.names)

        assertThrows<IllegalArgumentException> { load(yaml("post:\n  also:\n    max: 0")) }
        assertThrows<IllegalArgumentException> { load(yaml("post:\n  also:\n    names: { ign.com: \"\" }")) }
    }

    @Test
    fun `persona block needs a prompt file, a dedup block and sane knobs`() {
        val dedup = "dedup:\n  promptFile: prompts/games-dedup.example.yaml\n"
        val ok = load(yaml(dedup + "post:\n  persona:\n    promptFile: prompts/persona.example.yaml\n    share: 0.3"))
        val p = ok.categories.getValue("gaming").post!!.persona!!
        assertEquals(0.3, p.share)
        assertEquals(160, p.maxChars)
        assertEquals(PersonaConfig.DEFAULT_BANNED_PHRASES, p.bannedPhrases)

        assertThrows<IllegalArgumentException> { load(yaml(dedup + "post:\n  persona:\n    share: 0.5")) }
        assertThrows<IllegalArgumentException> { load(yaml("post:\n  persona:\n    promptFile: p.yaml")) }
        for (bad in listOf("share: 1.5", "maxChars: 5", "timeoutSeconds: 0")) {
            assertThrows<IllegalArgumentException> { load(yaml(dedup + "post:\n  persona:\n    promptFile: p.yaml\n    $bad")) }
        }
        val llm = load(yaml(dedup + "llm:\n  persona: { provider: openai, model: gpt-mini }\npost:\n  persona:\n    promptFile: p.yaml"))
        assertEquals("gpt-mini", llm.categories.getValue("gaming").llm!!.persona!!.model)
    }

    @Test
    fun `official block needs a dedup block and real domains`() {
        val ok = load(yaml("dedup:\n  promptFile: prompts/games-dedup.example.yaml\npost:\n  official:\n    domains: [blog.playstation.com, nintendo.com]"))
        val official = ok.categories.getValue("gaming").post!!.official!!
        assertEquals(listOf("blog.playstation.com", "nintendo.com"), official.domains)
        assertEquals(false, official.matchEntityDomain)

        assertThrows<IllegalArgumentException> { load(yaml("post:\n  official:\n    domains: [nintendo.com]")) }
        assertThrows<IllegalArgumentException> {
            load(yaml("dedup:\n  promptFile: prompts/games-dedup.example.yaml\npost:\n  official:\n    domains: [\"https://nintendo.com\"]"))
        }
        assertThrows<IllegalArgumentException> {
            load(yaml("dedup:\n  promptFile: prompts/games-dedup.example.yaml\npost:\n  official:\n    domains: []"))
        }
    }

    @Test
    fun `images block needs enableImages and sane budgets`() {
        val ok = load(yaml("enableImages: true\npost:\n  images:\n    minWidth: 500\n    ignore: [\"default-og.png\"]"))
        val images = ok.categories.getValue("gaming").post!!.images!!
        assertTrue(images.ogFirst)
        assertTrue(images.fromCluster)
        assertEquals(false, images.probe)
        assertEquals(500, images.minWidth)
        assertEquals(listOf("default-og.png"), images.ignore)

        assertThrows<IllegalArgumentException> { load(yaml("post:\n  images:\n    minWidth: 500")) }
        assertThrows<IllegalArgumentException> { load(yaml("enableImages: true\npost:\n  images:\n    timeoutSeconds: 10\n    maxTotalSeconds: 5")) }
    }

    @Test
    fun `crossLinks block is parsed and needs known categories with event vectors`() {
        val sd = "semanticDedup:\n  eventEnabled: true"
        val ok = load(
            yaml("$sd\npost:\n  crossLinks:\n    categories: [politics]\n    minSimilarity: 0.85\n    logOnly: true")
                .replace("    channelId: \"@politics\"\n", "    channelId: \"@politics\"\n    $sd\n".replace("\n  eventEnabled", "\n      eventEnabled"))
        )
        val c = ok.categories.getValue("gaming").post!!.crossLinks!!
        assertEquals(listOf("politics"), c.categories)
        assertEquals(0.85, c.minSimilarity)
        assertEquals(3L, c.maxAgeDays)
        assertTrue(c.logOnly)

        // politics has no semanticDedup.eventEnabled
        assertThrows<IllegalArgumentException> { load(yaml("$sd\npost:\n  crossLinks:\n    categories: [politics]")) }
        assertThrows<IllegalArgumentException> { load(yaml("$sd\npost:\n  crossLinks:\n    categories: [nope]")) }
        assertThrows<IllegalArgumentException> { load(yaml("$sd\npost:\n  crossLinks:\n    categories: []")) }
        assertThrows<IllegalArgumentException> { load(yaml("$sd\npost:\n  crossLinks:\n    categories: [gaming]")) }
    }

    @Test
    fun `longForm telegraph block and the global telegraph account are parsed and validated`() {
        val config = load(yaml("post:\n  longForm:\n    telegraph:\n      mode: full") + "telegraph:\n  authorName: \"Ігри\"\n  timeoutSeconds: 5\n")
        val t = config.categories.getValue("gaming").post!!.longForm!!.telegraph!!
        assertTrue(t.enabled)
        assertEquals("full", t.mode)
        assertEquals("Ігри", config.telegraph.authorName)
        assertEquals(5L, config.telegraph.timeoutSeconds)
        assertEquals("RssNewsBot", config.telegraph.shortName)
        assertEquals("teaser", load(yaml("post:\n  longForm:\n    telegraph: {}")).categories.getValue("gaming").post!!.longForm!!.telegraph!!.mode)

        assertThrows<IllegalArgumentException> { load(yaml("post:\n  longForm:\n    telegraph:\n      mode: page")) }
        assertThrows<IllegalArgumentException> { load(yaml("") + "telegraph:\n  timeoutSeconds: 0\n") }
        assertThrows<IllegalArgumentException> { load(yaml("") + "telegraph:\n  authorUrl: \"t.me/x\"\n") }
    }
}
