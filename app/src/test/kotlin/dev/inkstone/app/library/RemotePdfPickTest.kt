package dev.inkstone.app.library

import dev.inkstone.core.sync.DavResource
import dev.inkstone.core.sync.ETag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What happens when a remote file is tapped — decided before a byte is downloaded.
 *
 * ⚠️ **This is a pure function precisely so that there is something to test.**
 * `LibraryRepository` cannot be constructed in a JVM test at all: its `PdfGateway` needs
 * `androidx.ink`'s native code. CLAUDE.md's standing answer is to hoist the *decision* out into
 * plain data — so the branch that says *"a read-only file becomes an ordinary local note"* is
 * asserted here rather than being a claim nothing runs.
 *
 * The `oc:permissions` case is the one worth the file. A shared handout the user may read and not
 * write cannot be annotated in place; binding it anyway would 403 on every PUT, park the row for
 * a day, and present as a note that quietly never syncs — the failure mode this repo has met
 * under four other names.
 */
class RemotePdfPickTest {

    @Test
    fun `a writable pdf is adopted in place`() {
        assertEquals(RemotePdfPick.Decision.AdoptInPlace, RemotePdfPick.decide(file("Handout.pdf", "RGDNVW")))
    }

    @Test
    fun `a pdf with no write permission is imported as a local note instead`() {
        // SPEC 7.3 — "check W/N/V/D/CK before offering the action". A share with `RGD` and no `W`
        // is what a colleague's read-only folder looks like, and it is a common way to receive a
        // handout.
        assertEquals(RemotePdfPick.Decision.ImportReadOnly, RemotePdfPick.decide(file("Handout.pdf", "RGD")))
    }

    @Test
    fun `absent permissions are treated as writable`() {
        // `mayWrite` defaults to true when the server sent no `oc:permissions` at all, and this
        // pins that reading: refusing on a missing property would make the whole feature vanish
        // against any server or proxy that does not send it, with nothing on screen to explain it.
        assertEquals(RemotePdfPick.Decision.AdoptInPlace, RemotePdfPick.decide(file("Handout.pdf", null)))
    }

    @Test
    fun `a collection is navigated rather than opened`() {
        val folder = file("Chemistry", "RGDNVCK").copy(isCollection = true)
        assertEquals(RemotePdfPick.Decision.NotAFile, RemotePdfPick.decide(folder))
    }

    @Test
    fun `anything that is not a pdf is refused`() {
        assertEquals(RemotePdfPick.Decision.NotAPdf, RemotePdfPick.decide(file("notes.docx", "RGDNVW")))
    }

    @Test
    fun `an upper case extension is still a pdf`() {
        // A scanner writing `SCAN0001.PDF` is ordinary, and a case-sensitive match would hide the
        // file from the picker while Nextcloud Files shows it — indistinguishable from a broken
        // listing.
        assertEquals(RemotePdfPick.Decision.AdoptInPlace, RemotePdfPick.decide(file("SCAN0001.PDF", "RGDNVW")))
    }

    @Test
    fun `the title is the file name without its extension`() {
        assertEquals("Chemistry handout", RemotePdfPick.titleOf(file("Documents/Chemistry handout.pdf", "RGDNVW")))
    }

    @Test
    fun `an upper case extension is stripped from the title too`() {
        // ⚠️ Left on, the note is called `SCAN0001.PDF` and its remote path gains a *second* `.pdf`
        // the first time it is renamed — because the path is derived from the title.
        assertEquals("SCAN0001", RemotePdfPick.titleOf(file("SCAN0001.PDF", "RGDNVW")))
    }

    @Test
    fun `a file called only pdf keeps its name rather than becoming empty`() {
        assertEquals(".pdf", RemotePdfPick.titleOf(file(".pdf", "RGDNVW")))
    }

    @Test
    fun `the listing offers pdfs and nothing else`() {
        val listing = listOf(
            file("Documents", "RGDNVCK").copy(isCollection = true),
            file("Documents/notes.docx", "RGDNVW"),
            file("Documents/zeta.pdf", "RGDNVW"),
            file("Documents/Alpha.PDF", "RGD"),
            file("Documents/beta.pdf", "RGDNVW"),
        )

        // Case-folded order, so capitals do not separate names that belong together — and the
        // read-only one is present, because hiding it would be indistinguishable from a listing
        // that failed.
        assertEquals(
            listOf("Alpha.PDF", "beta.pdf", "zeta.pdf"),
            RemotePdfPick.pdfsIn(listing).map { it.name },
        )
    }

    private fun file(path: String, permissions: String?) = DavResource(
        path = path,
        href = "/remote.php/dav/files/noah/$path",
        isCollection = false,
        etag = ETag("etag-value", weak = false),
        lastModified = null,
        contentLength = 1024L,
        ocId = "00000042ocidinstance",
        fileId = "42",
        permissions = permissions,
        recursiveSize = null,
        checksums = emptyList(),
        dataFingerprint = null,
        lockOwner = null,
        locked = false,
        quotaAvailableBytes = null,
    )
}
