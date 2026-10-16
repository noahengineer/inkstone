package dev.inkstone.app.handwriting

import android.content.Context
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.inkstone.android.ink.MultiFingerTapDetector
import dev.inkstone.android.ink.isCancelledPointer
import dev.inkstone.android.ink.isHardwareEraser
import dev.inkstone.android.ink.isStylusPointer
import dev.inkstone.app.ui.ChipDivider
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.TextChip
import dev.inkstone.core.handwriting.QuickStart
import dev.inkstone.core.handwriting.QuickStartResult
import dev.inkstone.core.model.PenTrajectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI

/**
 * ⭐ D14's optional quick start — about three minutes copying [QuickStart.PROMPTS], for someone with
 * no notes to learn from yet.
 *
 * The writing area is a plain capture surface, not a note: what is written here is the prompt's
 * sample, never ink in a document. Every pen sample is kept — position, time, pressure, tilt — in
 * page points at this screen's density, which is what a learned word's trajectory is in.
 *
 * It takes the pen the way a page does ([PenInput]): the pen writes, a flipped pen or a held barrel
 * button erases, and two fingers tapped undo while three redo. A slip costs one undo, not the whole
 * prompt — the first version had only *Clear*.
 *
 * Nothing here calls the recogniser. The prompt is the label (`LabelSource.CONFIRMED`); the next
 * learning pass is what cuts joined runs and reads them back.
 */
@Composable
fun QuickStartScreen(
    onSave: suspend (prompt: String, index: Int, written: PenTrajectory) -> QuickStartResult,
    onDone: () -> Unit,
    startAt: Int = 0,
) {
    val context = LocalContext.current
    val ink = remember { PromptInk() }
    val pen = remember { PenInput(context, ink) }
    val progress = remember { QuickStartProgress(ink, pen, startAt) }
    val scope = rememberCoroutineScope()
    val pxPerPoint = LocalDensity.current.density * DP_PER_INCH / POINTS_PER_INCH

    Column(modifier = Modifier.fillMaxSize().background(Ink.Canvas)) {
        GlassBar(modifier = Modifier.padding(InkDims.EdgePadding)) {
            TextChip(label = "‹ Back", onClick = onDone)
            ChipDivider()
            MicroLabel("Quick start", color = Ink.TextStrong)
        }
        Column(
            modifier = Modifier.padding(InkDims.EdgePadding),
            verticalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        ) {
            val prompt = progress.prompt
            if (prompt == null) {
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    MicroLabel(
                        "Done: ${progress.learned} words recorded. Letters written joined up are cut on the " +
                            "next learning pass.",
                    )
                    TextChip(label = "Finish", selected = true, onClick = onDone)
                }
                return@Column
            }
            MicroLabel(
                "Copy this on one line, the way you usually write, with a clear gap between words. " +
                    "${progress.index + 1} of ${QuickStart.PROMPTS.size}.",
            )
            Text(prompt, style = MaterialTheme.typography.headlineSmall, color = Ink.TextStrong)
            WritingArea(pen, progress, pxPerPoint)
            MicroLabel("Two fingers tap to undo, three to redo. Flip the pen or hold its button to erase.")
            EditRow(progress, onNext = { progress.next(scope, onSave) })
            if (progress.message.isNotEmpty()) MicroLabel(progress.message)
        }
    }
}

@Composable
private fun EditRow(progress: QuickStartProgress, onNext: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap)) {
        // Read so the row recomposes on every edit: what can be undone changes with each stroke.
        @Suppress("UNUSED_EXPRESSION")
        progress.version
        TextChip(label = "Erase", selected = progress.eraseMode, onClick = progress::toggleErase)
        TextChip(label = "Undo", enabled = progress.ink.canUndo, onClick = progress::undo)
        TextChip(label = "Redo", enabled = progress.ink.canRedo, onClick = progress::redo)
        TextChip(label = "Clear", enabled = !progress.ink.isEmpty, onClick = progress::clear)
        ChipDivider()
        TextChip(
            label = if (progress.saving) "Saving…" else "Next",
            selected = true,
            enabled = !progress.saving && !progress.ink.isEmpty,
            onClick = onNext,
        )
        TextChip(label = "Skip", onClick = progress::skip)
    }
}

/** Where the screen is in [QuickStart.PROMPTS], and what it last said. */
@Stable
private class QuickStartProgress(val ink: PromptInk, private val pen: PenInput, startAt: Int) {
    var index by mutableIntStateOf(startAt)
    var message by mutableStateOf("")
    var saving by mutableStateOf(false)
    var learned by mutableIntStateOf(0)
    var eraseMode by mutableStateOf(false)
        private set

    /** Bumped on every change to what is written, so the area and the chips redraw. */
    var version by mutableIntStateOf(0)
        private set

    val prompt: String? get() = QuickStart.PROMPTS.getOrNull(index)

    fun changed() {
        version++
    }

    fun toggleErase() {
        eraseMode = !eraseMode
        pen.eraseMode = eraseMode
    }

    fun undo() {
        if (ink.undo()) changed()
    }

    fun redo() {
        if (ink.redo()) changed()
    }

    /** One undoable edit, like any other: *Clear* by mistake is one undo away. */
    fun clear() {
        ink.clear()
        message = ""
        changed()
    }

    fun skip() {
        startPrompt(index + 1)
    }

    private fun startPrompt(next: Int) {
        pen.cancel()
        ink.reset()
        message = ""
        index = next
        changed()
    }

    /** Saves what was written for this prompt; a prompt that did not split into its words stays. */
    fun next(scope: CoroutineScope, onSave: suspend (String, Int, PenTrajectory) -> QuickStartResult) {
        val written = ink.trajectory() ?: return
        val at = prompt ?: return
        saving = true
        scope.launch {
            val result = onSave(at, index, written)
            saving = false
            if (result is QuickStartResult.Learned) {
                learned += result.samples.size
                startPrompt(index + 1)
            }
            message = messageOf(result)
        }
    }
}

/** What the screen says after a prompt is saved, or why it was not. */
private fun messageOf(result: QuickStartResult): String = when (result) {
    is QuickStartResult.Learned -> "Recorded ${result.samples.size} words."

    QuickStartResult.Empty -> "Nothing was written."

    QuickStartResult.NotOneLine -> "Please write it on one line. Erase or undo what ran over, and try again."

    is QuickStartResult.WordsDidNotSplit ->
        "The gaps did not make ${result.expected} words — leave a clearer space between words."
}

/** The surface written on: every sample recorded, and drawn as it comes. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WritingArea(pen: PenInput, progress: QuickStartProgress, pxPerPoint: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(AreaHeight)
            .background(Color(PAPER_ARGB), InkDims.PanelCorner)
            .border(1.dp, Ink.PanelBorder, InkDims.PanelCorner)
            .semantics { contentDescription = "Quick start writing area" }
            .pointerInteropFilter { event ->
                pen.onTouch(event, pxPerPoint)
                progress.changed()
                true
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            progress.version
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (written in progress.ink.visible) drawSamples(written, pxPerPoint, stroke)
            pen.inProgress?.let { drawSamples(it, pxPerPoint, stroke) }
        }
    }
}

private fun DrawScope.drawSamples(samples: List<Sample>, pxPerPoint: Float, style: Stroke) {
    if (samples.isEmpty()) return
    val path = Path()
    samples.forEachIndexed { i, sample ->
        val at = Offset(sample.x * pxPerPoint, sample.y * pxPerPoint)
        if (i == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
    }
    drawPath(path, Color(INK_ARGB), style = style)
}

/**
 * The writing area's touch rules — the note editor's, on a capture surface (SPEC §3.3):
 * - **the pen writes.** Flipped, or with its barrel button held, it erases ([isHardwareEraser]),
 *   and with [eraseMode] on its tip erases too. A stroke the system cancels is dropped.
 * - **fingers never draw.** Two tapped together undo and three redo ([MultiFingerTapDetector]),
 *   exactly as on a page. A palm is delivered as a finger, so it cannot mark the sample either.
 *
 * The UI thread is the only caller, and the saving coroutine reads [PromptInk] once, after the
 * press on *Next*, so nothing here is read while written. Internal for `QuickStartPenInputTest`,
 * which drives it with real multi-pointer events — `adb shell input` cannot tap with two fingers or
 * hold a barrel button, so a device walk alone could not reach either rule.
 */
internal class PenInput(context: Context, private val ink: PromptInk) {
    private val taps = MultiFingerTapDetector(context)
    private var current: MutableList<Sample>? = null
    private var penId = MotionEvent.INVALID_POINTER_ID
    private var erasing = false
    private var lastX = 0f
    private var lastY = 0f
    private var firstAt = -1L

    var eraseMode = false

    /** The stroke the pen is drawing, not yet in [ink]. */
    val inProgress: List<Sample>? get() = current

    fun onTouch(event: MotionEvent, pxPerPoint: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> down(event, event.actionIndex, pxPerPoint)
            MotionEvent.ACTION_MOVE -> move(event, pxPerPoint)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(event, event.actionIndex, pxPerPoint)
            MotionEvent.ACTION_CANCEL -> cancel()
        }
    }

    private fun down(event: MotionEvent, index: Int, pxPerPoint: Float) {
        // One pen at a time: a hand landing beside a stroke in progress is neither ink nor a tap.
        if (penId != MotionEvent.INVALID_POINTER_ID) return
        if (!event.isStylusPointer(index)) {
            taps.onPointerDown(event)
            return
        }
        taps.cancel()
        penId = event.getPointerId(index)
        if (firstAt < 0) firstAt = event.downTime
        erasing = eraseMode || event.isHardwareEraser(index)
        if (erasing) {
            lastX = event.getX(index) / pxPerPoint
            lastY = event.getY(index) / pxPerPoint
            ink.eraseAlong(lastX, lastY, lastX, lastY, ERASE_RADIUS_PT)
        } else {
            current = mutableListOf()
            add(event, index, pxPerPoint)
        }
    }

    private fun move(event: MotionEvent, pxPerPoint: Float) {
        taps.onMove(event)
        val index = event.findPointerIndex(penId)
        if (index < 0) return
        if (erasing) {
            for (h in 0 until event.historySize) {
                eraseTo(event.getHistoricalX(index, h) / pxPerPoint, event.getHistoricalY(index, h) / pxPerPoint)
            }
            eraseTo(event.getX(index) / pxPerPoint, event.getY(index) / pxPerPoint)
        } else {
            add(event, index, pxPerPoint)
        }
    }

    private fun up(event: MotionEvent, index: Int, pxPerPoint: Float) {
        if (event.getPointerId(index) != penId) {
            when (taps.onPointerUp(event)) {
                MultiFingerTapDetector.UNDO_FINGERS -> ink.undo()
                MultiFingerTapDetector.REDO_FINGERS -> ink.redo()
                else -> Unit
            }
            return
        }
        if (erasing) {
            ink.endErase()
        } else if (!event.isCancelledPointer()) {
            add(event, index, pxPerPoint)
            current?.let { ink.add(it.toList()) }
        }
        current = null
        penId = MotionEvent.INVALID_POINTER_ID
        erasing = false
    }

    /** The gesture ended without finishing: a half stroke is dropped, an erase so far is kept. */
    fun cancel() {
        if (erasing) ink.endErase()
        current = null
        penId = MotionEvent.INVALID_POINTER_ID
        erasing = false
        taps.cancel()
    }

    private fun eraseTo(x: Float, y: Float) {
        ink.eraseAlong(lastX, lastY, x, y, ERASE_RADIUS_PT)
        lastX = x
        lastY = y
    }

    private fun add(event: MotionEvent, index: Int, pxPerPoint: Float) {
        val stroke = current ?: return
        for (h in 0 until event.historySize) {
            stroke += Sample(
                event.getHistoricalX(index, h) / pxPerPoint,
                event.getHistoricalY(index, h) / pxPerPoint,
                event.getHistoricalEventTime(h) - firstAt,
                event.getHistoricalPressure(index, h).coerceIn(0f, 1f),
                event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, h).coerceIn(0f, HALF_PI),
            )
        }
        stroke += Sample(
            event.getX(index) / pxPerPoint,
            event.getY(index) / pxPerPoint,
            event.eventTime - firstAt,
            event.getPressure(index).coerceIn(0f, 1f),
            event.getAxisValue(MotionEvent.AXIS_TILT, index).coerceIn(0f, HALF_PI),
        )
    }
}

private val AreaHeight = 180.dp
private const val DP_PER_INCH = 160f
private const val POINTS_PER_INCH = 72f
private const val HALF_PI = (PI / 2).toFloat()
private const val PAPER_ARGB = 0xFFF8F7F2.toInt()
private const val INK_ARGB = 0xFF1A1A1A.toInt()

/**
 * The eraser's reach, in page points: about 3 mm, several times the line's own width, so a stroke is
 * easy to hit. Whole strokes go, so a generous reach cannot leave a sliver behind.
 */
private const val ERASE_RADIUS_PT = 8f
