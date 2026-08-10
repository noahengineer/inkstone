package dev.inkstone.app.note

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageTemplate
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.storage.BundleContents
import dev.inkstone.core.storage.BundleWriter
import dev.inkstone.core.storage.MergeReport
import dev.inkstone.core.storage.NoteStore
import dev.inkstone.core.storage.PageContents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertIs

/**
 * ⭐ **SPEC §13.2 row 10, JVM half — a peer's edits arriving while the note is open.**
 *
 * The row's requirement is not "the strokes appear". It is that they appear **and nothing else
 * happens**, and the three things that must not happen are each one indirection away from code that
 * looks harmless:
 *
 * 1. **The camera must not be refit.** `display()` → `NoteSurface.loadPage` →
 *    `InkSurfaceView.pageSize`'s setter → `resetCamera(FIT_WIDTH)`, unconditionally. The camera is
 *    persisted nowhere, so once refit the previous value is gone: the user's zoom and pan vanish
 *    mid-sentence. Asserted here by `loadedPage` being the *same object* it was before the merge —
 *    `loadPage` is the only route to a refit, so proving it was not called proves the camera stood
 *    still.
 * 2. **The in-progress stroke must not be cancelled.** `loadPage` also calls
 *    `wetView.cancelUnfinishedStrokes()`, which eats the stroke being drawn at that instant. The wet
 *    layer is private to `InkSurfaceView` and cannot be observed from the JVM, so the honest test of
 *    this is instrumented — but it fails for the *same* reason as (1), so the `loadedPage` assertion
 *    covers the cause even where it cannot see the symptom.
 * 3. **The peer's ops must not reach the undo stack.** SPEC §4.6: *"undoing does not undo someone
 *    else's work."* If they did, the user's next two-finger tap would delete their collaborator's
 *    writing — and `flush()` persists the undo stack to `undo.json`, so it would survive a restart.
 *
 * Every one of the three passes if the merge is routed through `display()`, which is why the
 * assertions are about what the surface was *told* rather than about what the note contains.
 *
 * ## ⚠️ Why these drive `mergeRemoteBundle` and not an op list
 *
 * They used to call `NoteEditor.applyRemoteOps(pageId, ops)`, which was a door **nothing in
 * production ever opened**: the reconciler hands `NoteSessionHost` a bundle path, the host hands it
 * to this editor's `mergeRemoteBundle`, and the ops are folded and the screen reconciled inside one
 * critical section there. Six green tests over a dead seam is worse than no tests, because the row
 * they were filed under reads as covered. Worse, that seam called `NoteSession.merge` with no
 * `NoteMerge.mergeDocument` in front of it — the SPEC §4.3 trap where a remote page's ops are
 * journaled without a `PageRef`, skipped by `BundleWriter`, and then destroyed by the journal reset
 * inside `materialize`, leaving a valid PDF with a page missing and nothing reported.
 *
 * So the seam is gone and every claim below is now made against the production path: a real bundle
 * written by `BundleWriter`, opened by `NoteMerge.mergeBundle`, folded through `NoteSession.merge`,
 * with the real `materialize(carried, carriedManifest)` behind it. The fixture is a peer's published
 * `note.inkstone`, which is exactly what `PulledPdfExtractor` stages for the host.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorRemoteOpsTest {

    private val clock = Clock { NOW }

    /** A note on disk before any editor exists, so a peer's bundle can name it. */
    private class Fixture(val fs: InMemoryFileSystem, val document: Document, val page: PageId) {
        val id: DocumentId get() = document.id
    }

    private suspend fun fixture(): Fixture {
        val fs = InMemoryFileSystem()
        val store = NoteStore(fs, clock, IdFactory(clock))
        // Created here rather than by the editor because the peer's bundle has to carry *this*
        // note's `DocumentId` and page id. A bundle naming a different document comes back
        // `DifferentDocument`, which is layer 3 and has none of this row's behaviour in it.
        val note = store.create(title = "Shared", deviceId = "device-a")
        return Fixture(fs, note.document, note.document.pages.single().id)
    }

    /**
     * A peer's published bundle for this note, written where the extractor would have staged it.
     *
     * @param schemaVersion overridden only by the last test here. `BundleWriter` writes whatever the
     *   document says, which is the only way to author a file from the future without a newer build.
     */
    private suspend fun Fixture.peerBundle(ops: List<PageOp>, schemaVersion: Int = Document.SCHEMA_VERSION): String {
        val path = "download/${id.value}.inkstone"
        fs.writeBytesAtomically(
            path,
            BundleWriter.write(
                BundleContents(
                    document = document.copy(schemaVersion = schemaVersion),
                    pages = mapOf(page to PageContents(PageSize.Letter, Background.Blank, ops)),
                    highestClockCounter = PEER_COUNTER,
                ),
            ),
        )
        return path
    }

    private fun TestScope.editorOver(fixture: Fixture) = NoteEditor(
        fs = fixture.fs,
        clock = clock,
        scope = this,
        drainDispatcher = UnconfinedTestDispatcher(testScheduler),
        // The autosave loop is driven by hand in these tests; a live timer would make the number of
        // `applied` batches depend on how long the test took.
        autosaveIntervalMs = Long.MAX_VALUE,
        documentId = fixture.id,
    )

    /** A peer's stroke: another device's id in the clock, and an opId this device has never seen. */
    private fun remoteStroke(id: String) = PageOp.AddObject(
        opId = "peer-op-$id",
        clock = LamportClock(PEER_COUNTER, "device-b"),
        obj = StrokeObject(ObjectId(id), StrokeGeometry(byteArrayOf(4, 5, 6))),
    )

    /** Stamped above the peer's, as a gesture drawn while the pull was in flight would be. */
    private fun localStroke(id: String) = PageOp.AddObject(
        opId = "local-op-$id",
        clock = LamportClock(PEER_COUNTER + 3, "device-a"),
        obj = StrokeObject(ObjectId(id), StrokeGeometry(byteArrayOf(1, 2, 3))),
    )

    @Test
    fun `a peers strokes reach the screen without journaling them again`() = runTest {
        val fixture = fixture()
        val bundle = fixture.peerBundle(listOf(remoteStroke(PEER_STROKE)))
        val editor = editorOver(fixture)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()
        surface.applied.clear()

        val commit = editor.mergeRemoteBundle(bundle)
        assertNotNull("the merge did not run against the editor's session", commit)
        assertIs<MergeReport.Merged>(commit!!.report, "the bundle would not merge, so this test proves nothing")

        val applied = surface.applied.singleOrNull()
        assertNotNull("the peer's stroke never reached the surface", applied)
        assertEquals(1, applied!!.ops.size)
        // `record = false` is the whole difference between showing an op and re-recording it. A true
        // here would append the peer's stroke to this device's log a second time on every poll,
        // growing the journal by the size of the note forever.
        assertEquals(
            "a remote op was recorded, which journals a peer's stroke a second time",
            false,
            applied.record,
        )
        editor.close()
    }

    @Test
    fun `a remote merge does not reload the page and so cannot refit the camera`() = runTest {
        val fixture = fixture()
        val bundle = fixture.peerBundle(listOf(remoteStroke(PEER_STROKE)))
        val editor = editorOver(fixture)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()
        val loadedBefore = surface.loadedPage
        assertNotNull("the note never loaded, so this test would pass vacuously", loadedBefore)

        editor.mergeRemoteBundle(bundle)

        // Identity, not equality. `loadPage` is the only route to `resetCamera(FIT_WIDTH)` and to
        // `cancelUnfinishedStrokes()`, so a second call — even with an equal fold — is the bug.
        assertSame(
            "the page was reloaded, which refits the camera and cancels the in-progress stroke",
            loadedBefore,
            surface.loadedPage,
        )
        editor.close()
    }

    @Test
    fun `a remote merge leaves the undo stack alone`() = runTest {
        val fixture = fixture()
        val bundle = fixture.peerBundle(listOf(remoteStroke(PEER_STROKE)))
        val editor = editorOver(fixture)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()

        // One local stroke first, so `canUndo` is true and the assertion is about the merge rather
        // than about an empty stack staying empty.
        surface.queueGesture(listOf(localStroke(LOCAL_STROKE)))
        editor.save()
        val undoBefore = editor.state.value.canUndo
        val redoBefore = editor.state.value.canRedo
        assertTrue("the local stroke did not reach the undo stack, so this test proves nothing", undoBefore)

        editor.mergeRemoteBundle(bundle)

        assertEquals(
            "a peer's op entered the undo stack — the next two-finger tap would delete their work",
            undoBefore,
            editor.state.value.canUndo,
        )
        assertEquals(redoBefore, editor.state.value.canRedo)
        editor.close()
    }

    @Test
    fun `queued local ink is flushed before the union is taken`() = runTest {
        // Without the `save()` at the top of the merge, the diff is computed against a page missing
        // the last two seconds of writing — and `reconcile` would then push a *removal* for a stroke
        // that is on screen and in the log, making the user's own ink disappear on a poll.
        val fixture = fixture()
        val bundle = fixture.peerBundle(listOf(remoteStroke(PEER_STROKE)))
        val editor = editorOver(fixture)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()

        surface.queueGesture(listOf(localStroke(LOCAL_STROKE)))
        editor.mergeRemoteBundle(bundle)

        val removals = surface.applied.flatMap { it.ops }.filterIsInstance<PageOp.DeleteObject>()
        assertTrue("the merge told the surface to remove a local stroke it had just drawn", removals.isEmpty())
        val folded = editor.foldedPage(fixture.page)
        assertEquals(
            "both the local and the remote stroke should be on the page after a merge",
            setOf(ObjectId(LOCAL_STROKE), ObjectId(PEER_STROKE)),
            folded?.objects?.mapTo(HashSet()) { it.id },
        )
        editor.close()
    }

    @Test
    fun `a remote page meta change is pushed without reloading the page`() = runTest {
        // `StrokeStore.applyOps` has `else -> false`, so before M6 a peer's `SetPageMeta` folded
        // correctly and rendered not at all — screen and log disagreed about the paper until the next
        // page switch, with no error anywhere. This asserts the editor's half: the change is
        // recognised and pushed through `setPageMeta`, which does not touch the camera.
        val fixture = fixture()
        val bundle = fixture.peerBundle(
            listOf(
                PageOp.SetPageMeta(
                    opId = "peer-op-meta",
                    clock = LamportClock(PEER_COUNTER, "device-b"),
                    background = Background.Template(PageTemplate.CORNELL),
                ),
            ),
        )
        val editor = editorOver(fixture)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()
        val loadedBefore = surface.loadedPage

        editor.mergeRemoteBundle(bundle)

        assertEquals(
            "the peer's paper change never reached the surface",
            Background.Template(PageTemplate.CORNELL),
            surface.pageMeta?.second,
        )
        assertSame(
            "the page was reloaded to change the paper, which refits the camera",
            loadedBefore,
            surface.loadedPage,
        )
        // The picker follows the page it is describing, or the template menu would show the old one.
        assertEquals(PageTemplate.CORNELL, editor.state.value.template)
        editor.close()
    }

    @Test
    fun `a poll that re-delivers nothing novel does not touch the surface`() = runTest {
        // The common case by a wide margin, and the reason the guard cannot live at an entry point:
        // the remote ETag moves every time *this* device uploads, so most merges fold a log this
        // device already has. `NoteSession.merge` subtracts it by opId and the two folds come out
        // identical. Reaching the surface with an empty diff would cost a call per poll on every
        // open note, and `setPageMeta` with unchanged paper would be a redraw of the whole page.
        val fixture = fixture()
        val bundle = fixture.peerBundle(emptyList())
        val editor = editorOver(fixture)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()
        surface.applied.clear()

        val commit = editor.mergeRemoteBundle(bundle)
        assertNotNull("the merge did not run at all", commit)
        val report = assertIs<MergeReport.Merged>(commit!!.report)
        assertEquals("the fixture gave the peer something novel, so this proves nothing", 0, report.remoteNovelOps)

        assertTrue("a merge with nothing novel still talked to the surface", surface.applied.isEmpty())
        assertNull("a merge with nothing novel changed the page metadata", surface.pageMeta)
        editor.close()
    }

    @Test
    fun `a peers bundle from a newer schema is reported as too new and not as a different note`() = runTest {
        // ⚠️ `BundleReader.open` refuses a bundle from the future by **throwing** (SPEC §5.8), and
        // both gate implementations wrapped it in `runCatching { … }.getOrNull()` — so every refusal
        // arrived as `DifferentDocument` and `MergeReport.TooNew` was a branch nothing could reach.
        // The two lead to the same place (layer 3, both copies kept, the ETag left alone) and say
        // different things to the user: `REMOTE_TOO_NEW` is *"update Inkstone and this note opens"*,
        // `UNMERGEABLE` is *"this file cannot be merged"*. The second is a lie about the first.
        //
        // The file is reachable in production: the extractor's own `TOO_NEW` verdict comes off the
        // XMP marker and never stages a bundle at all, so what lands here is a file whose marker and
        // whose manifest disagree.
        val fixture = fixture()
        val bundle = fixture.peerBundle(listOf(remoteStroke(PEER_STROKE)), schemaVersion = FUTURE_SCHEMA)
        val editor = editorOver(fixture)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()
        val loadedBefore = surface.loadedPage
        surface.applied.clear()

        val commit = editor.mergeRemoteBundle(bundle)
        assertNotNull(
            "a bundle this build cannot read must answer with a report, never null — null means " +
                "'no session here' and has the host open a second one over this note",
            commit,
        )

        val report = assertIs<MergeReport.TooNew>(commit!!.report)
        assertEquals(FUTURE_SCHEMA, report.remoteVersion)
        assertEquals(Document.SCHEMA_VERSION, report.understoodVersion)
        // And the local note is untouched — SPEC §7.7 layer 3 keeps both copies and supersedes
        // nothing. Above all it is not re-saved in this build's vocabulary (SPEC §5.6).
        assertEquals(
            "a file from the future was merged into the local note",
            emptySet<ObjectId>(),
            editor.foldedPage(fixture.page)?.objects?.mapTo(HashSet()) { it.id },
        )
        assertTrue("a refused bundle still talked to the surface", surface.applied.isEmpty())
        assertSame(loadedBefore, surface.loadedPage)
        editor.close()
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val PEER_STROKE = "peer-1"
        const val LOCAL_STROKE = "mine-1"

        /** Above anything the local note has issued, so the peer's ops are genuinely novel. */
        const val PEER_COUNTER = 9L

        /** Comfortably past anything this build will grow into. */
        const val FUTURE_SCHEMA = 99
    }
}
