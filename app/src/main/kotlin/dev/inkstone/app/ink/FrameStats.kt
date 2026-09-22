package dev.inkstone.app.ink

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.util.concurrent.atomic.AtomicLong

/**
 * SPEC §3.6's other half — **the frame-rate gate, measured from inside the app.**
 *
 * ## Why this exists
 *
 * SPEC §3.6 requires *"sustained 60fps (120 on a 120Hz panel) with 5,000 dry strokes on-page,
 * **via `dumpsys gfxinfo`**"*, and the Measure panel already has the "Load 5,000 strokes" button
 * that sets that scene up. But `dumpsys` needs adb, and adb needs USB debugging authorised — which
 * on the one device the gate is actually about (CLAUDE.md's definition of done #4: the physical
 * Galaxy Tab) is routinely *not* granted, and cannot be granted from the desktop.
 *
 * So the gate had a harness that ran on the tablet and a readout that did not, and "is it 60fps?"
 * on the tablet came down to whether it *felt* smooth — which SPEC §3.6 opens by refusing:
 * *"do not declare M1 done on vibes."* [LatencyStats] is the same argument for the wet layer and
 * already lives beside this; this is the dry layer's.
 *
 * ## Why `OnFrameMetricsAvailableListener` and not a `Choreographer` callback
 *
 * ⚠️ **A self-reposting `Choreographer.postFrameCallback` measures a frame rate it is itself
 * causing.** Posting a frame callback *requests a frame*, so an idle page that should be drawing
 * nothing renders continuously at the panel's refresh rate for as long as the counter is on — the
 * measurement burns battery, and worse, it reports a healthy 60fps for a page that would in
 * reality have drawn two frames in that second.
 *
 * `Window.addOnFrameMetricsAvailableListener` reports frames that were **actually drawn**, with
 * the same per-stage breakdown `dumpsys gfxinfo` prints, and requests nothing. It is the same data
 * source, read from this side of the process boundary.
 *
 * The listener is handed a background [Handler] deliberately: it fires once per frame on whatever
 * thread it is given, and giving it the main looper would put the measurement on the thread whose
 * stalls are being measured.
 */
class FrameStats {

    private val frames = AtomicLong()
    private val totalNanos = AtomicLong()
    private val worstNanos = AtomicLong()

    /** Frames that missed a 60 Hz vsync. Counted separately because the mean hides a stutter. */
    private val janky = AtomicLong()

    /**
     * How much of the total the *draw* stage took — issuing the display list and handing it to
     * the render thread.
     *
     * Kept apart from the total because it is the number that says **which** fix is called for:
     * a page whose frames are long but whose draw stage is short is waiting on the GPU, and no
     * amount of doing less work on the UI thread will move it.
     */
    private val drawNanos = AtomicLong()

    /**
     * ⚠️ **The draw-call wall does not appear in [drawNanos], and this is the metric that sees it.**
     *
     * `DRAW_DURATION` is the UI thread *recording* a display list. Handing those commands to the
     * GPU happens on the RenderThread and is `COMMAND_ISSUE_DURATION` — so a page of 2,000 strokes
     * whose display list is cached (nothing to re-record) reads as a tiny `draw` and a huge total,
     * and without this it is impossible to say from inside the app whether the missing time is
     * **submitting** 2,000 draw calls or **shading** their pixels.
     *
     * That distinction decides the whole fix: submission cost is what a rasterised cache removes,
     * and shading cost is not.
     */
    private val issueNanos = AtomicLong()

    private var window: Window? = null
    private var thread: HandlerThread? = null

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        frames.addAndGet(1L + dropped.coerceAtLeast(0).toLong())
        totalNanos.addAndGet(total)
        drawNanos.addAndGet(metrics.getMetric(FrameMetrics.DRAW_DURATION))
        issueNanos.addAndGet(metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION))
        if (total > budgetNanos) janky.incrementAndGet()
        while (true) {
            val worst = worstNanos.get()
            if (total <= worst || worstNanos.compareAndSet(worst, total)) break
        }
    }

    /**
     * The frame budget being counted against, from the display's **actual** refresh rate.
     *
     * ⚠️ Not a hardcoded 60. A Galaxy Tab's panel is 90 or 120 Hz, so a frame that comfortably
     * clears 16.7 ms can still be a dropped one — counting against 60 on such a panel reports a
     * visibly stuttering page as perfect. Falls back to 60 only when the rate cannot be read.
     */
    private var budgetNanos = VSYNC_60_NANOS

    /** The refresh rate the jank count is being judged against, for the readout to state. */
    var refreshHz: Float = SIXTY_HZ
        private set

    /** Begin counting. Idempotent — a second call on the same window does nothing. */
    fun attach(context: Context) {
        if (window != null) return
        val activity = context.activity() ?: return
        val host = activity.window ?: return
        val hz = activity.display?.refreshRate ?: SIXTY_HZ
        if (hz > 1f) {
            refreshHz = hz
            budgetNanos = (NANOS_PER_SECOND / hz).toLong()
        }
        val looper = HandlerThread("frame-stats").apply { start() }
        thread = looper
        window = host
        host.addOnFrameMetricsAvailableListener(listener, Handler(looper.looper))
    }

    /**
     * Stop counting and release the thread.
     *
     * ⚠️ Not optional. The listener holds the window, and the [HandlerThread] runs until it is
     * quit — an editor left and re-entered a dozen times would otherwise leave a dozen threads
     * running and a dozen dead windows referenced.
     */
    fun detach() {
        window?.removeOnFrameMetricsAvailableListener(listener)
        window = null
        thread?.quitSafely()
        thread = null
    }

    fun reset() {
        frames.set(0)
        totalNanos.set(0)
        worstNanos.set(0)
        janky.set(0)
        drawNanos.set(0)
        issueNanos.set(0)
    }

    fun snapshot(): Snapshot {
        val count = frames.get()
        return Snapshot(
            frameCount = count,
            meanMillis = if (count == 0L) 0f else totalNanos.get() / count / NANOS_PER_MILLI,
            worstMillis = worstNanos.get() / NANOS_PER_MILLI,
            jankyCount = janky.get(),
            meanDrawMillis = if (count == 0L) 0f else drawNanos.get() / count / NANOS_PER_MILLI,
            meanIssueMillis = if (count == 0L) 0f else issueNanos.get() / count / NANOS_PER_MILLI,
            refreshHz = refreshHz,
        )
    }

    /**
     * @property meanDrawMillis the UI thread recording the display list.
     * @property meanIssueMillis the RenderThread handing it to the GPU — see [issueNanos]. This is
     *   the one that answers *"is it the draw calls?"*, and the two must be read together: draw
     *   low and issue high is thousands of submissions, both low against a large total is pixel
     *   shading or a stalled swap.
     */
    data class Snapshot(
        val frameCount: Long,
        val meanMillis: Float,
        val worstMillis: Float,
        val jankyCount: Long,
        val meanDrawMillis: Float,
        val meanIssueMillis: Float = 0f,
        val refreshHz: Float = SIXTY_HZ,
    ) {
        /** Frames per second implied by the mean, or 0 before anything has been drawn. */
        val fps: Float get() = if (meanMillis <= 0f) 0f else MILLIS_PER_SECOND / meanMillis
    }

    internal companion object {
        const val NANOS_PER_MILLI = 1_000_000f
        const val MILLIS_PER_SECOND = 1000f
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val SIXTY_HZ = 60f

        /** The fallback budget, used only when the display will not say what it runs at. */
        const val VSYNC_60_NANOS = 16_666_667L
    }
}

/** The [Activity] behind a Compose `LocalContext`, which is usually a `ContextWrapper` chain. */
private fun Context.activity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
