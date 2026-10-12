// A byte layout's masks, shifts and field offsets ARE the specification — 0xFF is not a
// magic number here, it is a byte, and `shl 8` is not a tunable, it is the next byte along.
// Naming each one would add a constant per bit position and make the layout harder to check
// against the format it implements, which is the opposite of what the rule is for.
@file:Suppress("MagicNumber")

package dev.inkstone.core.storage

/**
 * A deterministic, **stored** (uncompressed) ZIP writer and a random-access reader.
 *
 * SPEC §5.3 makes `note.inkstone` a ZIP, and the app needs both halves of that:
 * write it byte-identically for the same content, and read one entry out of it
 * without inflating the rest.
 *
 * ## Why hand-written
 *
 * `java.util.zip` is JVM-only and would silently kill the iPad path (CLAUDE.md rule
 * 2 — the exact class of mistake OkHttp and dav4jvm represent). Okio's `openZip`
 * reads but does not write. A stored ZIP is a few hundred bytes of framing, and
 * writing it here means one implementation on every target.
 *
 * ## Why stored and not deflated
 *
 * Three reasons, in order of weight:
 *
 * 1. **Byte-identical round-trip is an acceptance criterion** (ROADMAP M2). Deflate
 *    output depends on the encoder's version and settings; stored output depends on
 *    nothing.
 * 2. **There is very little to compress.** The bulk of a bundle is `.ink` geometry
 *    blobs, which `androidx.ink`'s serializer already compresses.
 * 3. **The container compresses it anyway.** M4 embeds this bundle in the PDF as a
 *    `/FlateDecode` stream (SPEC §5.2), so the JSON *is* compressed on the wire and
 *    at rest — just one layer out.
 *
 * The timestamp field is fixed at the DOS epoch for the same reason: a real
 * timestamp would make two identical bundles differ.
 */
internal object Zip {

    private const val LOCAL_HEADER_SIGNATURE = 0x04034B50L
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014B50L
    private const val END_OF_CENTRAL_SIGNATURE = 0x06054B50L

    private const val LOCAL_HEADER_SIZE = 30
    private const val CENTRAL_HEADER_SIZE = 46
    private const val END_OF_CENTRAL_SIZE = 22

    private const val VERSION_NEEDED = 20
    private const val METHOD_STORED = 0

    /** 1980-01-01 00:00 in DOS format. Zero is not a legal DOS date; this is the epoch. */
    private const val DOS_TIME = 0x0000
    private const val DOS_DATE = 0x0021

    /**
     * The end-of-central-directory record is the last 22 bytes unless the archive has a
     * comment, and this writer never writes one — so the first read is small, and the
     * 64 KB a comment is allowed to be is only read if the small read comes up empty.
     *
     * The difference is not academic: reading 66 KB to open every note would be most of a
     * 200-page bundle's size, which is precisely the cost SPEC §5.3's lazy loading exists
     * to avoid.
     */
    private const val EOCD_FIRST_READ = 512
    private const val EOCD_SEARCH_WINDOW = 66_000

    /** One entry, ready to write. */
    data class Entry(val name: String, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Entry && name == other.name && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = HASH_MULTIPLIER * name.hashCode() + bytes.contentHashCode()

        private companion object {
            const val HASH_MULTIPLIER = 31
        }
    }

    /**
     * Write [entries] in the order given.
     *
     * Order is the caller's business and it matters: `manifest.json` goes first so a
     * reader that only wants the document's metadata reads the front of the file.
     */
    fun write(entries: List<Entry>): ByteArray {
        // Sized exactly, up front. The buffer used to start at 1 KB and double, so writing the
        // chemistry note's 8.4 MB bundle allocated ~16 MB of abandoned intermediate arrays and then
        // copied the result once more — on every materialize. [ByteBuffer.toByteArray] hands the
        // array back without a copy when it is exactly full, which it now is; if this sum were ever
        // wrong the buffer would simply grow, so correctness does not depend on it.
        val exactSize = entries.sumOf { entry ->
            val nameBytes = entry.name.encodeToByteArray().size
            LOCAL_HEADER_SIZE + CENTRAL_HEADER_SIZE + 2 * nameBytes + entry.bytes.size
        } + END_OF_CENTRAL_SIZE
        val out = ByteBuffer(exactSize)
        val offsets = ArrayList<Long>(entries.size)
        val crcs = ArrayList<Long>(entries.size)

        for (entry in entries) {
            val name = entry.name.encodeToByteArray()
            val crc = Crc32.of(entry.bytes)
            offsets += out.size.toLong()
            crcs += crc

            out.u32(LOCAL_HEADER_SIGNATURE)
            out.u16(VERSION_NEEDED)
            out.u16(0) // flags
            out.u16(METHOD_STORED)
            out.u16(DOS_TIME)
            out.u16(DOS_DATE)
            out.u32(crc)
            out.u32(entry.bytes.size.toLong()) // compressed size
            out.u32(entry.bytes.size.toLong()) // uncompressed size
            out.u16(name.size)
            out.u16(0) // extra length
            out.bytes(name)
            out.bytes(entry.bytes)
        }

        val centralStart = out.size.toLong()
        for ((index, entry) in entries.withIndex()) {
            val name = entry.name.encodeToByteArray()
            out.u32(CENTRAL_HEADER_SIGNATURE)
            out.u16(VERSION_NEEDED) // version made by
            out.u16(VERSION_NEEDED) // version needed
            out.u16(0) // flags
            out.u16(METHOD_STORED)
            out.u16(DOS_TIME)
            out.u16(DOS_DATE)
            out.u32(crcs[index])
            out.u32(entry.bytes.size.toLong())
            out.u32(entry.bytes.size.toLong())
            out.u16(name.size)
            out.u16(0) // extra
            out.u16(0) // comment
            out.u16(0) // disk number
            out.u16(0) // internal attributes
            out.u32(0) // external attributes
            out.u32(offsets[index])
            out.bytes(name)
        }
        val centralSize = out.size - centralStart

        out.u32(END_OF_CENTRAL_SIGNATURE)
        out.u16(0) // this disk
        out.u16(0) // disk with central directory
        out.u16(entries.size)
        out.u16(entries.size)
        out.u32(centralSize)
        out.u32(centralStart)
        out.u16(0) // comment length

        return out.toByteArray()
    }

    /**
     * Read the central directory only.
     *
     * The point of the whole exercise: this is two small reads regardless of how big
     * the archive is, and it is what makes a 200-page notebook open without
     * deserialising 200 pages.
     *
     * @return null when [source] is not a ZIP — which is not an error here. A note's
     *   bundle can be missing or foreign (SPEC §5.6's "Recovered" and "Foreign PDF"
     *   modes both start with exactly this observation), and a null is how the caller
     *   finds that out.
     */
    // Each return is a different way the bytes are not a readable archive, and a null from any
    // of them means the same thing to the caller (see the KDoc). Funnelling them through one exit
    // would mean a nullable local threaded past every check.
    @Suppress("ReturnCount")
    suspend fun readDirectory(source: ByteSource): Directory? {
        val size = source.size()
        if (size < END_OF_CENTRAL_SIZE) return null

        var tailLength = minOf(size, EOCD_FIRST_READ.toLong()).toInt()
        var tail = source.read(size - tailLength, tailLength)
        var eocd = findEndOfCentralDirectory(tail)
        if (eocd == null && size > tailLength) {
            tailLength = minOf(size, EOCD_SEARCH_WINDOW.toLong()).toInt()
            tail = source.read(size - tailLength, tailLength)
            eocd = findEndOfCentralDirectory(tail) ?: return null
        }
        if (eocd == null) return null

        val entryCount = tail.readU16(eocd + ENTRY_COUNT_OFFSET)
        val centralSize = tail.readU32(eocd + CENTRAL_SIZE_OFFSET).toInt()
        val centralOffset = tail.readU32(eocd + CENTRAL_OFFSET_OFFSET)
        if (centralOffset < 0 || centralOffset + centralSize > size) return null

        val central = source.read(centralOffset, centralSize)
        val entries = LinkedHashMap<String, Located>(entryCount)
        var cursor = 0
        repeat(entryCount) {
            if (cursor + CENTRAL_HEADER_SIZE > central.size) return null
            if (central.readU32(cursor) != CENTRAL_HEADER_SIGNATURE) return null
            val crc = central.readU32(cursor + CENTRAL_CRC_OFFSET)
            val storedSize = central.readU32(cursor + CENTRAL_COMPRESSED_OFFSET).toInt()
            val nameLength = central.readU16(cursor + CENTRAL_NAME_LENGTH_OFFSET)
            val extraLength = central.readU16(cursor + CENTRAL_EXTRA_LENGTH_OFFSET)
            val commentLength = central.readU16(cursor + CENTRAL_COMMENT_LENGTH_OFFSET)
            val localOffset = central.readU32(cursor + CENTRAL_LOCAL_OFFSET_OFFSET)
            val nameStart = cursor + CENTRAL_HEADER_SIZE
            if (nameStart + nameLength > central.size) return null
            val name = central.decodeToString(nameStart, nameStart + nameLength)
            entries[name] = Located(name, localOffset, storedSize, crc)
            cursor = nameStart + nameLength + extraLength + commentLength
        }
        return Directory(source, entries)
    }

    private fun findEndOfCentralDirectory(tail: ByteArray): Int? {
        // Scan backwards: the record is at the very end unless there is a comment, and
        // scanning forwards would stop on a byte pattern inside an entry's data.
        for (offset in tail.size - END_OF_CENTRAL_SIZE downTo 0) {
            if (tail.readU32(offset) == END_OF_CENTRAL_SIGNATURE) return offset
        }
        return null
    }

    /** An entry's location in the archive, from the central directory. */
    data class Located(val name: String, val localHeaderOffset: Long, val size: Int, val crc: Long)

    /** The archive's index. Entries are read on demand, one at a time. */
    class Directory(private val source: ByteSource, private val entries: Map<String, Located>) {

        val names: Set<String> get() = entries.keys

        fun contains(name: String): Boolean = name in entries

        fun sizeOf(name: String): Int = entries[name]?.size ?: -1

        /** Whether the file this index was read from is still the one at its path. See [ByteSource.isCurrent]. */
        suspend fun isCurrent(): Boolean = source.isCurrent()

        /** Release the source. See [ByteSource.close]. */
        fun close() = source.close()

        /**
         * @return the entry's bytes, or null if it is absent.
         * @throws IllegalStateException if the entry's checksum does not match, which
         *   means the file is damaged. That is not a recoverable condition to paper
         *   over — SPEC §5.6 treats a bundle that does not verify as Recovered mode,
         *   and Recovered mode is a decision made above this layer.
         */
        suspend fun read(name: String): ByteArray? {
            val located = entries[name] ?: return null
            // The local header repeats the name, whose length is needed to find the
            // data. Reading it also catches an archive whose directory and headers
            // disagree, which is the shape a truncated upload has.
            val header = source.read(located.localHeaderOffset, LOCAL_HEADER_SIZE)
            if (header.size < LOCAL_HEADER_SIZE || header.readU32(0) != LOCAL_HEADER_SIGNATURE) {
                error("Bundle entry '$name' has no local header")
            }
            val nameLength = header.readU16(LOCAL_NAME_LENGTH_OFFSET)
            val extraLength = header.readU16(LOCAL_EXTRA_LENGTH_OFFSET)
            val dataOffset = located.localHeaderOffset + LOCAL_HEADER_SIZE + nameLength + extraLength
            val bytes = source.read(dataOffset, located.size)
            check(bytes.size == located.size) { "Bundle entry '$name' is truncated" }
            check(Crc32.of(bytes) == located.crc) { "Bundle entry '$name' failed its checksum" }
            return bytes
        }
    }

    // Field offsets, named so the readers above are checkable against the spec.
    private const val ENTRY_COUNT_OFFSET = 10
    private const val CENTRAL_SIZE_OFFSET = 12
    private const val CENTRAL_OFFSET_OFFSET = 16
    private const val CENTRAL_CRC_OFFSET = 16
    private const val CENTRAL_COMPRESSED_OFFSET = 20
    private const val CENTRAL_NAME_LENGTH_OFFSET = 28
    private const val CENTRAL_EXTRA_LENGTH_OFFSET = 30
    private const val CENTRAL_COMMENT_LENGTH_OFFSET = 32
    private const val CENTRAL_LOCAL_OFFSET_OFFSET = 42
    private const val LOCAL_NAME_LENGTH_OFFSET = 26
    private const val LOCAL_EXTRA_LENGTH_OFFSET = 28
}
