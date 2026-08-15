package dev.inkstone.app.ink

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.inkstone.app.elements.Element
import dev.inkstone.core.model.ImageCrop
import dev.inkstone.core.model.ImageEdit
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.TextBoxObject
import dev.inkstone.core.model.bounds

/**
 * ⭐ ROADMAP M8c — what the screen is doing with a text box, an image or an Element right now.
 *
 * Kept apart from [InkUiState], which its own KDoc scopes to *"everything the M1 toolbar can
 * change, and nothing else"*. None of this is a toolbar setting: it is which object is being typed
 * into, which picture the crop handles belong to, and which Element the next tap will stamp — all
 * of it transient, none of it persisted, and all of it cleared by turning a page.
 *
 * ⚠️ **Nothing here is the truth about the document.** The text being typed is a *draft*; the
 * register is written by `NoteEditor.updateText` on a debounce and on focus loss, and the fold is
 * what the surface draws. Treating this as the value would put a second source of truth beside an
 * LWW register, which is how a peer's edit gets silently overwritten by whatever the screen
 * happened to be holding.
 */
@Stable
class ObjectToolState {

    /** The text box being typed into, or null. */
    var editing by mutableStateOf<TextEditing?>(null)
        private set

    /**
     * What is in the field — **not** what is in the register.
     *
     * Separate from [editing] because it changes on every keystroke and [editing] does not: an
     * `@Immutable` holder rewritten per character would re-lay-out the field's position and its
     * format bar sixty times a second while somebody types.
     */
    var draft by mutableStateOf("")

    /** The editing box's format, kept optimistically so the bar's chips move when tapped. */
    var format by mutableStateOf(ObjectFormat.Default)

    /** The editing box's style. Never null — SPEC §4.9.2; see [DEFAULT_TEXT_STYLE]. */
    var style by mutableStateOf(DEFAULT_TEXT_STYLE)

    /**
     * The selected image — what Rotate, Flip and Crop act on.
     *
     * ⚠️ Written from `InkSelection.soleImage` on every selection change, **not** by a tap in an
     * overlay. That is what let the Image tool stop owning touch, which is what let an image be
     * dragged at all — see [EditorTool.IMAGE].
     */
    var image by mutableStateOf<ImageFocus?>(null)
        private set

    /**
     * The crop being dragged, or null when not cropping.
     *
     * ⚠️ Holds the **region**, because a crop is now adjusted rather than drawn once: the handles
     * move this and nothing is written until Done. The old `CropMode` said only *which gesture was
     * armed*, which is all a one-shot crop needed and is exactly why it could not be adjusted.
     */
    var cropping by mutableStateOf<CropSession?>(null)
        private set

    /** Whether the Elements panel is open. */
    var elementsOpen by mutableStateOf(false)

    /** The Element a tap with the Element tool will stamp, or null when the next drag captures one. */
    var pendingElement by mutableStateOf<Element?>(null)

    /** A captured area waiting for a name and a collection. */
    var capturedArea by mutableStateOf<PageRect?>(null)

    fun edit(box: TextBoxObject, bounds: PageRect, text: String, format: ObjectFormat) {
        editing = TextEditing(box.id, bounds)
        draft = text
        this.format = format
        style = box.style ?: DEFAULT_TEXT_STYLE
        image = null
        cropping = null
    }

    /**
     * ⭐ A field over a box that **does not exist yet**.
     *
     * ⚠️ Not an oversight and not laziness — it is what `TextBoxEdit.create`'s KDoc requires.
     * `Undo.inverseOf` reads a `SetTextContent`'s previous value out of the fold and the op cannot
     * carry *"unset"*, so the first `setText` on a box created **empty** has no inverse:
     * `UndoStack.commit` drops the entry and the press falls through to the create, deleting the
     * box the user is typing in. Placing and typing therefore have to be one gesture, so the tap
     * only remembers *where*, and the object is created by the first commit that has characters in
     * it. A tap that types nothing leaves nothing behind, which is also what a user expects from
     * tapping in the wrong place.
     */
    fun place(bounds: PageRect, format: ObjectFormat = ObjectFormat.Default) {
        editing = TextEditing(id = null, bounds = bounds)
        draft = ""
        this.format = format
        style = DEFAULT_TEXT_STYLE
        image = null
        cropping = null
    }

    /** The placed box has been created; later edits address it by id. */
    fun attach(id: ObjectId) {
        editing = editing?.copy(id = id)
    }

    fun stopEditing() {
        editing = null
        draft = ""
    }

    /**
     * The selection changed to exactly this image, or to none.
     *
     * ⚠️ A crop in progress survives a *re-report of the same image* and is dropped for anything
     * else. `onSelectionChanged` fires for reasons that are not the user changing their mind — a
     * peer's op arriving, a bounds refresh after a drag — and tearing down a half-dragged crop
     * because a merge landed would lose work the user could see on screen.
     */
    fun focusImage(image: ImageFocus?, format: ObjectFormat) {
        if (image?.id != this.image?.id) cropping = null
        this.image = image
        if (image != null) {
            this.format = format
            stopEditing()
        }
    }

    fun focus(image: ImageObject, bounds: PageRect, format: ObjectFormat) {
        focusImage(ImageFocus(image.id, bounds, image.widthPt, image.heightPt), format)
    }

    fun clearFocus() {
        image = null
        cropping = null
    }

    // -----------------------------------------------------------------------
    // Cropping
    // -----------------------------------------------------------------------

    /**
     * Open the crop overlay on the focused picture.
     *
     * The session is built from the **whole-source** frame, recovered from the live transform and
     * whatever crop is already applied — see `ImageEdit.uncroppedFrame`. That is what makes the
     * handles start on the current crop and the fractions mean what `ImageCrop.Rect` says they
     * mean, and it is the step whose absence made a second crop select the wrong pixels.
     */
    fun beginCrop() {
        val focus = image ?: return
        cropping = CropSession(
            id = focus.id.value,
            source = ImageEdit.uncroppedFrame(focus.transform, focus.widthPt, focus.heightPt, format.crop),
            widthPt = focus.widthPt,
            heightPt = focus.heightPt,
            region = format.crop.asRegion(),
        )
    }

    /** A handle moved. Writes nothing — see [commitCrop]. */
    fun dragCrop(region: ImageCrop.Rect) {
        cropping = cropping?.copy(region = region)
    }

    /** Back to the whole picture, without leaving the overlay — so it can be re-tightened. */
    fun resetCrop() {
        cropping = cropping?.copy(region = WHOLE)
    }

    fun cancelCrop() {
        cropping = null
    }

    /**
     * Write the crop: one `SetFormat` and one `SetTransform`, as one gesture.
     *
     * A region covering the whole picture is written as **null** rather than as `0,0,1,1`. Both
     * render identically, but null is the absence of a record — it costs no bytes in the file, no
     * upload, and it is what `Uncrop` produces, so a picture cropped back out by hand and one never
     * cropped are the same document rather than two that merely look alike.
     */
    fun commitCrop(actions: ObjectActions) {
        val session = cropping ?: return
        val focus = image ?: return
        cropping = null
        actions.onCrop(focus.id, session.region.takeIf { !it.isWhole })
    }

    /**
     * Everything transient, dropped.
     *
     * Called on a page change, because every value here names an object on the page that is
     * leaving — a crop handle drawn over page 2 for an image on page 1 is chrome that lies, and a
     * debounced commit fired afterwards would write a register on an object the user cannot see.
     */
    fun reset() {
        stopEditing()
        clearFocus()
        pendingElement = null
        capturedArea = null
    }

    companion object {
        /**
         * ⚠️ A text box is created **with** a style, always (SPEC §4.9.2). `SetStyle` cannot
         * express "unset", so a box shipped with a null style has no inverse for its first
         * recolour and *"change the colour, press undo"* silently eats the press.
         *
         * The width is the one field of `ObjectStyle` a text box has no use for. One point rather
         * than zero, so that anything which ever draws a border round one has a hairline to use
         * and not a degenerate value to guard against.
         */
        val DEFAULT_TEXT_STYLE: ObjectStyle = ObjectStyle(colorArgb = 0xFF1A1A1A.toInt(), widthPt = 1f)
    }
}

/**
 * Which text box the field belongs to, and where it sits in page space.
 *
 * ⚠️ [id] is **null until the box exists** — see [ObjectToolState.place]. Every action that writes
 * takes a nullable id for that reason, and a null means *"keep it on screen, write nothing yet"*.
 */
@androidx.compose.runtime.Immutable
data class TextEditing(val id: ObjectId?, val bounds: PageRect)

/**
 * Which image the transform and crop buttons act on.
 *
 * Carries the object's own extents **and its live transform**, because both are needed to recover
 * the whole-source frame the crop handles are placed on ([ObjectToolState.beginCrop]).
 */
@androidx.compose.runtime.Immutable
data class ImageFocus(
    val id: ObjectId,
    val bounds: PageRect,
    val widthPt: Float,
    val heightPt: Float,
    val transform: Matrix3 = Matrix3.Identity,
)

/**
 * The crop this picture already has, as a rectangle the handles can start on.
 *
 * A polygon degrades to its bounding box: the overlay drags a rectangle, and offering handles that
 * silently discarded a freehand outline on first touch would be worse than showing the box it
 * occupies. A freehand crop is still readable and still renders — it is only *editing* one that
 * turns it into a rectangle, which is what dragging a rectangular frame means.
 */
private fun ImageCrop?.asRegion(): ImageCrop.Rect = when (this) {
    null -> WHOLE
    is ImageCrop.Rect -> this
    is ImageCrop.Polygon -> bounds() ?: WHOLE
}

private val WHOLE = ImageCrop.Rect(0f, 0f, 1f, 1f)
