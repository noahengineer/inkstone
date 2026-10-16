package dev.inkstone.core.handwriting

import dev.inkstone.core.model.FileSystem

/**
 * An in-memory [FileSystem]: `:core:storage`'s test double, copied because there is no shared
 * test-fixtures module. `writeBytesAtomically` is all-or-nothing, which is the contract the real one
 * gets from write-temp-then-rename (rule 10); [truncateNextAppendTo] is how a killed append is
 * expressed in a test.
 */
internal class FakeFileSystem : FileSystem {

    private val files = LinkedHashMap<String, ByteArray>()
    private val directories = LinkedHashSet<String>()

    var truncateNextAppendTo: Int? = null

    val paths: Set<String> get() = files.keys

    /** A file's bytes without suspending — for a test's non-suspending callback. */
    fun peek(path: String): ByteArray? = files[path]?.copyOf()

    fun put(path: String, bytes: ByteArray) {
        files[path] = bytes.copyOf()
        registerParents(path)
    }

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
        files[path] = bytes.copyOf()
        registerParents(path)
    }

    override suspend fun appendBytes(path: String, bytes: ByteArray) {
        val limit = truncateNextAppendTo
        truncateNextAppendTo = null
        val written = if (limit == null) bytes else bytes.copyOf(limit.coerceAtMost(bytes.size))
        files[path] = (files[path] ?: ByteArray(0)) + written
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
        val movedDirectories = directories.filter { it == from || it.startsWith("$from/") }
        directories.removeAll(movedDirectories.toSet())
        directories += movedDirectories.map { to + it.removePrefix(from) }
        registerParents("$to/x")
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
