package dev.inkstone.app.elements

import dev.inkstone.core.model.FileIdentity
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.ReadHandle

/**
 * [delegate], with every path under [root] — a private corner of the same storage.
 *
 * ⭐ What the Element editor runs its scratch note in (SPEC §4.9.7). The editor is the ordinary note
 * editor, so it writes `notes/<id>/…` exactly as it always does; under this wrapper those paths land
 * in `elements/editing/<session>/notes/<id>/…`, which **nothing that lists notes ever reads** — the
 * library index, the reconciler, the trash sweeper and the outbox all look at the real `notes/`. So
 * an Element being edited cannot appear in the library, cannot be uploaded, and cannot be merged
 * into, without a single one of those components knowing an editor of Elements exists.
 *
 * Every method forwards, including the two with defaults, so a platform that pins descriptors or
 * reports inodes (`AndroidFileSystem`) does so here too — CLAUDE.md's torn-read entry is about
 * exactly the readers that would otherwise silently take the default.
 */
class ScopedFileSystem(private val delegate: FileSystem, private val root: String) : FileSystem {

    private fun under(path: String): String = if (path.isEmpty()) root else "$root/$path"

    override suspend fun exists(path: String): Boolean = delegate.exists(under(path))

    override suspend fun size(path: String): Long = delegate.size(under(path))

    override suspend fun readBytes(path: String): ByteArray = delegate.readBytes(under(path))

    override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray =
        delegate.readRange(under(path), offset, length)

    override suspend fun openRead(path: String): ReadHandle? = delegate.openRead(under(path))

    override suspend fun identityOf(path: String): FileIdentity? = delegate.identityOf(under(path))

    override suspend fun writeBytesAtomically(path: String, bytes: ByteArray) =
        delegate.writeBytesAtomically(under(path), bytes)

    override suspend fun appendBytes(path: String, bytes: ByteArray) = delegate.appendBytes(under(path), bytes)

    override suspend fun createDirectories(path: String) = delegate.createDirectories(under(path))

    override suspend fun move(from: String, to: String) = delegate.move(under(from), under(to))

    override suspend fun delete(path: String) = delegate.delete(under(path))

    override suspend fun list(path: String): List<String> = delegate.list(under(path))
}
