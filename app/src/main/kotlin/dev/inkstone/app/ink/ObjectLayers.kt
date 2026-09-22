package dev.inkstone.app.ink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.inkstone.app.elements.Element
import dev.inkstone.app.elements.ElementPanel
import dev.inkstone.app.note.CropMath
import dev.inkstone.app.note.NoteEditorViewModel
import dev.inkstone.app.note.pageBounds
import dev.inkstone.app.note.topmostAt
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.TextBoxObject
import dev.inkstone.core.model.formatting
import dev.inkstone.core.model.viewToPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.inkstone.core.model.Offset as ViewOffset

/**
 * ⭐ ROADMAP M8c — every layer that sits over the ink surface, and the routing that decides which
 * of them a gesture belongs to.
 *
 * Extracted from `InkScreen` for the reason `SurfaceEffects` and `pageBarActions` were: that file
 * is about layout, and this is about what a tap *means* — which depends on the tool, on what is
 * under the finger, and on whether an Element is armed. Keeping the two apart is also what makes
 * the routing readable enough to check against CLAUDE.md's definition-of-done #6, which is the
 * whole point of this milestone: every one of the five features has to be reachable by pressing
 * something, and this is where the pressing arrives.
 */
@Composable
fun ObjectLayers(
    ui: InkUiState,
    objects: ObjectToolState,
    editor: NoteEditorViewModel,
    actions: ObjectActions,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var elements by remember { mutableStateOf(emptyList<Element>()) }

    // Loaded when the panel opens rather than held for the note's lifetime: the library is a file
    // on disk that another note may have written, so a list captured at open would go stale the
    // moment an element was saved from anywhere else.
    //
    // ⚠️ Re-read on [ObjectToolState.libraryRevision], which is bumped when a write **finishes** —
    // see its KDoc for the version of this that keyed on `capturedArea` and re-read the index a
    // moment before the save wrote it.
    LaunchedEffect(objects.libraryReloadKey) {
        if (objects.elementsOpen) elements = editor.elements()
    }

    Box(modifier = modifier.fillMaxSize()) {
        PageOverlay(
            ui = ui,
            objects = objects,
            gestures = PageGestures(
                onTap = { point -> scope.launch { onTap(point, ui, objects, editor) } },
                onDragBox = { box -> scope.launch { onDragBox(box, ui, objects) } },
            ),
        )
        // ⭐ Above the page overlay and below the panels: while it is up it owns the touches, which
        // is what stops a crop drag from also panning the page underneath.
        objects.cropping?.let { session ->
            CropOverlay(session = session, camera = ui.camera, onRegion = objects::dragCrop)
        }
        TextBoxLayer(ui, objects, actions)
        if (objects.elementsOpen) {
            ElementPanel(
                elements = elements,
                armed = objects.pendingElement,
                onArm = { objects.pendingElement = it },
                onDelete = { id ->
                    scope.launch {
                        editor.deleteElement(id)
                        // A removed element must not stay armed: the next tap would stamp
                        // something the panel no longer offers.
                        if (objects.pendingElement?.id == id) objects.pendingElement = null
                        objects.libraryChanged()
                    }
                },
                onClose = { objects.elementsOpen = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = PANEL_TOP_DP.dp, end = 12.dp),
            )
        }
    }

    // ⭐ M8c — the captured area's name and collection. A dialog rather than a bar, because it is a
    // question with an answer and the page underneath is exactly what the answer is about.
    //
    // ⚠️ It lives here rather than in the Elements toolbar row, and that move is load-bearing: the
    // redesign collapses row two whenever a contextual toolbar is up, so a dialog hung off that row
    // would be *un-summonable* the moment anything on the page was selected — a capture gesture that
    // does nothing, which is CLAUDE.md's definition-of-done #6 caused by a layout decision.
    if (objects.capturedArea != null) NameElementDialog(objects, actions)
}

/** Name and file the captured area. Two fields, because a library with one collection is a list. */
@Composable
private fun NameElementDialog(state: ObjectToolState, actions: ObjectActions) {
    var name by remember { mutableStateOf("") }
    var collection by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { state.capturedArea = null },
        title = { Text("Save as Element") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(
                    value = collection,
                    onValueChange = { collection = it },
                    label = { Text("Collection") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { actions.onSaveElement(name, collection) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { state.capturedArea = null }) { Text("Cancel") } },
    )
}

/**
 * What a tap does, per tool.
 *
 * ⚠️ The fold is read **now**, not held: `NoteEditor.currentFold` takes the editor's lock, so what
 * comes back includes anything a peer merged a moment ago. A screen-held copy would hit-test
 * against a page that no longer exists, which is how a tap "misses" an image that is plainly there.
 */
private suspend fun onTap(point: PagePoint, ui: InkUiState, objects: ObjectToolState, editor: NoteEditorViewModel) {
    val fold = editor.currentFold() ?: return
    when (ui.tool) {
        EditorTool.TEXT -> tapText(point, fold, objects, editor)
        EditorTool.ELEMENT -> objects.pendingElement?.let { editor.stampElement(it, point) }
        else -> Unit
    }
}

/**
 * Tapping an existing box edits it; tapping empty paper places a new one.
 *
 * ⚠️ Placing does **not** create an object — see [ObjectToolState.place]. The first commit with
 * characters in it does, as one gesture, because a box created empty has no inverse for its first
 * `SetTextContent` and *"type a word, press undo"* would delete the box instead of the word.
 */
private suspend fun tapText(
    point: PagePoint,
    fold: FoldedPage,
    objects: ObjectToolState,
    editor: NoteEditorViewModel,
) {
    // Whatever is being typed reaches the register before the field moves, or the tail of it is in
    // no journal at all — the same reason the field commits on focus loss.
    objects.editing?.id?.let { editor.updateText(it, objects.draft) }
    // ⚠️ Hit-tested against the **frame**, not the payload. A text box is resized by
    // `SetFormat` (`ObjectFormat.boxWidthPt`), so measuring one at its placed extents means a box the
    // user has widened cannot be tapped in the part they widened it into - and tapping there places
    // a second box on top of the first instead.
    val hit = fold.objects.topmostAt(point, { fold.formatting(it.id) }) { it is TextBoxObject } as? TextBoxObject
    if (hit != null) {
        val format = fold.formatting(hit.id)
        val bounds = hit.pageBounds(format) ?: return
        objects.edit(hit, bounds, fold.textOf(hit.id) ?: hit.text, format)
    } else {
        objects.place(
            PageRect(point.x, point.y, point.x + NEW_BOX_WIDTH_PT, point.y + NEW_BOX_HEIGHT_PT),
            objects.format,
        )
    }
}

// ⚠️ `tapImage` is gone. It hit-tested against `InkObject.pageBounds()` — the **axis-aligned** hull
// of the four mapped corners — while the ink surface's own tap test maps the point through the
// object's inverse and tests the true quad. Two tools therefore disagreed about where a rotated
// picture was, and the one that owned images was the one that was wrong. Selection is the single
// answer now; see `InkSelection.soleImage`.

/**
 * A dragged rectangle: an Element capture.
 *
 * ⚠️ **The crop used to arrive here too**, and that is what made it a one-shot gesture rather than
 * a frame you adjust — the drag ended, `cropping` cleared, and changing your mind meant drawing a
 * whole new region over a picture that was by then showing the *previous* crop's pixels. It lives
 * in `CropOverlay` now, which holds the region across the whole interaction and writes once.
 */
private fun onDragBox(box: PageRect, ui: InkUiState, objects: ObjectToolState) {
    if (ui.tool != EditorTool.ELEMENT) return
    objects.capturedArea = box
}

/**
 * The page-space rectangle currently on screen.
 *
 * What an insert is centred on. Centring on the *page* instead would put a picture off-screen for
 * anybody zoomed into a corner, which is indistinguishable from nothing having happened —
 * definition-of-done #6 in its mildest form.
 */
fun visiblePage(camera: Camera, widthPx: Int, heightPx: Int, page: PageCanvas): PageRect {
    val topLeft = viewToPage(ViewOffset(0f, 0f), camera)
    val bottomRight = viewToPage(ViewOffset(widthPx.toFloat(), heightPx.toFloat()), camera)
    // ⚠️ Clamped to the **paper**, which on a grown canvas does not start at the page-space origin
    // (SPEC §4.5). Clamping to `0..size` instead would drop a picture at the top-left of a canvas
    // the user had scrolled a long way from — placed where they were not looking, in the one
    // gesture whose whole meaning is *"put it here"*.
    return PageRect(
        left = topLeft.x.coerceIn(page.left, page.right),
        top = topLeft.y.coerceIn(page.top, page.bottom),
        right = bottomRight.x.coerceIn(page.left, page.right),
        bottom = bottomRight.y.coerceIn(page.top, page.bottom),
    )
}

/** A default label: wide enough for a sentence, tall enough for two lines at the default size. */
private const val NEW_BOX_WIDTH_PT = 220f
private const val NEW_BOX_HEIGHT_PT = 44f

/** Clear of the toolbar card, which is anchored to the same corner region. */
private const val PANEL_TOP_DP = 76
