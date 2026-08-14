package dev.inkstone.app.ink

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
    LaunchedEffect(objects.elementsOpen, objects.capturedArea) {
        if (objects.elementsOpen) elements = editor.elements()
    }

    Box(modifier = modifier.fillMaxSize()) {
        PageOverlay(
            ui = ui,
            objects = objects,
            gestures = PageGestures(
                onTap = { point -> scope.launch { onTap(point, ui, objects, editor) } },
                onDragBox = { box -> scope.launch { onDragBox(box, ui, objects, editor, actions) } },
                onLasso = { points -> scope.launch { onLasso(points, objects, editor, actions) } },
            ),
        )
        TextBoxLayer(ui, objects, actions)
        if (objects.elementsOpen) {
            ElementPanel(
                elements = elements,
                armed = objects.pendingElement,
                onArm = { objects.pendingElement = it },
                onDelete = { id ->
                    scope.launch {
                        editor.deleteElement(id)
                        elements = editor.elements()
                    }
                },
                onClose = { objects.elementsOpen = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = PANEL_TOP_DP.dp, end = 12.dp),
            )
        }
    }
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
        EditorTool.IMAGE -> tapImage(point, fold, objects)
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
    val hit = fold.objects.topmostAt(point) { it is TextBoxObject } as? TextBoxObject
    if (hit != null) {
        val bounds = hit.pageBounds() ?: return
        objects.edit(hit, bounds, fold.textOf(hit.id) ?: hit.text, fold.formatting(hit.id))
    } else {
        objects.place(
            PageRect(point.x, point.y, point.x + NEW_BOX_WIDTH_PT, point.y + NEW_BOX_HEIGHT_PT),
            objects.format,
        )
    }
}

private fun tapImage(point: PagePoint, fold: FoldedPage, objects: ObjectToolState) {
    val hit = fold.objects.topmostAt(point) { it is ImageObject } as? ImageObject
    if (hit == null) {
        objects.clearFocus()
        return
    }
    hit.pageBounds()?.let { objects.focus(hit, it, fold.formatting(hit.id)) }
}

/**
 * A dragged rectangle: a rectangular crop, or an Element capture.
 *
 * ⚠️ The crop is normalised against the image's **own** box, not against the page — SPEC §4.9.2, so
 * that a later resize or rotate carries it rather than having to rewrite it in a second register
 * that could then disagree. `CropMath` does the arithmetic; this only decides which gesture it was.
 */
private suspend fun onDragBox(
    box: PageRect,
    ui: InkUiState,
    objects: ObjectToolState,
    editor: NoteEditorViewModel,
    actions: ObjectActions,
) {
    if (ui.tool == EditorTool.ELEMENT) {
        objects.capturedArea = box
        return
    }
    if (ui.tool != EditorTool.IMAGE || objects.cropping != CropMode.RECT) return
    val focused = objects.image ?: return
    val obj = editor.currentFold()?.find(focused.id) ?: return
    val crop = CropMath.rectOf(obj, PagePoint(box.left, box.top), PagePoint(box.right, box.bottom)) ?: return
    actions.onCrop(focused.id, crop)
    objects.cropping = null
}

/** The freehand crop's lasso — a closed polygon of fractions of the source image. */
private suspend fun onLasso(
    points: List<PagePoint>,
    objects: ObjectToolState,
    editor: NoteEditorViewModel,
    actions: ObjectActions,
) {
    val focused = objects.image ?: return
    val fold = editor.currentFold() ?: return
    val obj = fold.find(focused.id) ?: return
    val crop = CropMath.polygonOf(obj, points) ?: return
    actions.onCrop(focused.id, crop)
    objects.cropping = null
}

/**
 * The page-space rectangle currently on screen.
 *
 * What an insert is centred on. Centring on the *page* instead would put a picture off-screen for
 * anybody zoomed into a corner, which is indistinguishable from nothing having happened —
 * definition-of-done #6 in its mildest form.
 */
fun visiblePage(camera: Camera, widthPx: Int, heightPx: Int, page: PageSize): PageRect {
    val topLeft = viewToPage(ViewOffset(0f, 0f), camera)
    val bottomRight = viewToPage(ViewOffset(widthPx.toFloat(), heightPx.toFloat()), camera)
    return PageRect(
        left = topLeft.x.coerceIn(0f, page.widthPt),
        top = topLeft.y.coerceIn(0f, page.heightPt),
        right = bottomRight.x.coerceIn(0f, page.widthPt),
        bottom = bottomRight.y.coerceIn(0f, page.heightPt),
    )
}

/** A default label: wide enough for a sentence, tall enough for two lines at the default size. */
private const val NEW_BOX_WIDTH_PT = 220f
private const val NEW_BOX_HEIGHT_PT = 44f

/** Clear of the toolbar card, which is anchored to the same corner region. */
private const val PANEL_TOP_DP = 120
