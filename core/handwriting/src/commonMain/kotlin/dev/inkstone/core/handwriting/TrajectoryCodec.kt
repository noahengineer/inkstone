package dev.inkstone.core.handwriting

import dev.inkstone.core.model.PenTrajectory
import kotlin.math.PI
import kotlin.math.roundToLong

/**
 * [PenTrajectory] as bytes — docs/HANDWRITING-SYNTHESIS.md §4.2.
 *
 * ```
 * "IHWT" | version u8 | flags u8 | strokeCount varint | pointCount varint × strokeCount
 *        | per point: Δx Δy Δt [Δpressure] [Δtilt], each a zig-zag varint of a quantised integer
 * ```
 *
 * Quantised to **1/100 pt** for position, **1 ms** for time and **1/1023** of the range for
 * pressure and tilt, then delta-coded, so a slowly moving pen costs a byte or two per channel per
 * sample: about 6 bytes a point against 16 for raw floats. A word is a few hundred points and a
 * profile tens of thousands of words, so the difference is tens of megabytes on a tablet.
 *
 * A hundredth of a point is a few thousandths of a millimetre — far below what a digitiser
 * resolves — and still lossy, which matters in one place: two distinct samples can round to the
 * same position at the same millisecond. `androidx.ink` refuses that repeat, so [decode] rebuilds
 * through [PenTrajectory.Builder], which drops it.
 */
public object TrajectoryCodec {
    private val MAGIC = "IHWT".encodeToByteArray()
    private const val VERSION = 1
    private const val HAS_PRESSURE = 1
    private const val HAS_TILT = 2

    /** Positions are stored in hundredths of a point. */
    public const val POSITION_SCALE: Float = 100f

    /** Pressure and tilt are stored as 1023 steps across their range. */
    public const val CHANNEL_STEPS: Float = 1023f

    private const val HALF_PI = (PI / 2).toFloat()

    public fun encode(trajectory: PenTrajectory): ByteArray {
        val sink = ByteSink(trajectory.pointCount * BYTES_PER_POINT_ESTIMATE + HEADER_ESTIMATE)
        write(trajectory, sink)
        return sink.toByteArray()
    }

    /** Null for anything that is not a whole, valid trajectory written by this or an older build. */
    public fun decode(bytes: ByteArray): PenTrajectory? = read(ByteCursor(bytes))

    internal fun write(trajectory: PenTrajectory, sink: ByteSink) {
        sink.bytes(MAGIC)
        sink.byte(VERSION)
        val pressure = trajectory.pressure
        val tilt = trajectory.tilt
        sink.byte((if (pressure != null) HAS_PRESSURE else 0) or (if (tilt != null) HAS_TILT else 0))
        sink.varint(trajectory.strokeCount.toLong())
        for (stroke in 0 until trajectory.strokeCount) {
            sink.varint((trajectory.strokeEnd(stroke) - trajectory.strokeStart(stroke)).toLong())
        }
        var lastX = 0L
        var lastY = 0L
        var lastT = 0L
        var lastP = 0L
        var lastTilt = 0L
        for (i in 0 until trajectory.pointCount) {
            val qx = (trajectory.x[i] * POSITION_SCALE).roundToLong()
            val qy = (trajectory.y[i] * POSITION_SCALE).roundToLong()
            val qt = trajectory.tMs[i].toLong()
            sink.zigzag(qx - lastX)
            sink.zigzag(qy - lastY)
            sink.zigzag(qt - lastT)
            lastX = qx
            lastY = qy
            lastT = qt
            if (pressure != null) {
                val qp = (pressure[i] * CHANNEL_STEPS).roundToLong()
                sink.zigzag(qp - lastP)
                lastP = qp
            }
            if (tilt != null) {
                val qtl = (tilt[i] / HALF_PI * CHANNEL_STEPS).roundToLong()
                sink.zigzag(qtl - lastTilt)
                lastTilt = qtl
            }
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    internal fun read(cursor: ByteCursor): PenTrajectory? {
        val magic = cursor.take(MAGIC.size) ?: return null
        if (!magic.contentEquals(MAGIC)) return null
        val version = cursor.byte() ?: return null
        // A newer version may have changed what the bytes after this mean. Refuse rather than guess:
        // the caller keeps the bytes and does not rewrite them (`ProfileStore`'s unreadable shards).
        if (version > VERSION) return null
        val flags = cursor.byte() ?: return null
        val hasPressure = flags and HAS_PRESSURE != 0
        val hasTilt = flags and HAS_TILT != 0

        val strokes = cursor.varint() ?: return null
        if (strokes <= 0 || strokes > MAX_STROKES) return null
        val counts = IntArray(strokes.toInt())
        var total = 0L
        for (s in counts.indices) {
            val count = cursor.varint() ?: return null
            if (count <= 0 || count > MAX_POINTS) return null
            counts[s] = count.toInt()
            total += count
        }
        if (total > MAX_POINTS) return null

        val builder = PenTrajectory.Builder(total.toInt(), hasPressure, hasTilt)
        var lastX = 0L
        var lastY = 0L
        var lastT = 0L
        var lastP = 0L
        var lastTilt = 0L
        for (count in counts) {
            repeat(count) {
                lastX += cursor.zigzag() ?: return null
                lastY += cursor.zigzag() ?: return null
                lastT += cursor.zigzag() ?: return null
                if (hasPressure) lastP += cursor.zigzag() ?: return null
                if (hasTilt) lastTilt += cursor.zigzag() ?: return null
                builder.add(
                    x = lastX / POSITION_SCALE,
                    y = lastY / POSITION_SCALE,
                    tMs = lastT.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    pressure = (lastP / CHANNEL_STEPS).coerceIn(0f, 1f),
                    tilt = (lastTilt / CHANNEL_STEPS * HALF_PI).coerceIn(0f, HALF_PI),
                )
            }
            builder.endStroke()
        }
        return builder.build()
    }

    private const val BYTES_PER_POINT_ESTIMATE = 8
    private const val HEADER_ESTIMATE = 16

    /** A word is hundreds of points; these bounds only refuse garbage before it allocates. */
    private const val MAX_STROKES = 10_000L
    private const val MAX_POINTS = 2_000_000L
}
