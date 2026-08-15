package dev.inkstone.app.ink

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import dev.inkstone.android.ink.InkSurfaceView
import dev.inkstone.android.ink.SyntheticInk
import dev.inkstone.app.note.CameraCapture
import dev.inkstone.app.note.NoteEditor
import dev.inkstone.app.note.NoteEditorState
import dev.inkstone.app.note.NoteEditorViewModel
import dev.inkstone.app.note.PageBar
import dev.inkstone.app.note.PageBarActions
import dev.inkstone.app.note.PageOverviewSheet
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.FitMode
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val ui = remember { InkUiState() }
    val objects = remember { ObjectToolState() }
    val scope = rememberCoroutineScope()
    val note by editor.state.collectAsState()
    val recording by editor.recording.collectAsState()
    var surface by remember { mutableStateOf<InkSurfaceView?>(null) }
    var pageOverviewOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val objectActions = rememberObjectActions(ui, objects, editor, surface)

    SurfaceEffects(ui = ui, note = note, surface = surface)
    ObjectEffects(ui, objects, note, surface, editor, objectActions)

    DisposableEffect(surface) {
        surface?.let { editor.onSurfaceAttached(it.asNoteSurface(scope, editor)) }
        onDispose { editor.onSurfaceDetached() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        InkSurface(ui = ui, objects = objects, editor = editor, onCreated = { surface = it })

        // ⭐ M8c — between the ink surface and the toolbar, deliberately. Above the surface so a
        // tap with the Text tool reaches it; below the toolbar so the format bar is still pressable
        // while a field has focus.
        ObjectLayers(ui = ui, objects = objects, editor = editor, actions = objectActions)

        InkToolbar(
            state = ui,
            objects = objects,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            actions = inkToolbarActions(surface, editor, ui, scope),
            objectActions = objectActions,
        )

        // ⭐ M12 §10 — top-right, away from the pen tools, because it is not one. Unmistakable
        // while running: a red pill counting up, not a tinted icon.
        RecordButton(
            state = recording,
            onToggle = { editor.toggleRecording() },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )

        // ⚠️ One `exporting` flag for **both** exports, not one each. They copy into the same share
        // cache directory and each sweeps it before writing, so two running at once would have the
        // second delete the first's files out from under a chooser the user already has open.
        val exports = Exports(exporting, { exporting = it }, scope, context, editor)
        PageBar(
            state = note,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            actions = pageBarActions(editor, note, { pageOverviewOpen = true }, exports),
            exporting = exporting,
        )
    }

    if (pageOverviewOpen) {
        PageOverviewSheet(
            note = note,
            thumbnail = { pageId -> editor.pageThumbnail(pageId) },
            onGoToPage = { index -> editor.goToPage(index) },
            onMovePage = { _, to -> editor.movePage(to) },
            onDismiss = { pageOverviewOpen = false },
        )
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
                    objects.elementsOpen = true
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
    val page = view.pageSize
    val report = editor.insertImage(uri, page, visiblePage(camera, view.width, view.height, page))
    editor.report(
        report?.let {
            "Picture added — ${it.sourceBytes / KB} kB in, ${it.storedBytes / KB} kB stored " +
                "at ${it.widthPx}×${it.heightPx}"
        } ?: IMAGE_FAILED,
    )
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
)

/**
 * What the two share buttons need, as one value.
 *
 * A parameter object because the pair is inseparable: they share the `busy` flag and the cache
 * directory it protects, and passing them individually is how one of them ends up with its own
 * flag and deletes the other's files mid-chooser.
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
                onSelectionChanged = { count ->
                    ui.selectionSize = count
                    // Asked of the surface here rather than pushed through the callback, because
                    // the counts are a *property of the selection* and this is the one moment it
                    // is known to have changed — a second callback would be a second thing to
                    // forget to fire.
                    val counts = selection.shapeCounts
                    ui.selectedShapes = counts.shapes
                    ui.selectedConnectors = counts.connectors
                    // ⭐ The image bar follows the selection, by the same argument. This is what
                    // let the Image tool give up owning touch — and giving that up is what let an
                    // image be dragged at all (see `EditorTool.IMAGE`).
                    val picture = selection.soleImage
                    objects.focusImage(
                        picture?.let { ImageFocus(it.id, it.bounds, it.widthPt, it.heightPt, it.transform) },
                        picture?.format ?: objects.format,
                    )
                }
                // ⭐ M8b — the acknowledgement. Without it the first snap is indistinguishable
                // from the app having eaten the stroke and drawn something else.
                // "attached" rather than the kind, when it bound: a connector and a line are
                // pixel-identical when drawn and behave differently the moment a box moves, so
                // the acknowledgement has to say which one the user got.
                onShapeSnapped = { kind, attached ->
                    // A connector's geometry is derived from what it is attached to, so it is the
                    // one snapped shape with no point handles to offer (see
                    // `SelectionController.soleShape`) — which is why only the other branch
                    // mentions them.
                    ui.lastSnapped = if (attached) {
                        "${kind.name.lowercase()} · attached"
                    } else {
                        "${kind.name.lowercase()} · drag its points"
                    }
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
        override fun loadPage(page: FoldedPage) {
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
        override fun setPageMeta(size: PageSize, background: Background) {
            this@asNoteSurface.setPageMeta(size, background)
            scope.launch { sourcePage = editor.sourcePage(background, size) }
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
