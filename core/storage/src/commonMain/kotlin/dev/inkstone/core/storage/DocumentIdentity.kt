package dev.inkstone.core.storage

import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId

/**
 * SPEC §5.6 ⭐ — **document identity and the three-way open decision.**
 *
 * ## The problem this solves
 *
 * *"A PDF with ink annotations but no embedded bundle"* also describes any PDF
 * somebody marked up in Acrobat. Without a marker the app cannot tell **our file,
 * mangled** from **a stranger's file** — and the two need opposite treatment. Open
 * a stranger's PDF as Recovered and you have silently claimed their document under
 * one of your document ids. Open your own mangled PDF as Foreign and you have
 * silently forked the note away from the copy on your other tablet.
 *
 * ## The marker
 *
 * Three properties in the PDF's XMP metadata (§5.6). XMP rather than `/Info`
 * because it survives more tools: it is a stream in the document catalog that
 * most editors copy through, where `/Info` is routinely rewritten.
 *
 * [bundleSha256] is what makes "mangled" detectable at all. A marker alone says
 * *this was ours*; the hash says *and the bundle inside it is still the one we
 * wrote*. A third-party editor that saves a full rewrite typically keeps the
 * embedded file but drops or rebuilds it — and a bundle that no longer hashes to
 * what the marker claims is not a bundle to fold a note from.
 *
 * The detection logic lives here, platform-free and unit-tested, because it is a
 * *policy* and not a PDF operation. `:android:pdf` extracts the three inputs; this
 * decides what they mean.
 */
public data class InkstoneMarker(
    val documentId: DocumentId,
    val schemaVersion: Int,
    val bundleSha256: String,
    val originalLength: Long = 0L,
    val originalSha256: String = "",
) {

    /**
     * True when this marker records where the file's base ends — which is every
     * file M4 or later published, imported or generated. False means a marker
     * written before these fields existed, or a file that was rewritten.
     */
    public val hasOriginal: Boolean get() = originalLength > 0L

    public companion object {
        /** The XMP namespace URI these properties live in. Versioned, and never reused. */
        public const val NAMESPACE: String = "https://inkstone.dev/ns/pdf/1.0/"

        public const val PREFIX: String = "inkstone"

        public const val DOCUMENT_ID: String = "documentId"
        public const val SCHEMA_VERSION: String = "schemaVersion"
        public const val BUNDLE_SHA256: String = "bundleSha256"

        /**
         * **Added in M4; SPEC §5.6 lists three properties and now lists five.**
         *
         * How many bytes at the front of the published file are the **base** —
         * the imported PDF, or the pages Inkstone generated for a note created
         * from scratch, which SPEC §5.2 says to *"treat as the original"* — and
         * what those bytes hash to. Both are load-bearing:
         *
         * - **[ORIGINAL_LENGTH] is what makes the file stop growing.** Every
         *   materialization appends an incremental update. Append the next one on
         *   top of the last and a note re-published a hundred times carries a
         *   hundred update sections. Knowing where the original ends lets each
         *   materialization truncate back to it and append exactly one — so the
         *   published file is *original + one update*, always, however many
         *   editing sessions it has seen.
         * - **[ORIGINAL_SHA256] is what makes the contract checkable.** ROADMAP M4
         *   requires the original bytes to be *recoverable byte-identically after
         *   any number of edit cycles*. Without a hash of them that claim can only
         *   be asserted; with one, `PdfSource.verifyOriginal` proves it — and
         *   Recovered mode can report [RecoveryLoss.PRISTINE_ORIGINAL] as a fact
         *   rather than a guess, which matters precisely because the bundle it
         *   would otherwise have consulted is the thing that went missing.
         *
         * Both are absent (0 / empty) only in a marker written before they
         * existed. A file that carries a marker without them is rebased on its
         * whole current length, which is safe — it never truncates into the
         * middle of an object — and costs one extra update section, once.
         */
        public const val ORIGINAL_LENGTH: String = "originalLength"

        public const val ORIGINAL_SHA256: String = "originalSha256"
    }
}

/**
 * SPEC §5.6's table, as a type.
 *
 * | Marker | Bundle | Mode |
 * |---|---|---|
 * | ✅ | ✅, sha matches | [Normal] |
 * | ✅ | ❌ or sha mismatch | [Recovered] |
 * | ❌ | — | [Foreign] |
 *
 * [TooNew] is a fourth case, **added in M4**, and SPEC §5.6 has been amended.
 * The table above assumes every marker this build finds is one it understands,
 * and a marker carrying a `schemaVersion` from the future is not: recovering such
 * a file would rewrite a newer format's document with this build's vocabulary,
 * under the original `documentId`, with a Lamport counter deliberately bumped
 * past everything — i.e. it would win the next sync and take the newer data with
 * it. SPEC §5.8 already forbids re-saving a file from the future; this is the
 * same rule reaching the one path that was about to break it.
 */
public sealed interface PdfOpenMode {

    /** Marker and bundle both present and consistent. Full fidelity. */
    public data class Normal(val marker: InkstoneMarker) : PdfOpenMode

    /**
     * Ours, but the bundle is gone or does not verify.
     *
     * Reconstruct from `/Ink` annotations, **replacing** the previous op log —
     * never unioning with it. See [RecoveredImport].
     */
    public data class Recovered(
        val marker: InkstoneMarker,
        val reason: RecoveryReason,
        val losses: Set<RecoveryLoss>,
    ) : PdfOpenMode

    /** No marker. Not ours. Import as a new document with a new [DocumentId]. */
    public data object Foreign : PdfOpenMode

    /** Ours, written by a newer build. Do not open, and above all do not re-save. */
    public data class TooNew(val marker: InkstoneMarker, val understoodVersion: Int) : PdfOpenMode
}

/** Why a file that carries our marker could not be opened normally. */
public enum class RecoveryReason {

    /** The `/Names /EmbeddedFiles` entry is not there. Something stripped it. */
    BUNDLE_MISSING,

    /** The bundle is there but does not hash to what the marker claims. */
    BUNDLE_HASH_MISMATCH,

    /** The bundle is there and hashes correctly, but is not a readable archive. */
    BUNDLE_UNREADABLE,
}

/**
 * SPEC §5.6 point 4 — *"warn explicitly, and say what was lost"*.
 *
 * A recovery that says "recovered" and nothing else is worse than useless: the
 * user keeps writing, the note looks fine, and the missing fidelity surfaces
 * weeks later as "my pen strokes all got fatter". Each entry here is a concrete
 * sentence the UI can show.
 */
public enum class RecoveryLoss {

    /** `/AP` is a filled path, not a stroke. Reconstruction gets uniform width. */
    PRESSURE_AND_WIDTH,

    /** The op log is gone; the recovered page is one `AddObject` per stroke. */
    OP_HISTORY,

    /** Undo/redo are local and per-device (SPEC §4.6), and were in the bundle's sibling file. */
    UNDO_STACK,

    /**
     * The file was rewritten rather than incrementally updated, so the original
     * imported PDF's bytes are no longer at the front of it (SPEC §5.2).
     */
    PRISTINE_ORIGINAL,

    /**
     * The page used the flattened overlay (SPEC §6.4, >500 strokes), so there are
     * no per-stroke annotations to reconstruct — only a flat image.
     */
    FLATTENED_PAGE,

    /**
     * At least one stroke or page had no recoverable identity, so it cannot merge
     * onto the peer's copy and will appear twice if the peer still holds it.
     *
     * The one loss the user has to act on rather than accept — see
     * [RecoveredNote.unidentifiedStrokes].
     */
    UNIDENTIFIED_OBJECTS,
}

/**
 * The three-way decision. `:android:pdf` supplies the observations; this is what
 * they mean.
 *
 * @param marker the XMP marker, or null when the PDF carries none.
 * @param bundle the bytes of the embedded `note.inkstone`, or null when the
 *   `/Names /EmbeddedFiles` entry is absent.
 * @param bundleIsReadable whether those bytes actually open as a bundle. Passed in
 *   rather than computed, because opening one is suspending I/O and this is not.
 * @param originalBytesIntact whether the published file still begins with the
 *   imported PDF verbatim (SPEC §5.2). Only consulted to describe the losses.
 */
public fun detectOpenMode(
    marker: InkstoneMarker?,
    bundle: ByteArray?,
    bundleIsReadable: Boolean = bundle != null,
    originalBytesIntact: Boolean = true,
    hadFlattenedPage: Boolean = false,
    understoodVersion: Int = Document.SCHEMA_VERSION,
): PdfOpenMode {
    if (marker == null) return PdfOpenMode.Foreign
    if (marker.schemaVersion > understoodVersion) return PdfOpenMode.TooNew(marker, understoodVersion)

    val reason = when {
        bundle == null -> RecoveryReason.BUNDLE_MISSING
        Sha256.hex(bundle) != marker.bundleSha256 -> RecoveryReason.BUNDLE_HASH_MISMATCH
        !bundleIsReadable -> RecoveryReason.BUNDLE_UNREADABLE
        else -> return PdfOpenMode.Normal(marker)
    }

    val losses = buildSet {
        add(RecoveryLoss.PRESSURE_AND_WIDTH)
        add(RecoveryLoss.OP_HISTORY)
        add(RecoveryLoss.UNDO_STACK)
        if (!originalBytesIntact) add(RecoveryLoss.PRISTINE_ORIGINAL)
        if (hadFlattenedPage) add(RecoveryLoss.FLATTENED_PAGE)
    }
    return PdfOpenMode.Recovered(marker, reason, losses)
}

/**
 * Writes and reads the XMP packet SPEC §5.6 specifies.
 *
 * ## Why this is string handling and not an XMP library
 *
 * Because it has to be. `xmpbox` is not bundled with PdfBox-Android (found in
 * M4 — SPEC §6.3's table implies otherwise), there is no KMP XMP library, and
 * `:core:storage` may not take a JVM-only one anyway (CLAUDE.md rule 2). More to
 * the point, the packet has exactly three properties in one namespace, all of
 * them values this app produces, so the write is a template and the read is a
 * search for three attribute names.
 *
 * The read is deliberately tolerant in one direction: XMP allows a property to
 * appear either as an attribute on `rdf:Description` or as a child element, and
 * a tool that rewrites metadata may convert between the two. [parse] accepts
 * both, so an otherwise-intact file is not thrown into Recovered mode by a
 * serialisation choice some other editor made.
 */
public object InkstoneXmp {

    /**
     * The packet, ready to be a `/Metadata` stream.
     *
     * `xpacket` wrapper included: it is what makes the block findable by tools
     * that scan a file for XMP rather than walking the object graph, which is
     * most of the ones that would strip a bundle in the first place.
     */
    public fun packet(marker: InkstoneMarker): String = buildString {
        append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Inkstone\">\n")
        append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append("  <rdf:Description rdf:about=\"\"\n")
        append("    xmlns:${InkstoneMarker.PREFIX}=\"${InkstoneMarker.NAMESPACE}\"\n")
        append("    ${qualified(InkstoneMarker.DOCUMENT_ID)}=\"${escape(marker.documentId.value)}\"\n")
        append("    ${qualified(InkstoneMarker.SCHEMA_VERSION)}=\"${marker.schemaVersion}\"\n")
        append("    ${qualified(InkstoneMarker.BUNDLE_SHA256)}=\"${escape(marker.bundleSha256)}\"\n")
        append("    ${qualified(InkstoneMarker.ORIGINAL_LENGTH)}=\"${marker.originalLength}\"\n")
        append("    ${qualified(InkstoneMarker.ORIGINAL_SHA256)}=\"${escape(marker.originalSha256)}\"/>\n")
        append(" </rdf:RDF>\n")
        append("</x:xmpmeta>\n")
        // Trailing whitespace is conventional: it lets a tool rewrite the packet
        // in place without moving every byte after it.
        append("<?xpacket end=\"w\"?>")
    }

    /** @return the marker this packet carries, or null when it carries none of ours. */
    public fun parse(xmp: String?): InkstoneMarker? {
        if (xmp == null) return null
        // The namespace URI must be present. A file could otherwise be claimed by
        // any producer that happened to use the prefix "inkstone".
        if (!xmp.contains(InkstoneMarker.NAMESPACE)) return null
        val id = value(xmp, InkstoneMarker.DOCUMENT_ID) ?: return null
        val schema = value(xmp, InkstoneMarker.SCHEMA_VERSION)?.toIntOrNull() ?: return null
        val sha = value(xmp, InkstoneMarker.BUNDLE_SHA256).orEmpty()
        // Absent in a file written before these two existed, and absent by design
        // in a note created from scratch. Both defaults mean "no original to keep
        // verbatim", which is the safe reading either way: a materialization that
        // cannot find the original's extent rebases on the whole current file
        // rather than truncating into the middle of one.
        return InkstoneMarker(
            documentId = DocumentId(id),
            schemaVersion = schema,
            bundleSha256 = sha,
            originalLength = value(xmp, InkstoneMarker.ORIGINAL_LENGTH)?.toLongOrNull() ?: 0L,
            originalSha256 = value(xmp, InkstoneMarker.ORIGINAL_SHA256).orEmpty(),
        )
    }

    public fun parse(xmp: ByteArray?): InkstoneMarker? = parse(xmp?.decodeToString())

    private fun qualified(property: String) = "${InkstoneMarker.PREFIX}:$property"

    /** Attribute form first, then element form — see the class KDoc on why both. */
    private fun value(xmp: String, property: String): String? =
        attributeValue(xmp, property) ?: elementValue(xmp, property)

    private fun attributeValue(xmp: String, property: String): String? {
        val key = "${qualified(property)}=\""
        val start = xmp.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        val end = xmp.indexOf('"', from)
        return if (end < 0) null else unescape(xmp.substring(from, end))
    }

    private fun elementValue(xmp: String, property: String): String? {
        val open = "<${qualified(property)}>"
        val close = "</${qualified(property)}>"
        val start = xmp.indexOf(open)
        if (start < 0) return null
        val from = start + open.length
        val end = xmp.indexOf(close, from)
        return if (end < 0) null else unescape(xmp.substring(from, end).trim())
    }

    // The values are a UUIDv7 and a hex digest, so nothing here can actually need
    // escaping today. It is done anyway because the day a title or a device name
    // joins them is the day an unescaped `&` makes every note unreadable.
    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unescape(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&amp;", "&")
}
