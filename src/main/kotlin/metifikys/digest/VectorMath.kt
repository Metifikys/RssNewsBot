package metifikys.digest

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Encoding and similarity helpers for embedding vectors.
 *
 * Vectors are persisted as little-endian Float32 BLOBs (4 bytes per dim).
 * They are L2-normalized at encode time so cosine similarity reduces to a
 * plain dot product at query time, making the brute-force scan path cheap.
 */
object VectorMath {

    /** Returns a copy of [v] scaled so its L2 norm is 1.0. Zero-vectors are returned unchanged. */
    fun l2Normalize(v: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in v) sumSq += x.toDouble() * x.toDouble()
        val norm = sqrt(sumSq)
        if (norm == 0.0) return v.copyOf()
        val out = FloatArray(v.size)
        val invNorm = 1.0 / norm
        for (i in v.indices) out[i] = (v[i] * invNorm).toFloat()
        return out
    }

    /** Encodes a vector as little-endian Float32 bytes. Length is `v.size * 4`. */
    fun encode(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return buf.array()
    }

    /** Decodes a little-endian Float32 byte array back into a vector. */
    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % 4 == 0) { "Embedding blob length ${bytes.size} is not a multiple of 4" }
        val dim = bytes.size / 4
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(dim)
        for (i in 0 until dim) out[i] = buf.float
        return out
    }

    /**
     * Cosine similarity in [-1.0, 1.0]. Returns 0.0 if either vector is zero
     * (avoids NaN). Falls back to the full division — callers should still
     * normalize once at encode time to keep the scan path tight, but this
     * stays correct for un-normalized inputs.
     */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Vector size mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val ai = a[i].toDouble()
            val bi = b[i].toDouble()
            dot += ai * bi
            na += ai * ai
            nb += bi * bi
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }
}
