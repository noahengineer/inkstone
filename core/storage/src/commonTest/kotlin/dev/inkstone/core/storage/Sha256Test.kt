package dev.inkstone.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SHA-256 against the published vectors.
 *
 * Worth the file on its own: SPEC §5.6 uses this hash to decide whether a note
 * opens Normally or in Recovered mode, and a wrong digest fails in whichever
 * direction is worse — a false mismatch throws away a good note's op history, a
 * false match folds a damaged bundle as if it were whole. A hand-written hash
 * needs vectors, not a review.
 */
class Sha256Test {

    /** FIPS 180-2 Appendix B.1 and B.2, and the standard empty-input vector. */
    @Test
    fun `matches the published vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ByteArray(0).sha256Hex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "abc".encodeToByteArray().sha256Hex(),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray().sha256Hex(),
        )
    }

    /**
     * A million 'a' characters — the vector that exercises multi-block padding and
     * the length field, which is where a hand-written implementation goes wrong.
     */
    @Test
    fun `matches the long vector`() {
        val input = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", input.sha256Hex())
    }

    /**
     * Every length across a block boundary. Padding has three cases — room for the
     * length, exactly no room, and a whole extra block — and the boundaries between
     * them are 55, 56 and 64 bytes.
     */
    @Test
    fun `is stable across the padding boundaries`() {
        val digests = (50..70).map { length -> ByteArray(length) { 'x'.code.toByte() }.sha256Hex() }
        assertEquals(digests.size, digests.toSet().size, "two different lengths hashed the same")
        assertEquals(64, digests.first().length)
    }
}
