package dev.inkstone.app.note

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageText
import dev.inkstone.core.search.SearchHit
import dev.inkstone.core.search.SearchIndex

/**
 * Shared doubles for the editor's JVM tests.
 *
 * Extracted from `NoteEditorBindingTest` when M3 gave the editor a second thing to test —
 * what it reports to the library. Two copies of an in-memory filesystem would drift, and
 * the one that drifted would be the one whose test still passed.
 */
internal class FakeSurface : NoteEditor.NoteSurface {

    var bindCount: Int = 0
        private set

    val bound: Boolean get() = bindCount > 0

    var loadedPage: FoldedPage? = null
        private set

    /** How many times the editor asked for a page — the readout docs/M8-PERF-RENDER-CACHE.md §5 wanted. */
    var loadCount: Int = 0
        private set

    val applied = ArrayList<Applied>()

    class Applied(val ops: List<PageOp>, val record: Boolean)

    private val gestures = ArrayDeque<List<PageOp>>()

    fun queueGesture(ops: List<PageOp>) {
        gestures += ops
    }

    override fun bindPage(nextObjectId: () -> ObjectId, nextOpId: () -> String, nextClock: () -> LamportClock) {
        bindCount++
    }

    override suspend fun loadPage(page: FoldedPage) {
        loadedPage = page
        loadCount++
    }

    override fun drainOps(): List<List<PageOp>> {
        val drained = gestures.toList()
        gestures.clear()
        return drained
    }

    override fun applyOps(ops: List<PageOp>, record: Boolean) {
        applied += Applied(ops, record)
    }

    /**
     * What [NoteEditor.NoteSurface.highlight] was last given (SPEC §8.4).
     *
     * ⚠️ **A recording fake, and it can only ever prove that the editor asked.** M6's
     * `setPageMeta` was recorded by this same class while the production surface did nothing at
     * all, and every assertion was simultaneously true of the editor and false of the app. So
     * the fact that this records is not evidence the highlight is drawn — that lives in
     * `InkSurfaceView.highlight` and `PagePainter.paintHighlight`, and the only check that
     * covers it is driving the app.
     */
    var highlighted: PageRect? = null
        private set

    override fun highlight(box: PageRect?) {
        highlighted = box
    }

    /**
     * What [NoteEditor.NoteSurface.setPageMeta] was last given, or null if it was never called.
     *
     * A field of its own rather than a flag on [loadedPage], because the whole point of the method is
     * that it is *not* `loadPage`: `loadPage` refits the camera and cancels the in-progress stroke,
     * and SPEC §13.2 row 10 forbids both. A test that could not tell them apart could not tell a
     * correct remote page-meta change from the one that eats the user's stroke.
     *
     * ⚠️ **Recording the call is all this can prove, and for one milestone that was less than it
     * looked.** The production surface is the anonymous `NoteSurface` in `InkScreen`, and its
     * `setPageMeta` was `= Unit` for the whole of M6 — so every assertion made against this field was
     * true about the editor and false about the app, which is CLAUDE.md's *"correct in every test and
     * inert in the app"* precisely. What closes the gap is
     * `app/src/androidTest/…/device/PageMetaLiveChangeTest.kt`, which drives the real
     * `InkSurfaceView` on a device and asserts the paper actually changed, the camera did not move
     * and the wet stroke survived. Read a green tick here as *"the editor asked"*, never as *"the
     * screen changed"*.
     */
    var pageMeta: Pair<PageCanvas, Background>? = null
        private set

    override fun setPageMeta(canvas: PageCanvas, background: Background) {
        pageMeta = canvas to background
    }
}

/** The same in-memory filesystem shape `:core:storage` tests use, with append counting. */
internal class InMemoryFileSystem : FileSystem {

    private val files = LinkedHashMap<String, ByteArray>()
    private val directories = LinkedHashSet<String>()

    var appends: Int = 0
        private set

    override suspend fun exists(path: String): Boolean = path in files || path in directories

    override suspend fun size(path: String): Long = files[path]?.size?.toLong() ?: -1L

    override suspend fun readBytes(path: String): ByteArray = files[path] ?: error("no such file: $path")

    override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val bytes = files[path] ?: error("no such file: $path")
        val start = offset.coerceIn(0L, bytes.size.toLong()).toInt()
        val end = (start.toLong() + length).coerceAtMost(bytes.size.toLong()).toInt()
        return bytes.copyOfRange(start, end)
    }

    override suspend fun writeBytesAtomically(path: String, bytes: ByteArray) {
        files[path] = bytes.copyOf()
        register(path)
    }

    override suspend fun appendBytes(path: String, bytes: ByteArray) {
        appends++
        files[path] = (files[path] ?: ByteArray(0)) + bytes
        register(path)
    }

    override suspend fun createDirectories(path: String) {
        directories += path
    }

    override suspend fun move(from: String, to: String) {
        files.keys.filter { it == from || it.startsWith("$from/") }.forEach {
            files[to + it.removePrefix(from)] = files.getValue(it)
            files.remove(it)
        }
    }

    override suspend fun delete(path: String) {
        files.keys.filter { it == path || it.startsWith("$path/") }.forEach { files.remove(it) }
        directories.removeAll { it == path || it.startsWith("$path/") }
    }

    override suspend fun list(path: String): List<String> = (files.keys + directories)
        .filter { it.startsWith("$path/") }
        .map { it.removePrefix("$path/").substringBefore('/') }
        .distinct()

    private fun register(path: String) {
        var index = path.indexOf('/')
        while (index >= 0) {
            directories += path.substring(0, index)
            index = path.indexOf('/', index + 1)
        }
    }
}

/**
 * An [InMemoryFileSystem] that can be **parked mid-write**, and that remembers the order it was
 * written in.
 *
 * Both halves exist for the same reason: the editor's operations are only unsafe *while they are
 * suspended*, and a test that calls two of them in sequence proves nothing about what happens when
 * one lands inside the other. The suspension points that matter are all file I/O, so this is where a
 * test can stand between a bundle write and the journal reset that follows it — the window in which
 * a flushed stroke is destroyed.
 *
 * [writes] is the serialization evidence. Data assertions alone would let a refactor preserve the
 * bytes by luck while reinstating the interleaving; the order of `write:`/`append:` entries is what
 * says the two operations did not overlap.
 */
internal class GatedFileSystem(private val delegate: InMemoryFileSystem = InMemoryFileSystem()) :
    FileSystem by delegate {

    /** Every write and append, in order, as `write:<path>` / `append:<path>`. */
    val writes = ArrayList<String>()

    /**
     * Runs **before** each atomic write, with the destination path.
     *
     * Before rather than after, because `writeBytesAtomically` is one step of a caller's sequence
     * and what a test needs to interrupt is the sequence, not the write.
     */
    var onWrite: suspend (String) -> Unit = {}

    override suspend fun writeBytesAtomically(path: String, bytes: ByteArray) {
        onWrite(path)
        writes += "write:$path"
        delegate.writeBytesAtomically(path, bytes)
    }

    override suspend fun appendBytes(path: String, bytes: ByteArray) {
        writes += "append:$path"
        delegate.appendBytes(path, bytes)
    }
}

/**
 * ⭐ A [FileSystem] that remembers **which thread** each call ran on.
 *
 * The only way to assert that opening a note does its reading and decoding off the thread the
 * editor was started from. `NoteEditor.work` is `Dispatchers.Main.immediate` in the app and the
 * test scope's dispatcher here, and before `openOrCreate` was given a `withContext` the whole open
 * — the journal read and CRC, the full op decode, `NoteStore.loadUndo` — ran there. That is not
 * visible from any result: the note opens correctly either way, and the only difference is a
 * blocked UI thread (docs/M8-PERF-RENDER-CACHE.md §5).
 *
 * ⚠️ **A test using this must give the editor a real dispatcher**, not an
 * `UnconfinedTestDispatcher`: unconfined does not dispatch, so every hop stays on the calling
 * thread and the assertion passes against code that never moved.
 */
internal class ThreadRecordingFileSystem(private val delegate: FileSystem) : FileSystem {

    private val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Every thread name that has run a call on this file system.
     *
     * ⚠️ **Match these by prefix, never by equality.** `kotlinx-coroutines-debug` — which the
     * test runtime turns on — appends ` @coroutine#N` to the running thread's name, so an exact
     * comparison against a `ThreadFactory` name never matches and every assertion built on one
     * passes vacuously. That is what [ranOn] is for; it cost a revert-and-check-red to find, which
     * is the only reason it was found at all.
     */
    val threads: Set<String> get() = seen.toSet()

    /** Whether any call ran on the thread named [name] — see [threads] on why this is a prefix test. */
    fun ranOn(name: String): Boolean = seen.any { it == name || it.startsWith("$name ") }

    /**
     * ⚠️ **On entry, before the call.** Recording the thread the delegate *returned* on measures
     * where the work resumed rather than where it ran, which is a different thread the moment any
     * implementation hops internally.
     */
    private fun entered() {
        seen += Thread.currentThread().name
    }

    override suspend fun exists(path: String): Boolean {
        entered()
        return delegate.exists(path)
    }

    override suspend fun size(path: String): Long {
        entered()
        return delegate.size(path)
    }

    override suspend fun readBytes(path: String): ByteArray {
        entered()
        return delegate.readBytes(path)
    }

    override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray {
        entered()
        return delegate.readRange(path, offset, length)
    }

    override suspend fun writeBytesAtomically(path: String, bytes: ByteArray) {
        entered()
        delegate.writeBytesAtomically(path, bytes)
    }

    override suspend fun appendBytes(path: String, bytes: ByteArray) {
        entered()
        delegate.appendBytes(path, bytes)
    }

    override suspend fun createDirectories(path: String) {
        entered()
        delegate.createDirectories(path)
    }

    override suspend fun move(from: String, to: String) {
        entered()
        delegate.move(from, to)
    }

    override suspend fun delete(path: String) {
        entered()
        delegate.delete(path)
    }

    override suspend fun list(path: String): List<String> {
        entered()
        return delegate.list(path)
    }
}

/** A [SearchIndex] that does nothing, for a test whose subject is not the index. */
internal class NoOpSearchIndex : SearchIndex {

    override suspend fun indexPage(noteId: DocumentId, text: PageText) = Unit

    override suspend fun forgetPage(noteId: DocumentId, pageId: PageId) = Unit

    override suspend fun forgetNote(noteId: DocumentId) = Unit

    override suspend fun search(query: String, limit: Int): List<SearchHit> = emptyList()
}
