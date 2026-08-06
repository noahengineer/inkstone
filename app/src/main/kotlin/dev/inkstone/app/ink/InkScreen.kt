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
import dev.inkstone.core.model.FitMode
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
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
        surface?.let { editor.onSurfaceAttached(it.asNoteSurface()) }
        onDispose { editor.onSurfaceDetached() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        InkSurface(ui = ui, editor = editor, onCreated = { surface = it })

        InkToolbar(
            state = ui,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            actions = InkToolbarActions(
                onFit = { mode -> surface?.resetCamera(mode) },
                onUndo = { editor.undo() },
                onRedo = { editor.redo() },
                onClear = { surface?.clearPage() },
                onLoadSyntheticPage = { count -> loadSyntheticPage(scope, surface, ui, count) },
                onResetLatency = {
                    surface?.latency?.reset()
                    surface?.stylus?.reset()
                },
            ),
        )

        PageBar(
            state = note,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            actions = pageBarActions(editor, note) { pageOverviewOpen = true },
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

private fun pageBarActions(editor: NoteEditorViewModel, note: NoteEditorState, onShowPages: () -> Unit) =
    PageBarActions(
        onPrevious = { editor.goToPage(note.pageIndex - 1) },
        onNext = { editor.goToPage(note.pageIndex + 1) },
        onAddPage = { editor.addPage(note.template) },
        onDuplicatePage = { editor.duplicateCurrentPage() },
        onDeletePage = { editor.deleteCurrentPage() },
        onMoveLeft = { editor.movePage(note.pageIndex - 1) },
        onMoveRight = { editor.movePage(note.pageIndex + 1) },
        onTemplate = { template -> editor.setTemplate(template) },
        onShowPages = onShowPages,
    )

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
private fun InkSurfaceView.asNoteSurface(): NoteEditor.NoteSurface = object : NoteEditor.NoteSurface {
    override fun bindPage(nextObjectId: () -> ObjectId, nextOpId: () -> String, nextClock: () -> LamportClock) =
        this@asNoteSurface.bindPage(nextObjectId, nextOpId, nextClock)

    override fun loadPage(page: FoldedPage) = this@asNoteSurface.loadPage(page)

    override fun drainOps(): List<List<PageOp>> = this@asNoteSurface.drainOps()

    override fun applyOps(ops: List<PageOp>, record: Boolean) = this@asNoteSurface.applyOps(ops, record)
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
