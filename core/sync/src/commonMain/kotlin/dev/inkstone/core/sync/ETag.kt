package dev.inkstone.core.sync

/**
 * SPEC §7.3 — ETag handling, and the one that causes a permanent failure loop.
 *
 * > ⚠️ **Weak ETags:** behind a gzip-compressing proxy, servers may emit `W/"..."`.
 * > Returning one in `If-Match` causes a **permanent 412 loop**. Strip `W/` and quotes
 * > on read; if you had to strip `W/`, treat the ETag as advisory and compare
 * > checksums instead of using `If-Match`.
 *
 * That is why this is a type and not a `String`. A bare string cannot carry
 * "…and this one was weak", so the fact would have to be recomputed at the call
 * site — which is exactly the sort of thing that gets recomputed in three of four
 * places and produces a device that can never upload again and never says why.
 *
 * Stored **unquoted** (SPEC §7.3's table) and re-quoted only when it goes back out.
 */
public data class ETag(
    /** The opaque value, with `W/` and the surrounding quotes removed. */
    val value: String,
    /**
     * True when the server sent `W/"..."`.
     *
     * A weak validator says "semantically equivalent", not "byte-identical", so it is
     * not usable as an `If-Match` precondition — see [asIfMatch].
     */
    val weak: Boolean,
) {

    /**
     * The header value for `If-Match`, or **null when this ETag must not be used as one**.
     *
     * Null for a weak ETag. The caller then falls back to comparing `OC-Checksum`
     * (SPEC §7.3), which is a real comparison rather than a precondition the server
     * will refuse forever.
     */
    public val asIfMatch: String?
        get() = if (weak) null else "\"$value\""

    override fun toString(): String = if (weak) "W/\"$value\"" else "\"$value\""

    public companion object {

        /** Parse a `getetag` property or an `ETag`/`OC-ETag` response header. Null for blank. */
        public fun parse(raw: String?): ETag? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val weak = trimmed.startsWith("W/", ignoreCase = true)
            val body = if (weak) trimmed.substring(2).trim() else trimmed
            val unquoted = body.removeSurrounding("\"")
            return if (unquoted.isEmpty()) null else ETag(unquoted, weak)
        }
    }
}
