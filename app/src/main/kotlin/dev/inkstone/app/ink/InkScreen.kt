package dev.inkstone.app.ink

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import dev.inkstone.android.ink.InkSelection
import dev.inkstone.android.ink.InkSurfaceView
import dev.inkstone.android.ink.SyntheticInk
import dev.inkstone.app.audio.RecordingUiState
import dev.inkstone.app.note.CameraCapture
import dev.inkstone.app.note.NoteEditor
import dev.inkstone.app.note.NoteEditorState
import dev.inkstone.app.note.NoteEditorViewModel
import dev.inkstone.app.note.PageBar
import dev.inkstone.app.note.PageBarActions
import dev.inkstone.app.note.PageOverviewSheet
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.rememberDotGridBrush
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.FitMode
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The note editor: one page of ink, the M1 toolbar, and M2's page bar.
 *
 * SPEC §3.2 — the ink surface is the View-based `androidx.ink` stack wrapped in an
 * [AndroidView]. The `ink-*-compose` artifacts are not used, and this is the seam where that
 * decision shows up.
 *
 * M2 added the other seam: the surface records ops and [NoteEditorViewModel] drains them on a
 * two-second timer. Nothing in this file knows what a journal is.
 */
@Composable
fun InkScreen(modifier: Modifier = Modifier, editor: NoteEditorViewModel = hiltViewModel()) {
    val ui = rememberInkUiState(editor) ?: return
    val objects = remember { ObjectToolState() }
    val scope = rememberCoroutineScope()
    val note by editor.state.collectAsState()
    val recording by editor.recording.collectAsState()
    var surface by remember { mutableStateOf<InkSurfaceView?>(null) }
    var pageOverviewOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    // ⭐ M10 — the two summoned panels, and this Box's own place in the window. All three are
    // UI-local: none survives leaving the note and none is a property of the document. See
    // [ColourPopover] on why the colour one is an anchor rather than a flag.
    var panel by remember { mutableStateOf<SummonedPanel?>(null) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current
    val objectActions = rememberObjectActions(ui, objects, editor, surface)

    SurfaceEffects(ui = ui, note = note, surface = surface)
    ObjectEffects(ui, objects, note, surface, editor, objectActions)

    DisposableEffect(surface) {
        surface?.let { editor.onSurfaceAttached(it.asNoteSurface(scope, editor)) }
        onDispose { editor.onSurfaceDetached() }
    }

    val actions = inkToolbarActions(surface, editor, ui, scope)
    val onSummon: (PanelKind, Rect) -> Unit = { kind, at -> panel = panel.toggled(kind, at) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Ink.Canvas)
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        EditorCanvas(ui, objects, editor, objectActions) { surface = it }

        TopChrome(
            ui = ui,
            objects = objects,
            actions = actions,
            objectActions = objectActions,
            onSummon = onSummon,
            recording = recording,
            onToggleRecording = { editor.toggleRecording() },
        )

        val exports = Exports(exporting, { exporting = it }, scope, context, editor)
        BottomWidgets(
            ui = ui,
            note = note,
            actions = actions,
            pageActions = pageBarActions(editor, note, { pageOverviewOpen = true }, exports),
            exporting = exporting,
            diagnosticsOpen = diagnosticsOpen,
        ) { diagnosticsOpen = !diagnosticsOpen }

        val panels = OpenPanels(
            panel?.let { it.copy(anchor = it.anchor.translate(-origin.x, -origin.y)) },
            { panel = null },
            diagnosticsOpen,
            note.localRev,
        ) { diagnosticsOpen = false }
        SidePanels(ui, objects, actions, objectActions, panels)
        ui.colorDrag.colorArgb?.let { DragPreview(it, ui.colorDrag.position - origin) }
    }

    if (pageOverviewOpen) PageOverview(note, editor) { pageOverviewOpen = false }
}

/**
 * ⭐ ROADMAP M10 — the colour under the finger during a swatch drag.
 *
 * ⚠️ Drawn by the **root**, last, above every panel and pill. The colour is picked up in the colour
 * panel's grid and lands on a swatch inside a toolbar pill that scrolls and clips, so a preview
 * parented to either end would be cut off at that end's edge halfway across the gesture.
 *
 * Larger than a swatch and lifted clear of the fingertip, because what it is being dropped on is a
 * 22 dp circle that a fingertip covers completely.
 */
@Composable
private fun DragPreview(argb: Int, at: Offset) {
    Box(
        modifier = Modifier
            .offset {
                IntOffset((at.x - DRAG_PREVIEW_PX / 2).roundToInt(), (at.y - DRAG_PREVIEW_PX).roundToInt())
            }
            .size(DRAG_PREVIEW.dp)
            .background(Color(argb), CircleShape)
            .border(2.dp, Ink.Accent, CircleShape),
    )
}

/**
 * M10 — the editor's state, seeded from what was stored, and written back on a debounce.
 *
 * ## Why this is a helper and not four lines in [InkScreen]
 *
 * Partly detekt — `InkScreen`'s body is within a couple of lines of `LongMethod`, which reports at
 * **>=** the threshold and not above it. Mostly, though, the three pieces belong together: the
 * `?: return`, the `remember(prefs)` that must be keyed on the seed, and the `snapshotFlow` that
 * carries changes the other way are one mechanism, and splitting them across a long function is how
 * one of them gets deleted.
 *
 * WARNING: **Returns null while the read is in flight**, which blanks the editor for a frame or
 * two. That is a deliberate trade against seeding with defaults and swapping: the swap would repaint
 * the palette in front of the user, and — worse — any edit made in that window would be written on
 * top of the stored palette by the very next debounce. `EditorPreferences.warm` cannot hang: a
 * failed read resolves to defaults rather than staying null, so there is no state in which this
 * returns null for ever.
 *
 * WARNING: `snapshotFlow { ui.toPrefs() }` reads **only** persisted fields, because it re-runs
 * whenever any state it touched changes. `toPrefs`'s KDoc is where that constraint is written down;
 * adding `camera` to it would be a database write per frame of every pan.
 */
@Composable
private fun rememberInkUiState(editor: NoteEditorViewModel): InkUiState? {
    val prefs = editor.editorPrefs.collectAsState().value ?: return null
    val ui = remember(prefs) { InkUiState(prefs) }
    LaunchedEffect(ui) {
        snapshotFlow { ui.toPrefs() }.collect(editor::editorPrefsChanged)
    }
    return ui
}

/** ROADMAP M3's page overview, reached by tapping the page counter in the page bar. */
@Composable
private fun PageOverview(note: NoteEditorState, editor: NoteEditorViewModel, onDismiss: () -> Unit) {
    PageOverviewSheet(
        note = note,
        thumbnail = { pageId -> editor.pageThumbnail(pageId) },
        onGoToPage = { index -> editor.goToPage(index) },
        onMovePage = { _, to -> editor.movePage(to) },
        onDismiss = onDismiss,
    )
}

/**
 * ⭐ ROADMAP M10 — everything under the chrome: the canvas, the page, and the object overlays.
 *
 * The dot grid is the design's *"Deep Charcoal with a subtle dot-grid pattern for scale and
 * alignment"*, drawn **behind** the `AndroidView` — which is a transparent `FrameLayout` everywhere
 * the page is not, so the grid shows around the paper and nowhere else. One tiled draw call; see
 * `rememberDotGridBrush` on why that matters here of all places.
 *
 * ⚠️ **The three are in this order deliberately.** `ObjectLayers` sits above the surface so a tap
 * with the Text tool reaches it, and below the toolbars so a contextual bar is still pressable while
 * a field has focus. Re-ordering them is how the app loses either its keyboard or its pen.
 */
@Composable
private fun BoxScope.EditorCanvas(
    ui: InkUiState,
    objects: ObjectToolState,
    editor: NoteEditorViewModel,
    objectActions: ObjectActions,
    onCreated: (InkSurfaceView) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(rememberDotGridBrush()))
    InkSurface(ui = ui, objects = objects, editor = editor, onCreated = onCreated)
    ObjectLayers(ui = ui, objects = objects, editor = editor, actions = objectActions)
    // ⚠️ Above the surface, because that is what it is about — see [InkUiState.pageLoading] on
    // why a page that is briefly blank has to say so rather than look empty.
    if (ui.pageLoading) PageLoadingNote()
}

/**
 * *"Loading page…"*, centred over the paper, for as long as its objects are being built.
 *
 * The same [GlassBar] vocabulary as every other piece of chrome (see `Glass.kt`), so a page that is
 * mid-load looks like the app rather than like a failure. No progress *value*: the work is one
 * `PreparedPage.of` call and it does not report a fraction, and a bar that jumps 0 → 100 says less
 * than a spinner does.
 */
@Composable
private fun BoxScope.PageLoadingNote() {
    GlassBar(modifier = Modifier.align(Alignment.Center), scrollable = false) {
        CircularProgressIndicator(
            modifier = Modifier.size(LoadingSpinner),
            strokeWidth = LoadingSpinnerStroke,
            color = Ink.Accent,
        )
        Text(
            text = "Loading page…",
            color = Ink.TextNormal,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = InkDims.ChipGap),
        )
    }
}

/**
 * ⭐ ROADMAP M10 — the design's three bottom zones: history left, pages centre, zoom right.
 *
 * > *"Separated from the main tools to prevent accidental taps during drawing."*
 *
 * ⚠️ **One `Row`, not three `align`ments, and that is a bug fix rather than tidiness.** Aligning the
 * three independently to `BottomStart` / `BottomCenter` / `BottomEnd` is what the design describes
 * and it is what shipped for exactly one emulator run: the page bar is eleven chips wide, so on a
 * 2560 px tablet it ran *underneath* the zoom widget and the two share chips were unreachable —
 * drawn, listed by `uiautomator`, and covered by a pill that swallowed the tap. Laying them out as
 * siblings makes the overlap unrepresentable: the centre zone is what is left after the other two,
 * and `GlassBar` scrolls inside it.
 *
 * The page bar is therefore centred **between its neighbours** rather than on the screen. They
 * differ in width by about 90 dp, so it sits ~45 dp off true centre — which is invisible, and is the
 * price of a bar that cannot hide its own controls.
 *
 * ## ⚠️ Why this stays one row even when it does not fit
 *
 * On a narrow window it genuinely does not: 143 dp of history, 598 dp of page bar and 390 dp of
 * zoom, with two [ZONE_GAP]s and the edge padding, is 1,211 dp against **800 dp** of tablet held in
 * portrait, so the weighted centre zone collapses to 188 dp and most of the page bar is reachable
 * only by dragging it sideways.
 *
 * Moving the page bar onto a second line fixes that and was tried, twice — above the other two, then
 * below them — and both were rejected on sight: *"the thing that has ADD DUPLICATE and DELETE is
 * moved up and i dont like that"*, then *"its not like how it was at all"*. The second version was
 * worse than the first for a reason worth keeping: a stacked row pushes the history and zoom pills
 * **up onto the paper**, so they straddle the page's bottom edge instead of sitting on the canvas
 * below it, and two pills cut in half by the edge of the page read as broken rather than as
 * separated.
 *
 * So the layout does not change with the window, and what changed instead is that `GlassBar` now
 * *fades* at an end it has more content beyond — the same controls, in the same places, with the
 * one thing they were missing, which is any sign that the bar can be dragged.
 */
@Composable
private fun BoxScope.BottomWidgets(
    ui: InkUiState,
    note: NoteEditorState,
    actions: InkToolbarActions,
    pageActions: PageBarActions,
    exporting: Boolean,
    diagnosticsOpen: Boolean,
    onToggleDiagnostics: () -> Unit,
) {
    val edge = InkDims.EdgePadding
    Row(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(edge),
        // ⚠️ **Wider than `edge`.** At 12 dp the page bar's rounded end sat almost against the zoom
        // widget's, and two pills a hairline apart read as one bar that has been sliced rather than
        // as the design's three separate zones.
        horizontalArrangement = Arrangement.spacedBy(ZONE_GAP.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        HistoryWidget(ui, actions)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
            PageBar(state = note, actions = pageActions, exporting = exporting)
        }
        ZoomWidget(
            state = ui,
            actions = actions,
            diagnosticsOpen = diagnosticsOpen,
            onToggleDiagnostics = onToggleDiagnostics,
        )
    }
}

/**
 * ⭐ ROADMAP M10 — the contextual toolbars, the Dynamic Dock, and the record chip.
 *
 * ⚠️ **`ContextBars` is composed before `EditorDock`, and that is two things at once.** Composition
 * order is draw order in a `Box`, so the dock draws over a bar that would otherwise slide under it;
 * and the bars' return value is what tells the dock to collapse its secondary row — the design's
 * *"summons its respective Contextual Toolbar and hides the Dynamic Dock's secondary row"*. Swapping
 * the two lines breaks the second silently, because a `Boolean` read one recomposition late is a
 * row that flickers rather than a compile error.
 *
 * ⚠️ **The dock and the record chip abut; they do not overlap, and this was checked.** Held in
 * portrait the dock's primary row ends at x=1401 and `RECORD` begins at x=1401, so the two touch
 * and the rounded ends read as one merged blob — but every chip keeps its own hit rectangle and
 * `RULER` is pressable. Row two is 100 dp lower and never meets the chip at all.
 *
 * ⚠️ **Two fixes for that crowding were built and both were reverted, which is worth recording
 * so a third is not attempted.** Laying the two out as siblings in a `Row` — which is what
 * [BottomWidgets] does, and which makes overlap unrepresentable — centres the dock in what is left
 * rather than on the window, and moved it **63 dp** in landscape. Reserving the record chip's
 * measured width symmetrically instead keeps landscape byte-identical, but narrows the dock in
 * portrait by 212 dp, which pushes `RULER` off the end of a scrolling bar: it trades a chip that
 * looks cramped for a chip that is not there. Neither is worth it for an appearance problem, and
 * the user's requirement is that landscape stay exactly as it shipped.
 */
@Composable
private fun BoxScope.TopChrome(
    ui: InkUiState,
    objects: ObjectToolState,
    actions: InkToolbarActions,
    objectActions: ObjectActions,
    onSummon: (PanelKind, Rect) -> Unit,
    recording: RecordingUiState,
    onToggleRecording: () -> Unit,
) {
    val edge = InkDims.EdgePadding
    val contextual = ContextBars(
        ui = ui,
        objects = objects,
        actions = actions,
        objectActions = objectActions,
        onSummon = onSummon,
    )
    EditorDock(
        state = ui,
        objects = objects,
        actions = actions,
        objectActions = objectActions,
        onSummon = onSummon,
        secondarySuppressed = contextual,
        modifier = Modifier.align(Alignment.TopCenter).padding(edge),
    )
    // ⭐ M12 §10 — the design's *"Global Utilities (Top Right)"* zone, away from the pen tools
    // because it is not one.
    RecordButton(recording, onToggleRecording, Modifier.align(Alignment.TopEnd).padding(edge))
}

/**
 * Pressing the control that opened a panel closes it; pressing a *different* one swaps.
 *
 * A function rather than three lines in [InkScreen] because that composable is exactly at detekt's
 * `LongMethod` limit, which reports at **>=** and not above — and because this is the whole rule
 * that makes one value rather than one flag per panel work. See [PanelKind].
 */
private fun SummonedPanel?.toggled(kind: PanelKind, at: Rect): SummonedPanel? =
    if (this?.kind == kind) null else SummonedPanel(kind, at)

/**
 * Which summoned panels are up, as one value.
 *
 * A holder rather than four positional parameters, for the reason `Exports` is one: the two are
 * always passed together and a bare `Boolean` beside a nullable `Rect` is a pair of arguments that
 * can be swapped without the compiler noticing.
 */
@Immutable
private class OpenPanels(
    /** Which panel is up and where it hangs from, in [InkScreen]'s own coordinates. Null = none. */
    val panel: SummonedPanel?,
    val onClosePanel: () -> Unit,
    val diagnostics: Boolean,
    /** `local_rev`, for the diagnostics panel's heartbeat line. */
    val revision: Long,
    val onCloseDiagnostics: () -> Unit,
)

/** The two panels that are summoned rather than resident, and nothing else. */
@Composable
private fun BoxScope.SidePanels(
    ui: InkUiState,
    objects: ObjectToolState,
    actions: InkToolbarActions,
    objectActions: ObjectActions,
    open: OpenPanels,
) {
    if (open.diagnostics) {
        DiagnosticsPanel(
            state = ui,
            actions = actions,
            revision = open.revision,
            onClose = open.onCloseDiagnostics,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = SIDE_PANEL_BOTTOM.dp, end = InkDims.EdgePadding),
        )
    }
    when (open.panel?.kind) {
        PanelKind.COLOUR -> ColourPopover(open.panel.anchor, ui, objects, actions, open.onClosePanel, objectActions)
        PanelKind.SIZE -> SizePopover(open.panel.anchor, ui, open.onClosePanel)
        null -> Unit
    }
}

/**
 * ⭐ ROADMAP M10 — the extended colour panel, under the `+` that opened it.
 *
 * ## Why the open state is an anchor and not a boolean
 *
 * The design says the `+` *"summons a side-panel"*, and a panel that appears in a corner has to be
 * found; one under the control you just pressed does not. So [InkScreen] holds the `+`'s bounds
 * rather than a flag — the two carry the same information, and only one of them can also say
 * *where*. There is never more than one `+` on screen to be ambiguous about: row two collapses
 * whenever a contextual toolbar is up.
 *
 * ⚠️ Those bounds arrive in **root** coordinates and [InkScreen]'s Box is inset by the Scaffold's
 * status-bar padding, so they are rebased against its `positionInRoot` before they get here. Skip
 * that and the panel opens one status bar too low, which looks like a rounding error rather than
 * like a missing subtraction.
 *
 * ## Why a scrim, and why it eats the tap
 *
 * The panel closes when you press **anything else — including the paper**. That is a full-screen
 * transparent layer under it, and the tap that dismisses it does not reach the page. Letting it
 * through would mean a stroke drawn by the same press that put the panel away, which is the one
 * thing somebody adjusting a colour is not trying to do.
 *
 * ⚠️ **Not a `Popup`.** A popup is a second window, and this screen's wet ink is a front-buffered
 * `SurfaceView` that SurfaceFlinger composites over the app's window (SPEC §3.2) — window ordering
 * against it is exactly the thing this codebase has been bitten by twice. An in-window layer is
 * ordered by composition, which is a rule with no platform in it.
 *
 * ## Which colour it edits
 *
 * The same `when` `ContextBars` uses, and it has to be: there is only ever one `+` on screen, because
 * row two collapses whenever a contextual toolbar is up. So a text box being typed into takes the
 * text colour, a selection takes a recolour of the selection, and otherwise it is the pen. Deriving
 * it here rather than threading three different callbacks down is what stops the two disagreeing
 * about which control the open panel belongs to.
 */
@Composable
private fun BoxScope.ColourPopover(
    anchor: Rect,
    ui: InkUiState,
    objects: ObjectToolState,
    actions: InkToolbarActions,
    onDismiss: () -> Unit,
    objectActions: ObjectActions,
) {
    val editing = objects.editing
    val argb = if (editing != null) objects.style.colorArgb else ui.pen.colorArgb
    val onColorChange: (Int) -> Unit = when {
        editing != null -> { next ->
            objects.style = objects.style.copy(colorArgb = next)
            objectActions.onColourChanged(editing.id, next)
        }

        ui.selectionSize > 0 -> actions.onRecolourSelection

        else -> ui::setColor
    }

    Box(
        modifier = Modifier
            .matchParentSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    )
    AnchoredPanel(anchor = anchor) {
        ColorPanel(
            argb = argb,
            slots = ui.slots,
            // ⭐ Every change writes the chosen slot as well as applying the colour, which is what
            // makes the wheel *edit a swatch*. `updateChosenSlot` is a no-op until a slot has been
            // tapped, so mixing a colour without choosing one still leaves the palette alone.
            onColorChange = { next ->
                ui.updateChosenSlot(next)
                onColorChange(next)
            },
            onChooseSlot = ui::chooseSlot,
            onClose = onDismiss,
            drag = ui.colorDrag,
            onDropped = ui::swapSlots,
        )
    }
}

/**
 * ⭐ ROADMAP M10 — the saved-sizes panel, under the number that opened it.
 *
 * *"hold down on the number to bring up a menu like the color menu"* — so it is deliberately the
 * same shape as [ColourPopover]: the same anchor arithmetic, the same full-screen scrim that eats a
 * tap anywhere (including on the paper) and closes.
 *
 * ⚠️ **Its own scrim rather than one hoisted into [AnchoredPanel].** Hoisting is tidier and would
 * have edited a shipped, working dismissal path for no behavioural gain; composition order in a
 * `Box` is hit-test order, so getting it subtly wrong stops the *colour* panel dismissing on a tap
 * on the paper — a regression in the thing this round was asked to preserve.
 */
@Composable
private fun BoxScope.SizePopover(anchor: Rect, ui: InkUiState, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    )
    // ⚠️ Every route that takes this panel off screen — the scrim, `✕`, pressing the readout again,
    // opening a different panel — ends the edit here, so the ladder is never left mid-edit (and so
    // possibly unsorted, see `InkUiState.editingWidthSlot`) behind a closed panel.
    DisposableEffect(ui) { onDispose { ui.finishWidthEdit() } }
    AnchoredPanel(anchor = anchor) {
        SizePanel(
            widthDp = ui.pen.widthDp,
            slots = ui.widthSlots,
            editingIndex = ui.editingWidthSlot,
            defaultWidthDp = ui.defaultWidthDp,
            maxWidthDp = ui.maxWidthDp,
            edits = SizeEdits(
                onWidthChange = ui::editWidth,
                onChoose = ui::chooseWidthSlot,
                onForget = ui::removeWidthSlot,
                onAdd = ui::addWidthSlot,
                onDelete = ui::deleteEditedWidthSlot,
                onMakeDefault = ui::makeDefaultWidth,
                onMaxChange = ui::setMaxWidth,
            ),
            onClose = onDismiss,
        )
    }
}

/**
 * Places a panel under [anchor], centred on it, flipped above when it will not fit below.
 *
 * The same arithmetic `ContextBars.AnchoredBar` does for a toolbar, and separate for the reason it is
 * separate there: it has to be measured before it can be centred, so it is invisible for one frame
 * rather than visibly jumping in from the corner.
 */
@Composable
private fun AnchoredPanel(anchor: Rect, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        var size by remember { mutableStateOf(IntSize.Zero) }
        val density = LocalDensity.current
        val gap = with(density) { InkDims.ChipGap.toPx() }
        val pad = with(density) { InkDims.EdgePadding.toPx() }

        val maxX = (constraints.maxWidth - size.width - pad).coerceAtLeast(pad)
        val maxY = (constraints.maxHeight - size.height - pad).coerceAtLeast(pad)
        val below = anchor.bottom + gap
        val fits = below + size.height <= constraints.maxHeight - pad
        val x = (anchor.center.x - size.width / 2f).coerceIn(pad, maxX)
        val y = (if (fits) below else anchor.top - size.height - gap).coerceIn(pad, maxY)

        Box(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .onSizeChanged { size = it }
                .alpha(if (size == IntSize.Zero) 0f else 1f),
        ) { content() }
    }
}

/**
 * ⭐ M8c — the effects that keep the object layers in step with the note.
 *
 * Beside [SurfaceEffects] and for the same reason, and each is a bug that would otherwise be
 * silent:
 *
 * 1. **The asset cache has to be refreshed and handed over**, or every image on the page draws the
 *    missing-asset placeholder. A cache that is correct and never reaches the surface is M6's
 *    inert-feature shape exactly (`PageAssets`).
 * 2. **Turning a page, or picking another tool, commits and then drops everything transient.** The
 *    commit is the data-loss half — a user who types and turns the page has left before the
 *    debounce fired. The drop is the honesty half: crop handles drawn over page 2 for an image on
 *    page 1 are chrome that lies.
 */
@Composable
private fun ObjectEffects(
    ui: InkUiState,
    objects: ObjectToolState,
    note: NoteEditorState,
    surface: InkSurfaceView?,
    editor: NoteEditorViewModel,
    actions: ObjectActions,
) {
    // Keyed on the revision as well as the page: an image merged from a peer arrives as a revision
    // bump, and without that key its bytes would sit in the bundle undecoded until the next page
    // turn (SPEC §13.2 row 10 — the peer's edit has to be visible *now*).
    LaunchedEffect(surface, note.pageIndex, note.localRev) {
        val view = surface ?: return@LaunchedEffect
        if (editor.assetCache.refresh()) view.setPageAssets(editor.assetCache)
    }
    LaunchedEffect(surface) { surface?.setPageAssets(editor.assetCache) }

    // ⚠️ **Commit, then reset — in that order, and keyed on both.** Turning a page or picking
    // another tool is a user leaving a text box, and the debounce has not fired yet: without the
    // commit the tail of what they typed is in no register and no journal, which is the same data
    // loss the field's focus-loss handler exists to prevent by a different route. `updateText` is
    // idempotent, so the two firing together costs nothing. And the reset has to happen or crop
    // handles stay drawn over a page they do not belong to.
    //
    // ⚠️ **The reset is not inside the null check**, and it used to be. `objects.editing` is null
    // whenever a *picture* is what is focused, so the early return skipped `reset()` for exactly
    // the case the comment above describes: the crop handles were the thing that stayed drawn over
    // a page they did not belong to, and every image button then acted on an id
    // `NoteEditor.setCrop` could no longer find — no message, no snackbar, nothing happening.
    LaunchedEffect(note.pageIndex, ui.tool) {
        objects.editing?.let { actions.onTextChanged(it.id, objects.draft) }
        objects.reset()
    }
}

/**
 * ⭐ M8c — what the object toolbars and the overlay actually call.
 *
 * A `@Composable` because two of the actions are activity results — the photo picker and the
 * camera — and a launcher can only be remembered inside composition. The rest are one line each
 * onto [NoteEditorViewModel], which is the point: every M8c edit goes through the editor's lock
 * rather than through the ink surface, because none of them is on the ink path.
 */
@Composable
private fun rememberObjectActions(
    ui: InkUiState,
    objects: ObjectToolState,
    editor: NoteEditorViewModel,
    surface: InkSurfaceView?,
): ObjectActions {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var cameraTarget by remember { mutableStateOf<android.net.Uri?>(null) }

    val insert: (android.net.Uri?) -> Unit = { uri ->
        val view = surface
        if (uri != null && view != null) {
            scope.launch { insertPicture(editor, uri, ui.camera, view) }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { insert(it) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) insert(cameraTarget)
    }

    return ObjectActions(
        // ⚠️ A null id means the box has not been created yet — placing and typing are one gesture
        // (`TextBoxEdit.create`), so the *first* commit with characters in it is what creates the
        // object, and a tap that types nothing leaves nothing behind.
        onTextChanged = { id, text -> scope.launch { commitText(editor, objects, id, text) } },
        // A format change before the box exists is kept on screen only; it rides into
        // `TextBoxEdit.create` with the first words, as one gesture and one undo entry.
        onFormatChanged = { id, change -> id?.let { editor.updateFormat(it, change) } },
        onColourChanged = { id, argb -> id?.let { editor.recolour(it, argb) } },
        onInsertImage = { source ->
            when (source) {
                ImageSource.GALLERY ->
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

                ImageSource.CAMERA -> {
                    val target = CameraCapture.target(context)
                    cameraTarget = target
                    if (target == null) editor.report(CAMERA_FAILED) else takePhoto.launch(target)
                }
            }
        },
        onTurn = { id, degrees, flipH, flipV -> editor.turnObject(id, degrees, flipH, flipV) },
        onCrop = { id, crop -> editor.setCrop(id, crop) },
        // ⚠️ Through the surface's own ruler, never through the mirror in `InkUiState`. The edge,
        // its position and the projection matrix that snaps ink to it all live in
        // `RulerController`; writing the mirror alone would draw a ruler here that guided nothing.
        onRulerEnabled = { on -> surface?.ruler?.enabled = on },
        onRulerAngle = { degrees -> surface?.ruler?.angleDegrees = degrees },
        onSaveElement = { name, collection ->
            val area = objects.capturedArea
            objects.capturedArea = null
            if (area != null) {
                scope.launch {
                    val saved = editor.saveElement(name, collection, area)
                    editor.report(if (saved == null) NOTHING_TO_SAVE else "Saved “${saved.name}” to Elements")
                    // ⚠️ Both, and in this order. `elementsOpen = true` shows the panel, but it is
                    // a no-op when the panel is **already** open — which is the case whenever
                    // somebody is looking at the library while filing into it — so on its own it
                    // reloads nothing and the new element never appears. `libraryChanged` is what
                    // says the index on disk has moved; see `ObjectToolState.libraryRevision`.
                    objects.elementsOpen = true
                    if (saved != null) objects.libraryChanged()
                }
            }
        },
    )
}

/**
 * Decode, downscale, insert, and say what it cost.
 *
 * The byte numbers are said out loud once, on purpose. *"Downscaled at insert time"* is a claim
 * about **sizes**, and a claim about sizes with no measurement behind it is how a 4 MB photograph
 * ends up inside a note's bundle, inside the published PDF, uploaded on every sync, forever.
 */
private suspend fun insertPicture(
    editor: NoteEditorViewModel,
    uri: android.net.Uri,
    camera: dev.inkstone.core.model.Camera,
    view: InkSurfaceView,
) {
    val paper = view.pageCanvas
    val report = editor.insertImage(uri, paper.size, visiblePage(camera, view.width, view.height, paper))
    editor.report(
        report?.let {
            "Picture added — ${it.sourceBytes / KB} kB in, ${it.storedBytes / KB} kB stored " +
                "at ${it.widthPx}×${it.heightPx}"
        } ?: IMAGE_FAILED,
    )
}

/**
 * Everything `:app` mirrors from a selection change: the count, the box it occupies, what its shapes
 * can have done to them, and which single object — if any — has a contextual bar of its own.
 *
 * ⭐ The image bar and the *"Edit text"* chip both follow the **selection** rather than a tool's
 * own tap. That is what let the Image tool give up owning touch, which is what let an image be
 * dragged at all (see `EditorTool.IMAGE`), and it gives a text box the same property: reached with
 * the lasso, with a tap or by a snap, it offers the same way back into the field.
 */
private fun applySelection(count: Int, ui: InkUiState, objects: ObjectToolState, selection: InkSelection) {
    ui.selectionSize = count
    // ⭐ M10 — where the selection *is*, so its contextual toolbar can float beside it.
    ui.selectionBounds = selection.bounds
    ui.selectionChrome = selection.chromeBounds
    ui.selectionManipulating = selection.isManipulating
    val counts = selection.shapeCounts
    ui.selectedShapes = counts.shapes
    ui.selectedConnectors = counts.connectors
    ui.selectedFillable = counts.fillable
    ui.selectedRoundable = counts.roundable
    ui.selectedRounded = counts.rounded
    ui.selectedIrregular = counts.irregular
    ui.selectedArrowable = counts.arrowable
    ui.selectedArrowed = counts.arrowed
    ui.selectedKind = counts.soleKind
    val picture = selection.soleImage
    objects.focusImage(
        picture?.let { ImageFocus(it.id, it.bounds, it.widthPt, it.heightPt, it.transform) },
        picture?.format ?: objects.format,
    )
    val box = selection.soleTextBox
    objects.focusText(box?.let { TextFocus(it.id, it.bounds, it.widthPt, it.heightPt, it.text, it.format, it.style) })
}

/**
 * The words, whether or not the box exists yet.
 *
 * ⚠️ A null [id] is a box that has only been *placed* — `TextBoxEdit.create`'s KDoc requires the
 * placement and the first words to be one gesture, because the first `SetTextContent` on an empty
 * box has no inverse and *"type a word, press undo"* would delete the box instead. So the first
 * commit with characters in it creates the object, and a placement that never gets any leaves
 * nothing behind.
 */
private suspend fun commitText(editor: NoteEditorViewModel, objects: ObjectToolState, id: ObjectId?, text: String) {
    when {
        id != null -> editor.updateText(id, text)

        text.isBlank() -> Unit

        else -> {
            val bounds = objects.editing?.bounds ?: return
            editor.insertTextBox(bounds, text, objects.style, objects.format)?.let(objects::attach)
        }
    }
}

private const val KB = 1024

private const val IMAGE_FAILED = "That file could not be read as a picture."
private const val CAMERA_FAILED = "There was nowhere to save the photo. Free some space and try again."
private const val NOTHING_TO_SAVE =
    "Nothing in that box could be saved yet — Elements hold pictures, text and shapes, not handwriting."

/** The M1 toolbar's wiring — kept beside [pageBarActions] rather than inline, for the same reason. */
private fun inkToolbarActions(
    surface: InkSurfaceView?,
    editor: NoteEditorViewModel,
    ui: InkUiState,
    scope: CoroutineScope,
) = InkToolbarActions(
    onFit = { mode -> surface?.resetCamera(mode) },
    onUndo = { editor.undo() },
    onRedo = { editor.redo() },
    onClear = { surface?.clearPage() },
    onLoadSyntheticPage = { count -> loadSyntheticPage(scope, surface, ui, count) },
    onResetLatency = {
        surface?.latency?.reset()
        surface?.stylus?.reset()
    },
    // ⭐ ROADMAP M8a. Straight through to the surface, like `onClear` and unlike `onUndo`:
    // these are gestures on the page, so they belong with the other gestures, and routing them
    // through the view model would assign their Lamport clocks inside a coroutine (see
    // `SelectionController`). The surface calls `onSelectionChanged` back, which is what makes
    // the row disappear after a delete rather than offering actions for nothing.
    onDeleteSelection = { surface?.selection?.delete() },
    onDuplicateSelection = { surface?.selection?.duplicate() },
    onRecolourSelection = { argb -> surface?.selection?.recolour(argb) },
    onResizeSelectionStroke = { widthDp -> surface?.selection?.setWidth(widthDp) },
    onBringSelectionToFront = { surface?.selection?.bringToFront() },
    onSendSelectionToBack = { surface?.selection?.sendToBack() },
    onClearSelection = { surface?.selection?.clear() },
    onFillSelection = { colorArgb, opacity -> surface?.selection?.setFill(colorArgb, opacity) },
    onRoundSelection = { radiusPt -> surface?.selection?.setCornerRadius(radiusPt) },
    onRouteSelection = { route -> surface?.selection?.setRoute(route) },
    onRegulariseSelection = { surface?.selection?.makeRegular() },
    onArrowSelection = { shown -> surface?.selection?.setArrowHead(shown) },
)

/**
 * What the two share buttons need, as one value.
 *
 * A parameter object because the pair is inseparable: they share the `busy` flag and the cache
 * directory it protects, and passing them individually is how one of them ends up with its own
 * flag and deletes the other's files mid-chooser.
 *
 * ⚠️ **One `exporting` flag for both, not one each.** They copy into the same share cache directory
 * and each sweeps it before writing, so two running at once would have the second delete the first's
 * files out from under a chooser the user already has open.
 */
private class Exports(
    val busy: Boolean,
    val onBusy: (Boolean) -> Unit,
    val scope: CoroutineScope,
    val context: android.content.Context,
    val editor: NoteEditorViewModel,
) {
    fun run(export: suspend (NoteEditorViewModel, android.content.Context) -> Unit) {
        if (busy) return
        onBusy(true)
        scope.launch {
            export(editor, context)
            onBusy(false)
        }
    }
}

private fun pageBarActions(
    editor: NoteEditorViewModel,
    note: NoteEditorState,
    onShowPages: () -> Unit,
    exports: Exports,
) = PageBarActions(
    onPrevious = { editor.goToPage(note.pageIndex - 1) },
    onNext = { editor.goToPage(note.pageIndex + 1) },
    onAddPage = { editor.addPage(note.template) },
    onDuplicatePage = { editor.duplicateCurrentPage() },
    onDeletePage = { editor.deleteCurrentPage() },
    onMoveLeft = { editor.movePage(note.pageIndex - 1) },
    onMoveRight = { editor.movePage(note.pageIndex + 1) },
    onTemplate = { template -> editor.setTemplate(template) },
    onToggleInfiniteCanvas = { editor.setInfiniteCanvas(!note.infiniteCanvas) },
    onShowPages = onShowPages,
    onShare = { exports.run(::exportAndShare) },
    onShareRecording = { exports.run(::exportRecordingAndShare) },
)

/**
 * ROADMAP M4 — materialize, then hand the published PDF to whatever the user picks.
 *
 * Lives here rather than in the view model because both halves are Android UI: a chooser
 * needs an `Activity` context, and [NoteEditorViewModel] deliberately holds neither.
 *
 * [context] must be the activity's, not the application's — starting a chooser from an
 * application context requires `FLAG_ACTIVITY_NEW_TASK`, which launches it outside the note's
 * task and leaves the user in a back stack that does not return here.
 */
private suspend fun exportAndShare(editor: NoteEditorViewModel, context: android.content.Context) {
    val uri = editor.exportPdf()
    if (uri == null) {
        editor.reportExportFailed()
        return
    }
    context.startActivity(editor.chooserFor(uri))
}

/**
 * ⭐ M12 — hand this note's recordings to another app (docs/M12-AUDIO.md §15.5).
 *
 * The same shape as [exportAndShare] and here for the same reason: a chooser needs an Activity
 * context and the view model deliberately holds none. No materialize first — a finished recording
 * is immutable, which is the one way this is simpler than the PDF.
 */
private suspend fun exportRecordingAndShare(editor: NoteEditorViewModel, context: android.content.Context) {
    val uris = editor.exportRecordings()
    if (uris == null) {
        editor.reportNoRecordings()
        return
    }
    context.startActivity(editor.chooserForRecordings(uris))
}

/**
 * The two effects that keep the surface and the UI state in step.
 *
 * Extracted from [InkScreen] so that it stays about layout. Neither is optional: the first is the
 * only source of SPEC §3.6's HUD, and the second is what makes the undo button honest.
 */
@Composable
private fun SurfaceEffects(ui: InkUiState, note: NoteEditorState, surface: InkSurfaceView?) {
    // The latency HUD (SPEC §3.6). Polled rather than pushed: ink reports a LatencyData for
    // every input, and turning 240 of those a second into recompositions would be measuring the
    // measurement.
    LaunchedEffect(surface) {
        val view = surface ?: return@LaunchedEffect
        while (true) {
            ui.latency = view.latency.snapshot()
            ui.stylus = view.stylus.snapshot()
            ui.frames = ui.frameStats.snapshot()
            ui.erasedCount = view.erasedCount
            ui.hasHighlighter = view.hasHighlighter
            // The texture size is printed in every state: it is the number that says whether the
            // cached page is at device resolution, and reading it wrong is a visible artefact.
            ui.dryLayer = when {
                view.dryLayerRefused -> "REFUSED ${view.dryLayerSize} > max ${view.maxTextureSize}"
                view.dryLayerActive -> "on ${view.dryLayerSize} (max ${view.maxTextureSize})"
                else -> "off (zooming) ${view.dryLayerSize}"
            }
            delay(LATENCY_POLL_MS)
        }
    }

    // Undo state lives with the session now (SPEC §4.6 — undo is an op in the log), so it is
    // pushed into the view rather than read out of it.
    LaunchedEffect(note.canUndo, note.canRedo, surface) {
        ui.canUndo = note.canUndo
        ui.canRedo = note.canRedo
        surface?.canUndo = note.canUndo
        surface?.canRedo = note.canRedo
    }
}

/**
 * The ink surface itself — SPEC §3.2's `AndroidView` seam.
 *
 * Split out of [InkScreen] because the two halves have nothing to say to each other: this one
 * is view interop and callback wiring, the other is layout.
 */
@Composable
private fun InkSurface(
    ui: InkUiState,
    objects: ObjectToolState,
    editor: NoteEditorViewModel,
    onCreated: (InkSurfaceView) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            InkSurfaceView(context).apply {
                // ⭐ M8c — the camera is mirrored into the UI state as well as the zoom readout,
                // because the Compose overlay lays a text field out at a page-space box and turns
                // a tap back into page space. A `View`'s field is not Compose state, so without
                // this push nothing would invalidate the overlay on a pan and the field would
                // stay where the page used to be.
                onCameraChanged = { camera ->
                    ui.zoomPercent = zoomPercent()
                    ui.camera = camera
                }
                onStrokeCountChanged = { ui.strokeCount = it }
                onPageLoadingChanged = { ui.pageLoading = it }
                onHistoryChanged = { ui.strokeCount = strokeCount }
                onUndoRequested = { editor.undo() }
                onRedoRequested = { editor.redo() }
                onEraserAutoReleased = { ui.tool = EditorTool.PEN }
                // ⭐ M8c — the ruler's own state, pushed back so the angle field follows a finger
                // drag instead of going stale the moment anybody touches the edge itself. The
                // surface owns the edge; `InkUiState` only mirrors it (see `InkUiState.rulerEnabled`).
                onRulerChanged = {
                    ui.rulerEnabled = ruler.enabled
                    ui.rulerAngleDegrees = ruler.angleDegrees
                }
                // ⭐ M8a — what the selection action row is shown and enabled by.
                //
                // The body is a named function so the whole of it reads at one indent: it asks the
                // surface five separate questions, and every one of them is a *property of the
                // selection* asked at the one moment the selection is known to have changed. A
                // second callback per property would be a second thing to forget to fire.
                onSelectionChanged = { count -> applySelection(count, ui, objects, selection) }
                // ⭐ M8b — the acknowledgement. Without it the first snap is indistinguishable
                // from the app having eaten the stroke and drawn something else.
                // "attached" rather than the kind, when it bound: a connector and a line are
                // pixel-identical when drawn and behave differently the moment a box moves, so
                // the acknowledgement has to say which one the user got.
                // ⭐ A tap on bare paper in Select puts the pen back — *"if you just tap the screen
                // it unselects (which it already does) and exits the select tool (which it does
                // not)"*. `selectBrush` restores whichever brush was last in hand rather than a
                // fixed one, so the tool you get back is the tool you left.
                onSelectTappedAway = { ui.selectBrush(ui.kind) }
                onShapeSnapped = { result ->
                    // A connector's geometry is derived from what it is attached to, so it is the
                    // one snapped shape with no point handles to offer (see
                    // `SelectionController.soleShape`) — which is why only the other branch
                    // mentions them.
                    val what = if (result.curved) "curve" else result.kind.name.lowercase()
                    ui.lastSnapped = if (result.attached) "$what · attached" else "$what · drag its points"
                    // ⭐ **Hand the shape over, don't keep drawing on it.** The surface selects what
                    // it just made, so its handles are already on screen — and until this milestone
                    // the pen was still the active tool, so the next stroke landed *on* the new
                    // shape instead of editing it. In the user's words: *"it makes it so that you
                    // draw immediately after, so you end up drawing on the shape rather then
                    // editing it"*.
                    //
                    // ⚠️ This reverses SPEC §4.8.7's *"the tool is deliberately not changed"*, whose
                    // reasoning was that a flowchart is five boxes in a row and stealing the pen
                    // after each costs five taps to give it back. That trade is real and it was
                    // called the wrong way: the tap to get the pen back is on a chip the user is
                    // already looking at, while the cost of the old default was drawing on your own
                    // shape and undoing it. SPEC has been corrected rather than contradicted.
                    ui.tool = EditorTool.SELECT
                }
                // ⭐ M12 §3 — the timeline's only input. Fired on the UI thread in the frame the
                // dry layer redraws, so the view model does one subtraction and one enqueue and
                // returns (CLAUDE.md rule 7); the file append rides the journal's own rhythm.
                onStrokeCaptured = { objectId, atElapsed -> editor.strokeCaptured(objectId, atElapsed) }
                onCreated(this)
            }
        },
        update = { view ->
            view.pen = ui.pen
            // ⚠️ M8a — the line that makes the Select chip do anything. A tool the toolbar
            // knows about and this lambda does not push is a chip that highlights and changes
            // nothing, which is CLAUDE.md's definition-of-done #6 in one missing assignment.
            //
            // ⭐ M8c — `.ink`, because the tool is now `EditorTool` and three of its values are
            // handled by the Compose overlay rather than by the surface. See that enum on why the
            // fall-through is SELECT and not PEN.
            view.tool = ui.tool.ink
            view.eraseMode = ui.eraseMode
            view.predictionEnabled = ui.predictionEnabled
            view.inputModel = ui.inputModel
            view.fingerDrawingEnabled = ui.fingerDrawingEnabled
            // ⭐ M8b — the same missing-assignment trap the `tool` line above records. Two chips
            // that highlight and change nothing is exactly what definition-of-done #6 is about.
            view.shapeSnapEnabled = ui.shapeSnapEnabled
            view.arrowHeads = ui.arrowHeads
            // ⭐ Grid snap. The state and its persistence existed for a milestone with nothing
            // reading either — no chip, no assignment here, no lattice in `:android:ink` — which is
            // the shape definition-of-done #6 describes: a preference that round-trips through the
            // settings table perfectly and does nothing to a shape.
            view.gridSnapEnabled = ui.gridSnapEnabled
            view.marqueeSelection = ui.marqueeSelection
        },
    )
}

/**
 * Adapts the ink surface to what the editor needs.
 *
 * The view model must not import `androidx.ink` — CLAUDE.md keeps third-party types out of
 * module boundaries, and `:app`'s own classes taking ink types is the same leak in a different
 * direction. So the editor declares an interface and this satisfies it.
 */
private fun InkSurfaceView.asNoteSurface(scope: CoroutineScope, editor: NoteEditorViewModel): NoteEditor.NoteSurface =
    object : NoteEditor.NoteSurface {
        override fun bindPage(nextObjectId: () -> ObjectId, nextOpId: () -> String, nextClock: () -> LamportClock) =
            this@asNoteSurface.bindPage(nextObjectId, nextOpId, nextClock)

        /**
         * SPEC §6.2 — the ink first, then the paper it was drawn on.
         *
         * Two steps rather than one, and in this order deliberately: folding a page is
         * immediate, rasterising a page of a 200-page scan is not, and a note that waited
         * for its background would feel slower than one that draws the strokes and fills
         * the paper in behind them. A page with no source PDF gets a null and stays white.
         */
        override suspend fun loadPage(page: FoldedPage) {
            // ⚠️ Awaited, not launched: the ink is what the caller is waiting for. The scan behind
            // it is deliberately not — rasterising a page of a 200-page PDF is its own long job,
            // and a note that waited for its paper before showing its writing would be slower than
            // the load this method exists to stop blocking.
            this@asNoteSurface.loadPage(page)
            scope.launch { sourcePage = editor.sourcePage(page.background, page.size) }
        }

        override fun drainOps(): List<List<PageOp>> = this@asNoteSurface.drainOps()

        override fun applyOps(ops: List<PageOp>, record: Boolean) = this@asNoteSurface.applyOps(ops, record)

        // SPEC §8.4 — the real thing, not a stub. See the interface's KDoc for why that
        // sentence is worth writing down.
        override fun highlight(box: PageRect?) = this@asNoteSurface.highlight(box)

        /**
         * A paper change that must not disturb the pen — a peer's, or the undo of a local one.
         *
         * ⭐ **This was `= Unit` for the whole of M6**, with a KDoc explaining that `:android:ink`
         * had no method that could change the paper without refitting the camera and cancelling the
         * in-progress stroke. True at the time; the missing method is now
         * `InkSurfaceView.setPageMeta`. Worth recording what the stub cost, because it is
         * CLAUDE.md's definition-of-done #6 exactly: `NoteEditor.reconcile` computed the change,
         * `FakeSurface.setPageMeta` *recorded* it, a test asserted the recording, and production
         * threw it away. Green tick, inert feature — and not only for a peer: the undo of a local
         * template change takes the identical path, so *"pick a paper, press undo"* did nothing on
         * screen while the PageBar chip's label moved. One device, by hand, no sync involved.
         *
         * The raster is re-rendered after the fact, not before, so the ink and the new template are
         * on screen immediately and an imported scan fills in behind — the same ordering [loadPage]
         * uses, and the reason `setPageMeta` clears the old bitmap itself rather than waiting.
         */
        override fun setPageMeta(canvas: PageCanvas, background: Background) {
            this@asNoteSurface.setPageMeta(canvas, background)
            scope.launch { sourcePage = editor.sourcePage(background, canvas.size) }
        }
    }

/**
 * Builds the page the SPEC §3.6 frame-rate gate needs.
 *
 * Off the main thread, deliberately: 5,000 strokes is 5,000 native tessellations, and
 * CLAUDE.md rule 7 has no exception for work that happens to run while the user is not
 * writing.
 */
private fun loadSyntheticPage(scope: CoroutineScope, surface: InkSurfaceView?, ui: InkUiState, count: Int) {
    val view = surface ?: return
    if (ui.busyMessage != null) return
    ui.busyMessage = "Generating $count strokes…"
    scope.launch {
        val generated = withContext(Dispatchers.Default) {
            SyntheticInk.generate(count, view.pageSize, view.brushFactory)
        }
        view.addPregeneratedStrokes(generated)
        view.resetCamera(FitMode.FIT_WIDTH)
        ui.busyMessage = null
    }
}

private const val LATENCY_POLL_MS = 500L

/**
 * How far the diagnostics panel sits above the zoom widget that summons it.
 *
 * ⚠️ Its sibling `SIDE_PANEL_TOP` is gone with the colour panel's corner. That panel is placed under
 * the `+` that opened it now (`ColourPopover`), so it has no fixed inset at all — which is the point:
 * a panel parked in a corner has to be *found*, and one under the control you just pressed does not.
 */
private const val SIDE_PANEL_BOTTOM = 76

/** The dragged-colour blob. Bigger than a swatch so a fingertip does not hide it entirely. */
private const val DRAG_PREVIEW = 34

/** [DRAG_PREVIEW] in pixels at density 2, which is only used to lift the blob clear of the finger. */
private const val DRAG_PREVIEW_PX = 68f

/** The spinner on [PageLoadingNote] — chip-sized, so the pill keeps the height every other bar has. */
private val LoadingSpinner = 14.dp

private val LoadingSpinnerStroke = 2.dp

/** The gutter between the history, page and zoom zones. See `BottomWidgets`. */
private const val ZONE_GAP = 28
