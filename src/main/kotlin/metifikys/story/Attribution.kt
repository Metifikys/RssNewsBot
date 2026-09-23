package metifikys.story

/**
 * "This article retells someone else's report": attribution phrases in a title or teaser
 * (`via Insider Gaming`, `according to Bloomberg`, `як повідомляє Reuters`, `з посиланням на
 * IGN`) and the outlet they name. Pure, deterministic. Misses are expected and harmless — an
 * unrecognised retelling only counts as one more original source.
 *
 * The named outlet must look like a name (capitalised words, up to four, or a domain):
 * "according to the company" / "за даними аналітиків" name nobody. A mention of the article's own
 * outlet ("according to IGN" in an IGN piece) is no attribution.
 */
object Attribution {

    /** A phrase that credits [source] (as written). */
    data class Mention(val source: String) {
        /** Comparison key: lowercase letters and digits only, a leading "the" dropped. */
        val key: String get() = keyOf(source)
    }

    private const val WORD = """[\p{Lu}\d][\p{L}\d'’&.\-]*"""
    private const val NAME = """((?:$WORD)(?:\s+$WORD){0,3}|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,})"""

    /** Phrase alternatives, case-insensitive; the name after them is case-sensitive on purpose. */
    private val PHRASES = listOf(
        """via""",
        """according\s+to(?:\s+an?\s+(?:new\s+)?report\s+(?:from|by|in))?""",
        """(?:first\s+)?reported\s+by""",
        """(?:as\s+)?spotted\s+by""",
        """per\s+an?\s+(?:new\s+)?report\s+(?:from|by|in)""",
        """як\s+повідомля(?:є|ють)""",
        """за\s+даними""",
        """пише""",
        """з\s+посиланням\s+на"""
    )

    private val PATTERN = Regex("""(?<![\p{L}\d])(?iu:${PHRASES.joinToString("|")})\s+(?:the\s+)?$NAME""")

    /**
     * Keys that follow these phrases without naming an outlet: pronouns, anonymous sources, and
     * platforms ("available via Steam", "via Twitter") — a store or a social network is not a newsroom.
     */
    private val NOT_A_SOURCE = setOf(
        "a", "an", "this", "that", "its", "his", "her", "their", "our", "we", "sources", "source", "insiders",
        "steam", "twitter", "x", "youtube", "reddit", "discord", "instagram", "tiktok", "bluesky", "threads",
        "facebook", "telegram", "email", "press", "pressrelease"
    )

    /** Every attribution in [text], in order. */
    fun mentions(text: String): List<Mention> =
        PATTERN.findAll(text).mapNotNull { m ->
            val name = trimTrailingNoise(m.groupValues[1].trimEnd('.', '-', '\'', '’'))
            if (name.isEmpty() || keyOf(name) in NOT_A_SOURCE || keyOf(name).isEmpty()) null else Mention(name)
        }.toList()

    /** Title-case headlines run on after the name: "…According To Insider Gaming Report" → "Insider Gaming". */
    private val TRAILING_NOISE = setOf("report", "reports", "says", "claims", "leak", "leaks", "article", "sources")

    private fun trimTrailingNoise(name: String): String {
        val words = name.split(Regex("""\s+""")).toMutableList()
        while (words.size > 1 && words.last().lowercase() in TRAILING_NOISE) words.removeAt(words.size - 1)
        return words.joinToString(" ")
    }

    /**
     * The outlet [text] credits that is not [ownDomain] itself (first one), or null when the text
     * credits nobody else.
     */
    fun creditedSource(text: String, ownDomain: String): Mention? =
        mentions(text).firstOrNull { !isOwn(it, ownDomain) }

    /** True when [mention] names the outlet behind [domain] (`PC Gamer` ↔ `pcgamer.com`, `The Verge` ↔ `theverge.com`). */
    fun isOwn(mention: Mention, domain: String): Boolean {
        val core = domainCore(domain)
        val key = mention.key
        if (core.isEmpty() || key.isEmpty()) return false
        if (key == core || keyOf(mention.source.substringBefore('.')) == core) return true
        return (key.length >= 4 && core.contains(key)) || (core.length >= 4 && key.contains(core))
    }

    fun keyOf(name: String): String {
        val lower = name.lowercase().trim()
        val noThe = if (lower.startsWith("the ")) lower.removePrefix("the ") else lower
        // A domain-looking name compares by its registrable label: "insider-gaming.com" → "insidergaming".
        val base = if (Regex("""^[a-z0-9-]+(\.[a-z0-9-]+)*\.[a-z]{2,}$""").matches(noThe)) domainCore(noThe) else noThe
        return base.filter { it.isLetterOrDigit() }
    }

    /** `www.insider-gaming.com` → `insidergaming`, `uk.ign.com` → `ign`: the label before the TLD. */
    fun domainCore(domain: String): String {
        val labels = domain.lowercase().removePrefix("www.").split('.').filter { it.isNotEmpty() }
        val label = when {
            labels.size >= 3 && labels[labels.size - 2].length <= 3 && labels.last().length == 2 -> labels[labels.size - 3] // co.uk, com.ua
            labels.size >= 2 -> labels[labels.size - 2]
            else -> labels.firstOrNull() ?: ""
        }
        return label.filter { it.isLetterOrDigit() }
    }
}
