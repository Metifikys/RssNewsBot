package metifikys.story

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttributionTest {

    private fun credited(text: String, own: String = "example.com") = Attribution.creditedSource(text, own)?.source

    @Test
    fun `english attribution phrases name the outlet`() {
        assertEquals("Insider Gaming", credited("Silksong delayed again, via Insider Gaming"))
        assertEquals("Bloomberg", credited("Ubisoft cuts 200 jobs, according to Bloomberg"))
        assertEquals("Bloomberg", credited("Studio closes according to a new report from Bloomberg"))
        assertEquals("Kotaku", credited("The leak was first reported by Kotaku earlier today"))
        assertEquals("VGC", credited("The listing, as spotted by VGC, shows a date"))
        assertEquals("The Verge", credited("Price hike per a report from The Verge"))
        assertEquals("insider-gaming.com", credited("Details via insider-gaming.com"))
    }

    @Test
    fun `title case headlines lose the trailing noise word`() {
        assertEquals("Insider Gaming", credited("GTA 6 Delayed Again, According To Insider Gaming Report"))
    }

    @Test
    fun `ukrainian attribution phrases name the outlet`() {
        assertEquals("Reuters", credited("Microsoft звільняє 650 людей, як повідомляє Reuters"))
        assertEquals("IGN", credited("Реліз перенесли — з посиланням на IGN"))
        assertEquals("Bloomberg", credited("Про це пише Bloomberg"))
        assertEquals("The Information", credited("За даними The Information, OpenAI готує пристрій"))
    }

    @Test
    fun `phrases that name nobody are no attribution`() {
        assertNull(credited("The game is available via the Steam store"))
        assertNull(credited("according to the company, sales are up"))
        assertNull(credited("за даними аналітиків продажі зросли"))
        assertNull(credited("A trivia Night event"))
        assertNull(credited("According to sources familiar with the matter"))
    }

    @Test
    fun `a mention of the article's own outlet does not count`() {
        assertNull(credited("According to IGN, the game is out", own = "ign.com"))
        assertNull(credited("as spotted by PC Gamer", own = "www.pcgamer.com"))
        assertNull(credited("via The Verge", own = "theverge.com"))
        assertEquals("IGN", credited("According to IGN, the game is out", own = "uk.gamespot.com"))
        assertTrue(Attribution.isOwn(Attribution.Mention("Eurogamer"), "eurogamer.net"))
    }

    @Test
    fun `domain core skips subdomains and two-level tlds`() {
        assertEquals("ign", Attribution.domainCore("uk.ign.com"))
        assertEquals("theguardian", Attribution.domainCore("www.theguardian.co.uk"))
        assertEquals("insidergaming", Attribution.domainCore("insider-gaming.com"))
        assertEquals("insidergaming", Attribution.keyOf("insider-gaming.com"))
        assertEquals("verge", Attribution.keyOf("The Verge"))
    }
}
