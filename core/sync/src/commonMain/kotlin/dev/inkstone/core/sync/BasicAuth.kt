// Base64's group is three bytes to four characters, and the four shift amounts ARE
// RFC 4648 §4's structure. Naming each one would be four constants that say "position 3",
// "position 2" and so on, which is less checkable against the standard, not more — the
// same argument Md5.kt makes for its constants.
@file:Suppress("MagicNumber")

package dev.inkstone.core.sync

/**
 * `Authorization: Basic …`, built by hand rather than with Ktor's `Auth` plugin.
 *
 * ## Why not the plugin
 *
 * Ktor's `basic` provider is a **challenge-response** provider: it lets the first
 * request go out unauthenticated, reads the `WWW-Authenticate` on the 401, and
 * repeats the request with credentials. Against Nextcloud that is one deliberate 401
 * per call, into brute-force protection that is on by default (SPEC §7.2) and shared
 * by every device behind the same NAT. `sendWithoutRequest` suppresses the first
 * half of that, but the plugin still owns the retry decision — and CLAUDE.md rule 5
 * says that decision is not the HTTP library's to make.
 *
 * So the header is attached explicitly, once, and a 401 comes back to the caller as
 * [DavFailure.Unauthorized] with nothing sent after it.
 *
 * ## Base64 by hand
 *
 * Twenty lines rather than an opt-in to an experimental stdlib API in the one module
 * that has to keep linking for iOS. It is also the whole of the encoder this project
 * needs — there is no decoder here, because nothing decodes an app password.
 */
internal object BasicAuth {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val BYTE_MASK = 0xFF
    private const val SEXTET_MASK = 0x3F
    private const val BITS_PER_BYTE = 8
    private const val BITS_PER_SEXTET = 6
    private const val GROUP_BYTES = 3

    /** Base64 encodes three bytes as four characters. */
    private const val GROUP_CHARS = 4

    /** The full header value, `Basic <base64(loginName:appPassword)>`. */
    fun header(loginName: String, appPassword: String): String =
        "Basic " + encode("$loginName:$appPassword".encodeToByteArray())

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return buildString((bytes.size + GROUP_BYTES - 1) / GROUP_BYTES * GROUP_CHARS) {
            var index = 0
            while (index < bytes.size) {
                val remaining = bytes.size - index
                val b0 = bytes[index].toInt() and BYTE_MASK
                val b1 = if (remaining > 1) bytes[index + 1].toInt() and BYTE_MASK else 0
                val b2 = if (remaining > 2) bytes[index + 2].toInt() and BYTE_MASK else 0
                val triple = (b0 shl (BITS_PER_BYTE * 2)) or (b1 shl BITS_PER_BYTE) or b2

                append(ALPHABET[(triple shr (BITS_PER_SEXTET * 3)) and SEXTET_MASK])
                append(ALPHABET[(triple shr (BITS_PER_SEXTET * 2)) and SEXTET_MASK])
                append(if (remaining > 1) ALPHABET[(triple shr BITS_PER_SEXTET) and SEXTET_MASK] else '=')
                append(if (remaining > 2) ALPHABET[triple and SEXTET_MASK] else '=')
                index += GROUP_BYTES
            }
        }
    }
}
