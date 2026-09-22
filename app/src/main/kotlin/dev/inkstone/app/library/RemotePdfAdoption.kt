package dev.inkstone.app.library

import dev.inkstone.android.pdf.PdfOpenResult
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.sync.DavResource

/**
 * What happened when a PDF picked off the user's Nextcloud was brought in
 * ([LibraryRepository.adoptRemotePdf]).
 *
 * Five answers rather than a nullable id, because four of them are things the user has to be
 * *told* and only one of them is a note to open. SPEC §5.6's whole point is that a PDF's identity
 * is a decision the file makes, so "import a PDF" has never been one outcome here.
 */
sealed interface RemotePdfAdoption {

    /** The note, or null when there is nothing to open. */
    val noteId: DocumentId?

    /** Imported and bound to the file it came from. Ink goes back onto that path. */
    data class Adopted(override val noteId: DocumentId, val remotePath: String, val result: PdfOpenResult.Opened) :
        RemotePdfAdoption

    /**
     * Imported, but this device already syncs that note somewhere — so the existing binding was
     * left exactly as it was. Opening it is right; re-pointing it is not (see the method's KDoc).
     */
    data class AlreadySynced(override val noteId: DocumentId, val result: PdfOpenResult.Opened) : RemotePdfAdoption

    /**
     * SPEC §5.6 point 4 — the file was ours and its bundle did not survive. The note is here and
     * the losses have to be reported *before* anything is published back over the file they were
     * recovered from, so nothing was bound.
     */
    data class Recovered(override val noteId: DocumentId, val result: PdfOpenResult.Opened) : RemotePdfAdoption

    /** Imported, but the account went away between the download and the bind. Nothing is owed. */
    data class Unbound(override val noteId: DocumentId, val result: PdfOpenResult.Opened) : RemotePdfAdoption

    /** SPEC §5.8 — a newer schema, or a PDF with nothing in it. Not opened, and not re-saved. */
    data class Refused(val result: PdfOpenResult) : RemotePdfAdoption {
        override val noteId: DocumentId? get() = null
    }
}

/**
 * ⭐ Whether a remote file may be *written on*, decided before a byte is downloaded.
 *
 * A pure function over the one listing the browser already has, so it can be tested at all —
 * `LibraryRepository` cannot be constructed in a JVM test (its `PdfGateway` needs `androidx.ink`'s
 * native code), which is CLAUDE.md's standing instruction to hoist the *decision* out where a test
 * can reach it.
 *
 * ⚠️ [DavResource.mayWrite] is SPEC §7.3's `oc:permissions` check, and this is the one feature in
 * the app that genuinely needs it: everything else this app uploads is a file it created, in a
 * folder the user chose. Here the user is pointing at somebody else's shared handout as often as
 * their own, and a read-only one cannot be annotated **in place** — the PUT would 403 forever, park
 * for a day, and the only symptom would be a note that quietly never syncs. Importing it as an
 * ordinary local note is the honest answer, and saying so is the rest of it.
 */
object RemotePdfPick {

    /** What picking [file] should do. */
    fun decide(file: DavResource): Decision = when {
        file.isCollection -> Decision.NotAFile
        !file.name.endsWith(PDF_EXTENSION, ignoreCase = true) -> Decision.NotAPdf
        !file.mayWrite() -> Decision.ImportReadOnly
        else -> Decision.AdoptInPlace
    }

    /**
     * The note's title: the filename without its extension.
     *
     * Not the whole path, and not a name the user types — this is the same derivation
     * `Reconciler.titleOf` uses for a peer's note, so a file that arrives both ways is called the
     * same thing either way. ⚠️ The extension is stripped case-insensitively; `Lecture.PDF`
     * otherwise becomes a note called `Lecture.PDF` whose remote path would gain a second `.pdf`
     * the first time it is renamed.
     */
    fun titleOf(file: DavResource): String {
        val name = file.name
        val bare = if (name.endsWith(PDF_EXTENSION, ignoreCase = true)) {
            name.dropLast(PDF_EXTENSION.length)
        } else {
            name
        }
        return bare.trim().ifEmpty { name }
    }

    /**
     * The PDFs in one `Depth: 1` listing, in the order the picker shows them.
     *
     * ⚠️ **The collection's own entry is in that listing**, and dropping it here is free because a
     * collection is not a PDF — but the sibling bug is worth naming, since M12 shipped it in a
     * second WebDAV client: matching the self-entry *by name* fails at the account root, where the
     * name is the uid and matches no child. `!isCollection` cannot have that bug.
     *
     * Sorted by name, case-folded, so `Appendix.pdf` and `appendix B.pdf` sit together rather than
     * being separated by every capital letter in the folder.
     */
    fun pdfsIn(resources: List<DavResource>): List<DavResource> = resources
        .filter { decide(it) == Decision.AdoptInPlace || decide(it) == Decision.ImportReadOnly }
        .sortedBy { it.name.lowercase() }

    sealed interface Decision {
        /** Download it and bind the note to this very file. */
        data object AdoptInPlace : Decision

        /** Download it, but as an ordinary local note — the server copy may not be written to. */
        data object ImportReadOnly : Decision

        data object NotAPdf : Decision

        data object NotAFile : Decision
    }

    private const val PDF_EXTENSION = ".pdf"
}
