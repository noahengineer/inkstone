// A hash's constants, rotations and shifts ARE the specification (RFC 1321 §3.4).
// Naming them would add sixty-eight constants and make the implementation harder to
// check against the standard, which is the opposite of what the rule is for.
@file:Suppress("MagicNumber")

package dev.inkstone.core.sync

/**
 * MD5 (RFC 1321), in common Kotlin — for **one header and one purpose**.
 *
 * SPEC §7.5 puts `OC-Checksum: md5:<hex>` on every PUT. Nextcloud verifies it, which
 * turns a truncated upload into a 400 rather than a note that opens to half a page. The
 * algorithm is not this project's choice: it is what Nextcloud's default
 * `checksums.supportedTypes` accepts, and sending an algorithm the server does not know
 * gets the header ignored.
 *
 * ## This is not a security primitive and must never become one
 *
 * MD5 is broken for anything adversarial. It is used here as a **transport integrity
 * check against accidental corruption**, agreed with the server, and nothing else. The
 * project's actual content fingerprint is SHA-256 (`:core:storage`, SPEC §5.6) and stays
 * that way.
 *
 * Hand-written for the same reason SHA-256 is: `java.security.MessageDigest` is JVM-only
 * and would kill the iPad path exactly as OkHttp would (CLAUDE.md rule 2).
 */
internal object Md5 {

    /** RFC 1321 §3.4 — `T[i] = floor(2^32 × abs(sin(i)))`, transcribed as unsigned hex. */
    private val T = intArrayOf(
        0xd76aa478u.toInt(), 0xe8c7b756u.toInt(), 0x242070dbu.toInt(), 0xc1bdceeeu.toInt(),
        0xf57c0fafu.toInt(), 0x4787c62au.toInt(), 0xa8304613u.toInt(), 0xfd469501u.toInt(),
        0x698098d8u.toInt(), 0x8b44f7afu.toInt(), 0xffff5bb1u.toInt(), 0x895cd7beu.toInt(),
        0x6b901122u.toInt(), 0xfd987193u.toInt(), 0xa679438eu.toInt(), 0x49b40821u.toInt(),
        0xf61e2562u.toInt(), 0xc040b340u.toInt(), 0x265e5a51u.toInt(), 0xe9b6c7aau.toInt(),
        0xd62f105du.toInt(), 0x02441453u.toInt(), 0xd8a1e681u.toInt(), 0xe7d3fbc8u.toInt(),
        0x21e1cde6u.toInt(), 0xc33707d6u.toInt(), 0xf4d50d87u.toInt(), 0x455a14edu.toInt(),
        0xa9e3e905u.toInt(), 0xfcefa3f8u.toInt(), 0x676f02d9u.toInt(), 0x8d2a4c8au.toInt(),
        0xfffa3942u.toInt(), 0x8771f681u.toInt(), 0x6d9d6122u.toInt(), 0xfde5380cu.toInt(),
        0xa4beea44u.toInt(), 0x4bdecfa9u.toInt(), 0xf6bb4b60u.toInt(), 0xbebfbc70u.toInt(),
        0x289b7ec6u.toInt(), 0xeaa127fau.toInt(), 0xd4ef3085u.toInt(), 0x04881d05u.toInt(),
        0xd9d4d039u.toInt(), 0xe6db99e5u.toInt(), 0x1fa27cf8u.toInt(), 0xc4ac5665u.toInt(),
        0xf4292244u.toInt(), 0x432aff97u.toInt(), 0xab9423a7u.toInt(), 0xfc93a039u.toInt(),
        0x655b59c3u.toInt(), 0x8f0ccc92u.toInt(), 0xffeff47du.toInt(), 0x85845dd1u.toInt(),
        0x6fa87e4fu.toInt(), 0xfe2ce6e0u.toInt(), 0xa3014314u.toInt(), 0x4e0811a1u.toInt(),
        0xf7537e82u.toInt(), 0xbd3af235u.toInt(), 0x2ad7d2bbu.toInt(), 0xeb86d391u.toInt(),
    )

    /** RFC 1321 §3.4 — the per-round left-rotation amounts. */
    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    private const val HEX = "0123456789abcdef"

    fun hex(bytes: ByteArray): String {
        val digest = digest(bytes)
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX[value shr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    // The four-branch round function is RFC 1321's structure. Splitting it into four
    // methods would make it harder, not easier, to check against §3.4.
    private fun digest(message: ByteArray): ByteArray {
        var a0 = 0x67452301
        var b0 = 0xefcdab89u.toInt()
        var c0 = 0x98badcfeu.toInt()
        var d0 = 0x10325476

        val padded = pad(message)
        val block = IntArray(16)

        var offset = 0
        while (offset < padded.size) {
            for (index in 0 until 16) {
                val base = offset + index * 4
                // Little-endian, unlike SHA-256. RFC 1321 §3.4.
                block[index] = (padded[base].toInt() and 0xFF) or
                    ((padded[base + 1].toInt() and 0xFF) shl 8) or
                    ((padded[base + 2].toInt() and 0xFF) shl 16) or
                    ((padded[base + 3].toInt() and 0xFF) shl 24)
            }

            var a = a0
            var b = b0
            var c = c0
            var d = d0

            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when (i / 16) {
                    0 -> {
                        f = (b and c) or (b.inv() and d)
                        g = i
                    }

                    1 -> {
                        f = (d and b) or (d.inv() and c)
                        g = (5 * i + 1) % 16
                    }

                    2 -> {
                        f = b xor c xor d
                        g = (3 * i + 5) % 16
                    }

                    else -> {
                        f = c xor (b or d.inv())
                        g = (7 * i) % 16
                    }
                }
                val rotated = (a + f + T[i] + block[g]).rotl(S[i])
                a = d
                d = c
                c = b
                b += rotated
            }

            a0 += a
            b0 += b
            c0 += c
            d0 += d
            offset += 64
        }

        return wordsToBytes(a0, b0, c0, d0)
    }

    /** RFC 1321 §3.1–3.2 — 0x80, zeroes, then the **little-endian** 64-bit bit length. */
    private fun pad(message: ByteArray): ByteArray {
        val bitLength = message.size.toLong() * 8
        val paddedLength = ((message.size + 8) / 64 + 1) * 64
        val padded = ByteArray(paddedLength)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (index in 0 until 8) {
            padded[paddedLength - 8 + index] = ((bitLength ushr (8 * index)) and 0xFF).toByte()
        }
        return padded
    }

    // Named `rotl` rather than `rotateLeft` so it cannot be confused with — or silently
    // shadow — the stdlib extension of that name.
    private fun Int.rotl(bits: Int): Int = (this shl bits) or (this ushr (32 - bits))

    private fun wordsToBytes(vararg words: Int): ByteArray {
        val out = ByteArray(words.size * 4)
        for ((index, word) in words.withIndex()) {
            for (byte in 0 until 4) {
                out[index * 4 + byte] = ((word ushr (8 * byte)) and 0xFF).toByte()
            }
        }
        return out
    }
}
