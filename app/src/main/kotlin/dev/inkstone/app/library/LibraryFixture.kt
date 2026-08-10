package dev.inkstone.app.library

import dev.inkstone.android.ink.InkBrushFactory
import dev.inkstone.android.ink.SyntheticInk
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageTemplate
import dev.inkstone.core.storage.NoteSession
import dev.inkstone.core.storage.NoteStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ROADMAP M3's frame-rate gate, as a button.
 *
 * > 500 notes across 50 nested folders → library scrolls at 60fps
 *
 * There is no honest way to run that without 500 notes, and no honest way to *get* 500 notes
 * except to generate them. This is the library-scale twin of M1's "Load 5,000 strokes",
 * which exists for exactly the same reason.
 *
 * ## Why the notes contain real ink
 *
 * A library of 500 blank notes would measure nothing. Every thumbnail would be an identical
 * near-empty WebP that decodes instantly, so both costs the gate exists to catch — rendering
 * a page and decoding its picture — would be absent, and the measurement would pass while
 * telling you nothing. Each generated note therefore gets a page of synthetic strokes.
 *
 * That makes generation slow: 500 notes × [STROKES_PER_NOTE] strokes is tens of thousands of
 * native tessellations plus a bundle write each. It reports progress and runs on
 * [Dispatchers.Default], and it is a measurement tool, not a feature.
 *
 * ## What it does not do
 *
 * **It cannot clean up after itself.** ROADMAP M3's other hard rule is no hard delete
 * anywhere in the UI, and a "delete the fixture" button would be exactly that — with the
 * blast radius of a wildcard. The generated notes are ordinary notes: select them and move
 * them to the trash, where they expire in 30 days like anything else. Uninstalling is the
 * other option.
 */
@Singleton
class LibraryFixture @Inject constructor(
    private val repository: LibraryRepository,
    private val fs: dev.inkstone.core.model.FileSystem,
    private val clock: Clock,
    private val brushes: InkBrushFactory,
) {

    private val ids = IdFactory(clock)
    private val store = NoteStore(fs, clock, ids)

    /**
     * Build the fixture.
     *
     * @param onProgress called with notes created so far, on the calling context's thread —
     *   the caller marshals it to the UI. Generation takes long enough that a silent app is
     *   indistinguishable from a hung one.
     * @return how many notes were created.
     */
    suspend fun generate(
        folderCount: Int = FOLDERS,
        noteCount: Int = NOTES,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): Int {
        val folders = createFolders(folderCount)

        var created = 0
        for (index in 0 until noteCount) {
            // Round-robin rather than random, so every folder is populated and the
            // measurement is not at the mercy of a seed that left half of them empty.
            val folder = folders.getOrNull(index % folders.size.coerceAtLeast(1))
            createNote(index, folder, dispatcher)
            created++
            if (created % PROGRESS_EVERY == 0 || created == noteCount) onProgress(created, noteCount)
        }
        return created
    }

    /**
     * A tree, not a flat list — the criterion says *nested*.
     *
     * [TOP_LEVEL_FOLDERS] parents with the rest distributed beneath them, which gives a
     * breadcrumb worth walking and a `FolderTree` with real depth to resolve.
     */
    private suspend fun createFolders(count: Int): List<FolderId> {
        val roots = ArrayList<FolderId>(TOP_LEVEL_FOLDERS)
        repeat(minOf(TOP_LEVEL_FOLDERS, count)) { index ->
            roots += repository.createFolder(null, "Subject ${index + 1}")
        }
        val all = ArrayList(roots)
        var made = roots.size
        var child = 0
        while (made < count && roots.isNotEmpty()) {
            val parent = roots[child % roots.size]
            all += repository.createFolder(parent, "Term ${child / roots.size + 1}")
            child++
            made++
        }
        return all
    }

    private suspend fun createNote(index: Int, folder: FolderId?, dispatcher: CoroutineDispatcher) {
        val note = store.create(
            title = "Notebook ${index + 1}",
            deviceId = FIXTURE_DEVICE_ID,
            background = Background.Template(PageTemplate.RULED),
        )
        val session = NoteSession.open(store, ids, clock, note, FIXTURE_DEVICE_ID)
        val page = note.document.livePages.first().id

        // Tessellation and encoding, off whatever thread the caller is on.
        val ops = withContext(dispatcher) {
            SyntheticInk.generatePageOps(
                count = STROKES_PER_NOTE,
                page = PageSize.Letter,
                factory = brushes,
                // A different seed per note, so the thumbnails differ — 500 identical
                // pictures would let a caching bug pass the measurement unnoticed.
                seed = FIXTURE_SEED + index,
                nextObjectId = ids::newObjectId,
                nextOpId = session::nextOpId,
                nextClock = session::nextClock,
            )
        }
        session.replay(page, ops)
        // Materializes, so the note is complete on disk and its thumbnail can be rendered
        // from the bundle rather than from a journal that is still open.
        session.close(NoteSyncState.LOCAL_ONLY)

        repository.adoptGenerated(note.document.id, "Notebook ${index + 1}", folder)
    }

    private companion object {
        /** ROADMAP M3's numbers, exactly. */
        const val NOTES = 500
        const val FOLDERS = 50
        const val TOP_LEVEL_FOLDERS = 10

        /**
         * Strokes per generated page.
         *
         * A page of real handwriting is a few hundred; this is a deliberate compromise
         * between a thumbnail that costs something to render and a fixture that finishes
         * while you are still watching it.
         */
        const val STROKES_PER_NOTE = 120

        const val FIXTURE_SEED = 20260806
        const val PROGRESS_EVERY = 10

        /** One device id for the whole fixture — these notes never sync. */
        const val FIXTURE_DEVICE_ID = "fixture-device"
    }
}
