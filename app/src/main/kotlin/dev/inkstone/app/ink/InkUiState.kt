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
import dev.inkstone.app.ui.ColorDragState
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.ShapeKind

/**
 * Everything the editor's toolbar can change, and nothing else.
 *
 * ## M10 — part of this now survives the app being closed
 *
 * This said, for nine milestones: *"M1 has no persistence, so this is plain in-memory Compose state
 * with no repository, no DataStore and no ViewModel behind it. Close the app and the drawing is
 * gone; that is correct for this milestone."* It is no longer true, and a stale KDoc explaining why
 * the wrong thing is right is the expensive kind this codebase keeps paying for.
 *
 * [EditorPrefs] is the persisted subset: the palette **and its order**, the saved widths, the
 * default width, a pen per brush, and the four toggles. It arrives through [adopt] — from the
 * constructor, and again on `ON_START` — and leaves through [toPrefs].
 *
 * WARNING: **Never adopted mid-gesture.** A debounced write echoing back into state the user is
 * currently dragging is the *"two mechanisms for one change"* trap `setTemplate` records; `ON_START`
 * is the one other moment a value comes in, and nothing can be being dragged then.
 *
 * WARNING: **What is deliberately volatile**, and why, because the list is not obvious:
 * - [selectedSlot] — which swatch the panel is editing. Restoring it would mean the first movement
 *   of the wheel in a new session silently repaints a slot the user has not looked at.
 * - [tool], [rulerEnabled] — where you were, not what you like. Reopening a note in the eraser is a
 *   surprise; reopening it with your pen is not.
 * - [camera], [selectionBounds], [latency], [stylus] — per note, per frame, or per device.
 *
 * A field added to [EditorPrefs] **and** to [toPrefs] is persisted with no third edit, so
 * *"forgot to call save"* is unreachable. That is the whole reason [toPrefs] exists as one function.
 *
 * @param initial what was stored, or [EditorPrefs] defaults on a fresh install or a failed read.
 */
@Stable
class InkUiState(initial: EditorPrefs = EditorPrefs()) {

    /**
     * One remembered configuration per brush, so switching pens keeps colour and width.
     *
     * WARNING: **Two overlays, not one.** [pen] is `getValue`, which throws on a missing key, and it
     * is read on every composition of the dock and every push to the ink surface — so a stored map
     * missing one brush would be a crash on opening a note, sourced from a settings row. Starting
     * from the full default map and overlaying what was stored makes that unrepresentable here as
     * well as in the codec, which is deliberate belt and braces for a `getValue` this hot.
     */
    private val perBrush = mutableStateMapOf<InkBrushKind, PenConfig>().apply { putAll(EditorPrefs.DEFAULT_PENS) }

    var kind by mutableStateOf(STOCK.brush)
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
     * ⭐ ROADMAP M10 — the page-space box round the selection, or null when nothing is selected.
     *
     * The redesign floats a selection's actions *beside the objects they act on* rather than in a
     * global row, so the screen has to know where those objects are. Mirrored from
     * `InkSelection.bounds` inside `onSelectionChanged`, beside [selectedShapes] and for the same
     * reason: it is a property of the selection and the surface is the only thing that knows it.
     *
     * ⚠️ Updated when the selection *changes*, not while it is being dragged. `SelectionController`
     * deliberately fires `onChanged` at the end of a gesture and `onPreview` per frame, because
     * recomputing membership per input event fanned hundreds of recompositions a second into
     * Compose (CLAUDE.md rule 7). A toolbar that chased a finger would be the same cost for a worse
     * result.
     */
    var selectionBounds by mutableStateOf<PageRect?>(null)

    /**
     * ⭐ [selectionBounds] plus the rotate grip above it — `InkSelection.chromeBounds`. What the
     * floating bar keeps clear of, so it cannot sit on a grip.
     */
    var selectionChrome by mutableStateOf<PageRect?>(null)

    /**
     * ⭐ True while the selection is being dragged, resized, rotated or reshaped — the bar hides for
     * exactly that long. See `InkSelection.isManipulating`; it flips at pen-down and pen-up only.
     */
    var selectionManipulating by mutableStateOf(false)

    /**
     * ⭐ M8b — how much of the selection the shape row applies to.
     *
     * Kept beside [selectionSize] rather than derived from it, because only the ink surface knows
     * what kind each selected object is: `:app` holds ids and a count, never the render state.
     */
    var selectedShapes by mutableIntStateOf(0)

    var selectedConnectors by mutableIntStateOf(0)

    /**
     * ⭐ ROADMAP M10 — how many of the selected shapes each control actually applies to.
     *
     * The selection bar shows `FILL` only when something in the selection has an interior, and the
     * corner toggle only when something in it has corners. A control that cannot act on anything in
     * the selection is **absent**, not greyed: the design's progressive disclosure, and the answer to
     * *"if a shape can't do it then it should not show up"*.
     */
    var selectedFillable by mutableIntStateOf(0)

    var selectedRoundable by mutableIntStateOf(0)

    /** How many of [selectedRoundable] are rounded now — what makes the corner chip a *toggle*. */
    var selectedRounded by mutableIntStateOf(0)

    /**
     * ⭐ How many selected shapes are not yet the regular member of their kind — what makes the
     * `Circle` / `Square` / `Equal` chip appear, and disappear once it would do nothing.
     */
    var selectedIrregular by mutableIntStateOf(0)

    /** ⭐ Selected segments, and how many of them show an arrowhead — the `Arrow` chip's toggle. */
    var selectedArrowable by mutableIntStateOf(0)

    var selectedArrowed by mutableIntStateOf(0)

    /**
     * ⭐ The kind every selected shape is, or null for a mixed selection.
     *
     * Only so the regularise chip can be named after what it will produce. "Regular" is the concept
     * and nobody thinks in it; **Circle** over an ellipse says what the button does before it is
     * pressed, which is the whole job of a label.
     */
    var selectedKind by mutableStateOf<ShapeKind?>(null)

    /**
     * ⭐ SPEC §4.9.6 — selected objects that can knock out (everything but a highlighter), and how
     * many already do. The `Knockout` chip shows when the first is non-zero and reads *on* only when
     * the two are equal, so it never claims a state half the selection is not in.
     */
    var selectedKnockoutEligible by mutableIntStateOf(0)

    var selectedKnockoutOn by mutableIntStateOf(0)

    /**
     * ⭐ The pivot of the Element copy in the selection, in page space, or null (SPEC §4.9.7). Drawn,
     * and it is what brings the Grid toggle to a selection with no shapes in it — handwriting snaps
     * by its pivot.
     */
    var selectionPivot by mutableStateOf<dev.inkstone.core.model.PagePoint?>(null)

    /** SPEC §3.5, revised in M1: partial erase is the default. */
    var eraseMode by mutableStateOf(STOCK.eraseMode)

    var strokeCount by mutableIntStateOf(0)

    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)

    var zoomPercent by mutableFloatStateOf(100f)

    var predictionEnabled by mutableStateOf(true)

    /** SPEC §3.4 — how much the raw input is smoothed before geometry. */
    var inputModel by mutableStateOf(STOCK.inputModel)

    var fingerDrawingEnabled by mutableStateOf(STOCK.fingerDrawingEnabled)

    /**
     * ⭐ ROADMAP M8b — draw a shape, hold still, and the ink becomes the shape.
     *
     * On by default and switchable, because the acceptance criterion is *"no fighting it to draw a
     * freehand curve"*: the honest answer to somebody it still fights is a switch, not a promise
     * that the classifier's confidence bar is tuned correctly for their handwriting.
     */
    var shapeSnapEnabled by mutableStateOf(STOCK.shapeSnapEnabled)

    /**
     * Whether a snapped straight line gets an arrowhead.
     *
     * SPEC §4.8.7 — an arrowhead is a few samples at the end of a gesture and is indistinguishable
     * from a hooked pen-lift, so it is never recognised. This is the choosing.
     */
    var arrowHeads by mutableStateOf(STOCK.arrowHeads)

    /**
     * ⭐ Whether a snapped shape, a move, a resize or a dragged shape vertex lands on a lattice.
     *
     * ⚠️ **The lattice is the paper's own wherever the paper has one** — a grid page snaps to the
     * squares it prints and a ruled page snaps y to its rules and leaves x free (`SnapGrid`). Blank
     * paper and an imported PDF page have none, so they get the default pitch and the ink surface
     * **draws it as chrome** while this is on: the alternative considered was a chip disabled on
     * those pages, which would have withdrawn the feature from the place diagrams are most often
     * drawn — over a lecture slide.
     *
     * ⚠️ This property, its codec entry and a `pageHasGrid` beside it were written a milestone
     * before anything read them: no chip, no assignment in [InkScreen], and no lattice in
     * `:android:ink`. `pageHasGrid` is deleted rather than left — the chip is offered on every page
     * now, so nothing needs the answer.
     */
    var gridSnapEnabled by mutableStateOf(STOCK.gridSnapEnabled)

    /**
     * ⭐ True while the page's paper is up and its objects are still being built off the main
     * thread (`PreparedPage`).
     *
     * ⚠️ **A note's ink appearing a moment after its paper needs saying out loud.** This app's
     * whole subject is not losing work, so a page that shows as blank — even for the third of a
     * second a small note takes — reads as *"my note is empty"*, which is the most alarming thing
     * it could possibly say. The surface reports the flag; [dev.inkstone.app.ink.EditorCanvas]
     * turns it into a line of text.
     */
    var pageLoading by mutableStateOf(false)

    /** What the last gesture snapped to, for the toolbar's one-line acknowledgement. Null once seen. */
    var lastSnapped by mutableStateOf<String?>(null)

    /**
     * ⭐ ROADMAP M8a — the rectangular marquee. A mode on the select tool rather than a second
     * tool, so leaving Select still clears the selection exactly as before.
     */
    var marqueeSelection by mutableStateOf(false)

    var latency by mutableStateOf(LatencyStats.Snapshot(0, 0f, 0f))

    /**
     * SPEC §3.6's frame-rate gate, readable on a tablet with no adb. See [FrameStats] for why it
     * cannot be a `Choreographer` callback, and why the gate had no readout on the one device it
     * is actually about until this existed.
     */
    var frames by mutableStateOf(FrameStats.Snapshot(0, 0f, 0f, 0, 0f))

    /** Collects [frames]. Attached only while the Measure panel is open — it costs a thread. */
    val frameStats = FrameStats()

    /** SPEC §3.6 — objects carrying an erased region; each costs a `saveLayer` per frame. */
    var erasedCount by mutableIntStateOf(0)

    /** SPEC §3.6 — one highlighter disables the dry layer's append fast path for the whole page. */
    var hasHighlighter by mutableStateOf(false)

    /** SPEC §3.6 — is the dry page a rasterised texture right now, and what refused it if not. */
    var dryLayer by mutableStateOf("")

    /** SPEC §3.3 — what the digitiser reports. See [StylusStats]; polled with [latency]. */
    var stylus by mutableStateOf(StylusStats.Snapshot(0, 0f, 0f, 0f, 0f, 0))

    var busyMessage by mutableStateOf<String?>(null)

    /** SPEC §3.4 — "12 saved slots". Seeded from [EditorPrefs.palette]; the order is the value. */
    val slots = mutableStateListOf<Int>().apply { addAll(STOCK.palette) }

    /**
     * M10 — the saved stroke widths the dock's slider magnetises onto and marks on its track.
     *
     * Every one is the user's to edit, delete or add to, with no limit (`SizePanel`). Ascending and
     * deduplicated **between** edits, which [EditorPrefsCodec] guarantees on the way in and
     * [finishWidthEdit] restores — see [editingWidthSlot] for why not during one.
     */
    val widthSlots = mutableStateListOf<Float>().apply { addAll(STOCK.widthSlots) }

    /** The top of both width sliders' range — the user's, see [setMaxWidth]. */
    var maxWidthDp by mutableFloatStateOf(STOCK.maxWidthDp)
        private set

    /**
     * Which saved size the size panel is editing, or -1.
     *
     * The width counterpart of [selectedSlot], and volatile for the same reason: restoring it would
     * let the first touch of the slider in a new session rewrite a size nobody has looked at.
     *
     * ⚠️ While it is set, [widthSlots] may be **out of order or hold a duplicate**. Dragging a saved
     * 3 dp up past a saved 4 dp passes through exactly 4.0, and normalising there would merge the two
     * and delete the size the user was not touching. [finishWidthEdit] sorts and merges once the edit
     * ends. Both readers tolerate the interim: the ticks are only marks, and `WidthMagnet` works on
     * an unsorted list and treats a duplicate as no pull.
     */
    var editingWidthSlot by mutableIntStateOf(NO_SLOT)
        private set

    /**
     * M10 — *"or set as the default size"*.
     *
     * WARNING: **Not the same thing as "the last width you used".** That was the tempting
     * simplification, on the grounds that the last width now survives the app closing — but a
     * default is a value you can deliberately **return to**, and the last one used is by definition
     * wherever you happen to have left the slider. Someone whose default is 0.5 dp and who is
     * currently drawing at 12 dp has no way back without it.
     */
    var defaultWidthDp by mutableFloatStateOf(STOCK.defaultWidthDp)
        private set

    /**
     * ⭐ ROADMAP M10 — which slot the current colour came from, or -1.
     *
     * What makes the colour panel *edit a swatch* rather than only read one: pick a slot, then move
     * the wheel or a channel, and the slot follows. Without it the wheel changed the pen and left
     * the palette alone, so mixing a colour and keeping it meant a separate long-press-to-save that
     * nothing on screen mentioned.
     *
     * ⚠️ -1 rather than null, and set **only** by an explicit tap on a slot. A colour arriving from
     * anywhere else — a brush switch, a peer, a hex paste — must not silently overwrite whichever
     * swatch happened to be chosen last.
     */
    var selectedSlot by mutableIntStateOf(NO_SLOT)

    /** ⭐ M10 — a colour being dragged from one swatch to another. See [ColorDragState]. */
    val colorDrag = ColorDragState()

    /**
     * ⭐ Where the chrome sits, how big it is and which chips it shows. Persisted; see [ChromeLayout].
     *
     * ⚠️ Written at the **end** of a drag, not per frame — a bar being moved or resized lives in
     * [chromeDrag] until it is let go. Writing here per frame would recompose every bar on screen
     * and re-run [toPrefs] at 120 Hz for a value nobody reads until the finger lifts.
     */
    var layout by mutableStateOf(STOCK.layout)

    /**
     * True while the layout editor is open. Volatile, like [tool]: reopening a note half-way through
     * rearranging its toolbars would be a surprise, and the editor's state is all in [layout] anyway.
     */
    var editingLayout by mutableStateOf(false)

    /** The bar under the finger in the layout editor, if any. See [ChromeDrag]. */
    val chromeDrag = ChromeDrag()

    /** The chip in the air in the layout editor, if any. See [ItemDrag]. */
    val itemDrag = ItemDrag()

    // ⚠️ After every field [adopt] writes — Kotlin runs initialisers and `init` in textual order.
    init {
        adopt(initial)
    }

    /**
     * Take [prefs] as the whole persisted subset — the inverse of [toPrefs].
     *
     * **The constructor is this and nothing else**: the fields above start at stock values and are
     * seeded here. So a persisted field seeded on open but not on adopt is unrepresentable —
     * forgetting it here forgets it on every open, which the first restart shows.
     *
     * Called on `ON_START` too, for the case where another editor changed the toolbar while this
     * one was behind it: the Element editor is a second `NoteActivity` stacked on the note's, and
     * without this, returning to the note showed the old palette and the next touch of any control
     * wrote that old palette back over the new one. `ON_START` cannot be mid-gesture, which is what
     * keeps this clear of the class KDoc's *"two mechanisms for one change"* — and an equal value
     * returns before writing anything, so the common case costs one comparison.
     */
    fun adopt(prefs: EditorPrefs) {
        if (prefs == toPrefs()) return
        if (prefs.palette != slots.toList()) {
            slots.clear()
            slots.addAll(prefs.palette)
            // The index now names a different colour; see [selectedSlot] on why that must not be kept.
            selectedSlot = NO_SLOT
        }
        if (prefs.widthSlots != widthSlots.toList()) {
            editingWidthSlot = NO_SLOT
            widthSlots.clear()
            widthSlots.addAll(prefs.widthSlots)
        }
        maxWidthDp = WidthLadder.clampMax(prefs.maxWidthDp)
        defaultWidthDp = prefs.defaultWidthDp
        // One write, and total over the brushes — see [perBrush] on why a partial map must not reach it.
        perBrush.putAll(EditorPrefs.DEFAULT_PENS + prefs.pens)
        kind = prefs.brush
        eraseMode = prefs.eraseMode
        inputModel = prefs.inputModel
        fingerDrawingEnabled = prefs.fingerDrawingEnabled
        shapeSnapEnabled = prefs.shapeSnapEnabled
        arrowHeads = prefs.arrowHeads
        gridSnapEnabled = prefs.gridSnapEnabled
        layout = prefs.layout
    }

    /**
     * Choose [index] and take its colour.
     *
     * ⚠️ The pen is set through the caller's own setter rather than here, because *what* a colour
     * change applies to depends on the context the panel was opened from (`ColourPopover`): the pen,
     * a selection, or a text box.
     */
    fun chooseSlot(index: Int) {
        selectedSlot = index
    }

    /**
     * Write [argb] into the chosen slot, if there is one.
     *
     * Called on every colour change while the panel is open, which is what makes the wheel edit the
     * swatch live.
     */
    fun updateChosenSlot(argb: Int) {
        slots.getOrNull(selectedSlot)?.let { slots[selectedSlot] = argb }
    }

    /**
     * ⭐ M10 — drop the colour from slot [from] onto slot [to], as a **swap**.
     *
     * Swapped rather than copied so the palette never loses a colour to a mis-aimed drag: twelve
     * slots go in and twelve come out, and dragging back undoes it. The first three are the quick
     * swatches in the toolbar, so *"drag a colour into the quick select"* is this with `to < 3`.
     */
    fun swapSlots(from: Int, to: Int) {
        if (from !in slots.indices || to !in slots.indices || from == to) return
        val moved = slots[from]
        slots[from] = slots[to]
        slots[to] = moved
        if (selectedSlot == from) {
            selectedSlot = to
        } else if (selectedSlot == to) {
            selectedSlot = from
        }
    }

    val pen: PenConfig get() = perBrush.getValue(kind)

    fun selectBrush(next: InkBrushKind) {
        kind = next
        tool = EditorTool.PEN
    }

    fun setColor(argb: Int) {
        perBrush[kind] = pen.copy(colorArgb = argb)
    }

    /** The dock's slider. Its range is already `MIN_WIDTH_DP..maxWidthDp`, so nothing is clamped here. */
    fun setWidth(widthDp: Float) {
        perBrush[kind] = pen.copy(widthDp = widthDp)
    }

    /**
     * Tap a saved size in the panel: the pen takes it, and the panel edits it from now on.
     *
     * Tapping the one already being edited stops editing — which is how a user who has just added or
     * edited a size gets the slider back for picking a width that is *not* saved.
     *
     * ⚠️ Resolved **by value**, not index. Finishing the previous edit can re-sort the list, so the
     * index the user tapped may name a different rung by the time it is used.
     */
    fun chooseWidthSlot(index: Int) {
        val value = widthSlots.getOrNull(index) ?: return
        val again = index == editingWidthSlot
        finishWidthEdit()
        if (again) return
        editingWidthSlot = widthSlots.indexOf(value)
        setWidth(value)
    }

    /** The panel's slider and number field: the pen, and the saved size being edited if there is one. */
    fun editWidth(widthDp: Float) {
        val width = WidthLadder.clamp(widthDp, maxWidthDp)
        setWidth(width)
        if (editingWidthSlot in widthSlots.indices) widthSlots[editingWidthSlot] = width
    }

    /** `ADD` — keep the current width as a saved size and start editing it. There is no limit. */
    fun addWidthSlot() {
        finishWidthEdit()
        val width = WidthLadder.clamp(pen.widthDp, maxWidthDp)
        if (width !in widthSlots) {
            val at = widthSlots.indexOfFirst { it > width }
            widthSlots.add(if (at >= 0) at else widthSlots.size, width)
        }
        editingWidthSlot = widthSlots.indexOf(width)
        setWidth(width)
    }

    /**
     * `DELETE` — forget the size being edited.
     *
     * The last one can go too. That used to be refused because an empty ladder left the readout's tap
     * cycle nothing to do; the readout opens this panel now, and an empty ladder is simply a slider
     * with no marks — which is what someone who deleted every size asked for.
     */
    fun deleteEditedWidthSlot() {
        if (editingWidthSlot in widthSlots.indices) widthSlots.removeAt(editingWidthSlot)
        finishWidthEdit()
    }

    /** A long press on a saved size: forget it without choosing it. */
    fun removeWidthSlot(index: Int) {
        val value = widthSlots.getOrNull(index) ?: return
        finishWidthEdit()
        widthSlots.remove(value)
    }

    /** Stop editing, then sort and merge. Called whenever the panel leaves the screen, by any route. */
    fun finishWidthEdit() {
        editingWidthSlot = NO_SLOT
        val tidy = WidthLadder.normalised(widthSlots, maxWidthDp)
        if (tidy != widthSlots.toList()) {
            widthSlots.clear()
            widthSlots.addAll(tidy)
        }
    }

    /**
     * `MAX` — the top of both width sliders.
     *
     * ⚠️ Everything above the new maximum comes down to it: every brush's width, every saved size
     * (merging any that land together) and the default. A width the slider cannot reach is a thumb
     * pinned at its end stop reading a number it is not at. That is also why this is only ever called
     * with a **committed** number — see `SizePanel` on typing `12` passing through `1`.
     */
    fun setMaxWidth(maxDp: Float) {
        val max = WidthLadder.clampMax(maxDp)
        val edited = widthSlots.getOrNull(editingWidthSlot)
        maxWidthDp = max
        InkBrushKind.entries.forEach { brush ->
            val config = perBrush.getValue(brush)
            if (config.widthDp > max) perBrush[brush] = config.copy(widthDp = max)
        }
        defaultWidthDp = defaultWidthDp.coerceAtMost(max)
        finishWidthEdit()
        // Keep editing the same rung, if it survived — changing the range is not a reason to lose place.
        edited?.let { editingWidthSlot = widthSlots.indexOf(WidthLadder.clamp(it, max)) }
    }

    /** *"or set as the default size"* — see [defaultWidthDp] on why this is not the last-used width. */
    fun makeDefaultWidth(widthDp: Float) {
        defaultWidthDp = widthDp
    }

    /**
     * The persisted subset, and **only** the persisted subset.
     *
     * WARNING: `InkScreen` reads this through `snapshotFlow`, so a per-frame field in here —
     * `camera`, `selectionBounds`, `latency` — is a database write on every frame of every pan. The
     * class KDoc lists what is deliberately left out and why.
     */
    fun toPrefs(): EditorPrefs = EditorPrefs(
        palette = slots.toList(),
        widthSlots = widthSlots.toList(),
        defaultWidthDp = defaultWidthDp,
        maxWidthDp = maxWidthDp,
        pens = perBrush.toMap(),
        brush = kind,
        eraseMode = eraseMode,
        inputModel = inputModel,
        fingerDrawingEnabled = fingerDrawingEnabled,
        shapeSnapEnabled = shapeSnapEnabled,
        arrowHeads = arrowHeads,
        gridSnapEnabled = gridSnapEnabled,
        layout = layout,
    )

    private companion object {
        const val NO_SLOT = -1

        /** What every persisted field holds for the instant before [adopt] seeds it. */
        val STOCK = EditorPrefs()
    }
}
