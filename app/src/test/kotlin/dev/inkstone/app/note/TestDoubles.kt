package dev.inkstone.app.note

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize

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

    val applied = ArrayList<Applied>()

    class Applied(val ops: List<PageOp>, val record: Boolean)

    private val gestures = ArrayDeque<List<PageOp>>()

    fun queueGesture(ops: List<PageOp>) {
        gestures += ops
    }

    override fun bindPage(nextObjectId: () -> ObjectId, nextOpId: () -> String, nextClock: () -> LamportClock) {
        bindCount++
    }

    override fun loadPage(page: FoldedPage) {
        loadedPage = page
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
     * What [NoteEditor.NoteSurface.setPageMeta] was last given, or null if it was never called.
     *
     * A field of its own rather than a flag on [loadedPage], because the whole point of the method is
     * that it is *not* `loadPage`: `loadPage` refits the camera and cancels the in-progress stroke,
     * and SPEC §13.2 row 10 forbids both. A test that could not tell them apart could not tell a
     * correct remote page-meta change from the one that eats the user's stroke.
     */
    var pageMeta: Pair<PageSize, Background>? = null
        private set

    override fun setPageMeta(size: PageSize, background: Background) {
        pageMeta = size to background
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
