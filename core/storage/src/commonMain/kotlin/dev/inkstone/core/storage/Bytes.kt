// A byte layout's masks, shifts and field offsets ARE the specification — 0xFF is not a
// magic number here, it is a byte, and `shl 8` is not a tunable, it is the next byte along.
// Naming each one would add a constant per bit position and make the layout harder to check
// against the format it implements, which is the opposite of what the rule is for.
@file:Suppress("MagicNumber")

package dev.inkstone.core.storage

import dev.inkstone.core.model.FileSystem

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

    fun toByteArray(): ByteArray = buffer.copyOf(size)

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
}

/** Reads through the SPEC §2.2 [FileSystem], so `:core` still touches no platform API. */
public class FileByteSource(private val fs: FileSystem, private val path: String) : ByteSource {

    private var cachedSize: Long = -1L

    override suspend fun size(): Long {
        if (cachedSize < 0) cachedSize = fs.size(path)
        return cachedSize
    }

    override suspend fun read(offset: Long, length: Int): ByteArray = fs.readRange(path, offset, length)
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
