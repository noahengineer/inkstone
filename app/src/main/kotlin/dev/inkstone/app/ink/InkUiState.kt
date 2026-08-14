package dev.inkstone.app.ink

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.inkstone.android.ink.EraseMode
import dev.inkstone.android.ink.InkBrushKind
import dev.inkstone.android.ink.InkInputModel
import dev.inkstone.android.ink.LatencyStats
import dev.inkstone.android.ink.PenConfig
import dev.inkstone.android.ink.StylusStats
import dev.inkstone.core.model.Camera

/**
 * Everything the M1 toolbar can change, and nothing else.
 *
 * M1 has **no persistence** (ROADMAP M1, "Explicitly NOT in M1"), so this is
 * plain in-memory Compose state with no repository, no DataStore and no
 * ViewModel behind it. Close the app and the drawing is gone; that is correct
 * for this milestone.
 */
@Stable
class InkUiState {

    /**
     * One remembered configuration per brush, so switching pens keeps colour
     * and width. Defaults are chosen so each brush is immediately
     * recognisable: a fine black fountain pen, a slightly heavier blue
     * ballpoint, a soft grey pencil, and a wide translucent yellow highlighter.
     */
    private val perBrush = mutableStateMapOf(
        InkBrushKind.FOUNTAIN to PenConfig(InkBrushKind.FOUNTAIN, 0xFF1A1A1A.toInt(), 3f),
        InkBrushKind.BALLPOINT to PenConfig(InkBrushKind.BALLPOINT, 0xFF1D4ED8.toInt(), 2.5f),
        InkBrushKind.PENCIL to PenConfig(InkBrushKind.PENCIL, 0xFF44403C.toInt(), 4f),
        InkBrushKind.HIGHLIGHTER to PenConfig(InkBrushKind.HIGHLIGHTER, 0x80FDE047.toInt(), 16f),
    )

    var kind by mutableStateOf(InkBrushKind.FOUNTAIN)
        private set

    /**
     * ⭐ M8a — pen, eraser or lasso, as one value. ⭐ M8c — and the five tools it added.
     *
     * Was `eraserSelected: Boolean`. Selection made a second boolean the obvious move and a
     * wrong one: two flags make "eraser and lasso both on" representable and nothing would have
     * resolved it. M8c faced the identical choice for its overlay tools and took the identical
     * answer — see [EditorTool], which is now the single value and derives what the ink surface
     * is told.
     */
    var tool by mutableStateOf(EditorTool.PEN)

    /**
     * ⭐ M8c — the ink surface's camera, mirrored so Compose can lay out over the page.
     *
     * The overlay places a text field at a page-space box and turns a tap back into page space, so
     * it needs the same transform the ink surface is drawing with. Pushed from
     * `InkSurfaceView.onCameraChanged` — the callback that already existed for the zoom readout —
     * rather than read on recomposition, because a `View`'s field is not Compose state and nothing
     * would invalidate on a pan.
     */
    var camera by mutableStateOf(Camera.Identity)

    /**
     * ⭐ ROADMAP M8c — whether the straight edge is out.
     *
     * ⚠️ **A mirror of `InkSurfaceView.ruler.enabled`, not the truth.** The edge, its position, its
     * angle and the snap all live in `:android:ink`'s `RulerController`, because the snap is
     * applied by handing ink a projection matrix at `ACTION_DOWN` — free on the ink path, and
     * impossible to do from Compose. A `View`'s field is not Compose state, so the surface pushes
     * changes here through `onRulerChanged` and the toolbar writes back through the actions. Two
     * rulers, one drawn here and one there, was the first version of this and is exactly the
     * *"two mechanisms for one change"* trap `setTemplate` records.
     */
    var rulerEnabled by mutableStateOf(false)

    /**
     * The ruler's angle in degrees, clockwise from the page's x axis — the same mirror.
     *
     * Degrees rather than radians because the numeric entry field is the reason it is settable at
     * all: ROADMAP M8c asks for *"an exact numeric angle"*, and a user typing `30` means thirty
     * degrees. Dragging a straight edge to exactly 30° is not something a finger can do.
     */
    var rulerAngleDegrees by mutableFloatStateOf(0f)

    /** How many objects the lasso currently holds — what the selection actions are enabled by. */
    var selectionSize by mutableIntStateOf(0)

    /**
     * ⭐ M8b — how much of the selection the shape row applies to.
     *
     * Kept beside [selectionSize] rather than derived from it, because only the ink surface knows
     * what kind each selected object is: `:app` holds ids and a count, never the render state.
     */
    var selectedShapes by mutableIntStateOf(0)

    var selectedConnectors by mutableIntStateOf(0)

    /** SPEC §3.5, revised in M1: partial erase is the default. */
    var eraseMode by mutableStateOf(EraseMode.PARTIAL)

    var strokeCount by mutableIntStateOf(0)

    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)

    var zoomPercent by mutableFloatStateOf(100f)

    var predictionEnabled by mutableStateOf(true)

    /** SPEC §3.4 — how much the raw input is smoothed before geometry. */
    var inputModel by mutableStateOf(InkInputModel.SPRING)

    var fingerDrawingEnabled by mutableStateOf(false)

    /**
     * ⭐ ROADMAP M8b — draw a shape, hold still, and the ink becomes the shape.
     *
     * On by default and switchable, because the acceptance criterion is *"no fighting it to draw a
     * freehand curve"*: the honest answer to somebody it still fights is a switch, not a promise
     * that the classifier's confidence bar is tuned correctly for their handwriting.
     */
    var shapeSnapEnabled by mutableStateOf(true)

    /**
     * Whether a snapped straight line gets an arrowhead.
     *
     * SPEC §4.8.7 — an arrowhead is a few samples at the end of a gesture and is indistinguishable
     * from a hooked pen-lift, so it is never recognised. This is the choosing.
     */
    var arrowHeads by mutableStateOf(false)

    /** What the last gesture snapped to, for the toolbar's one-line acknowledgement. Null once seen. */
    var lastSnapped by mutableStateOf<String?>(null)

    /**
     * ⭐ ROADMAP M8a — the rectangular marquee. A mode on the select tool rather than a second
     * tool, so leaving Select still clears the selection exactly as before.
     */
    var marqueeSelection by mutableStateOf(false)

    var latency by mutableStateOf(LatencyStats.Snapshot(0, 0f, 0f))

    /** SPEC §3.3 — what the digitiser reports. See [StylusStats]; polled with [latency]. */
    var stylus by mutableStateOf(StylusStats.Snapshot(0, 0f, 0f, 0f, 0f, 0))

    var busyMessage by mutableStateOf<String?>(null)

    /** SPEC §3.4 — "12 saved slots". */
    val slots = mutableStateListOf<Int>().apply { addAll(DEFAULT_SLOTS) }

    val pen: PenConfig get() = perBrush.getValue(kind)

    fun selectBrush(next: InkBrushKind) {
        kind = next
        tool = EditorTool.PEN
    }

    fun setColor(argb: Int) {
        perBrush[kind] = pen.copy(colorArgb = argb)
    }

    fun setWidth(widthDp: Float) {
        perBrush[kind] = pen.copy(widthDp = widthDp)
    }

    fun saveToSlot(index: Int) {
        slots[index] = pen.colorArgb
    }

    private companion object {
        val DEFAULT_SLOTS = listOf(
            0xFF1A1A1A.toInt(), 0xFF6B7280.toInt(), 0xFFB91C1C.toInt(), 0xFFEA580C.toInt(),
            0xFFCA8A04.toInt(), 0xFF15803D.toInt(), 0xFF0D9488.toInt(), 0xFF1D4ED8.toInt(),
            0xFF6D28D9.toInt(), 0xFFBE185D.toInt(), 0xFF78350F.toInt(), 0xFFFFFFFF.toInt(),
        )
    }
}
