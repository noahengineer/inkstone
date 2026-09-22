package dev.inkstone.app.ink

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.inkstone.android.ink.InkSelection
import dev.inkstone.app.ui.ChipDivider
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.SegmentChip
import dev.inkstone.app.ui.Segmented
import dev.inkstone.app.ui.SwatchStrip
import dev.inkstone.app.ui.TextChip
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.ConnectorRoute
import dev.inkstone.core.model.ListStyle
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.TextAlign
import dev.inkstone.core.model.TextFont
import dev.inkstone.core.model.pageToView
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign

/**
 * ⭐ ROADMAP M10 — **the contextual toolbars**, and the design's sharpest departure from the old UI.
 *
 * > *"These toolbars do not exist in the global UI. They spawn dynamically and float adjacent to the
 * > object being manipulated on the canvas."*
 *
 * Three of them — a selection's actions, a text box's formatting, a picture's transforms — and each
 * appears only when it has something to act on. That gate is not new; `ShapeRow` and `TextFormatRow`
 * already had it, for the reason `UndoStack.commit` has it one layer down: a *"Fill"* button over a
 * selection of pen strokes is a button that does nothing when pressed. What *is* new is where they
 * appear, and that turns out to fix a second thing — a row of actions 700 px away from the object it
 * acts on is a row you have to look away from your own diagram to use.
 *
 * ⚠️ **Exactly one of the three is ever on screen.** A sole selected image raises the image bar and
 * not the selection bar, because the image bar carries the selection's own commands as well; a text
 * box being typed into raises neither of the others, because typing clears the focus
 * (`ObjectToolState.edit`). The single `?:` chain below is what enforces that rather than three
 * independent conditions that could all be true at once.
 *
 * @return true when a bar is showing, so [EditorDock] can collapse its secondary row — the design's
 *   *"hides the Dynamic Dock's secondary row to maintain focus"*.
 */
@Composable
fun ContextBars(
    ui: InkUiState,
    objects: ObjectToolState,
    actions: InkToolbarActions,
    objectActions: ObjectActions,
    onSummon: (PanelKind, Rect) -> Unit,
    modifier: Modifier = Modifier,
): Boolean {
    val editing = objects.editing
    val image = objects.image
    val selection = ui.selectionBounds.takeIf { ui.selectionSize > 0 }

    return when {
        editing != null -> {
            // A box being typed into is not selected, so it has no grips to keep clear of.
            AnchoredBar(editing.bounds, ui.camera, clearsGrips = false, hidden = false, modifier = modifier) {
                TextFormatBar(editing.id, objects, ui.slots, objectActions, onSummon)
            }
            true
        }

        // ⚠️ Both of these still answer **true** while hidden. The dock's secondary row collapses
        // for a contextual bar, and letting it spring open for the length of a drag and shut again on
        // the lift would move the chrome every time the user touched a selection.
        image != null -> {
            val chrome = ui.selectionChrome.takeIf { ui.selectionSize > 0 }
            AnchoredBar(
                chrome ?: image.bounds,
                ui.camera,
                clearsGrips = chrome != null,
                hidden = ui.selectionManipulating,
                modifier = modifier,
            ) {
                ImageBar(objects, actions, objectActions)
            }
            true
        }

        selection != null -> {
            AnchoredBar(
                ui.selectionChrome ?: selection,
                ui.camera,
                clearsGrips = true,
                hidden = ui.selectionManipulating,
                modifier = modifier,
            ) {
                SelectionBar(ui, objects, actions, onSummon)
            }
            true
        }

        else -> false
    }
}

/**
 * Places a floating bar beside a page-space box, in view space, clamped to the screen.
 *
 * ⚠️ **Above the object if it fits, below if it does not, and never under the dock.** An object near
 * the top of the page would otherwise put its own toolbar behind the Dynamic Dock, where it is
 * un-pressable and looks like the feature is broken — the same class of defect as a tool that owns
 * touch and swallows the gesture beneath it (`EditorTool.IMAGE`).
 *
 * The bar is invisible for its first frame. It has to be measured before it can be centred, and the
 * alternative to hiding it is a visible jump from the top-left corner on every selection.
 *
 * ⚠️ **[clearsGrips] keeps the bar out of the grips' touch reach, not just off their drawing.** A
 * selection's anchor is its *chrome* (`InkSelection.chromeBounds`, which reaches up to the rotate
 * grip); anchored to the box alone with the 14 dp gap, the bar sat squarely on the rotate grip.
 *
 * ⚠️ **[hidden] for the length of a drag.** The bar is placed from where the selection was at
 * pen-down, so while the pen moves it stands still over whatever the object is being dragged across —
 * *"it only updates its position once you let go … hard to see what you are doing"*. Not composed at
 * all while hidden, so none of its chips can take a stray press either.
 */
@Composable
private fun AnchoredBar(
    bounds: PageRect,
    camera: Camera,
    clearsGrips: Boolean,
    hidden: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    if (hidden) return
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        var size by remember { mutableStateOf(IntSize.Zero) }
        val density = LocalDensity.current
        val gap = with(density) { InkDims.ContextGap.toPx() }
        val reach = with(density) { InkSelection.HANDLE_REACH_DP.dp.toPx() }
        val pad = with(density) { InkDims.EdgePadding.toPx() }
        val safeTop = with(density) { DockSafeTop.toPx() }

        val topLeft = pageToView(PagePoint(bounds.left, bounds.top), camera)
        val bottomRight = pageToView(PagePoint(bounds.right, bounds.bottom), camera)
        val (x, y) = ContextBarPlacement.place(
            anchorLeft = topLeft.x,
            anchorTop = topLeft.y,
            anchorRight = bottomRight.x,
            anchorBottom = bottomRight.y,
            barWidth = size.width.toFloat(),
            barHeight = size.height.toFloat(),
            viewportWidth = constraints.maxWidth.toFloat(),
            viewportHeight = constraints.maxHeight.toFloat(),
            clearance = if (clearsGrips) max(gap, reach) else gap,
            edgePadding = pad,
            safeTop = safeTop,
        )

        GlassBar(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .onSizeChanged { size = it }
                .alpha(if (size == IntSize.Zero) 0f else 1f),
            content = content,
        )
    }
}

/**
 * ⭐ ROADMAP M8a/M8b — what a selection can have done to it, floating over the selection.
 *
 * The width chip is deliberately the pen's width and not the selection's: a selection has no single
 * width to show (the strokes in it may differ, and SPEC §4.2's `SetStyle` is per object), so it is
 * an *instruction* rather than a readout. Pressing it says "make them this wide"; it does not claim
 * they already are.
 *
 * ⚠️ Fill, rounding and route appear only when the selection contains something they apply to. Fill
 * takes the pen's current colour at [FILL_OPACITY], which is fixed so that a filled box does not
 * hide the ink drawn over it (ROADMAP M8b asks for fill to *exist*, not for a panel to set it from).
 */
@Composable
private fun RowScope.SelectionBar(
    ui: InkUiState,
    objects: ObjectToolState,
    actions: InkToolbarActions,
    onSummon: (PanelKind, Rect) -> Unit,
) {
    EditTextChip(objects, actions)
    // ⚠️ **No "N selected" caption.** The bar's own presence says something is selected and the
    // handles on the page say which — a count repeated in words is the kind of caption the design
    // review removed everywhere else, and this one survived a round because it reads as data.
    TextChip(label = "Delete", danger = true, onClick = actions.onDeleteSelection)
    TextChip(label = "Duplicate", onClick = actions.onDuplicateSelection)
    Segmented {
        SegmentChip(label = "Front", selected = false, onClick = actions.onBringSelectionToFront)
        SegmentChip(label = "Back", selected = false, onClick = actions.onSendSelectionToBack)
    }
    TextChip(
        label = "${ui.pen.widthDp.roundToInt()} dp",
        kind = ChipKind.VALUE,
        onClick = { actions.onResizeSelectionStroke(ui.pen.widthDp) },
    )
    // ⚠️ Each gated on what the selection can actually *do*, not on it containing shapes. A `FILL`
    // chip over a selected line, or `ROUNDED` over an ellipse, is a control that does nothing when
    // pressed — and the two used to appear together for any shape at all.
    if (ui.selectedFillable > 0) {
        ChipDivider()
        Segmented {
            SegmentChip(
                label = "Fill",
                selected = false,
                onClick = { actions.onFillSelection(ui.pen.colorArgb, FILL_OPACITY) },
            )
            SegmentChip(label = "No fill", selected = false, onClick = { actions.onFillSelection(null, 1f) })
        }
    }
    if (ui.selectedRoundable > 0) {
        if (ui.selectedFillable == 0) ChipDivider()
        // ⭐ One toggle, not a `ROUND` / `SQUARE` pair. The pair was two chips for one boolean and
        // neither of them said which one you were currently on; a toggle is half the width and shows
        // the state it sets.
        val rounded = ui.selectedRounded > 0
        TextChip(
            label = "Rounded",
            selected = rounded,
            onClick = { actions.onRoundSelection(if (rounded) 0f else CORNER_RADIUS_PT) },
        )
    }
    ShapeGeometryChips(ui, actions)
    if (ui.selectedConnectors > 0) {
        ChipDivider()
        ConnectorRoute.entries.forEach { route ->
            TextChip(label = route.name.lowercase(), onClick = { actions.onRouteSelection(route) })
        }
    }
    ChipDivider()
    SwatchStrip(
        colors = ui.slots.take(QUICK_SLOTS),
        selectedArgb = ui.pen.colorArgb,
        onPick = { argb ->
            ui.chooseSlot(ui.slots.indexOf(argb))
            actions.onRecolourSelection(argb)
        },
        onMore = { onSummon(PanelKind.COLOUR, it) },
        drag = ui.colorDrag,
        onDropped = ui::swapSlots,
    )
    // ⚠️ **No "Draw again".** It was M8b's return leg for the pen a snap steals, and it is gone at
    // the user's request — the two routes back are the `DRAW` chip in the dock and a tap on bare
    // paper, which `onSelectTappedAway` already turns into "restore whichever brush was last in
    // hand". If putting the pen back ever feels like a hunt again, that is what this bar is missing.
}

/**
 * ⭐ The three buttons that change a selected shape's **geometry**, in the user's words:
 *
 * > *"it should have buttons for makeing it a perfect circle for example or perfect triangle …
 * > should be able to say if its an arrow or not if its a line and of course the gid snaping"*
 *
 * ⚠️ Each is gated on what the selection can actually **do**, the rule the fill and corner chips
 * above already follow: a `Circle` chip over a shape that is already a circle, or an `Arrow` chip
 * over a rectangle, is a control that does nothing when pressed. The regularise chip disappears the
 * moment it would be a no-op, which is also how you can tell the press landed.
 */
@Composable
private fun RowScope.ShapeGeometryChips(ui: InkUiState, actions: InkToolbarActions) {
    if (ui.selectedShapes == 0) return
    ChipDivider()
    if (ui.selectedIrregular > 0) {
        TextChip(label = regulariseLabel(ui.selectedKind), onClick = actions.onRegulariseSelection)
    }
    if (ui.selectedArrowable > 0) {
        // One toggle rather than an `Arrow` / `Plain` pair, for the reason `Rounded` is one: the
        // pair is two chips for one boolean and neither of them says which one you are on.
        val arrowed = ui.selectedArrowed > 0
        TextChip(label = "Arrow", selected = arrowed, onClick = { actions.onArrowSelection(!arrowed) })
    }
    // ⚠️ **A toggle for the mode, not a one-shot "snap this now"** — which is what it was, and
    // the report was *"it just snaps the points to an invisible grid"*. It was: the chrome that
    // draws the lattice on paper with none of its own is gated on the mode, so pressing a chip
    // that snapped once and left the mode off moved the shape onto lines nobody could see. The
    // same switch as the dock's `GRID`, put where a selected shape is — press it and the grid
    // appears **and** what you drag lands on it.
    TextChip(
        label = "Grid",
        selected = ui.gridSnapEnabled,
        onClick = { ui.gridSnapEnabled = !ui.gridSnapEnabled },
    )
}

/**
 * What the regularise chip is called, given the one kind in the selection.
 *
 * Named after what it will **produce**, not after the operation: "Regular" is a concept nobody
 * thinks in, and a mixed selection is the only case with nothing better to say.
 */
private fun regulariseLabel(kind: ShapeKind?): String = when (kind) {
    ShapeKind.ELLIPSE -> "Circle"

    ShapeKind.RECTANGLE -> "Square"

    ShapeKind.TRIANGLE -> "Equal"

    // A segment's regular form is an angle rather than a shape — see `ShapeRegular`.
    ShapeKind.LINE, ShapeKind.ARROW -> "45°"

    null -> "Regular"
}

/**
 * The way back into a text box's words, on the bar for the object that is selected.
 *
 * ⚠️ Shown only for a **sole** text box (`InkSelection.soleTextBox`, exactly `soleImage`'s
 * argument): a bar offering it for a mixed selection would open a field over one of them at random.
 *
 * ⚠️ **The tool is deliberately not changed.** `InkScreen`'s
 * `LaunchedEffect(note.pageIndex, ui.tool)` commits the field and calls `ObjectToolState.reset`
 * whenever the tool changes, so switching to `TEXT` here would close the field this chip had just
 * opened — one frame later, looking exactly like a button that does nothing. The field, its frame
 * handles and the formatting bar all work from Select; `Done` is the way out.
 */
@Composable
private fun RowScope.EditTextChip(objects: ObjectToolState, actions: InkToolbarActions) {
    val focus = objects.text ?: return
    TextChip(
        label = "Edit text",
        onClick = {
            objects.edit(focus)
            // After `edit`, which is what makes the re-report harmless: `focusText` refuses to touch
            // a box that is being typed into.
            actions.onClearSelection()
        },
    )
    ChipDivider()
}

/**
 * ROADMAP M8c — *"font, size, colour, alignment, bullet and numbered lists with nesting"*, floating
 * over the box being typed into.
 *
 * ⚠️ **Colour goes to `SetStyle`, not into the format record** (SPEC §4.9.2). One of the five
 * properties in that scope line was never homeless, and noticing it came from reading the type
 * rather than the sentence.
 */
@Composable
private fun RowScope.TextFormatBar(
    id: ObjectId?,
    state: ObjectToolState,
    slots: List<Int>,
    actions: ObjectActions,
    onSummon: (PanelKind, Rect) -> Unit,
) {
    FontAndSize(id, state, actions)
    AlignAndLists(id, state, actions)
    ChipDivider()
    SwatchStrip(
        colors = slots.take(QUICK_SLOTS),
        selectedArgb = state.style.colorArgb,
        onPick = { argb ->
            state.style = state.style.copy(colorArgb = argb)
            actions.onColourChanged(id, argb)
        },
        onMore = { onSummon(PanelKind.COLOUR, it) },
    )
    ChipDivider()
    TextChip(
        label = "Done",
        onClick = {
            actions.onTextChanged(id, state.draft)
            state.stopEditing()
        },
    )
}

/** Family and size. Both are `SetFormat`, never `SetTextContent` — SPEC §4.9.2. */
@Composable
private fun RowScope.FontAndSize(id: ObjectId?, state: ObjectToolState, actions: ObjectActions) {
    val format = state.format
    Segmented {
        TextFont.entries.forEach { font ->
            SegmentChip(
                label = font.label,
                selected = format.font == font,
                onClick = {
                    state.format = format.copy(font = font)
                    actions.onFormatChanged(id) { it.copy(font = font) }
                },
            )
        }
    }
    Segmented {
        SegmentChip(label = "A−", selected = false, onClick = { state.resize(id, -SIZE_STEP_PT, actions) })
        Readout(
            "${format.sizePt.roundToInt()} pt",
            modifier = Modifier.width(SizeReadout),
            textAlign = ComposeTextAlign.Center,
        )
        SegmentChip(label = "A+", selected = false, onClick = { state.resize(id, SIZE_STEP_PT, actions) })
    }
}

/** Alignment, and *"bullet and numbered lists with nesting"*. */
@Composable
private fun RowScope.AlignAndLists(id: ObjectId?, state: ObjectToolState, actions: ObjectActions) {
    val format = state.format
    Segmented {
        TextAlign.entries.forEach { align ->
            SegmentChip(
                label = align.label,
                selected = format.align == align,
                onClick = {
                    state.format = format.copy(align = align)
                    actions.onFormatChanged(id) { it.copy(align = align) }
                },
            )
        }
    }
    Segmented {
        ListStyle.entries.forEach { style ->
            SegmentChip(
                label = style.label,
                selected = format.listStyle == style,
                onClick = { state.setList(id, style, actions) },
            )
        }
    }
    TextChip(
        label = "◀",
        kind = ChipKind.GLYPH,
        contentDescription = "Outdent",
        enabled = format.listLevel > 1,
        onClick = { state.indent(id, -1, actions) },
    )
    MicroLabel("lvl ${format.listLevel}")
    TextChip(
        label = "▶",
        kind = ChipKind.GLYPH,
        contentDescription = "Indent",
        enabled = format.listStyle != ListStyle.NONE && format.listLevel < MAX_LIST_LEVEL,
        onClick = { state.indent(id, 1, actions) },
    )
}

/**
 * ROADMAP M8c — what can be done to a picture that is already on the page.
 *
 * ⚠️ **While cropping, the bar is the crop's own controls and nothing else.** A Rotate button live
 * during a crop would write a `SetTransform` under the overlay's feet — the handles are positioned
 * from the frame it just changed — and the user would watch the frame jump off the picture.
 *
 * ⚠️ **Flip is here because Goodnotes cannot do it** (ROADMAP M8c acceptance 2), and it is one
 * `SetTransform` carrying a negated matrix — absolute, like everything else in that register.
 */
@Composable
private fun RowScope.ImageBar(state: ObjectToolState, actions: InkToolbarActions, objectActions: ObjectActions) {
    val focused = state.image ?: return
    if (state.cropping != null) {
        TextChip(label = "Done", onClick = { state.commitCrop(objectActions) })
        TextChip(label = "Reset", onClick = { state.resetCrop() })
        TextChip(label = "Cancel", onClick = { state.cancelCrop() })
        return
    }
    TextChip(label = "Rotate", onClick = { objectActions.onTurn(focused.id, QUARTER_TURN, false, false) })
    Segmented {
        SegmentChip(label = "Flip ⇄", selected = false, onClick = { objectActions.onTurn(focused.id, 0f, true, false) })
        SegmentChip(label = "Flip ⇅", selected = false, onClick = { objectActions.onTurn(focused.id, 0f, false, true) })
    }
    ChipDivider()
    TextChip(label = "Crop", onClick = { state.beginCrop() })
    TextChip(label = "Uncrop", onClick = { objectActions.onCrop(focused.id, null) })
    ChipDivider()
    TextChip(label = "Duplicate", onClick = actions.onDuplicateSelection)
    TextChip(label = "Delete", danger = true, onClick = actions.onDeleteSelection)
}

private fun ObjectToolState.resize(id: ObjectId?, delta: Float, actions: ObjectActions) {
    val next = (format.sizePt + delta).coerceIn(MIN_SIZE_PT, MAX_SIZE_PT)
    format = format.copy(sizePt = next)
    actions.onFormatChanged(id) { it.copy(sizePt = next) }
}

/**
 * Turning a list on gives it level 1, and turning it off leaves the level alone.
 *
 * Level 0 means *"not a list"* ([ObjectFormat]'s own KDoc), so a bullet chip that left the level at
 * zero would be a list style with nothing to indent — the chip would highlight and the text would
 * not move.
 */
private fun ObjectToolState.setList(id: ObjectId?, style: ListStyle, actions: ObjectActions) {
    val next = if (format.listStyle == style) ListStyle.NONE else style
    val level = if (next == ListStyle.NONE) 0 else format.listLevel.coerceAtLeast(1)
    format = format.copy(listStyle = next, listLevel = level)
    actions.onFormatChanged(id) { it.copy(listStyle = next, listLevel = level) }
}

private fun ObjectToolState.indent(id: ObjectId?, delta: Int, actions: ObjectActions) {
    val level = (format.listLevel + delta).coerceIn(1, MAX_LIST_LEVEL)
    format = format.copy(listLevel = level)
    actions.onFormatChanged(id) { it.copy(listLevel = level) }
}

private val TextFont.label: String
    get() = when (this) {
        TextFont.SANS -> "Sans"
        TextFont.SERIF -> "Serif"
        TextFont.MONO -> "Mono"
    }

private val TextAlign.label: String
    get() = when (this) {
        TextAlign.START -> "Left"
        TextAlign.CENTER -> "Centre"
        TextAlign.END -> "Right"
    }

private val ListStyle.label: String
    get() = when (this) {
        ListStyle.NONE -> "None"
        ListStyle.BULLET -> "Bullets"
        ListStyle.NUMBERED -> "Numbers"
    }

/** Clear of the Dynamic Dock's primary row, which is the one row that is always on screen. */
private val DockSafeTop = 78.dp
private val SizeReadout = 46.dp

/** Translucent enough that ink drawn over a filled box still reads. Not user-settable. */
private const val FILL_OPACITY = 0.35f

/** One tap's worth of rounding — a visible radius at any page size, and not a pill. */
private const val CORNER_RADIUS_PT = 8f

private const val SIZE_STEP_PT = 2f
private const val MIN_SIZE_PT = 6f
private const val MAX_SIZE_PT = 96f

/** Three levels of nesting. Past that the indent is wider than a page column. */
private const val MAX_LIST_LEVEL = 3

private const val QUICK_SLOTS = 3
private const val QUARTER_TURN = (PI / 2).toFloat()
