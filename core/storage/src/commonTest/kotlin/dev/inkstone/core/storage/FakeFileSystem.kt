package dev.inkstone.core.storage

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.FileSystem

/**
 * An in-memory [FileSystem] for tests, with one deliberate piece of realism: it can be
 * **cut off mid-append**.
 *
 * ROADMAP M2's first acceptance criterion is a process kill during a stroke, and the
 * failure it guards against — a torn journal record — cannot be produced by a filesystem
 * that always writes everything. [truncateNextAppendTo] is how a crash is expressed in a
 * unit test.
 *
 * `writeBytesAtomically` is genuinely all-or-nothing here, which is the contract the real
 * implementation gets from write-temp-then-rename (CLAUDE.md rule 10).
 */
internal class FakeFileSystem : FileSystem {

    private val files = LinkedHashMap<String, ByteArray>()
    private val directories = LinkedHashSet<String>()

    /** When set, the next [appendBytes] writes only this many bytes and then "crashes". */
    var truncateNextAppendTo: Int? = null

    var atomicWrites: Int = 0
        private set

    var appends: Int = 0
        private set

    val paths: Set<String> get() = files.keys

    override suspend fun exists(path: String): Boolean = path in files || path in directories

    override suspend fun size(path: String): Long = files[path]?.size?.toLong() ?: -1L

    override suspend fun readBytes(path: String): ByteArray = files[path]?.copyOf() ?: error("no such file: $path")

    override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val bytes = files[path] ?: error("no such file: $path")
        val start = offset.coerceIn(0L, bytes.size.toLong()).toInt()
        val end = (start.toLong() + length).coerceAtMost(bytes.size.toLong()).toInt()
        return bytes.copyOfRange(start, end)
    }

    override suspend fun writeBytesAtomically(path: String, bytes: ByteArray) {
        atomicWrites++
        files[path] = bytes.copyOf()
        registerParents(path)
    }

    override suspend fun appendBytes(path: String, bytes: ByteArray) {
        appends++
        val limit = truncateNextAppendTo
        truncateNextAppendTo = null
        val written = if (limit == null) bytes else bytes.copyOf(limit.coerceAtMost(bytes.size))
        val existing = files[path] ?: ByteArray(0)
        files[path] = existing + written
        registerParents(path)
    }

    override suspend fun createDirectories(path: String) {
        registerParents("$path/x")
        directories += path
    }

    override suspend fun move(from: String, to: String) {
        val movedFiles = files.keys.filter { it == from || it.startsWith("$from/") }
        require(movedFiles.isNotEmpty() || from in directories) { "no such path: $from" }
        for (path in movedFiles) {
            files[to + path.removePrefix(from)] = files.getValue(path)
            files.remove(path)
        }
        directories.removeAll { it == from || it.startsWith("$from/") }
        directories += to
    }

    override suspend fun delete(path: String) {
        files.keys.filter { it == path || it.startsWith("$path/") }.forEach { files.remove(it) }
        directories.removeAll { it == path || it.startsWith("$path/") }
    }

    override suspend fun list(path: String): List<String> {
        val prefix = "$path/"
        return (files.keys + directories)
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .distinct()
    }

    private fun registerParents(path: String) {
        var index = path.indexOf('/')
        while (index >= 0) {
            directories += path.substring(0, index)
            index = path.indexOf('/', index + 1)
        }
    }
}

/** A clock the test drives, so nothing depends on the machine's own time. */
internal class TestClock(var millis: Long = 1_800_000_000_000) : Clock {
    override fun nowEpochMillis(): Long = millis

    fun advance(by: Long) {
        millis += by
    }
}
