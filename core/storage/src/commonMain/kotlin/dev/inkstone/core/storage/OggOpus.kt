package dev.inkstone.core.storage

import dev.inkstone.core.model.FileSystem

/**
 * ⭐ M12 §9 — how long an Ogg/Opus recording actually is, read from the **container**.
 *
 * ## Why this exists rather than `bytes × 8 ÷ bitrate`
 *
 * Recovery (§9) has to put a duration on a lecture the process died in the middle of, and nothing
 * recorded one — the row still says CAPTURING. The obvious estimate is the file's length at the
 * bitrate it was asked for, and measured on a real recording it was **42% wrong**: 98,369 bytes of
 * a 23.03-second recording, against a requested 24 kbit/s that would imply 32.8 seconds.
 *
 * `MediaRecorder` treats `setAudioEncodingBitRate` as a hint and Opus is VBR, so the real average
 * came out at ~34 kbit/s. That is not a number to build on, and the container already knows the
 * answer exactly: **the last page's granule position is the stream's length in 48 kHz samples.**
 *
 * ## Reading the tail, not the file
 *
 * A three-hour lecture is tens of megabytes and this runs at launch, so it reads the **last
 * [TAIL_BYTES]** through [FileSystem.readRange] — the primitive SPEC §5.3 added so a 200-page
 * notebook opens without inflating 200 pages — and scans backwards for the last `OggS` capture
 * pattern. An Ogg page is at most ~64 KB (255 segments of 255 bytes plus its header), so a window
 * of that size always contains a complete page boundary.
 *
 * Returns null when the file is not an Opus stream, is empty, or holds no page with a granule —
 * *"cannot tell"*, never a guess. The caller shows nothing rather than something wrong.
 */
public object OggOpus {

    /** Opus is always 48 kHz in the container, whatever the input rate was (RFC 7845). */
    public const val GRANULE_RATE: Int = 48_000

    /** Comfortably larger than the maximum Ogg page (27 + 255 + 255×255 ≈ 65 KB). */
    public const val TAIL_BYTES: Int = 96 * 1024

    private const val HEADER_BYTES = 27
    private const val GRANULE_OFFSET = 6
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())

    public suspend fun durationMillis(fs: FileSystem, path: String): Long? {
        val size = fs.size(path)
        if (size <= 0L) return null
        val from = (size - TAIL_BYTES).coerceAtLeast(0L)
        val tail = fs.readRange(path, from, minOf(size - from, TAIL_BYTES.toLong()).toInt())
        val granule = lastGranule(tail) ?: return null
        return granule * MILLIS_PER_SECOND / GRANULE_RATE
    }

    /**
     * The granule position of the last complete-looking page in [window].
     *
     * ⚠️ Scans **backwards**, and takes the first page whose header fits — a killed recording ends
     * in a torn page, and the last four bytes that happen to read `OggS` may be inside audio data
     * rather than a real header. Requiring `HEADER_BYTES` to fit and the granule to be
     * non-negative rejects the common form of that; a wrong answer here costs a wrong number in a
     * list and never a wrong file.
     */
    internal fun lastGranule(window: ByteArray): Long? {
        var at = window.size - HEADER_BYTES
        while (at >= 0) {
            if (matchesMagic(window, at)) {
                val granule = readLittleEndianLong(window, at + GRANULE_OFFSET)
                if (granule >= 0) return granule
            }
            at--
        }
        return null
    }

    private fun matchesMagic(data: ByteArray, at: Int): Boolean {
        for (i in MAGIC.indices) if (data[at + i] != MAGIC[i]) return false
        return true
    }

    // A byte layout's shifts and masks ARE the specification — the same argument `Journal.kt`
    // makes at the top of the file. `8` is a byte, `7 downTo 0` is the eight bytes of a
    // little-endian 64-bit granule (RFC 3533 §6), and `0xFF` is a byte. Naming each one would add
    // a constant per bit position and make the layout harder to check against the format.
    @Suppress("MagicNumber")
    private fun readLittleEndianLong(data: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
        return value
    }

    private const val MILLIS_PER_SECOND = 1_000L
}
