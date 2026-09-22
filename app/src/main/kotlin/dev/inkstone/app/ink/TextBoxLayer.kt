package dev.inkstone.app.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.TextAlign
import dev.inkstone.core.model.TextFont
import dev.inkstone.core.model.pageToView
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign

/**
 * ⭐ ROADMAP M8c — *"text boxes with keyboard input"*, laid over the page where the box is.
 *
 * ## Why a Compose field over the ink surface, and not a `View`
 *
 * The IME is the point. A soft keyboard needs a focusable target, an input connection and an
 * `InputMethodManager` handshake that `InProgressStrokesView` has no business owning — and the ink
 * surface front-buffers through a `SurfaceView` (SPEC §3.2), which is the worst possible place to
 * put one. `BasicTextField` gets all of that for free, and every character it produces is a
 * `String` that reaches `SetTextContent` unaltered.
 *
 * ⚠️ **Non-Latin-1 characters have to survive the whole way, and one place in this app used to
 * destroy them silently**: `PdfContent`'s `toByteArray` truncated every `Char` above `U+00FF` into
 * a different, valid byte (docs/M8-TEXT.md §3.1). That was the *published PDF*'s writer, and M8c
 * fixed it by requiring ASCII in a content stream and putting real text through a CID font. On
 * this side nothing transforms the string at all — no encoding, no normalisation, no sanitiser —
 * which is the only way an IME's output arrives in the op log as the user typed it.
 *
 * ## When the register is written
 *
 * ## Resizing changes the **frame**, never the letters
 *
 * The three handles on the frame write `ObjectFormat.boxWidthPt` / `boxHeightPt` - an absolute LWW
 * register (CLAUDE.md rule 8) that `DryTextBox` re-wraps to. They deliberately do **not** write a
 * `SetTransform` the way the selection handles do, because scaling a paragraph magnifies every
 * glyph and re-wraps nothing: a user who drags a label wider to fit one more word gets the same
 * words, larger, still on the same number of lines. In the user's words, the frame *"should not
 * stretch or resize the text, it should just change the bounds of the text"*.
 *
 * ⚠️ **Nothing is written while the finger is down.** [ObjectToolState.resizeFrame] moves the
 * on-screen frame and the register is written once, on release - the shape [ObjectToolState.dragCrop]
 * already had, and for the same reason: a `SetFormat` per drag frame is a journal entry and an undo
 * entry per frame, of which only the last could ever have mattered.
 *
 * ⚠️ **The frame is axis-aligned, and so is this whole layer.** The field is placed at the
 * object's page-space box with no rotation, which it has been since M8c; a rotated text box is
 * therefore edited through an upright field. [TextEditing.scaleX] is what keeps the *size* honest in
 * the meantime - a box the selection handles have scaled has a page-space box bigger than its frame,
 * and a drag measured in page points would otherwise be magnified by that scale a second time.
 *
 * ## When the register is written
 *
 * On a [COMMIT_DEBOUNCE_MS] debounce **and** on focus loss, never per keystroke. Per keystroke
 * would put one `SetTextContent` per character into the journal — each its own undo entry, so
 * *"press undo"* after typing a word would appear to do nothing eight times — and would upload the
 * paragraph once per letter. Focus loss is what makes the debounce safe: a user who types and
 * immediately taps elsewhere must not lose the tail, and `NoteEditor.updateText` is idempotent so
 * the two firing together costs nothing.
 */
@Composable
fun TextBoxLayer(ui: InkUiState, objects: ObjectToolState, actions: ObjectActions, modifier: Modifier = Modifier) {
    val editing = objects.editing ?: return
    val camera = ui.camera
    val density = LocalDensity.current
    val focus = remember(editing.id) { FocusRequester() }
    val draft by rememberUpdatedState(objects.draft)

    // The debounce. Keyed on the draft, so each keystroke restarts it and the write happens once
    // the typing stops — the same shape as SPEC §8.3's recognition debounce and for the same
    // reason: the expensive thing must not run on the fast path.
    LaunchedEffect(editing.id, objects.draft) {
        delay(COMMIT_DEBOUNCE_MS)
        actions.onTextChanged(editing.id, draft)
    }

    val topLeft = pageToView(PagePoint(editing.bounds.left, editing.bounds.top), camera)
    val format = objects.format
    Box(modifier = modifier.fillMaxSize()) {
        BasicTextField(
            value = objects.draft,
            onValueChange = { objects.draft = it },
            textStyle = TextStyle(
                color = Color(objects.style.colorArgb),
                fontSize = with(density) { (format.sizePt * camera.scale).toSp() },
                fontFamily = format.font.family,
                textAlign = format.align.compose,
            ),
            cursorBrush = SolidColor(Color(objects.style.colorArgb)),
            // Multi-line, and `Default` rather than `Done`: a text box on a page is a paragraph,
            // and an IME action that dismissed the keyboard on Enter would make a list of three
            // bullets three separate boxes.
            keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(topLeft.x.toInt(), topLeft.y.toInt()) }
                .size(
                    width = with(density) { (editing.bounds.width * camera.scale).toDp() },
                    height = with(density) { (editing.bounds.height * camera.scale).toDp() },
                )
                .background(Color.Transparent)
                // ⭐ M10 — the same sky-500 the selection handles use, and at full alpha. The old
                // 60 %-of-`primary` was a pale line the moment `primary` became the design's
                // sky-400: this box is drawn on **white paper**, where a washed-out light blue is
                // the difference between "I am editing this" and "something might be here".
                .border(1.dp, Color(EDIT_BORDER_ARGB))
                .padding(2.dp)
                .focusRequester(focus)
                .onFocusChanged { state ->
                    // ⚠️ The other half of the debounce, and the half that stops it losing data.
                    // A user who types and taps away has left before the timer fires; without
                    // this the tail of what they typed is in no register and no journal.
                    if (!state.isFocused) actions.onTextChanged(editing.id, draft)
                },
        )
        // After the field, so the grips are on top of it: a handle the text field swallows the
        // touches for is a handle that does nothing, which is the same defect as a tool that owns
        // touch and hides the surface beneath it (`EditorTool.IMAGE`).
        FrameHandles(objects, camera, actions)
    }

    LaunchedEffect(editing.id) { focus.requestFocus() }
}

/**
 * The three grips that resize the frame: right edge, bottom edge, and the corner that does both.
 *
 * ⚠️ **Three and not eight, because the top-left corner is the object's origin.** Growing from
 * any other anchor moves it, and the origin is `SetTransform` - a second, different register - so a
 * left-edge drag would have to write two absolute values that a peer can then resolve independently
 * and disagree about. Three handles is the whole capability the frame needs and one register to
 * express it; a fourth would be a correctness question disguised as a convenience.
 */
@Composable
private fun FrameHandles(objects: ObjectToolState, camera: Camera, actions: ObjectActions) {
    val editing = objects.editing ?: return
    // ⚠️ Read at the *down*, never keyed on. `pointerInput(editing.bounds)` reads naturally and
    // tears the handler down on the first delta it reports, so the frame moves one frame and stops -
    // it looks exactly like a hit-test bug (CLAUDE.md, M8 follow-up).
    val live by rememberUpdatedState(editing)
    val liveCamera by rememberUpdatedState(camera)

    val resize: (Float, Float, Boolean, Boolean) -> Unit = { dxView, dyView, wide, tall ->
        val box = live.bounds
        val scale = liveCamera.scale
        val minWidth = MIN_FRAME_PT * live.scaleX
        val minHeight = MIN_FRAME_PT * live.scaleY
        objects.resizeFrame(
            PageRect(
                left = box.left,
                top = box.top,
                right = if (wide) max(box.left + minWidth, box.right + dxView / scale) else box.right,
                bottom = if (tall) max(box.top + minHeight, box.bottom + dyView / scale) else box.bottom,
            ),
        )
    }

    // ⚠️ A box that does not exist yet writes nothing, and that is not a gap: `insertTextBox`
    // takes its extents from these very bounds, so the frame the user dragged is already what the
    // first words will create. Writing the register too would put a record on the object saying the
    // same thing its payload says - see `ObjectToolState.place`.
    val commit: () -> Unit = {
        val target = live
        target.id?.let { id ->
            actions.onFormatChanged(id) {
                it.copy(boxWidthPt = target.frameWidthPt, boxHeightPt = target.frameHeightPt)
            }
        }
    }

    val topLeft = pageToView(PagePoint(editing.bounds.left, editing.bounds.top), camera)
    val width = editing.bounds.width * camera.scale
    val height = editing.bounds.height * camera.scale
    Grip(topLeft.x + width, topLeft.y + height / 2f, "Text width", resize, commit, wide = true, tall = false)
    Grip(topLeft.x + width / 2f, topLeft.y + height, "Text height", resize, commit, wide = false, tall = true)
    Grip(topLeft.x + width, topLeft.y + height, "Text frame", resize, commit, wide = true, tall = true)
}

/**
 * One grip, centred on a point in view space.
 *
 * The touch target is [GripTouch] and the dot inside it is [GripDot] - the gap between the two is
 * deliberate. A grip drawn at finger size would cover the corner of the paragraph it is resizing,
 * and one *touchable* at dot size is the 8 dp-hairline problem `DryShape.tappedAt` was written for.
 */
@Composable
@Suppress("LongParameterList") // A position, a label, and which axes this grip moves.
private fun Grip(
    x: Float,
    y: Float,
    label: String,
    resize: (Float, Float, Boolean, Boolean) -> Unit,
    commit: () -> Unit,
    wide: Boolean,
    tall: Boolean,
) {
    val density = LocalDensity.current
    val half = with(density) { GripTouch.toPx() } / 2f
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset { IntOffset((x - half).roundToInt(), (y - half).roundToInt()) }
            .size(GripTouch)
            // Keyed on the axes only: they are what the handler branches on, and nothing else it
            // reads is captured - see [FrameHandles].
            .pointerInput(wide, tall) {
                detectDragGestures(
                    onDragEnd = commit,
                    // A cancel is a second pointer arriving or the window losing the gesture, not a
                    // change of mind: the frame on screen has already moved, so writing it is what
                    // keeps the register and the glass agreeing.
                    onDragCancel = commit,
                ) { change, drag ->
                    change.consume()
                    resize(drag.x, drag.y, wide, tall)
                }
            }
            .semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .size(GripDot)
                .background(Color(EDIT_BORDER_ARGB), RoundedCornerShape(GripCorner))
                .border(1.dp, Color.White, RoundedCornerShape(GripCorner)),
        )
    }
}

/**
 * The font families M8c ships, as Compose sees them.
 *
 * ⚠️ Deliberately mapped from a **closed enum** and not from a family name. `ObjectFormat`'s KDoc
 * gives the format reason — a record is a `k=v;k=v` string and a user-supplied family name is the
 * one value that could carry a separator — and there is a second reason on this side: the PDF
 * writer has to guarantee an embeddable face for every value it can be handed (SPEC §4.9.5), and
 * it can only do that for a set it knows.
 */
val TextFont.family: FontFamily
    get() = when (this) {
        TextFont.SANS -> FontFamily.SansSerif
        TextFont.SERIF -> FontFamily.Serif
        TextFont.MONO -> FontFamily.Monospace
    }

val TextAlign.compose: ComposeTextAlign
    get() = when (this) {
        TextAlign.START -> ComposeTextAlign.Start
        TextAlign.CENTER -> ComposeTextAlign.Center
        TextAlign.END -> ComposeTextAlign.End
    }

/** Long enough that a fast typist writes a whole word between writes, short enough to survive a crash. */
const val COMMIT_DEBOUNCE_MS: Long = 600L

/** sky-500 — see `SelectionOverlayView.ACCENT` on why it is not the chrome’s sky-400. */
private const val EDIT_BORDER_ARGB = 0xFF0EA5E9.toInt()

/** Big enough to hit with a finger; the dot inside it is what the user aims at. */
private val GripTouch = 32.dp

private val GripDot = 12.dp

private val GripCorner = 3.dp

/**
 * The smallest frame a resize may leave, in the object's own points.
 *
 * A frame narrower than this wraps every word onto its own line and is smaller than the grip that
 * would have to be found to widen it again - the same "never resize a selection to nothing" rule
 * `SelectionController.minimumSize` states one layer down, at the size a paragraph needs rather than
 * the size a matrix needs.
 */
private const val MIN_FRAME_PT = 24f
