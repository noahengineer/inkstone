// A hash's constants, rotations and shifts ARE the specification (FIPS 180-4 §4.2.2,
// §6.2.2). Naming them would add sixty-four constants and make the implementation
// harder to check against the standard, which is the opposite of what the rule is for.
@file:Suppress("MagicNumber")

package dev.inkstone.core.storage

/**
 * SHA-256 (FIPS 180-4), in common Kotlin.
 *
 * ## Why hand-written
 *
 * SPEC §5.6 makes `bundleSha256` part of the PDF's identity marker: it is what
 * distinguishes *"our file, intact"* from *"our file, mangled"*, and getting it
 * wrong in either direction is a data-loss bug — a false mismatch throws a good
 * note into Recovered mode and loses its op history, a false match opens a
 * damaged bundle as if it were whole.
 *
 * So it has to be computed identically on every platform, and there is no common
 * Kotlin digest. `java.security.MessageDigest` is JVM-only and would kill the
 * iPad path (CLAUDE.md rule 2 — the same mistake OkHttp and dav4jvm represent);
 * `CommonCrypto` is the iOS half of an `expect`/`actual` pair whose two halves
 * could drift. SHA-256 is sixty lines and a published test vector, so there is
 * one implementation and one set of vectors proving it.
 *
 * Not a general-purpose crypto primitive: nothing here authenticates anything.
 * It is a content fingerprint.
 */
internal object Sha256 {

    /**
     * FIPS 180-4 §4.2.2 — the first 32 bits of the fractional parts of the cube
     * roots of the first 64 primes. Written unsigned and converted, so each one
     * is checkable character-for-character against the standard.
     */
    private val K = intArrayOf(
        // `u.toInt()` rather than a signed literal: the standard prints these as
        // unsigned hex, and a table transcribed into two's-complement negatives is a
        // table nobody can check against the standard.
        0x428a2f98u.toInt(), 0x71374491u.toInt(), 0xb5c0fbcfu.toInt(), 0xe9b5dba5u.toInt(),
        0x3956c25bu.toInt(), 0x59f111f1u.toInt(), 0x923f82a4u.toInt(), 0xab1c5ed5u.toInt(),
        0xd807aa98u.toInt(), 0x12835b01u.toInt(), 0x243185beu.toInt(), 0x550c7dc3u.toInt(),
        0x72be5d74u.toInt(), 0x80deb1feu.toInt(), 0x9bdc06a7u.toInt(), 0xc19bf174u.toInt(),
        0xe49b69c1u.toInt(), 0xefbe4786u.toInt(), 0x0fc19dc6u.toInt(), 0x240ca1ccu.toInt(),
        0x2de92c6fu.toInt(), 0x4a7484aau.toInt(), 0x5cb0a9dcu.toInt(), 0x76f988dau.toInt(),
        0x983e5152u.toInt(), 0xa831c66du.toInt(), 0xb00327c8u.toInt(), 0xbf597fc7u.toInt(),
        0xc6e00bf3u.toInt(), 0xd5a79147u.toInt(), 0x06ca6351u.toInt(), 0x14292967u.toInt(),
        0x27b70a85u.toInt(), 0x2e1b2138u.toInt(), 0x4d2c6dfcu.toInt(), 0x53380d13u.toInt(),
        0x650a7354u.toInt(), 0x766a0abbu.toInt(), 0x81c2c92eu.toInt(), 0x92722c85u.toInt(),
        0xa2bfe8a1u.toInt(), 0xa81a664bu.toInt(), 0xc24b8b70u.toInt(), 0xc76c51a3u.toInt(),
        0xd192e819u.toInt(), 0xd6990624u.toInt(), 0xf40e3585u.toInt(), 0x106aa070u.toInt(),
        0x19a4c116u.toInt(), 0x1e376c08u.toInt(), 0x2748774cu.toInt(), 0x34b0bcb5u.toInt(),
        0x391c0cb3u.toInt(), 0x4ed8aa4au.toInt(), 0x5b9cca4fu.toInt(), 0x682e6ff3u.toInt(),
        0x748f82eeu.toInt(), 0x78a5636fu.toInt(), 0x84c87814u.toInt(), 0x8cc70208u.toInt(),
        0x90befffau.toInt(), 0xa4506cebu.toInt(), 0xbef9a3f7u.toInt(), 0xc67178f2u.toInt(),
    )

    /** FIPS 180-4 §5.3.3 — fractional parts of the square roots of the first eight primes. */
    private val INITIAL = intArrayOf(
        0x6a09e667u.toInt(),
        0xbb67ae85u.toInt(),
        0x3c6ef372u.toInt(),
        0xa54ff53au.toInt(),
        0x510e527fu.toInt(),
        0x9b05688cu.toInt(),
        0x1f83d9abu.toInt(),
        0x5be0cd19u.toInt(),
    )

    private const val BLOCK_BYTES = 64

    /** The digest of [bytes] as 64 lowercase hex characters — the form SPEC §5.6 stores. */
    fun hex(bytes: ByteArray): String {
        val digest = of(bytes)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"

    fun of(bytes: ByteArray): ByteArray {
        val state = INITIAL.copyOf()
        val padded = pad(bytes)
        val schedule = IntArray(64)

        var offset = 0
        while (offset < padded.size) {
            for (index in 0 until 16) {
                schedule[index] = (padded[offset + index * 4].toInt() and 0xFF shl 24) or
                    (padded[offset + index * 4 + 1].toInt() and 0xFF shl 16) or
                    (padded[offset + index * 4 + 2].toInt() and 0xFF shl 8) or
                    (padded[offset + index * 4 + 3].toInt() and 0xFF)
            }
            for (index in 16 until 64) {
                val s0 = schedule[index - 15].rotr(7) xor schedule[index - 15].rotr(18) xor
                    (schedule[index - 15] ushr 3)
                val s1 = schedule[index - 2].rotr(17) xor schedule[index - 2].rotr(19) xor
                    (schedule[index - 2] ushr 10)
                schedule[index] = schedule[index - 16] + s0 + schedule[index - 7] + s1
            }
            compress(state, schedule)
            offset += BLOCK_BYTES
        }

        val out = ByteArray(32)
        for (index in state.indices) {
            out[index * 4] = (state[index] ushr 24).toByte()
            out[index * 4 + 1] = (state[index] ushr 16).toByte()
            out[index * 4 + 2] = (state[index] ushr 8).toByte()
            out[index * 4 + 3] = state[index].toByte()
        }
        return out
    }

    private fun compress(state: IntArray, schedule: IntArray) {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]

        for (index in 0 until 64) {
            val s1 = e.rotr(6) xor e.rotr(11) xor e.rotr(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + K[index] + schedule[index]
            val s0 = a.rotr(2) xor a.rotr(13) xor a.rotr(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj

            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    /** Append `0x80`, zeros to 56 mod 64, then the length in bits as a big-endian u64. */
    private fun pad(bytes: ByteArray): ByteArray {
        val remainder = (bytes.size + 9) % BLOCK_BYTES
        val padding = if (remainder == 0) 0 else BLOCK_BYTES - remainder
        val out = ByteArray(bytes.size + 9 + padding)
        bytes.copyInto(out)
        out[bytes.size] = 0x80.toByte()
        val bits = bytes.size.toLong() * 8
        for (index in 0 until 8) {
            out[out.size - 1 - index] = (bits ushr (index * 8)).toByte()
        }
        return out
    }

    private fun Int.rotr(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))
}

/**
 * The digest of these bytes, as 64 lowercase hex characters.
 *
 * The public face of [Sha256]: `:android:pdf` needs it for SPEC §5.6's
 * `bundleSha256` and `originalSha256`, and one extension is a smaller surface than
 * an object other modules can reach into.
 */
public fun ByteArray.sha256Hex(): String = Sha256.hex(this)
