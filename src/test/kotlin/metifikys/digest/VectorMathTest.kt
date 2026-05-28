package metifikys.digest

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorMathTest {

    private fun nearlyEquals(a: Double, b: Double, eps: Double = 1e-6): Boolean = abs(a - b) <= eps
    private fun nearlyEquals(a: Float, b: Float, eps: Float = 1e-6f): Boolean = abs(a - b) <= eps

    @Test
    fun `encode then decode round-trips a vector`() {
        val v = floatArrayOf(0.1f, -0.5f, 1.234f, 0f, -123.456f)

        val bytes = VectorMath.encode(v)
        val back = VectorMath.decode(bytes)

        assertEquals(v.size, back.size)
        assertEquals(v.size * 4, bytes.size)
        for (i in v.indices) {
            assertTrue(nearlyEquals(v[i], back[i]), "index $i: expected ${v[i]} got ${back[i]}")
        }
    }

    @Test
    fun `decode rejects byte arrays that are not multiples of 4`() {
        val bad = ByteArray(7)
        try {
            VectorMath.decode(bad)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `cosine of identical vectors is 1`() {
        val v = floatArrayOf(0.3f, 0.4f, 0.5f)
        assertTrue(nearlyEquals(VectorMath.cosine(v, v), 1.0))
    }

    @Test
    fun `cosine of opposite vectors is -1`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(-1f, -2f, -3f)
        assertTrue(nearlyEquals(VectorMath.cosine(a, b), -1.0))
    }

    @Test
    fun `cosine of orthogonal vectors is 0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertTrue(nearlyEquals(VectorMath.cosine(a, b), 0.0))
    }

    @Test
    fun `cosine returns 0 for zero vector inputs without throwing`() {
        val zero = floatArrayOf(0f, 0f, 0f)
        val nonZero = floatArrayOf(1f, 1f, 1f)
        assertEquals(0.0, VectorMath.cosine(zero, nonZero))
        assertEquals(0.0, VectorMath.cosine(zero, zero))
    }

    @Test
    fun `l2Normalize produces unit-length vector`() {
        val v = floatArrayOf(3f, 4f) // length 5
        val n = VectorMath.l2Normalize(v)
        var sumSq = 0.0
        for (x in n) sumSq += x.toDouble() * x.toDouble()
        assertTrue(nearlyEquals(sumSq, 1.0, eps = 1e-5))
    }

    @Test
    fun `l2Normalize leaves zero vector unchanged`() {
        val zero = floatArrayOf(0f, 0f, 0f)
        val out = VectorMath.l2Normalize(zero)
        assertEquals(3, out.size)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `cosine after L2-normalize equals raw cosine`() {
        val a = floatArrayOf(1.5f, -2.3f, 4.1f, 0.9f)
        val b = floatArrayOf(0.3f, 0.7f, -1.1f, 2.5f)
        val raw = VectorMath.cosine(a, b)
        val normalized = VectorMath.cosine(VectorMath.l2Normalize(a), VectorMath.l2Normalize(b))
        assertTrue(nearlyEquals(raw, normalized, eps = 1e-5),
            "raw=$raw normalized=$normalized")
    }

    @Test
    fun `cosine throws on size mismatch`() {
        val a = floatArrayOf(1f, 2f)
        val b = floatArrayOf(1f, 2f, 3f)
        try {
            VectorMath.cosine(a, b)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // ok
        }
    }
}
