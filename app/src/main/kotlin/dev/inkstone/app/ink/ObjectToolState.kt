package dev.inkstone.app.ink

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import dev.inkstone.core.storage.Element

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

    /**
     * The selected text box, or null - what the "Edit text" chip acts on.
     *
     * ⚠️ Written from `InkSelection.soleTextBox` on every selection change, exactly as [image]
     * is, and for the reason that entry's KDoc gives: the surface already holds the words, the frame
     * and the format, and reading them back out of the fold from here would be a second source for
     * values that must agree with what is on the glass.
     */
    var text by mutableStateOf<TextFocus?>(null)
        private set

    /** Whether the Elements panel is open. */
    var elementsOpen by mutableStateOf(false)

    /** ⭐ The Element editor's armed tool — an area to drag out, or the pivot to place — or null. */
    var elementTool by mutableStateOf<ElementEditorTool?>(null)

    /** ⭐ The Element editor's canvas. Dark, as asked; light for checking near-black ink. */
    var backdrop by mutableStateOf(dev.inkstone.android.ink.ElementBackdrop.DARK)

    /** The Element a tap with the Element tool will stamp, or null when the next drag captures one. */
    var pendingElement by mutableStateOf<Element?>(null)

    /**
     * What is about to be saved as an Element, waiting for a name and a collection — from a box
     * dragged with the Elements tool, or from *"Save as Element"* on a selection.
     */
    var capture by mutableStateOf<ElementCapture?>(null)

    /**
     * Bumped once the library on disk has changed, so an **open** panel re-reads it.
     *
     * ⚠️ It exists because the obvious key is the wrong one. `ObjectLayers` used to reload on
     * the captured area (now [capture]), which `onSaveElement` clears on the *first* line — before the coroutine that
     * writes `elements/index.json` has run — so the panel re-read the index it already had and went
     * on saying *"Nothing saved yet"*. A closed panel hid it, because opening one afterwards reads
     * disk anyway; with the panel open, which is how anybody looking at the library saves into it,
     * the newly captured element **never appeared, so there was nothing to arm and nothing to
     * stamp**. Stamping itself was never broken. The general shape is this repo's oldest one: a
     * reload keyed on the state that changes when the work *starts* rather than when it *finishes*.
     */
    var libraryRevision by mutableStateOf(0)
        private set

    /** Say the library on disk has changed. Called after a write completes, never before. */
    fun libraryChanged() {
        libraryRevision++
    }

    /**
     * What `ObjectLayers` re-reads `elements/index.json` on.
     *
     * A property rather than an argument list spelled out at the `LaunchedEffect`, so that
     * `ElementPanelRefreshTest` asserts the **production** key: written inline there, the key is a
     * decision no JVM test can see, and the version of this that reloaded a moment too early would
     * have gone on passing a test that named its own.
     */
    val libraryReloadKey: Pair<Boolean, Int> get() = elementsOpen to libraryRevision

    fun edit(box: TextBoxObject, bounds: PageRect, text: String, format: ObjectFormat) {
        edit(
            id = box.id,
            bounds = bounds,
            frameWidthPt = format.frameWidth(box.widthPt),
            frameHeightPt = format.frameHeight(box.heightPt),
            text = text,
            format = format,
            style = box.style ?: DEFAULT_TEXT_STYLE,
        )
    }

    /** "Edit text" on the selection bar - the same field, reached from a selection. */
    fun edit(focus: TextFocus) {
        edit(
            id = focus.id,
            bounds = focus.bounds,
            frameWidthPt = focus.widthPt,
            frameHeightPt = focus.heightPt,
            text = focus.text,
            format = focus.format,
            style = focus.style,
        )
    }

    /**
     * The one place [editing] is opened on a box that exists.
     *
     * [frameWidthPt] is the box in its **own** space and [bounds] is the same box in page space, so
     * their ratio is the scale a handle drag has to be divided by before it is written back - see
     * [TextEditing.scaleX]. Taking both rather than deriving one is deliberate: the caller is the
     * one holding the transform, and a second guess at it here is how two layers come to disagree
     * about where an object is.
     */
    @Suppress("LongParameterList") // The frame in both spaces, plus both registers. See the KDoc.
    private fun edit(
        id: ObjectId?,
        bounds: PageRect,
        frameWidthPt: Float,
        frameHeightPt: Float,
        text: String,
        format: ObjectFormat,
        style: ObjectStyle,
    ) {
        editing = TextEditing(
            id = id,
            bounds = bounds,
            scaleX = scaleOf(bounds.width, frameWidthPt),
            scaleY = scaleOf(bounds.height, frameHeightPt),
        )
        draft = text
        this.format = format
        this.style = style
        image = null
        this.text = null
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
        // ⚠️ **The frame is dropped, and the rest of the formatting is not.** Carrying the last
        // box's font and size to the next one is the point of passing a format at all; carrying its
        // *width* would make a new box claim a frame it does not have - `insertTextBox` takes its
        // extents from [bounds], so the record and the payload would disagree from the first op, in
        // a register that is absolute and wins.
        this.format = format.copy(boxWidthPt = null, boxHeightPt = null)
        style = DEFAULT_TEXT_STYLE
        image = null
        text = null
        cropping = null
    }

    /** The placed box has been created; later edits address it by id. */
    fun attach(id: ObjectId) {
        editing = editing?.copy(id = id)
    }

    /**
     * A frame handle moved, in page space. Writes **nothing** - see `TextBoxLayer`.
     *
     * The same shape as [dragCrop], and for the same reason: a `SetFormat` per frame of a drag is a
     * journal entry and an undo entry per frame, and the register is absolute, so only the last one
     * of them was ever going to matter.
     */
    fun resizeFrame(bounds: PageRect) {
        editing = editing?.copy(bounds = bounds)
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

    /**
     * The selection changed to exactly this text box, or to none.
     *
     * ⚠️ **Ignored while a field is open**, which [focusImage] does not need to say because
     * typing clears the image focus outright. Here the two are the same object: dropping the focus
     * on a re-report of the box being typed into would take the frame handles off the screen the
     * moment a peer's op refreshed the selection.
     */
    fun focusText(focus: TextFocus?) {
        if (editing != null) return
        text = focus
    }

    fun clearFocus() {
        image = null
        text = null
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
        capture = null
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
 * ⭐ What a save as Element will take: the objects, and the box the surface measured them at.
 *
 * Ids rather than an area since Elements could hold handwriting — an area can only be turned into
 * objects by something that can measure a stroke, and that is the surface (`objectsWithin`), so the
 * screen asks it once, at capture time, and the save never has to guess.
 *
 * @property bounds null only when the surface could not measure them, which the core library then
 *   answers from the payloads it can measure.
 */
@androidx.compose.runtime.Immutable
data class ElementCapture(val ids: List<ObjectId>, val bounds: PageRect?)

/**
 * Which text box the field belongs to, and where it sits in page space.
 *
 * ⚠️ [id] is **null until the box exists** — see [ObjectToolState.place]. Every action that writes
 * takes a nullable id for that reason, and a null means *"keep it on screen, write nothing yet"*.
 */
@androidx.compose.runtime.Immutable
data class TextEditing(val id: ObjectId?, val bounds: PageRect, val scaleX: Float = 1f, val scaleY: Float = 1f) {

    /**
     * [bounds] as a frame in the object's **own** space - what a resize writes.
     *
     * A text box that has also been *scaled* by the selection handles has a page-space box larger
     * than its frame, and a handle drag measured in page points would then write a frame magnified
     * by that scale a second time. Dividing by it is what makes the number reaching
     * `ObjectFormat.boxWidthPt` mean what the register says it means.
     */
    val frameWidthPt: Float get() = bounds.width / scaleX

    val frameHeightPt: Float get() = bounds.height / scaleY
}

/**
 * The selected text box, as the "Edit text" chip needs it.
 *
 * The mirror of `InkSelection.soleTextBox`, kept on this side for [ImageFocus]'s reason - `:app`
 * holds what the bar has to draw, and the surface holds the render state.
 */
@androidx.compose.runtime.Immutable
data class TextFocus(
    val id: ObjectId,
    val bounds: PageRect,
    val widthPt: Float,
    val heightPt: Float,
    val text: String,
    val format: ObjectFormat,
    val style: ObjectStyle,
)

/**
 * Page points per object point along one axis, from a box measured in both.
 *
 * One when either is degenerate: a zero scale would divide a handle drag by nothing, and the frame
 * that reached the register would be an infinity the codec refuses - a resize that silently does
 * not happen.
 */
private fun scaleOf(pageExtent: Float, frameExtent: Float): Float =
    if (pageExtent > 0f && frameExtent > 0f) pageExtent / frameExtent else 1f

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
