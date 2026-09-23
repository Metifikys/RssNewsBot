package metifikys.fetch

import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageProbeTest {

    private fun png(w: Int, h: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Test
    fun `png and jpeg sizes come from the header`() {
        assertEquals(1200 to 630, ImageProbe.dimensionsOf(png(1200, 630)))
        val jpg = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        assertEquals(320 to 200, ImageProbe.dimensionsOf(jpg.copyOf(minOf(jpg.size, 2048))))
        assertNull(ImageProbe.dimensionsOf("not an image".toByteArray()))
    }

    @Test
    fun `webp sizes are read from the RIFF header`() {
        fun riff(chunk: String, body: ByteArray): ByteArray {
            val head = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + chunk.toByteArray() + ByteArray(4)
            return (head + body).copyOf(64)
        }
        // VP8X: 24-bit (width-1), (height-1) at offset 24 / 27.
        val vp8x = riff("VP8X", byteArrayOf(0, 0, 0, 0, (1199 and 0xff).toByte(), (1199 shr 8).toByte(), 0, (674 and 0xff).toByte(), (674 shr 8).toByte(), 0))
        assertEquals(1200 to 675, ImageProbe.webp(vp8x))
        // VP8 (lossy): frame tag + start code, then 14-bit width / height at offset 26 / 28.
        val vp8 = riff("VP8 ", byteArrayOf(0, 0, 0, 0x9d.toByte(), 0x01, 0x2a, (640 and 0xff).toByte(), (640 shr 8).toByte(), (360 and 0xff).toByte(), (360 shr 8).toByte()))
        assertEquals(640 to 360, ImageProbe.webp(vp8))
        assertNull(ImageProbe.webp(png(10, 10)))
    }

    @Test
    fun `dimensions asks for a byte range and reads the size`() {
        val body = png(800, 450)
        var range: String? = null
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/img.png") { ex ->
            range = ex.requestHeaders.getFirst("Range")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val probe = ImageProbe(UrlGuard.OFF)
            assertEquals(800 to 450, probe.dimensions("http://localhost:${server.address.port}/img.png"))
            assertEquals("bytes=0-65535", range)
            assertNull(probe.dimensions("http://localhost:${server.address.port}/missing.png"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `the guard refuses a private address`() {
        assertNull(ImageProbe().dimensions("http://127.0.0.1:9/img.png"))
    }

    @Test
    fun `isPostable wants a 2xx image within the size cap`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        fun serve(path: String, type: String, length: Long) = server.createContext(path) { ex ->
            ex.responseHeaders.add("Content-Type", type)
            ex.responseHeaders.add("Content-Length", length.toString())
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        serve("/ok.jpg", "image/jpeg", 200_000)
        serve("/big.jpg", "image/jpeg", 6_000_000)
        serve("/page", "text/html; charset=utf-8", 1000)
        server.start()
        try {
            val probe = ImageProbe(UrlGuard.OFF)
            val base = "http://localhost:${server.address.port}"
            kotlin.test.assertTrue(probe.isPostable("$base/ok.jpg", 5_000_000))
            kotlin.test.assertFalse(probe.isPostable("$base/big.jpg", 5_000_000))
            kotlin.test.assertFalse(probe.isPostable("$base/page", 5_000_000))
            kotlin.test.assertFalse(probe.isPostable("$base/missing.jpg", 5_000_000))
            kotlin.test.assertFalse(ImageProbe().isPostable("$base/ok.jpg", 5_000_000))
        } finally {
            server.stop(0)
        }
    }
}
