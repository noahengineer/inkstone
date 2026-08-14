package dev.inkstone.core.model

import kotlin.random.Random

/**
 * UUIDv7 generation — the one identity source in the app (CLAUDE.md, "Identity").
 *
 * v7 rather than v4 because the leading 48 bits are a millisecond timestamp, so
 * ids sort roughly by creation time as plain strings. That is worth having for
 * free: entries land in the bundle's central directory in creation order, and a
 * log grouped by object id reads chronologically.
 *
 * It is **not** an ordering primitive. Ops are ordered by [LamportClock]; the
 * timestamp inside an id is wall clock and inherits every problem wall clock has
 * (SPEC §4.1).
 *
 * ## Monotonicity within a millisecond
 *
 * Ids are minted faster than 1 kHz in bursts, so purely random `rand_a` would let
 * two ids created in the same millisecond sort either way between runs. Those 12
 * bits are therefore a per-millisecond counter (RFC 9562 §6.2, "replace leftmost
 * random bits with increased clock precision", method 1). Saturating the counter
 * costs sort stability, not uniqueness — the remaining 62 bits stay random.
 *
 * ## Why a class
 *
 * [Clock] is a SPEC §2.2 interface and [Random] is injectable, so every id in a
 * test is deterministic. A generator that reaches for the system clock itself
 * cannot be tested for the monotonicity above.
 *
 * Not thread-safe: ids are minted where edits originate, on the UI thread.
 */
public class IdFactory(private val clock: Clock, private val random: Random = Random.Default) {

    private var lastMillis: Long = -1L
    private var sequence: Int = 0

    public fun newDocumentId(): DocumentId = DocumentId(newUuid7())

    public fun newPageId(): PageId = PageId(newUuid7())

    public fun newObjectId(): ObjectId = ObjectId(newUuid7())

    public fun newFolderId(): FolderId = FolderId(newUuid7())

    /** M8c — one saved Element (SPEC §4.9.4). See [ElementId] for why it is not an [ObjectId]. */
    public fun newElementId(): ElementId = ElementId(newUuid7())

    /** SPEC §4.2 — every op carries one. A plain [String]; ops are never looked up by id. */
    public fun newOpId(): String = newUuid7()

    /** A per-install device identifier, for the [LamportClock] tiebreak. */
    public fun newDeviceId(): String = newUuid7()

    /**
     * M12 — one recording (docs/M12-AUDIO.md).
     *
     * A plain [String] rather than a value class, because it is not an identity the op log or
     * any fold ever sees: a recording is capture data, never document data (§3). It is three
     * things at once, and they must be the same string — the `recording` row's primary key,
     * `outbox.subject` for the row that uploads it, and the remote filename.
     *
     * UUIDv7, so a listing of a note's media directory is in chronological order without the
     * name being a timestamp — which §6 sketched and which collides for two recordings in one
     * minute, the exact case this feature is checked against.
     */
    public fun newRecordingId(): String = newUuid7()

    /**
     * `xxxxxxxx-xxxx-7xxx-Nxxx-xxxxxxxxxxxx`, lowercase hex.
     *
     * A clock that reports a negative epoch (an unset device clock) is clamped to
     * zero rather than rejected: a wrong-but-ordered id is recoverable, a crash
     * on the edit path is not.
     */
    public fun newUuid7(): String {
        val millis = clock.nowEpochMillis().coerceAtLeast(0L) and TIMESTAMP_MASK
        sequence = if (millis == lastMillis) (sequence + 1) and SEQUENCE_MASK else 0
        lastMillis = millis

        val randomBits = random.nextLong()

        return buildString(UUID_LENGTH) {
            appendHex(millis ushr SIXTEEN, HEX_8) // unix_ts_ms, high 32 bits
            append('-')
            appendHex(millis and MASK_16, HEX_4) // unix_ts_ms, low 16 bits
            append('-')
            appendHex(VERSION_7_HIGH or sequence.toLong(), HEX_4) // ver + rand_a as counter
            append('-')
            appendHex(VARIANT_HIGH or ((randomBits ushr FORTY_EIGHT) and MASK_14), HEX_4)
            append('-')
            appendHex(randomBits and MASK_48, HEX_12)
        }
    }

    private fun StringBuilder.appendHex(value: Long, digits: Int) {
        for (index in digits - 1 downTo 0) {
            append(HEX_DIGITS[((value ushr (index * BITS_PER_NIBBLE)) and NIBBLE_MASK).toInt()])
        }
    }

    private companion object {
        const val HEX_DIGITS = "0123456789abcdef"
        const val UUID_LENGTH = 36
        const val BITS_PER_NIBBLE = 4
        const val NIBBLE_MASK = 0xFL
        const val HEX_4 = 4
        const val HEX_8 = 8
        const val HEX_12 = 12
        const val SIXTEEN = 16
        const val FORTY_EIGHT = 48
        const val MASK_14 = 0x3FFFL
        const val MASK_16 = 0xFFFFL
        const val MASK_48 = 0xFFFF_FFFF_FFFFL
        const val TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL
        const val SEQUENCE_MASK = 0xFFF
        const val VERSION_7_HIGH = 0x7000L
        const val VARIANT_HIGH = 0x8000L
    }
}
