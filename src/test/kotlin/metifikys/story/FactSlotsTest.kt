package metifikys.story

import metifikys.story.FactSlots.Slot
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FactSlotsTest {

    private val ref = LocalDate.of(2026, 9, 23)

    private fun facts(text: String, slot: Slot? = null) =
        FactSlots.extract(text, ref).filter { slot == null || it.slot == slot }.map { "${it.unit}=${it.value}" }

    // Headlines below are from the prod DB (articles.title), trimmed where noted.

    @Test
    fun `prices with symbols, codes, thousands separators and multipliers`() {
        assertEquals(listOf("USD=60"), facts("Grab this 130W Ugreen power bank for \$60 and finally charge your laptop", Slot.PRICE))
        assertEquals(listOf("AUD=899"), facts("its Max sibling is a heck of a lot cheaper at just AU\$899 ", Slot.PRICE))
        assertEquals(
            listOf("USD=350", "USD=2000"),
            facts("built a Braille printer from a \$350 Lego robotics kit ... the cheapest commercial machine cost about \$2,000", Slot.PRICE)
        )
        assertEquals(listOf("USD=3500000000"), facts("Snorkel AI triples valuation to \$3.5B as demand for AI training data booms", Slot.PRICE))
        assertEquals(listOf("USD=77000000000"), facts("but Saddled With More Than \$77 Billion in Debt", Slot.PRICE))
        assertEquals(listOf("USD=131000000"), facts("DoorDash to pay \$131M for underpaying delivery workers", Slot.PRICE))
        assertEquals(listOf("EUR=30950"), facts("Від €30 950: Citroën вивів топові версії Outdoor для C3", Slot.PRICE))
        assertEquals(
            listOf("UAH=18885", "UAH=648.75"),
            facts("До 18 885 грн пенсії та 648,75 грн доплати: які виплати передбачені для УБД", Slot.PRICE)
        )
        assertEquals(listOf("UAH=1999"), facts("Нова гра коштує ₴1 999 у PS Store", Slot.PRICE))
        assertEquals(listOf("USD=69.99", "EUR=59.99"), facts("\$69.99 in the US, 59,99 € in Europe", Slot.PRICE))
        assertEquals(listOf("USD=70"), facts("The game will cost 70 USD", Slot.PRICE))
    }

    @Test
    fun `a lone letter before the dollar sign is no country prefix`() {
        assertEquals(listOf("USD=5"), facts("a \$5 fee", Slot.PRICE))
        assertEquals(listOf("CAD=90"), facts("costs CA\$90 up north", Slot.PRICE))
    }

    @Test
    fun `dates in English and Ukrainian, with and without a year`() {
        assertEquals(listOf("date=2026-10-01"), facts("Castlevania: Belmont's Curse demo launches on October 1, save data carries over", Slot.DATE))
        assertEquals(listOf("date=2026-10-01"), facts("Castlevania: Belmont's Curse demo releasing on October 1st", Slot.DATE))
        assertEquals(listOf("date=2026-10-15"), facts("Stepfun released Step 5 Preview 600B-A27B, will release weight on October 15", Slot.DATE))
        assertEquals(listOf("date=2026-10-01"), facts("Як звітувати при переході на єдиний податок з 1 жовтня 2026 року", Slot.DATE))
        assertEquals(listOf("date=2026-10-05"), facts("документи приймають до 5 жовтня", Slot.DATE))
        assertEquals(listOf("date=2027-Q1"), facts("Huawei plans Q1 2027 launch of new AI chip as it takes on Nvidia", Slot.DATE))
        assertEquals(listOf("date=2026-10-14"), facts("Out 2026-10-14 on PC", Slot.DATE))
        assertEquals(listOf("date=2026-11"), facts("Delayed to November 2026", Slot.DATE))
        assertEquals(listOf("date=2027-03"), facts("вихід перенесли на березень 2027", Slot.DATE))
        assertEquals(listOf("date=2026-12-12"), facts("launches 12th of December", Slot.DATE))
        assertEquals(listOf("date=2026-Q4"), facts("coming Q4'26", Slot.DATE))
    }

    @Test
    fun `a day without a year takes the year closest to the article date`() {
        // Published late in December, "January 15" means next January.
        assertEquals(listOf("2027-01-15"), FactSlots.extract("Out January 15", LocalDate.of(2026, 12, 20)).map { it.value })
        assertEquals(listOf("2026-03-05"), FactSlots.extract("Out March 5", LocalDate.of(2026, 5, 1)).map { it.value })
    }

    @Test
    fun `head counts next to a layoffs or staff keyword, percentages too`() {
        assertEquals(listOf("layoffs=300"), facts("Studio cuts 300 jobs after launch", Slot.COUNT))
        assertEquals(listOf("layoffs=1900"), facts("Microsoft lays off 1,900 in gaming division", Slot.COUNT))
        assertEquals(listOf("layoffs%=10"), facts("Embracer to lay off 10% of its workforce", Slot.COUNT))
        assertEquals(listOf("staff=200"), facts("a studio of 200 employees", Slot.COUNT))
        assertEquals(listOf("layoffs=120"), facts("Ubisoft звільнила 120 працівників", Slot.COUNT))
        // No keyword nearby, a year, a price or a model number is no head count.
        assertEquals(emptyList(), facts("Xbox is less than the initial 3,200 estimate", Slot.COUNT))
        assertEquals(emptyList(), facts("2025 layoffs continue", Slot.COUNT))
        assertEquals(emptyList(), facts("PS5 jobs board", Slot.COUNT))
    }

    @Test
    fun `a span is claimed by one slot only`() {
        val all = FactSlots.extract("Layoffs hit \$300 million studio on October 14", ref)
        assertEquals(listOf(Slot.PRICE, Slot.DATE), all.map { it.slot })
        assertTrue(all.none { it.slot == Slot.COUNT })
    }
}
