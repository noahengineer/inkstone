package dev.inkstone.android.ink

import android.annotation.SuppressLint
import androidx.ink.authoring.ExperimentalLatencyDataApi
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.authoring.latency.LatencyData
import java.util.concurrent.atomic.AtomicLong

/**
 * Wires ink's per-input latency reporting into [stats].
 *
 * An extension rather than a line in `InkSurfaceView.init` so that both
 * annotations below stay next to the only code that needs them, instead of
 * widening to the whole surface class.
 *
 * `setLatencyDataCallback` is `@RestrictTo(LIBRARY_GROUP)` as well as
 * experimental. Suppressed deliberately and narrowly, on the same reasoning as
 * the pencil family (see [InkBrushKind]) but with less at stake: this is
 * measurement scaffolding for the SPEC §3.6 gate, not shipping behaviour. If a
 * future ink version removes it, the latency HUD goes away and the ink does not.
 */
@OptIn(ExperimentalLatencyDataApi::class)
@SuppressLint("RestrictedApi")
internal fun InProgressStrokesView.reportLatencyTo(stats: LatencyStats) {
    setLatencyDataCallback(stats::record)
}

/**
 * SPEC §3.6 — "do not declare M1 done on vibes."
 *
 * `androidx.ink` timestamps each input from the moment the OS detected the
 * event to the moment the resulting pixels are estimated to reach the panel.
 * The difference is end-to-end ink latency for *this* app, on *this* device, and
 * it costs nothing to collect.
 *
 * It does not replace the 240 fps camera comparison against Samsung Notes —
 * Samsung's number is not measurable from inside our process — but it is the
 * number that tells you whether a change helped, which is what you need while
 * you are still making changes. The camera test is the gate; this is the
 * instrument.
 *
 * Written from ink's internal threads and read from the UI thread, hence the
 * atomics. It is off the ink path in the sense that matters: no allocation, no
 * locks, no I/O.
 */
public class LatencyStats {

    private val samples = AtomicLong(0)
    private val totalNanos = AtomicLong(0)
    private val worstNanos = AtomicLong(0)

    @OptIn(ExperimentalLatencyDataApi::class)
    @SuppressLint("RestrictedApi") // The whole androidx.ink.authoring.latency package is library-group-restricted.
    internal fun record(latencyData: LatencyData) {
        if (!latencyData.isOsDetectsEventSet) return
        val elapsed = latencyData.estimatedPixelPresentationTime - latencyData.osDetectsEvent
        // A negative or absurd delta means one of the two clocks was not
        // populated for this event; counting it would poison the mean.
        if (elapsed <= 0L || elapsed > MAX_PLAUSIBLE_NANOS) return

        samples.incrementAndGet()
        totalNanos.addAndGet(elapsed)
        var currentWorst = worstNanos.get()
        while (elapsed > currentWorst && !worstNanos.compareAndSet(currentWorst, elapsed)) {
            currentWorst = worstNanos.get()
        }
    }

    public fun reset() {
        samples.set(0)
        totalNanos.set(0)
        worstNanos.set(0)
    }

    public fun snapshot(): Snapshot {
        val count = samples.get()
        return Snapshot(
            sampleCount = count,
            meanMillis = if (count == 0L) 0f else totalNanos.get() / count / NANOS_PER_MILLI,
            worstMillis = worstNanos.get() / NANOS_PER_MILLI,
        )
    }

    public data class Snapshot(val sampleCount: Long, val meanMillis: Float, val worstMillis: Float)

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000f
        const val MAX_PLAUSIBLE_NANOS = 500_000_000L
    }
}
