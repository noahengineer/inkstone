package dev.inkstone.app.ink

import androidx.compose.runtime.Immutable
import dev.inkstone.core.model.ImageCrop
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.storage.Element

/**
 * ⭐ ROADMAP M8c — everything the toolbar can do to a text box, an image or an Element.
 *
 * The sibling of `InkToolbarActions`, kept separate rather than added to it for two reasons. The
 * first is size: that class is already the whole of M1, M8a and M8b's surface and this would take
 * it past twenty fields. The second matters more — **none of these is a gesture on the ink
 * surface.** Every field in `InkToolbarActions` ends at `InkSurfaceView` or at an undo; every field
 * here ends at `NoteEditor`, under its lock, because these edits are not on the ink path and have
 * no reason to be stamped from the UI thread the way a lasso move does (`SelectionController`).
 */
@Immutable
@Suppress("LongParameterList") // A parameter object: one field per thing the toolbar can do, by design.
class ObjectActions(
    /** The words. Debounced and on focus loss — see [TextBoxLayer]. */
    val onTextChanged: (ObjectId?, String) -> Unit,
    /**
     * The presentation, as a change to whatever the register currently holds.
     *
     * ⚠️ A **function**, never a whole `ObjectFormat`. `SetFormat` carries the object's entire
     * record, so a bar that built one from the chip the user tapped would silently reset every
     * other field on the object — see `NoteEditor.updateFormat`.
     */
    val onFormatChanged: (ObjectId?, (ObjectFormat) -> ObjectFormat) -> Unit,
    /** Colour. `SetStyle`, which SPEC §4.9.2 notes needed nothing new. */
    val onColourChanged: (ObjectId?, Int) -> Unit,
    /**
     * Insert a picture from [ImageSource].
     *
     * One action rather than two, because they differ only in which activity-result contract
     * runs and everything after that — decode, downscale, re-encode, stage the asset, emit the
     * op — is one path. Two entry points into one path is where the second one drifts.
     */
    val onInsertImage: (ImageSource) -> Unit,
    /** Rotate and flip in one call, because they are one register: `SetTransform`, absolute. */
    val onTurn: (ObjectId, Float, Boolean, Boolean) -> Unit,
    val onCrop: (ObjectId, ImageCrop?) -> Unit,
    /** Save [ObjectToolState.capture] as an Element, under a name and a collection. */
    val onSaveElement: (String, String) -> Unit,
    /**
     * ⭐ A box dragged with the Elements tool, as the objects it encloses — handwriting included.
     *
     * Answered by the ink surface, the one thing that can measure a stroke (SPEC §5.4). Null when
     * the box encloses nothing, so the name dialog never opens for an Element of nothing.
     */
    val captureArea: (PageRect) -> ElementCapture?,
    /**
     * ⭐ Put [Element] on the page **centred** on a page-space point — a tap with one armed, or a
     * drop from the panel.
     *
     * The flag says whether to select what landed and hand over the Select tool. A **drop** does: the
     * next thing a person does with an Element they just dragged in is move it. A **tap** does not:
     * arming is M8c's way of stamping one Element four times in a row, and taking the tool away after
     * the first would make that four trips back to the dock.
     */
    val onPlaceElement: (Element, PagePoint, Boolean) -> Unit,
    /** ⭐ Open [Element] in the Element editor — a double-tap in the panel (SPEC §4.9.7). */
    val onEditElement: (Element) -> Unit,
    /**
     * ⚠️ Straight to `InkSurfaceView.ruler`, which owns the edge and the snap.
     *
     * The state on this side is a mirror pushed back by `onRulerChanged` — see
     * [InkUiState.rulerEnabled]. Writing only the mirror is how the ruler would end up drawn in one
     * place and snapping in another.
     */
    val onRulerEnabled: (Boolean) -> Unit,
    val onRulerAngle: (Float) -> Unit,
    /** ⭐ The Element editor's area, pivot and *Fit* tools (SPEC §4.9.7). Null on a note. */
    val elementEditor: ElementEditorActions? = null,
)

/** Where an inserted picture comes from. See [ObjectActions.onInsertImage]. */
enum class ImageSource { GALLERY, CAMERA }
