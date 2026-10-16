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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.inkstone.app.elements.ElementDrag
import dev.inkstone.app.elements.ElementPanel
import dev.inkstone.app.elements.ElementPreviews
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
import dev.inkstone.core.storage.Element
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    Box(modifier = modifier.fillMaxSize()) {
        PageOverlay(
            ui = ui,
            objects = objects,
            gestures = PageGestures(
                onTap = { point -> scope.launch { onTap(point, ui, objects, editor, actions) } },
                onDragBox = { box -> onDragBox(box, ui, objects, actions) },
            ),
        )
        // ⭐ Above the page overlay and below the panels: while it is up it owns the touches, which
        // is what stops a crop drag from also panning the page underneath.
        objects.cropping?.let { session ->
            CropOverlay(session = session, camera = ui.camera, onRegion = objects::dragCrop)
        }
        TextBoxLayer(ui, objects, actions)
        // ⭐ SPEC §4.9.7 — the Element editor's area and pivot tools, and the pivot itself: always on
        // the editor's canvas, and on a note for a selected copy that has one.
        if (editor.editingElement != null) {
            // The pivot itself is drawn by the page, on the bounds it belongs to (`PagePainter.paintGuides`),
            // so an undo moves it back with no state here to fall out of step.
            actions.elementEditor?.let { tools ->
                ElementEditorOverlay(
                    objects = objects,
                    camera = ui.camera,
                    onArea = { kind, box -> scope.launch { tools.addArea(kind, box) } },
                    onPivot = { point -> scope.launch { tools.placePivot(point) } },
                )
            }
        } else {
            ui.selectionPivot?.takeUnless { ui.selectionManipulating }?.let { PivotMarker(it, ui.camera) }
        }
    }

    // ⭐ M8c — the captured area's name and collection. A dialog rather than a bar, because it is a
    // question with an answer and the page underneath is exactly what the answer is about.
    //
    // ⚠️ It lives here rather than in the Elements toolbar row, and that move is load-bearing: the
    // redesign collapses row two whenever a contextual toolbar is up, so a dialog hung off that row
    // would be *un-summonable* the moment anything on the page was selected — a capture gesture that
    // does nothing, which is CLAUDE.md's definition-of-done #6 caused by a layout decision.
    if (objects.capture != null) NameElementDialog(objects, actions)
}

/**
 * ⭐ The Elements panel, and the ghost a drag out of it carries — **composed above the contextual
 * bars**, which is why it is not part of [ObjectLayers].
 *
 * It was, until double-tap-to-edit made its rows targets worth hitting: a selection near the top
 * right put its floating bar straight over the panel's first row, so the double-tap meant to open
 * the Element editor pressed *Save as Element* instead (found on the emulator). `InkScreen` composes
 * this after `TopChrome`, so a panel the user opened is above a bar they did not ask to move.
 *
 * Fills the screen with no input of its own, so everything outside the panel still reaches the ink
 * surface; its origin is the surface's, which is what the drop converts window positions into.
 *
 * @param geometry ⭐ the chrome, whose panel column (`PanelDock`, docs/UI-RAILS.md §3) the panel takes
 *   its place in — under nothing and over nothing — until it is dragged out (`PanelWindow`).
 */
@Composable
internal fun ElementPanelLayer(
    ui: InkUiState,
    objects: ObjectToolState,
    editor: NoteEditorViewModel,
    actions: ObjectActions,
    geometry: ChromeGeometry,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var elements by remember { mutableStateOf(emptyList<Element>()) }

    // Loaded when the panel opens rather than held for the note's lifetime: the library is a file
    // on disk that another note may have written, so a list captured at open would go stale the
    // moment an element was saved from anywhere else.
    //
    // ⚠️ Re-read on [ObjectToolState.libraryRevision], which is bumped when a write **finishes** —
    // see its KDoc for the version of this that keyed on the captured area and re-read the index a
    // moment before the save wrote it.
    LaunchedEffect(objects.libraryReloadKey) {
        if (objects.elementsOpen) elements = editor.elements()
    }
    val previews = rememberElementPreviews(editor, elements)
    val drop = remember { DropState() }
    val drag = remember(actions) { drop.drag(ui, actions) }

    Box(modifier = modifier.fillMaxSize().onGloballyPositioned { drop.layer = it }) {
        if (objects.elementsOpen) {
            PanelWindow(DockSlot.ELEMENTS, ui, geometry) {
                ElementPanel(
                    elements = elements,
                    armed = objects.pendingElement,
                    previews = previews,
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
                    onEdit = actions.onEditElement,
                    drag = drag,
                    modifier = Modifier.onGloballyPositioned { drop.panelBounds = it.boundsInWindow() },
                )
            }
        }
        drop.dragging?.let { held ->
            val local = drop.layer?.windowToLocal(held.window)
            if (local != null) DragGhost(held.element, previews[held.element.id], local, ui.camera)
        }
    }
}

/** Name and file the captured area. Two fields, because a library with one collection is a list. */
@Composable
private fun NameElementDialog(state: ObjectToolState, actions: ObjectActions) {
    var name by remember { mutableStateOf("") }
    var collection by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { state.capture = null },
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
        dismissButton = { TextButton(onClick = { state.capture = null }) { Text("Cancel") } },
    )
}

/**
 * What a tap does, per tool.
 *
 * ⚠️ The fold is read **now**, not held: `NoteEditor.currentFold` takes the editor's lock, so what
 * comes back includes anything a peer merged a moment ago. A screen-held copy would hit-test
 * against a page that no longer exists, which is how a tap "misses" an image that is plainly there.
 */
private suspend fun onTap(
    point: PagePoint,
    ui: InkUiState,
    objects: ObjectToolState,
    editor: NoteEditorViewModel,
    actions: ObjectActions,
) {
    when (ui.tool) {
        EditorTool.TEXT -> editor.currentFold()?.let { fold -> tapText(point, fold, objects, editor) }

        // Centred on the tap, exactly as a drop is centred on the finger — the two ways of placing
        // one Element must not put it in two different places for the same point.
        EditorTool.ELEMENT -> objects.pendingElement?.let { actions.onPlaceElement(it, point, false) }

        // ⭐ docs/HANDWRITING-SYNTHESIS.md §6.2 — written by the panel's layer, which holds the writer.
        EditorTool.WRITE -> objects.write.send(
            dev.inkstone.app.handwriting.WriteCommand.Place(point, dev.inkstone.core.handwriting.PlaceMode.TAP),
        )

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
private fun onDragBox(box: PageRect, ui: InkUiState, objects: ObjectToolState, actions: ObjectActions) {
    if (ui.tool != EditorTool.ELEMENT) return
    // ⭐ Turned into objects **now**, by the surface — handwriting included — rather than handed to
    // the save as an area. A box that encloses nothing opens no dialog at all; asking for a name for
    // an Element of nothing and then saying so afterwards is the worse order.
    objects.capture = actions.captureArea(box)
}

/**
 * One picture per Element, drawn once and kept for the screen's life — an Element never changes
 * after it is saved, so its id is a complete cache key.
 */
@Composable
private fun rememberElementPreviews(
    editor: NoteEditorViewModel,
    elements: List<Element>,
): Map<dev.inkstone.core.model.ElementId, ImageBitmap> {
    val drawn = remember { mutableStateMapOf<String, ImageBitmap>() }
    val widthPx = with(LocalDensity.current) { PREVIEW_DP.dp.roundToPx() } * PREVIEW_OVERSAMPLE
    LaunchedEffect(elements) {
        for (element in elements.filter { previewKey(it) !in drawn }) {
            previewOf(editor, element, widthPx)?.let { drawn[previewKey(element)] = it }
        }
    }
    return elements.mapNotNull { element -> drawn[previewKey(element)]?.let { element.id to it } }.toMap()
}

/**
 * What a picture of [element] depends on. ⚠️ **Not its id alone**: since the Element editor (SPEC
 * §4.9.7) an Element is revised in place under the same id, and a cache keyed on the id would go on
 * showing the old picture for as long as the note stayed open.
 */
private fun previewKey(element: Element): String =
    "${element.id.value}:${element.objects.hashCode()}:${element.texts.hashCode()}:${element.formats.hashCode()}"

private suspend fun previewOf(editor: NoteEditorViewModel, element: Element, widthPx: Int): ImageBitmap? {
    val bytes = editor.elementPreview(element, widthPx) ?: return null
    return withContext(Dispatchers.Default) {
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
}

/**
 * ⭐ A drag out of the panel. Window coordinates in, this layer's coordinates out — the layer sits
 * exactly over the ink surface, so its local space is the surface's view space, which is what the
 * camera maps to page space.
 */
@androidx.compose.runtime.Stable
private class DropState {
    var layer by mutableStateOf<LayoutCoordinates?>(null)
    var panelBounds by mutableStateOf<Rect?>(null)
    var dragging by mutableStateOf<DraggedElement?>(null)

    fun drag(ui: InkUiState, actions: ObjectActions) = ElementDrag(
        onStart = { element, window -> dragging = DraggedElement(element, window) },
        onMove = { window -> dragging = dragging?.copy(window = window) },
        onEnd = { drop(ui, actions) },
        onCancel = { dragging = null },
    )

    private fun drop(ui: InkUiState, actions: ObjectActions) {
        val dropped = dragging ?: return
        dragging = null
        // Letting go over the panel itself is "never mind", not "put it under the panel".
        if (panelBounds?.contains(dropped.window) == true) return
        val local = layer?.windowToLocal(dropped.window) ?: return
        actions.onPlaceElement(dropped.element, viewToPage(ViewOffset(local.x, local.y), ui.camera), true)
    }
}

/** An Element in flight out of the panel, and where the finger is in window space. */
private data class DraggedElement(val element: Element, val window: Offset)

/**
 * What the finger carries: the Element's picture at the size it will land at, centred on the finger.
 *
 * At the page's own scale rather than the tile's, because *"how big is this going to be"* is the
 * question a drag answers — a thumbnail-sized ghost dropped onto a page zoomed to 300 % lands three
 * times larger than it looked, which reads as the drop having done something else. Drawn without
 * input, so it never takes the touch it is following.
 */
@Composable
private fun DragGhost(element: Element, preview: ImageBitmap?, centre: Offset, camera: Camera) {
    val margin = ElementPreviews.marginPt(element) * camera.scale
    val width = ElementPreviews.widthPt(element) * camera.scale
    val height = ElementPreviews.heightPt(element) * camera.scale
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val left = centre.x - width / 2f
        val top = centre.y - height / 2f
        if (preview != null) {
            drawImage(
                image = preview,
                dstOffset = IntOffset((left - margin).toInt(), (top - margin).toInt()),
                dstSize = IntSize((width + 2 * margin).toInt(), (height + 2 * margin).toInt()),
                alpha = GHOST_ALPHA,
            )
        }
        drawRect(
            color = Color(GHOST_OUTLINE_ARGB),
            topLeft = Offset(left, top),
            size = Size(width, height),
            style = Stroke(width = GHOST_OUTLINE_PX),
        )
    }
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

/** The panel's tile, and how many times its pixel size the preview is drawn at — crisp, and reused for the ghost. */
private const val PREVIEW_DP = 52
private const val PREVIEW_OVERSAMPLE = 3

/** See-through enough to see what it is being dropped on. */
private const val GHOST_ALPHA = 0.75f

/** sky-500 — `PageOverlay`'s "the region you are describing" colour. */
private const val GHOST_OUTLINE_ARGB = 0xFF0EA5E9.toInt()
private const val GHOST_OUTLINE_PX = 2f
