package dev.inkstone.core.sync

import io.ktor.http.encodeURLPathPart

/**
 * Every URL this module builds, in one place — because two of them are the project's
 * most likely bug.
 *
 * ## The UID, not the login name (SPEC §0.1 #3, §7.1 rule 6)
 *
 * [davBase] takes a **uid**. Login Flow v2 hands back a `loginName` that may be an
 * email address, and on an instance where it is, `/remote.php/dav/files/<loginName>/`
 * 404s. The WebDAV path segment is `ocs.data.id` from `/ocs/v2.php/cloud/user`.
 *
 * The function signature is the guard: it names its parameter `uid` and there is no
 * overload that takes a credential, so a caller cannot pass the wrong half of one
 * without noticing what it is called.
 *
 * ## Never hardcode the login-flow URLs (SPEC §7.1 rule 1)
 *
 * There is deliberately **no** `pollUrl()` here. The server builds the poll endpoint
 * with `linkToRouteAbsolute`, so `/index.php` may or may not be in it — the client
 * POSTs to `poll.endpoint` **verbatim**. [loginFlowStart] is the one login URL that
 * is legitimately constructed, because it is the entry point.
 */
public object NextcloudUrls {

    /** SPEC §7.3 — "no v2 exists. Don't use legacy `/remote.php/webdav`." */
    private const val DAV_FILES = "remote.php/dav/files"

    public const val OCS_USER: String = "ocs/v2.php/cloud/user"
    public const val OCS_CAPABILITIES: String = "ocs/v2.php/cloud/capabilities"
    public const val OCS_APP_PASSWORD: String = "ocs/v2.php/core/apppassword"

    /**
     * Trim a server URL the user typed into one that can be joined to.
     *
     * Adds `https://` when no scheme is given — SPEC §14 is HTTPS-only, so guessing
     * `http` would be guessing wrong on purpose — and drops any trailing slash so
     * every join below is unambiguous.
     */
    public fun normaliseServer(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed

            // Kept, not rewritten. `http://` is refused by [isSecure] with an explanation,
            // which is more useful than silently upgrading and failing to connect.
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed

            else -> "https://$trimmed"
        }
    }

    /** SPEC §14 — HTTPS only, no cleartext exception, and no "accept anyway". */
    public fun isSecure(serverUrl: String): Boolean = serverUrl.startsWith("https://", ignoreCase = true)

    public fun join(serverUrl: String, path: String): String = serverUrl.trimEnd('/') + "/" + path.trimStart('/')

    public fun loginFlowStart(serverUrl: String): String = join(serverUrl, "index.php/login/v2")

    public fun ocsUser(serverUrl: String): String = join(serverUrl, OCS_USER)

    public fun ocsCapabilities(serverUrl: String): String = join(serverUrl, OCS_CAPABILITIES)

    public fun ocsAppPassword(serverUrl: String): String = join(serverUrl, OCS_APP_PASSWORD)

    /**
     * `<server>/remote.php/dav/files/<uid>/` — the one and only WebDAV base (SPEC §7.3).
     *
     * @param uid `ocs.data.id`, **not** `loginName`. See the class KDoc.
     */
    public fun davBase(serverUrl: String, uid: String): String =
        join(serverUrl, "$DAV_FILES/${uid.encodeURLPathPart()}") + "/"

    /**
     * An absolute WebDAV URL for a remote path like `Notes/Physics/Lecture.pdf`.
     *
     * Each segment is encoded separately so a `#` or a space in a folder name becomes
     * part of the path rather than a fragment — and so a `/` the caller put in a
     * *segment* would be visible as an extra level rather than silently encoded away.
     * [dev.inkstone.core.model.RemoteName] has already removed those (SPEC §7.8); this
     * is the second line.
     */
    public fun davUrl(serverUrl: String, uid: String, remotePath: String): String =
        davBase(serverUrl, uid) + encodePath(remotePath)

    /** Percent-encode a `/`-separated path, segment by segment. Empty segments are dropped. */
    public fun encodePath(remotePath: String): String = remotePath
        .split('/')
        .filter { it.isNotEmpty() }
        .joinToString("/") { it.encodeURLPathPart() }

    /**
     * The path part of an `href` a server returned, relative to [uid]'s DAV root.
     *
     * Servers answer PROPFIND with an absolute *path* (`/remote.php/dav/files/bob/Notes/`)
     * and some proxies return a full URL. Both are handled, both are decoded, and the
     * trailing slash a collection carries is dropped so a path compares equal to the one
     * that was asked for.
     */
    public fun pathFromHref(href: String, uid: String): String? {
        val withoutOrigin = href.substringAfter("://").let { rest ->
            if (rest === href) href else "/" + rest.substringAfter('/', "")
        }
        val marker = "/$DAV_FILES/${uid.encodeURLPathPart()}"
        val start = withoutOrigin.indexOf(marker)
        if (start < 0) return null
        val tail = withoutOrigin.substring(start + marker.length)
        return decodePath(tail).trim('/')
    }

    /** Percent-decoding, UTF-8 aware. Ktor's decoder is on the URL types, not on a bare string. */
    public fun decodePath(encoded: String): String {
        if ('%' !in encoded && '+' !in encoded) return encoded
        val bytes = ArrayList<Byte>(encoded.length)
        var index = 0
        while (index < encoded.length) {
            val character = encoded[index]
            when {
                character == '%' && index + HEX_DIGITS < encoded.length -> {
                    val hex = encoded.substring(index + 1, index + ESCAPE_LENGTH).toIntOrNull(HEX_RADIX)
                    if (hex == null) {
                        bytes += character.code.toByte()
                        index++
                    } else {
                        bytes += hex.toByte()
                        index += ESCAPE_LENGTH
                    }
                }

                else -> {
                    // Not `+` → space: that is form encoding, and a literal plus is legal
                    // in a Nextcloud filename. Decoding it would rename the user's file.
                    for (byte in character.toString().encodeToByteArray()) bytes += byte
                    index++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private const val HEX_RADIX = 16

    /** `%` plus two hex digits — the whole of a percent-escape. */
    private const val ESCAPE_LENGTH = 3
    private const val HEX_DIGITS = 2
}
