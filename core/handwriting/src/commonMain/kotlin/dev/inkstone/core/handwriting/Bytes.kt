package dev.inkstone.core.handwriting

/**
 * A growable byte buffer with the three encodings the profile's binary files use: varints,
 * zig-zag varints, and big-endian 32-bit words.
 *
 * Hand-written rather than borrowed from `:core:storage`, whose equivalents are internal to it and
 * shaped around ZIP entries. Thirty lines here cost less than an edge to a module this one has no
 * other reason to depend on.
 */
internal class ByteSink(initialCapacity: Int = INITIAL_CAPACITY) {
    private var bytes = ByteArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    fun byte(value: Int) {
        ensure(1)
        bytes[size++] = value.toByte()
    }

    fun bytes(values: ByteArray) {
        ensure(values.size)
        values.copyInto(bytes, size)
        size += values.size
    }

    /** An unsigned LEB128 varint. [value] must not be negative — use [zigzag] for signed values. */
    fun varint(value: Long) {
        var remaining = value
        while (remaining and LOW_SEVEN_BITS.inv() != 0L) {
            byte(((remaining and LOW_SEVEN_BITS) or CONTINUATION).toInt())
            remaining = remaining ushr SEVEN
        }
        byte(remaining.toInt())
    }

    /** A signed value, zig-zag encoded so small negative numbers stay one byte. */
    fun zigzag(value: Long) {
        varint((value shl 1) xor (value shr SIGN_SHIFT))
    }

    fun int32(value: Int) {
        byte(value ushr BYTE_3)
        byte(value ushr BYTE_2)
        byte(value ushr BYTE_1)
        byte(value)
    }

    fun float(value: Float) {
        int32(value.toRawBits())
    }

    fun string(value: String) {
        val encoded = value.encodeToByteArray()
        varint(encoded.size.toLong())
        bytes(encoded)
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra <= bytes.size) return
        var capacity = bytes.size * 2
        while (capacity < size + extra) capacity *= 2
        bytes = bytes.copyOf(capacity)
    }

    private companion object {
        const val INITIAL_CAPACITY = 256
        const val LOW_SEVEN_BITS = 0x7FL
        const val CONTINUATION = 0x80L
        const val SEVEN = 7
        const val SIGN_SHIFT = 63
        const val BYTE_3 = 24
        const val BYTE_2 = 16
        const val BYTE_1 = 8
    }
}

/**
 * Reads what [ByteSink] wrote. Every read answers **null** at the end of the data instead of
 * throwing: a truncated file is the expected shape of a write a process was killed in, and a
 * reader that throws on one turns a lost record into a profile that will not open.
 */
internal class ByteCursor(private val bytes: ByteArray, start: Int = 0, private val limit: Int = bytes.size) {
    var position: Int = start
        private set

    val remaining: Int get() = limit - position

    fun byte(): Int? = if (position < limit) bytes[position++].toInt() and BYTE_MASK else null

    @Suppress("ReturnCount")
    fun varint(): Long? {
        var result = 0L
        var shift = 0
        while (shift < MAX_SHIFT) {
            val next = byte() ?: return null
            result = result or ((next and LOW_SEVEN_BITS).toLong() shl shift)
            if (next and CONTINUATION == 0) return result
            shift += SEVEN
        }
        return null
    }

    fun zigzag(): Long? {
        val raw = varint() ?: return null
        return (raw ushr 1) xor -(raw and 1L)
    }

    @Suppress("ReturnCount")
    fun int32(): Int? {
        val b3 = byte() ?: return null
        val b2 = byte() ?: return null
        val b1 = byte() ?: return null
        val b0 = byte() ?: return null
        return (b3 shl BYTE_3) or (b2 shl BYTE_2) or (b1 shl BYTE_1) or b0
    }

    fun float(): Float? = int32()?.let(Float::fromBits)

    @Suppress("ReturnCount")
    fun string(): String? {
        val length = varint() ?: return null
        if (length < 0 || length > remaining) return null
        val text = bytes.decodeToString(position, position + length.toInt())
        position += length.toInt()
        return text
    }

    /** The next [count] bytes, or null when fewer remain. */
    fun take(count: Int): ByteArray? {
        if (count < 0 || count > remaining) return null
        val taken = bytes.copyOfRange(position, position + count)
        position += count
        return taken
    }

    fun skip(count: Int): Boolean {
        if (count < 0 || count > remaining) return false
        position += count
        return true
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val LOW_SEVEN_BITS = 0x7F
        const val CONTINUATION = 0x80
        const val SEVEN = 7
        const val MAX_SHIFT = 64
        const val BYTE_3 = 24
        const val BYTE_2 = 16
        const val BYTE_1 = 8
    }
}

/**
 * FNV-1a, 64-bit — a fingerprint for **deduplication and stable bucketing, never for security**.
 *
 * Used to recognise the same strokes when they are harvested twice (a sample's `sourceKey`) and
 * to put a word in the held-out split by what it *is* rather than by when it was harvested, so a
 * re-harvest cannot move a word from the test set into the training set. Like `:core:sync`'s MD5,
 * it must never become a security primitive.
 */
internal object Fingerprint {
    private const val OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val PRIME = 0x100000001b3L

    fun of(bytes: ByteArray): Long {
        var hash = OFFSET_BASIS
        for (byte in bytes) {
            hash = hash xor (byte.toLong() and BYTE_MASK)
            hash *= PRIME
        }
        return hash
    }

    fun of(parts: List<Long>): Long {
        val sink = ByteSink(parts.size * Long.SIZE_BYTES)
        for (part in parts) {
            sink.int32((part ushr Int.SIZE_BITS).toInt())
            sink.int32(part.toInt())
        }
        return of(sink.toByteArray())
    }

    fun hex(value: Long): String = value.toULong().toString(HEX_RADIX).padStart(HEX_DIGITS, '0')

    private const val BYTE_MASK = 0xFFL
    private const val HEX_RADIX = 16
    private const val HEX_DIGITS = 16
}
