package dev.inkstone.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicrosoftRedirectTest {

    @Test
    fun `the redirect is the package and the url-encoded base64 of the certificate hash`() {
        // 20 bytes, chosen so the base64 contains '+', '/' and the '=' padding — the three characters
        // that must be percent-encoded, and that a hand-built redirect gets wrong.
        val hash = byteArrayOf(
            0xFB.toByte(), 0xFF.toByte(), 0xBE.toByte(), 0x3E, 0x3F, 0x7B, 0x01, 0x02, 0x03, 0x04,
            0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E,
        )

        val uri = MicrosoftRedirect.uriFor("dev.inkstone", hash)

        assertEquals("msauth://dev.inkstone/%2B%2F%2B%2BPj97AQIDBAUGBwgJCgsMDQ4%3D", uri)
        assertFalse(uri.substringAfterLast('/').any { it == '+' || it == '=' || it == '/' })
    }

    @Test
    fun `sha1 of a known input matches the published vector`() {
        // FIPS 180 test vector: SHA-1("abc") = a9993e364706816aba3e25717850c26c9cd0d89d.
        val digest = MicrosoftRedirect.sha1("abc".encodeToByteArray())

        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", digest.joinToString("") { "%02x".format(it) })
        assertTrue(digest.size == 20)
    }
}
