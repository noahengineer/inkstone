package dev.inkstone.app.platform

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.inkstone.core.model.FileIdentity
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.ReadHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [FileSystem]: CLAUDE.md rule 10, **write-temp-then-atomic-rename, always.**
 *
 * ## Why every write goes through a temp file
 *
 * > The upload worker must never read a file mid-write — that produces a torn ZIP inside a
 * > PDF, which is the exact corruption class this design exists to prevent.
 *
 * The temp file lives in the **destination's own directory**, not in a cache dir: `rename` is
 * only atomic within a filesystem, and Android's cache directory can be on different storage
 * from app files. A cross-device rename silently degrades into copy-then-delete, which is
 * exactly the non-atomic write this is meant to rule out.
 *
 * `File.renameTo` is used rather than `Files.move(ATOMIC_MOVE)`: on the same filesystem it is
 * a `rename(2)`, which is atomic, and it does not need API 26+ NIO on a `minSdk 30` project
 * where it would still be the same syscall underneath.
 *
 * ## Paths
 *
 * All paths are relative to the app's private files directory. `:core:*` composes them from
 * [dev.inkstone.core.storage.NoteLayout] and never sees an absolute path, which is what lets
 * the same storage code run on iOS against a different root.
 *
 * ## Threading
 *
 * Everything hops to [Dispatchers.IO]. The suspend signatures in [FileSystem] exist for
 * exactly this reason, and the autosave path calls them from a coroutine that is not the
 * ink path.
 */
@Singleton
class AndroidFileSystem @Inject constructor(@ApplicationContext private val context: Context) : FileSystem {

    private val root: File get() = context.filesDir

    private fun resolve(path: String) = File(root, path)

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        resolve(path).exists()
    }

    override suspend fun size(path: String): Long = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (file.isFile) file.length() else -1L
    }

    override suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        resolve(path).readBytes()
    }

    override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.isFile || offset >= file.length()) return@withContext ByteArray(0)
        val toRead = length.toLong().coerceAtMost(file.length() - offset).toInt()
        val buffer = ByteArray(toRead)
        RandomAccessFile(file, "r").use { random ->
            random.seek(offset)
            random.readFully(buffer)
        }
        buffer
    }

    /**
     * ⛔ One open descriptor, read with positional reads — see [FileSystem.openRead] for the crash
     * this closes. [writeBytesAtomically] replaces a file by `rename(2)`, which changes what the
     * *name* points at and leaves an already-open descriptor on the old inode, so every read
     * through the handle sees the file as it was when the handle was opened.
     *
     * `FileChannel.read(buffer, position)` rather than `seek` + `read`: a positional read has no
     * shared cursor, so two coroutines reading one handle concurrently — which a session's reader
     * can have — cannot move each other's position.
     */
    override suspend fun openRead(path: String): ReadHandle? = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.isFile) return@withContext null
        val random = try {
            RandomAccessFile(file, "r")
        } catch (_: FileNotFoundException) {
            // Deleted between the check and the open — the same answer as never having existed.
            return@withContext null
        }
        ChannelReadHandle(random)
    }

    /**
     * Device and inode of what [path] names now — the other half of `FileByteSource.isCurrent`.
     *
     * ⚠️ Without this override (and [ChannelReadHandle.identity]) the interface defaults answer
     * null, `isCurrent` answers *"cannot tell, so yes"*, and the guard in `NoteStore.materialize`
     * is inert on every device while passing its JVM test — which is how it shipped for an hour.
     */
    override suspend fun identityOf(path: String): FileIdentity? = withContext(Dispatchers.IO) {
        try {
            val stat = Os.stat(resolve(path).path)
            FileIdentity(device = stat.st_dev, inode = stat.st_ino)
        } catch (_: ErrnoException) {
            null
        }
    }

    private class ChannelReadHandle(private val random: RandomAccessFile) : ReadHandle {

        private val channel = random.channel

        /** `fstat` of the open descriptor, so this is the size of the file actually held. */
        override val size: Long = channel.size()

        /** `fstat` again: the inode the descriptor holds, however the path has been renamed over since. */
        override val identity: FileIdentity? = try {
            val stat = Os.fstat(random.fd)
            FileIdentity(device = stat.st_dev, inode = stat.st_ino)
        } catch (_: ErrnoException) {
            null
        }

        override suspend fun read(offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
            if (offset < 0 || offset >= size) return@withContext ByteArray(0)
            val wanted = length.toLong().coerceAtMost(size - offset).toInt()
            val buffer = ByteBuffer.allocate(wanted)
            var position = offset
            while (buffer.hasRemaining()) {
                val got = channel.read(buffer, position)
                if (got < 0) break
                position += got
            }
            if (buffer.position() == wanted) buffer.array() else buffer.array().copyOf(buffer.position())
        }

        override fun close() {
            random.close()
        }
    }

    override suspend fun writeBytesAtomically(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val destination = resolve(path)
        destination.parentFile?.mkdirs()
        // Same directory as the destination, so the rename below is a rename and not a copy.
        //
        // ⭐ **Unique per call, since M6.** The name used to be `"${destination.name}.tmp"` — one
        // temp file per *destination* — so two concurrent writers aimed at one path interleaved
        // into a single temp file and then atomically renamed the mixture into place. A torn ZIP
        // inside a PDF, delivered by the very mechanism CLAUDE.md rule 10 exists to prevent, and
        // undetectable afterwards because the rename itself succeeded.
        //
        // M6's reconciler is single-flighted and stages downloads outside `notes/<id>/`, so it
        // cannot be the second writer any more. That closed the reachable path and left the
        // trap: any future second writer to one destination revives it, and the failure is
        // silent. The fix belongs here rather than in each caller's scheduling, because "only
        // one thing writes this path" is an invariant nothing in the type system holds.
        //
        // `createTempFile` in the destination's own directory keeps the rename a rename.
        val temp = File.createTempFile(destination.name, TEMP_SUFFIX, destination.parentFile)
        temp.outputStream().use { out ->
            out.write(bytes)
            // fsync before the rename. Without it the rename can be durable while the bytes
            // are not, and a power loss then leaves a correctly-named empty file — which is
            // worse than a torn one, because nothing detects it.
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(destination)) {
            temp.delete()
            error("could not rename ${temp.path} onto ${destination.path}")
        }
    }

    /**
     * The one non-atomic write in the app, by design (SPEC §5.1).
     *
     * An append cut short by a process kill costs the in-progress stroke and nothing else, and
     * `Journal` detects the torn tail on the way back in. That is the trade that makes a
     * two-second autosave affordable at all: rewriting the document would be a 40 MB write
     * every two seconds.
     */
    override suspend fun appendBytes(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val file = resolve(path)
        file.parentFile?.mkdirs()
        java.io.FileOutputStream(file, true).use { out ->
            out.write(bytes)
            out.flush()
            // The journal is the crash-recovery record, so it is worth an fsync per flush —
            // once every two seconds, off the ink path.
            out.fd.sync()
        }
    }

    override suspend fun createDirectories(path: String) = withContext(Dispatchers.IO) {
        resolve(path).mkdirs()
        Unit
    }

    override suspend fun move(from: String, to: String) = withContext(Dispatchers.IO) {
        val source = resolve(from)
        val destination = resolve(to)
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) {
            error("could not move ${source.path} to ${destination.path}")
        }
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        resolve(path).deleteRecursively()
        Unit
    }

    override suspend fun list(path: String): List<String> = withContext(Dispatchers.IO) {
        resolve(path).list()?.toList().orEmpty()
    }

    private companion object {
        const val TEMP_SUFFIX = ".tmp"
    }
}
