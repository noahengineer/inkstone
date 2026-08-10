package dev.inkstone.app.ink

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
    val scope = rememberCoroutineScope()
    val note by editor.state.collectAsState()
    var surface by remember { mutableStateOf<InkSurfaceView?>(null) }
    var pageOverviewOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val context = LocalContext.current

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

    DisposableEffect(surface) {
        surface?.let { editor.onSurfaceAttached(it.asNoteSurface(scope, editor)) }
        onDispose { editor.onSurfaceDetached() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        InkSurface(ui = ui, editor = editor, onCreated = { surface = it })

        InkToolbar(
            state = ui,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            actions = inkToolbarActions(surface, editor, ui, scope),
        )

        PageBar(
            state = note,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            actions = pageBarActions(editor, note, { pageOverviewOpen = true }) {
                if (!exporting) {
                    exporting = true
                    scope.launch {
                        exportAndShare(editor, context)
                        exporting = false
                    }
                }
            },
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
)

private fun pageBarActions(
    editor: NoteEditorViewModel,
    note: NoteEditorState,
    onShowPages: () -> Unit,
    onShare: () -> Unit,
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
    onShare = onShare,
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
 * The ink surface itself — SPEC §3.2's `AndroidView` seam.
 *
 * Split out of [InkScreen] because the two halves have nothing to say to each other: this one
 * is view interop and callback wiring, the other is layout.
 */
@Composable
private fun InkSurface(ui: InkUiState, editor: NoteEditorViewModel, onCreated: (InkSurfaceView) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            InkSurfaceView(context).apply {
                onCameraChanged = { ui.zoomPercent = zoomPercent() }
                onStrokeCountChanged = { ui.strokeCount = it }
                onHistoryChanged = { ui.strokeCount = strokeCount }
                onUndoRequested = { editor.undo() }
                onRedoRequested = { editor.redo() }
                onEraserAutoReleased = { ui.eraserSelected = false }
                onCreated(this)
            }
        },
        update = { view ->
            view.pen = ui.pen
            view.eraserSelected = ui.eraserSelected
            view.eraseMode = ui.eraseMode
            view.predictionEnabled = ui.predictionEnabled
            view.inputModel = ui.inputModel
            view.fingerDrawingEnabled = ui.fingerDrawingEnabled
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
