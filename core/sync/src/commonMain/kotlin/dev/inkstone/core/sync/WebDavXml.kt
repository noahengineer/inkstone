package dev.inkstone.core.sync

/**
 * SPEC §7.3 — the PROPFIND body, and reading the `multistatus` that comes back.
 *
 * The body below is the spec's, **verbatim and complete**, including the properties
 * later sections need (`oc:id` for identity, `oc:checksums` for the weak-ETag
 * fallback, `nc:lock*` for a 423, `nc:data-fingerprint` for "the admin restored a
 * backup"). Asking for them all in one request costs nothing and means M6's
 * reconciler does not need a second round trip per file.
 */
public object WebDavXml {

    public const val DAV: String = "DAV:"
    public const val OWNCLOUD: String = "http://owncloud.org/ns"
    public const val NEXTCLOUD: String = "http://nextcloud.org/ns"

    /** SPEC §7.3, verbatim. Do not trim this to "what M5 uses" — see the object KDoc. */
    public val PROPFIND_BODY: String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <d:propfind xmlns:d="DAV:"
                    xmlns:oc="http://owncloud.org/ns"
                    xmlns:nc="http://nextcloud.org/ns">
          <d:prop>
            <d:getetag/><d:getlastmodified/><d:getcontentlength/>
            <d:resourcetype/>
            <oc:id/><oc:fileid/><oc:permissions/><oc:size/><oc:checksums/>
            <nc:data-fingerprint/>
            <nc:lock/><nc:lock-owner-displayname/>
          </d:prop>
        </d:propfind>
    """.trimIndent()

    /**
     * SPEC §7.9 — quota. A separate, minimal PROPFIND because it is asked for rarely
     * and only about one collection.
     */
    public val QUOTA_BODY: String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <d:propfind xmlns:d="DAV:">
          <d:prop>
            <d:quota-available-bytes/><d:quota-used-bytes/>
          </d:prop>
        </d:propfind>
    """.trimIndent()

    /**
     * Parse a `multistatus` document into one [DavResource] per `response`.
     *
     * Only properties inside a `propstat` whose `status` is 2xx are kept. A server
     * reports a property it does not have as a 404 propstat alongside the 200 one,
     * and treating those as present is how `oc:id` ends up being the empty string on
     * an instance that happens not to expose it.
     *
     * @param uid the account's WebDAV path segment, for turning `href` into a path.
     */
    public fun parseMultistatus(body: String, uid: String): List<DavResource> {
        val root = XmlReader.parse(body)
        if (root.namespace != DAV || root.localName != "multistatus") {
            throw XmlParseException("expected {DAV:}multistatus, got {${root.namespace}}${root.localName}")
        }
        return root.childrenNamed(DAV, "response").mapNotNull { response ->
            val href = response.textOf(DAV, "href") ?: return@mapNotNull null
            val path = NextcloudUrls.pathFromHref(href, uid) ?: return@mapNotNull null
            val properties = okProperties(response)
            DavResource(
                path = path,
                href = href,
                isCollection = properties.resourceTypeIsCollection,
                etag = ETag.parse(properties[DAV to "getetag"]),
                lastModified = properties[DAV to "getlastmodified"],
                contentLength = properties[DAV to "getcontentlength"]?.toLongOrNull(),
                // SPEC §7.3: "`remote_binding.remote_file_id` stores `oc:id`, not `oc:fileid`."
                ocId = properties[OWNCLOUD to "id"],
                fileId = properties[OWNCLOUD to "fileid"],
                permissions = properties[OWNCLOUD to "permissions"],
                recursiveSize = properties[OWNCLOUD to "size"]?.toLongOrNull(),
                checksums = properties.checksums,
                dataFingerprint = properties[NEXTCLOUD to "data-fingerprint"],
                lockOwner = properties[NEXTCLOUD to "lock-owner-displayname"],
                locked = properties[NEXTCLOUD to "lock"] == "1",
                quotaAvailableBytes = properties[DAV to "quota-available-bytes"]?.toLongOrNull(),
            )
        }
    }

    private fun okProperties(response: XmlElement): OkProperties {
        val values = HashMap<Pair<String, String>, String>()
        val checksums = ArrayList<String>()
        var collection = false

        // Only the 2xx propstats. A server reports a property it does not have as a 404
        // propstat alongside the 200 one, and reading both is how `oc:id` ends up being
        // the empty string on an instance that happens not to expose a field.
        val properties = response.childrenNamed(DAV, "propstat")
            .filter { statusIsOk(it.textOf(DAV, "status").orEmpty()) }
            .flatMap { it.child(DAV, "prop")?.children.orEmpty() }

        for (property in properties) {
            when {
                property.namespace == DAV && property.localName == "resourcetype" ->
                    collection = property.has(DAV, "collection")

                property.namespace == OWNCLOUD && property.localName == "checksums" ->
                    property.children.forEach { checksums += it.text }

                else -> values[property.namespace to property.localName] = property.text
            }
        }
        return OkProperties(values, collection, checksums)
    }

    /**
     * `HTTP/1.1 200 OK` → true.
     *
     * Parsed rather than string-matched on `"200 OK"`: a `207` inside a propstat is
     * legal, and so is `HTTP/1.0`.
     */
    internal fun statusIsOk(statusLine: String): Boolean {
        val code = statusLine.split(' ').firstNotNullOfOrNull { it.toIntOrNull() } ?: return false
        return code in OK_FLOOR until REDIRECT_FLOOR
    }

    private class OkProperties(
        private val values: Map<Pair<String, String>, String>,
        val resourceTypeIsCollection: Boolean,
        val checksums: List<String>,
    ) {
        operator fun get(key: Pair<String, String>): String? = values[key]?.takeIf { it.isNotEmpty() }
    }

    private const val OK_FLOOR = 200
    private const val REDIRECT_FLOOR = 300
}

/**
 * One `d:response` from a PROPFIND, in the terms the rest of the app uses.
 *
 * @property path relative to the account's DAV root, no leading or trailing slash.
 *   `""` is the root itself.
 * @property ocId SPEC §7.3 — *"use `oc:id` everywhere"*. Globally unique
 *   (`<padded-fileid><instanceid>`), which is what makes a second Nextcloud instance
 *   survivable. **Not** `oc:fileid`.
 * @property etag already normalised — see [ETag] for the weak-validator trap.
 * @property recursiveSize `oc:size`, the recursive folder size. `contentLength` is
 *   files only, which is why both are here.
 * @property quotaAvailableBytes ⚠️ SPEC §7.9 sentinels: `-1` uncomputed, `-2` unknown,
 *   `-3` unlimited. Use [availableBytesOrNull] rather than rendering this directly.
 */
public data class DavResource(
    val path: String,
    val href: String,
    val isCollection: Boolean,
    val etag: ETag?,
    val lastModified: String?,
    val contentLength: Long?,
    val ocId: String?,
    val fileId: String?,
    val permissions: String?,
    val recursiveSize: Long?,
    val checksums: List<String>,
    val dataFingerprint: String?,
    val lockOwner: String?,
    val locked: Boolean,
    val quotaAvailableBytes: Long?,
) {

    /** The last path segment — what the folder browser shows. Empty for the root. */
    public val name: String get() = path.substringAfterLast('/')

    /** SPEC §7.3 — *"check `W`/`N`/`V`/`D`/`CK` before offering the action."* */
    public fun mayWrite(): Boolean = permissions?.contains('W') ?: true

    public fun mayDelete(): Boolean = permissions?.contains('D') ?: true

    public fun mayCreateFile(): Boolean = permissions?.contains('C') ?: true

    public fun mayCreateFolder(): Boolean = permissions?.contains('K') ?: true

    /** `md5:<hex>` and friends, as a map keyed by algorithm. */
    public fun checksum(algorithm: String): String? = checksums
        .firstOrNull { it.startsWith("$algorithm:", ignoreCase = true) }
        ?.substringAfter(':')

    /**
     * Free space in bytes, or null when the server did not say — **including** when it
     * said "unlimited".
     *
     * SPEC §7.9: `-1` uncomputed, `-2` unknown, `-3` unlimited. Rendering the raw value
     * gives the user "−3 MB left".
     */
    public fun availableBytesOrNull(): Long? = quotaAvailableBytes?.takeIf { it >= 0 }

    public val quotaIsUnlimited: Boolean get() = quotaAvailableBytes == UNLIMITED

    private companion object {
        const val UNLIMITED = -3L
    }
}
