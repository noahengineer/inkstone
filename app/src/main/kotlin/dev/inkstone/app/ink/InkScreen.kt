package dev.inkstone.app.ink

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.layout.layoutId
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
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.inkstone.android.ink.InkSelection
import dev.inkstone.android.ink.InkSurfaceView
import dev.inkstone.android.ink.SyntheticInk
import dev.inkstone.app.calculator.AnswerTarget
import dev.inkstone.app.calculator.CalculatorChip
import dev.inkstone.app.calculator.CalculatorLayer
import dev.inkstone.app.calculator.rememberAnswerTarget
import dev.inkstone.app.note.CameraCapture
import dev.inkstone.app.note.NoteEditor
import dev.inkstone.app.note.NoteEditorState
import dev.inkstone.app.note.NoteEditorViewModel
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
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.SnapGrid
import dev.inkstone.core.storage.Element
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
    var surface by remember { mutableStateOf<InkSurfaceView?>(null) }
    var pageOverviewOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    // ⭐ M10 — the two summoned panels, and this Box's own place in the window. All three are
    // UI-local: none survives leaving the note and none is a property of the document. See
    // [ColourPopover] on why the colour one is an anchor rather than a flag.
    var panel by remember { mutableStateOf<SummonedPanel?>(null) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val geometry = remember { ChromeGeometry() } // ⭐ Also read beside the rails: docs/UI-RAILS.md §3.
    val objectActions = rememberObjectActions(ui, objects, editor, surface)

    SurfaceEffects(ui = ui, note = note, surface = surface)
    ObjectEffects(ui, objects, note, surface, editor, objectActions)
    val elementDone = ElementEditorEffects(editor, objects, objectActions, surface, scope)
    SurfaceAttachment(surface, editor, scope)

    val actions = inkToolbarActions(surface, editor, ui, objects, scope)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Ink.Canvas)
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        EditorCanvas(ui, objects, editor, objectActions) { surface = it }
        DockedCalculator(geometry, rememberAnswerTarget(ui, objects, editor, surface)) // ⭐ docs/CALCULATOR.md D2, §6.

        val exports = Exports(exporting, { exporting = it }, scope, LocalContext.current, editor)
        Chrome(
            ui = ui,
            objects = objects,
            actions = actions,
            objectActions = objectActions,
            editor = editor,
            element = elementDone,
            wiring = ChromeWiring(
                onSummon = { kind, at -> panel = panel.toggled(kind, at) },
                note = note,
                pageActions = pageBarActions(editor, note, { pageOverviewOpen = true }, exports),
                exporting = exporting,
                diagnosticsOpen = diagnosticsOpen,
                onToggleDiagnostics = { diagnosticsOpen = !diagnosticsOpen },
                geometry = geometry,
            ),
        )

        val panels = OpenPanels(
            panel?.let { it.copy(anchor = it.anchor.translate(-origin.x, -origin.y)) },
            { panel = null },
            diagnosticsOpen,
            note.localRev,
        ) { diagnosticsOpen = false }
        SidePanels(ui, objects, editor, actions, objectActions, panels, geometry)
        if (ui.editingLayout) LayoutEditorPanel(ui)
        ItemDragGhost(ui.itemDrag, origin)
        ui.colorDrag.colorArgb?.let { DragPreview(it, ui.colorDrag.position - origin) }
    }

    if (pageOverviewOpen) PageOverview(note, editor) { pageOverviewOpen = false }
}

/**
 * Hands the editor the surface while there is one, and takes it back when it goes. Its own composable
 * only because [InkScreen] is at detekt's `LongMethod` limit.
 */
@Composable
private fun SurfaceAttachment(surface: InkSurfaceView?, editor: NoteEditorViewModel, scope: CoroutineScope) {
    DisposableEffect(surface) {
        surface?.let { editor.onSurfaceAttached(it.asNoteSurface(scope, editor)) }
        onDispose { editor.onSurfaceDetached() }
    }
}

/**
 * The calculator, in the right-hand column with the other panels until it is dragged out of it
 * (`PanelDock`, docs/UI-RAILS.md §3), and kept below the top row of chrome rather than the two rows
 * the library has (`CalculatorWindow`'s `safeTop`). Above every page layer and below the chrome (D2);
 * its answer can be dragged onto the page ([answers], docs/CALCULATOR.md §6).
 */
@Composable
private fun BoxScope.DockedCalculator(geometry: ChromeGeometry, answers: AnswerTarget) {
    CalculatorLayer(dock = geometry.dock, safeTop = EditorWindowSafeTop, target = answers)
}

/** The Element editor's label, read from the view model. See [ElementEditorLabel]. */
@Composable
private fun ElementEditorLabel(editor: NoteEditorViewModel, modifier: Modifier) {
    val name by editor.elementName.collectAsState()
    ElementEditorLabel(name, modifier)
}

/** [UpdateCopiesDialog], while Done is waiting on the answer. */
@Composable
private fun UpdateCopiesPrompt(done: ElementDone) {
    val prompt = done.prompt ?: return
    UpdateCopiesDialog(
        name = prompt.revision.element.name,
        copies = prompt.copies,
        notes = prompt.notes.size,
        onUpdate = { done.update(prompt) },
        onKeep = { done.finish() },
    )
}

/**
 * ⭐ SPEC §4.9.7 — the Element editor's half of the screen: it is this screen on a scratch note, with
 * a grid for paper. Pushes the backdrop to the surface, frames the camera on the Element, shows the
 * update-copies question, and returns the Done/Cancel controller whose bar replaces the page bar (an
 * Element has one page and is not shared on its own). Nothing here does anything for a note.
 */
@Composable
private fun ElementEditorEffects(
    editor: NoteEditorViewModel,
    objects: ObjectToolState,
    actions: ObjectActions,
    surface: InkSurfaceView?,
    scope: CoroutineScope,
): ElementDone {
    val editing = editor.editingElement != null
    LaunchedEffect(surface, objects.backdrop, editing) {
        // The lattice first, so the backdrop's first frame is drawn at the right spacing.
        if (editing) surface?.elementGridPt = editor.elementGridPt
        surface?.elementBackdrop = if (editing) objects.backdrop else null
    }
    // Framed on the Element rather than fitted to the page's width — the page itself is 3000 pt wide.
    val elementFrame by editor.elementFrame.collectAsState()
    LaunchedEffect(surface, elementFrame) {
        surface?.preferredFrame = elementFrame
    }
    val done = rememberElementDone(editor, objects, surface, scope, actions.elementEditor)
    UpdateCopiesPrompt(done)
    return done
}

/**
 * ⭐ The Element editor's way out (SPEC §4.9.7): save, ask about copies if there are any, then leave.
 *
 * The Activity is finished with `RESULT_OK` after a save, so the screen that opened the editor knows
 * to re-read its panel — the Element's picture has changed under the same id.
 */
private class ElementDone(
    /** The editor's bottom bar, or null for a note — which then shows its page bar. */
    val bar: (@Composable () -> Unit)?,
    /** The editor's top-left label, or null for a note. */
    val label: (@Composable (Modifier) -> Unit)?,
    val done: () -> Unit,
    val cancel: () -> Unit,
    val prompt: NoteEditorViewModel.ElementEditOutcome.Saved?,
    val update: (NoteEditorViewModel.ElementEditOutcome.Saved) -> Unit,
    val finish: () -> Unit,
)

@Composable
private fun rememberElementDone(
    editor: NoteEditorViewModel,
    objects: ObjectToolState,
    surface: InkSurfaceView?,
    scope: CoroutineScope,
    tools: ElementEditorActions?,
): ElementDone {
    val activity = LocalContext.current as? android.app.Activity
    var prompt by remember { mutableStateOf<NoteEditorViewModel.ElementEditOutcome.Saved?>(null) }
    val finish: () -> Unit = {
        prompt = null
        activity?.setResult(android.app.Activity.RESULT_OK)
        activity?.finish()
    }
    val done: () -> Unit = {
        scope.launch {
            when (val outcome = editor.saveElementEdit { ids -> surface?.boundsOf(ids) }) {
                is NoteEditorViewModel.ElementEditOutcome.Saved ->
                    if (outcome.copies > 0) prompt = outcome else finish()

                NoteEditorViewModel.ElementEditOutcome.Empty -> editor.report(EMPTY_ELEMENT)

                NoteEditorViewModel.ElementEditOutcome.Gone -> activity?.finish()
            }
        }
    }
    val cancel: () -> Unit = { activity?.finish() }
    val bar: (@Composable () -> Unit)? = if (editor.editingElement == null) {
        null
    } else {
        {
            ElementEditorBar(
                objects,
                onFit = { tools?.let { scope.launch { it.fit() } } },
                onCancel = cancel,
                onDone = done,
            )
        }
    }
    return ElementDone(
        bar = bar,
        label = if (editor.editingElement == null) null else { modifier -> ElementEditorLabel(editor, modifier) },
        done = done,
        cancel = cancel,
        prompt = prompt,
        update = { saved ->
            prompt = null
            scope.launch {
                val updated = editor.updateElementCopies(saved.revision, saved.notes)
                editor.report(if (updated == 1) "Updated 1 copy" else "Updated $updated copies")
                finish()
            }
        },
        finish = finish,
    )
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
    val stored = editor.editorPrefs.collectAsState().value ?: return null
    // ⛔ The *newest* value, not [stored]: that one is the store as it was at launch, and seeding a
    // second note from it wrote the launch-time palette back over the user's (`EditorPreferences`).
    val ui = remember { InkUiState(editor.currentEditorPrefs() ?: stored) }
    // Another editor may have changed the toolbar while this one was behind it — see [InkUiState.adopt].
    LifecycleStartEffect(ui) {
        editor.currentEditorPrefs()?.let(ui::adopt)
        onStopOrDispose { }
    }
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
 * ⭐ ROADMAP M10 — all of the chrome: the contextual toolbars, the Dynamic Dock, the two top corners,
 * and the design's three bottom zones — placed by [ChromeHost] and arranged by the user
 * ([ChromeLayout]; the layout editor is [LayoutEditorPanel]).
 *
 * > *"Separated from the main tools to prevent accidental taps during drawing."*
 *
 * ⚠️ **`ContextBars` is composed before the host, and that is two things at once.** Composition
 * order is draw order in a `Box`, so the dock draws over a bar that would otherwise slide under it;
 * and the bars' return value is what tells the dock to collapse its secondary row — the design's
 * *"summons its respective Contextual Toolbar and hides the Dynamic Dock's secondary row"*. Swapping
 * the two breaks the second silently, because a `Boolean` read one recomposition late is a row that
 * flickers rather than a compile error. In the layout editor they are not composed at all: a
 * selection's bar would be the one live control on a screen whose every other control is covered.
 *
 * ## What the stock layout keeps, and why — moved here from `BottomWidgets` and `TopChrome`
 *
 * - **The page bar is measured into the space between history and zoom, and centred there.** Aligned
 *   on its own it ran *underneath* the zoom widget on a 2560 px tablet and the share chips were
 *   unreachable — drawn, listed by `uiautomator`, and covered by a pill that swallowed the tap. It sits
 *   ~45 dp off true centre as a result, which is invisible. ([ChromeHost] keeps the arithmetic.)
 * - **It stays one row in portrait, where it scrolls and fades at an end with more beyond.** A second
 *   row of bottom chrome was built twice and rejected on sight — *"its not like how it was at all"* —
 *   because it pushes history and zoom up onto the paper, where the page's edge cuts them in half.
 * - **The dock and the record chip abut in portrait and do not overlap.** Two fixes were built and
 *   reverted: siblings in a `Row` moved the dock 63 dp in landscape, and reserving the chip's width
 *   pushed `RULER` off the end of the bar. The requirement was that landscape stay exactly as shipped.
 *
 * Each of those was a trade made *for* the user. All three can now be made *by* them instead: the page
 * bar can go somewhere it fits, and a dock that is crowded in portrait can be made smaller.
 */
@Composable
private fun BoxScope.Chrome(
    ui: InkUiState,
    objects: ObjectToolState,
    actions: InkToolbarActions,
    objectActions: ObjectActions,
    editor: NoteEditorViewModel,
    element: ElementDone,
    wiring: ChromeWiring,
) {
    val geometry = wiring.geometry
    val deps = ChromeDeps(ui, objects, actions, objectActions, wiring, geometry, elementEditor = element.bar != null)
    val contextual = if (ui.editingLayout) {
        false
    } else {
        ContextBars(ui, objects, actions, objectActions, wiring.onSummon, geometry)
    }
    if (ui.editingLayout) LayoutEditorScrim()
    // ⚠️ The Element editor's Done bar stands in the page bar's place and is a row whatever the page
    // bar's own shape is, so it is measured into the bottom edge between history and zoom.
    val pagesInRow = element.bar != null || ui.layout.arrangement(BarId.Stock(ChromeBar.PAGES)) == ChromeArrangement.ROW
    ChromeHost(ui, geometry, pagesInRow = pagesInRow) {
        ChromeBarFrame(DockBarId, ui, geometry) { ChromeContainer(DockBarId, deps) }
        ChromeBarFrame(BarId.Stock(ChromeBar.DRAW), ui, geometry) { DockSecondary(deps, suppressed = contextual) }
        ChromeBarFrame(BarId.Stock(ChromeBar.HISTORY), ui, geometry) {
            ChromeContainer(BarId.Stock(ChromeBar.HISTORY), deps)
        }
        ChromeBarFrame(BarId.Stock(ChromeBar.PAGES), ui, geometry) {
            val centre = element.bar
            if (centre != null) centre() else ChromeContainer(BarId.Stock(ChromeBar.PAGES), deps)
        }
        ChromeBarFrame(BarId.Stock(ChromeBar.ZOOM), ui, geometry) { ChromeContainer(BarId.Stock(ChromeBar.ZOOM), deps) }
        // ⭐ The bars the user made by dropping a chip on the page — each exactly as long as it holds one.
        ui.layout.floating.forEach { id ->
            key(id.n) { ChromeBarFrame(id, ui, geometry) { ChromeContainer(id, deps) } }
        }
        // ⭐ The Element editor's corner says what is being edited. Recording a lecture into an
        // Element, and a calculator over one, are both meaningless there, so the note's corners are
        // not shown.
        val label = element.label
        if (label != null) {
            label(Modifier.layoutId(LabelSlot))
        } else {
            // ⭐ M12 §10 — the design's *"Global Utilities (Top Right)"* zone, away from the pen tools
            // because it is not one; and the calculator in the one corner the design leaves empty.
            val recording by editor.recording.collectAsState()
            ChromeBarFrame(BarId.Stock(ChromeBar.RECORD), ui, geometry) {
                RecordButton(recording, { editor.toggleRecording() })
            }
            ChromeBarFrame(BarId.Stock(ChromeBar.CALCULATOR), ui, geometry) { CalculatorChip() }
        }
    }
}

private val DockBarId = BarId.Stock(ChromeBar.DOCK)

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

/**
 * ⭐ Every panel that opens beside the page — the Elements library, the colour and size panels, the
 * diagnostics — in the right-hand column (`PanelDock`, docs/UI-RAILS.md §3): one under the other, a new
 * column to the left when one is full, and never on top of each other or of a bar. The calculator joins
 * the same column from its own layer (`DockedCalculator`).
 *
 * > *"there is just so much overlapping still and that is what i wanted to avoid"*
 */
@Composable
private fun BoxScope.SidePanels(
    ui: InkUiState,
    objects: ObjectToolState,
    editor: NoteEditorViewModel,
    actions: InkToolbarActions,
    objectActions: ObjectActions,
    open: OpenPanels,
    geometry: ChromeGeometry,
) {
    // ⭐ The column follows the layout: which side, and in what order (docs/LAYOUTS.md).
    // ⚠️ Read here, in composition, and only handed over in the effect: read inside the effect, nothing
    // recomposed when the layout changed, the effect never ran again, and choosing *Left* in the editor
    // moved nothing (found on the emulator).
    val side = ui.layout.panelSide
    val order = ui.layout.orderedPanels
    SideEffect {
        geometry.dock.side = side
        geometry.dock.order = order
    }
    // ⭐ After the contextual bars, so a selection's bar never covers the library's rows. See
    // [ElementPanelLayer].
    ElementPanelLayer(ui, objects, editor, objectActions, geometry = geometry)
    dev.inkstone.app.handwriting.WritePanelLayer(ui, objects, editor, objectActions, geometry) // ⭐ §6.2.
    // ⭐ Each panel is a `PanelWindow`: in the column until dragged out, at its own size
    // (`ChromeLayout.panelScale`) inside its slot, so the column is laid out in the screen's px.
    if (open.diagnostics) {
        PanelWindow(DockSlot.DIAGNOSTICS, ui, geometry) {
            DiagnosticsPanel(
                state = ui,
                actions = actions,
                revision = open.revision,
                onClose = open.onCloseDiagnostics,
            )
        }
    }
    when (open.panel?.kind) {
        PanelKind.COLOUR -> DockedColourPanel(geometry, ui, objects, actions, open.onClosePanel, objectActions)
        PanelKind.SIZE -> DockedSizePanel(geometry, ui, open.onClosePanel)
        null -> Unit
    }
    SnapGuides(geometry)
}

/**
 * ⭐ ROADMAP M10 — the extended colour panel, in the right-hand column.
 *
 * ## In the column, not under the `+`
 *
 * It used to open under the `+` that summoned it, behind a full-screen scrim that closed it on the next
 * press anywhere. Under a `+` it landed wherever there was room, which was over the dock, over the
 * selection that opened it, or over another panel — each placement reasonable alone, and together the
 * pile-up the user asked to be rid of (docs/UI-RAILS.md §3). In the column it has a place of its own.
 *
 * It **stays open while you write**, which a scrim could not allow: a colour can be adjusted between
 * strokes without opening it again. The `+` that opened it closes it (`SummonedPanel.toggled`), and
 * so does its `✕`.
 *
 * ## Which colour it edits
 *
 * The same `when` `ContextBars` uses, and it has to be: a text box being typed into takes the text
 * colour, a selection takes a recolour of the selection, and otherwise it is the pen. Deriving it here
 * rather than threading three different callbacks down is what stops the two disagreeing about which
 * control the open panel belongs to — and, now that it stays open, what lets it follow a selection
 * made after it was opened.
 */
@Composable
private fun DockedColourPanel(
    geometry: ChromeGeometry,
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
    PanelWindow(DockSlot.COLOUR, ui, geometry) {
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
 * ⭐ ROADMAP M10 — the saved-sizes panel, in the right-hand column with the colour panel (see
 * [DockedColourPanel] on why there and not under the number that opened it).
 */
@Composable
private fun DockedSizePanel(geometry: ChromeGeometry, ui: InkUiState, onDismiss: () -> Unit) {
    // ⚠️ Every route that takes this panel off screen — `✕`, pressing the readout again, opening a
    // different panel — ends the edit here, so the ladder is never left mid-edit (and so possibly
    // unsorted, see `InkUiState.editingWidthSlot`) behind a closed panel.
    DisposableEffect(ui) { onDispose { ui.finishWidthEdit() } }
    PanelWindow(DockSlot.SIZE, ui, geometry) {
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
 * ⭐ M8c — the effects that keep the object layers in step with the note.
 *
 * Beside [SurfaceEffects] and for the same reason, and each is a bug that would otherwise be
 * silent:
 *
 * 1. **The asset cache has to be handed over, and allowed to say when it has new pictures**, or
 *    every image on the page draws a placeholder. A cache that is correct and never reaches the
 *    surface is M6's inert-feature shape exactly (`PageAssets`).
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
    // ⭐ The cache decodes what the painter asks for and tells the surface when a batch lands, so a
    // picture inserted, undone or merged in appears without this effect having to notice it. The
    // page-load hook only gets a page's pictures decoding *while* its strokes are built. Both are
    // dropped when the surface goes, so a cache outliving this screen holds no view.
    DisposableEffect(surface) {
        val view = surface
        if (view != null) {
            editor.assetCache.onChanged = { view.setPageAssets(editor.assetCache) }
            view.onPagePictures = { pictures -> editor.assetCache.beginPage(pictures) }
            view.setPageAssets(editor.assetCache)
        }
        onDispose {
            if (view != null) {
                editor.assetCache.onChanged = null
                view.onPagePictures = null
            }
        }
    }
    // Keyed on the revision: an image merged from a peer arrives as an object one poll before its
    // bytes, and a revision bump is when they can have turned up (SPEC §13.2 row 10 — the peer's
    // edit has to be visible *now*). Free unless a picture is actually missing.
    LaunchedEffect(note.localRev) { editor.assetCache.retryMissing() }

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
    // ⭐ The Element editor is a second NoteActivity; when it comes back after a save, the panel's
    // picture of that Element is stale under the same id, so the library is re-read.
    val editElement = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) objects.libraryChanged()
    }
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
        onSaveElement = { name, collection -> saveCapture(scope, editor, objects, name, collection) },
        captureArea = { box -> captureArea(surface, editor, box) },
        onEditElement = { element ->
            editElement.launch(
                android.content.Intent(context, dev.inkstone.app.note.NoteActivity::class.java)
                    .putExtra(dev.inkstone.app.note.NoteActivity.EXTRA_ELEMENT_ID, element.id.value)
                    // The note's own lattice, so the Element is drawn on the grid its copies snap to.
                    .putExtra(dev.inkstone.app.note.NoteActivity.EXTRA_GRID_PT, surface?.paperGrid.editorSpacing()),
            )
        },
        onPlaceElement = { element, centre, selectAfter ->
            scope.launch { placeElement(surface, editor, ui, element, centre, selectAfter) }
        },
        // ⭐ docs/HANDWRITING-SYNTHESIS.md §6.2 — a written phrase is selected, as a dropped Element is.
        onSelectPlaced = { ids -> placedSelect(surface, ui, ids) },
        elementEditor = editor.editingElement?.let { ElementEditorActions({ surface }, editor, ui) },
    )
}

/** Save [ObjectToolState.capture] under a name and a collection, then show it in the panel. */
private fun saveCapture(
    scope: CoroutineScope,
    editor: NoteEditorViewModel,
    objects: ObjectToolState,
    name: String,
    collection: String,
) {
    val capture = objects.capture ?: return
    objects.capture = null
    scope.launch {
        val saved = editor.saveElement(name, collection, capture.ids, capture.bounds)
        editor.report(if (saved == null) NOTHING_TO_SAVE else "Saved “${saved.name}” to Elements")
        // ⚠️ Both, and in this order. `elementsOpen = true` shows the panel, but it is a no-op when
        // the panel is **already** open — which is the case whenever somebody is looking at the
        // library while filing into it — so on its own it reloads nothing and the new element never
        // appears. `libraryChanged` is what says the index on disk has moved; see
        // `ObjectToolState.libraryRevision`.
        objects.elementsOpen = true
        if (saved != null) objects.libraryChanged()
    }
}

/**
 * The square lattice the Element editor should draw for a note with this grid: the note's own
 * spacing on whichever axis it has one. Ruled paper has rules every 24 pt and a free x axis, and an
 * Element drawn on 24 pt squares lands its pivot on a rule; 18 pt squares would not.
 */
private fun SnapGrid?.editorSpacing(): Float {
    val grid = this ?: return SnapGrid.DEFAULT_SPACING_PT
    return maxOf(grid.spacingXPt, grid.spacingYPt).takeIf { it >= SnapGrid.MIN_SPACING_PT }
        ?: SnapGrid.DEFAULT_SPACING_PT
}

/** A box dragged with the Elements tool, as what the surface finds inside it — handwriting included. */
private fun captureArea(surface: InkSurfaceView?, editor: NoteEditorViewModel, box: PageRect): ElementCapture? {
    val ids = surface?.objectsWithin(box).orEmpty()
    if (ids.isEmpty()) {
        editor.report(NOTHING_TO_SAVE)
        return null
    }
    // ⭐ With the grid on, an Element is saved with its bounds widened onto the paper's own lattice,
    // so its corner — the point a copy snaps by — sits on a line exactly as the ink did here. The
    // content's own box is a corner nobody chose (*"when creating an element it's not very correct"*).
    val measured = surface?.boundsOf(ids)
    return ElementCapture(ids, measured?.let { surface.snapGrid.enclosing(it) })
}

/** Stamp [element] centred on [centre]; after a drop, select it and hand over the Select tool. */
@Suppress("LongParameterList") // Where it lands, and the three things that change after it does.
private suspend fun placeElement(
    surface: InkSurfaceView?,
    editor: NoteEditorViewModel,
    ui: InkUiState,
    element: Element,
    centre: PagePoint,
    selectAfter: Boolean,
) {
    val dropped = PagePoint(centre.x - element.widthPt / 2f, centre.y - element.heightPt / 2f)
    // ⭐ With the grid on, the drop lands the Element's pivot — or its bounds' corner — on the lattice,
    // exactly as dragging it there afterwards would (SPEC §4.9.7).
    val pivot = element.pivot ?: PagePoint(0f, 0f)
    val snapped = surface?.snapGrid?.snap(PagePoint(dropped.x + pivot.x, dropped.y + pivot.y))
    val topLeft = snapped?.let { PagePoint(it.x - pivot.x, it.y - pivot.y) } ?: dropped
    val placed = editor.stampElement(element, topLeft)
    if (placed.isEmpty()) {
        editor.report(NOTHING_TO_STAMP)
    } else if (selectAfter) {
        // ⭐ Selected, then handed the Select tool — the snapped-shape order, and for the reason
        // `InkSurfaceView.select` gives. *"Drag it on something"* is the next thing a person does
        // with an Element they just put down, so the handles have to be there already rather than
        // behind a lasso.
        surface?.select(placed)
        ui.tool = EditorTool.SELECT
    }
}

/** Select [ids] first, then the tool — the order `InkSurfaceView.select`'s KDoc asks for. */
private fun placedSelect(surface: InkSurfaceView?, ui: InkUiState, ids: List<ObjectId>) {
    if (ids.isEmpty()) return
    surface?.select(ids)
    ui.tool = EditorTool.SELECT
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
    val knockout = selection.knockoutCounts
    ui.selectedKnockoutEligible = knockout.eligible
    ui.selectedKnockoutOn = knockout.on
    ui.selectionPivot = selection.pivot
    val picture = selection.soleImage
    objects.focusImage(
        picture?.let { ImageFocus(it.id, it.bounds, it.widthPt, it.heightPt, it.transform) },
        picture?.format ?: objects.format,
    )
    val box = selection.soleTextBox
    objects.focusText(box?.let { TextFocus(it.id, it.bounds, it.widthPt, it.heightPt, it.text, it.format, it.style) })
    objects.write.phrase = selection.phrase // ⭐ Write again / Edit text: docs/HANDWRITING-SYNTHESIS.md §6.2.
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
    "There is nothing inside that box to save — draw it right round what you want to keep."
private const val NOTHING_TO_STAMP = "That Element has nothing in it this version can draw."
private const val EMPTY_ELEMENT = "An Element needs something in it — draw something, or press Cancel."

/** The M1 toolbar's wiring — kept beside [pageBarActions] rather than inline, for the same reason. */
private fun inkToolbarActions(
    surface: InkSurfaceView?,
    editor: NoteEditorViewModel,
    ui: InkUiState,
    objects: ObjectToolState,
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
    onKnockoutSelection = { on -> surface?.selection?.setKnockout(on) },
    onSaveSelectionAsElement = {
        surface?.selection?.let { selection ->
            if (selection.size > 0) objects.capture = ElementCapture(selection.ids, selection.bounds)
        }
    },
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
                onStrokeCaptured = { objectId, atElapsed, ruled -> editor.strokeCaptured(objectId, atElapsed, ruled) }
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
 * The calculator's top line in the editor: under the one row of chrome along the top edge. The library
 * has two rows there and keeps `WindowSafeTop`.
 */
private val EditorWindowSafeTop =
    InkDims.EdgePadding + InkDims.ChipMinHeight + InkDims.BarPadding * 2 + InkDims.GroupGap

/** The dragged-colour blob. Bigger than a swatch so a fingertip does not hide it entirely. */
private const val DRAG_PREVIEW = 34

/** [DRAG_PREVIEW] in pixels at density 2, which is only used to lift the blob clear of the finger. */
private const val DRAG_PREVIEW_PX = 68f

/** The spinner on [PageLoadingNote] — chip-sized, so the pill keeps the height every other bar has. */
private val LoadingSpinner = 14.dp

private val LoadingSpinnerStroke = 2.dp
