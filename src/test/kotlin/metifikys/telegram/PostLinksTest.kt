package metifikys.telegram

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostLinksTest {

    @Test
    fun `c links map to -100 chat ids, query and thread ignored`() {
        assertEquals(
            listOf(PostRef(-1009876543210, null, 15205)),
            PostLinks.parse("https://t.me/c/9876543210/15205")
        )
        assertEquals(
            listOf(PostRef(-1009876543210, null, 15205), PostRef(-1009876543210, null, 15206)),
            PostLinks.parse("глянь t.me/c/9876543210/15205?single і http://telegram.me/c/9876543210/77/15206#x")
        )
    }

    @Test
    fun `username links keep the username, web preview and www forms too`() {
        assertEquals(
            listOf(PostRef(null, "my_gaming_channel", 42), PostRef(null, "Tech_News", 7)),
            PostLinks.parse("https://t.me/my_gaming_channel/42?comment=3\nhttps://www.t.me/s/Tech_News/7")
        )
    }

    @Test
    fun `junk is not a post link`() {
        assertEquals(emptyList(), PostLinks.parse(null))
        assertEquals(emptyList(), PostLinks.parse("no links here, /status"))
        assertEquals(emptyList(), PostLinks.parse("https://abt.me/channel/12"), "another host ending in t.me")
        assertEquals(emptyList(), PostLinks.parse("https://t.me/joinchat/123"))
        assertEquals(emptyList(), PostLinks.parse("https://t.me/c/abc/1"))
        assertEquals(emptyList(), PostLinks.parse("https://t.me/channel_name"), "no message id")
        assertEquals(emptyList(), PostLinks.parse("https://t.me/abc/12"), "usernames are 4+ chars here")
        assertEquals(emptyList(), PostLinks.parse("https://t.me/channel/12abc"))
        assertEquals(emptyList(), PostLinks.parse("https://example.com/t.me/c/1/2"))
    }

    @Test
    fun `duplicates collapse and at most five links are taken`() {
        val text = (1..8).joinToString(" ") { "https://t.me/c/100/$it" } + " https://t.me/c/100/1"
        val refs = PostLinks.parse(text)
        assertEquals(PostLinks.MAX_LINKS, refs.size)
        assertEquals((1L..5L).toList(), refs.map { it.messageId })
    }

    @Test
    fun `a forwarded channel post is a ref, a plain message is not`() {
        val plain = IncomingMessage(1, 7, "private", 7, 10, 0, "hi")
        assertNull(PostLinks.forwarded(plain))
        val fwd = plain.copy(forwardChatId = -1009876543210, forwardMessageId = 15205)
        assertEquals(PostRef(-1009876543210, null, 15205), PostLinks.forwarded(fwd))
        assertTrue(PostRef(-1009876543210, null, 15205).display().endsWith("c/9876543210/15205"))
        assertEquals("t.me/x_chan/3", PostRef(null, "x_chan", 3).display())
    }
}
