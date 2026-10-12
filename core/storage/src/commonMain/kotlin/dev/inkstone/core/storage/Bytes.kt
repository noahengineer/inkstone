// A byte layout's masks, shifts and field offsets ARE the specification — 0xFF is not a
// magic number here, it is a byte, and `shl 8` is not a tunable, it is the next byte along.
// Naming each one would add a constant per bit position and make the layout harder to check
// against the format it implements, which is the opposite of what the rule is for.
@file:Suppress("MagicNumber")

package dev.inkstone.core.storage

import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.ReadHandle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Little-endian integer accessors, and a growable output buffer.
 *
 * Hand-written because the two formats this module writes — the ZIP bundle
 * (SPEC §5.3) and the journal (SPEC §5.1) — are byte layouts, and a byte layout
 * wants explicit endianness at every field rather than whatever a helper library
 * defaults to. ZIP is little-endian by specification.
 */
internal fun ByteArray.readU16(offset: Int): Int =
    (this[offset].toInt() and BYTE_MASK) or ((this[offset + 1].toInt() and BYTE_MASK) shl 8)

internal fun ByteArray.readU32(offset: Int): Long = (this[offset].toLong() and BYTE_MASK_L) or
    ((this[offset + 1].toLong() and BYTE_MASK_L) shl 8) or
    ((this[offset + 2].toLong() and BYTE_MASK_L) shl 16) or
    ((this[offset + 3].toLong() and BYTE_MASK_L) shl 24)

private const val BYTE_MASK = 0xFF
private const val BYTE_MASK_L = 0xFFL

/** A growable byte buffer. `ArrayList<Byte>` boxes every byte; this does not. */
internal class ByteBuffer(initialCapacity: Int = DEFAULT_CAPACITY) {

    private var buffer = ByteArray(initialCapacity)

    var size: Int = 0
        private set

    fun u8(value: Int) {
        ensure(1)
        buffer[size++] = (value and 0xFF).toByte()
    }

    fun u16(value: Int) {
        u8(value)
        u8(value ushr 8)
    }

    fun u32(value: Long) {
        u16((value and 0xFFFF).toInt())
        u16(((value ushr 16) and 0xFFFF).toInt())
    }

    fun bytes(source: ByteArray, from: Int = 0, length: Int = source.size - from) {
        ensure(length)
        source.copyInto(buffer, size, from, from + length)
        size += length
    }

    fun text(value: String) {
        bytes(value.encodeToByteArray())
    }

    /** The bytes written. The backing array itself when it is exactly full, so no copy is made. */
    fun toByteArray(): ByteArray = if (size == buffer.size) buffer else buffer.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        var capacity = maxOf(buffer.size * 2, DEFAULT_CAPACITY)
        while (capacity < size + extra) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    private companion object {
        const val DEFAULT_CAPACITY = 1024
    }
}

/**
 * A readable byte range — a file, or bytes already in memory.
 *
 * The reason this exists rather than a `ByteArray` everywhere is SPEC §5.3's
 * *"one file per page gives lazy loading"*. Opening a 200-page notebook must read
 * the bundle's central directory and then only the pages being drawn; a
 * `ByteArray` parameter would have already read all 200. The in-memory
 * implementation exists because M4 will hand this the bundle extracted from
 * inside `document.pdf`, which by then is already in memory.
 */
public interface ByteSource {
    public suspend fun size(): Long

    /** [length] bytes from [offset], or fewer at end of source. */
    public suspend fun read(offset: Long, length: Int): ByteArray

    /** Release whatever the source holds open. Reading afterwards is the caller's bug. */
    public fun close() {
        // Nothing to release by default — an in-memory source holds an ordinary array.
    }

    /**
     * False only when this source is a file that something has since **replaced** — the path now
     * names a different file from the one being read. True when that cannot be told, which is
     * every in-memory source.
     */
    public suspend fun isCurrent(): Boolean = true
}

/**
 * Reads through the SPEC §2.2 [FileSystem], so `:core` still touches no platform API.
 *
 * ⛔ **Every read goes through one [FileSystem.openRead] handle, opened on first use.** It used to
 * call `fs.readRange(path, …)` per read, which re-resolves the path each time — so a
 * [Zip.Directory] built from one version of the file read its entries out of whichever version the
 * path named later. Every writer of `note.inkstone` replaces it by rename, and the note's own
 * editor materializes it every ~30 s while a bound note is being written, so any second reader
 * of the same note — the library's thumbnail, above all — raced it. The loss was *"Bundle entry
 * 'pages/….ink' has no local header"* thrown out of a main-thread coroutine, which killed the
 * process on 2026-09-24 with the physics note open; a rename one read earlier gives *"failed its
 * checksum"* instead, and one during the directory read makes [BundleReader.open] answer null,
 * which three callers read as *"there is no such note"*. A handle pins the file the first read
 * saw, so all of those become impossible rather than unlikely.
 *
 * The handle stays open for the source's lifetime — for an editor's session that is the whole
 * session, one descriptor per open note. [close] releases it; a source nobody closes is released
 * when it is collected. A read after [close] opens the path again, which is the old behaviour and
 * therefore no worse than it was.
 */
public class FileByteSource(private val fs: FileSystem, private val path: String) : ByteSource {

    private val opening = Mutex()

    private var handle: ReadHandle? = null

    private var opened = false

    private suspend fun handle(): ReadHandle? {
        if (opened) return handle
        return opening.withLock {
            if (!opened) {
                handle = fs.openRead(path)
                opened = true
            }
            handle
        }
    }

    /** The size of the file the handle holds, or -1 when there was no file — as `fs.size` answered. */
    override suspend fun size(): Long = handle()?.size ?: -1L

    override suspend fun read(offset: Long, length: Int): ByteArray = handle()?.read(offset, length) ?: ByteArray(0)

    override fun close() {
        val open = handle
        handle = null
        opened = false
        open?.close()
    }

    /**
     * ⚠️ **What keeps a pinned reader from turning a loud failure into a silent overwrite.**
     *
     * Before handles, a session whose bundle another writer replaced found out at its next read:
     * the offsets no longer matched and a checksum failed — `NoteSessionHost`'s KDoc names that
     * residual. Pinned, the same session reads its old file perfectly and would go on to
     * materialize over the other writer's bundle, which is the one outcome worse than a crash.
     * `NoteStore.materialize` asks this first and refuses to write when the answer is no.
     *
     * A path that names no file at all counts as current: nothing was put there by anybody else.
     */
    override suspend fun isCurrent(): Boolean {
        val held = handle()?.identity ?: return true
        val now = fs.identityOf(path) ?: return true
        return held == now
    }
}

public class MemoryByteSource(private val bytes: ByteArray) : ByteSource {

    override suspend fun size(): Long = bytes.size.toLong()

    override suspend fun read(offset: Long, length: Int): ByteArray {
        val start = offset.coerceIn(0L, bytes.size.toLong()).toInt()
        val end = (start + length).coerceAtMost(bytes.size)
        return bytes.copyOfRange(start, end)
    }
}

/**
 * CRC-32 (IEEE 802.3), the checksum ZIP requires and the journal reuses.
 *
 * Hand-written for the same reason as the byte accessors: `java.util.zip.CRC32` is
 * JVM-only and would kill the iOS link (CLAUDE.md rule 2). The table is built once,
 * lazily, and is 1 KB.
 */
internal object Crc32 {

    private val table = IntArray(TABLE_SIZE) { index ->
        var crc = index
        repeat(Byte.SIZE_BITS) {
            crc = if (crc and 1 != 0) (crc ushr 1) xor POLYNOMIAL else crc ushr 1
        }
        crc
    }

    fun of(vararg chunks: ByteArray): Long {
        var crc = INITIAL
        for (chunk in chunks) {
            for (byte in chunk) {
                crc = table[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
            }
        }
        return (crc xor INITIAL).toLong() and 0xFFFF_FFFFL
    }

    private const val TABLE_SIZE = 256
    private const val POLYNOMIAL = -0x12477CE0 // 0xEDB88320 reflected polynomial
    private const val INITIAL = -0x1 // 0xFFFFFFFF
}
