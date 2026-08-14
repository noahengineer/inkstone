package dev.inkstone.app.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.TextAlign
import dev.inkstone.core.model.TextFont
import dev.inkstone.core.model.pageToView
import kotlinx.coroutines.delay
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
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = BORDER_ALPHA))
                .padding(2.dp)
                .focusRequester(focus)
                .onFocusChanged { state ->
                    // ⚠️ The other half of the debounce, and the half that stops it losing data.
                    // A user who types and taps away has left before the timer fires; without
                    // this the tail of what they typed is in no register and no journal.
                    if (!state.isFocused) actions.onTextChanged(editing.id, draft)
                },
        )
    }

    LaunchedEffect(editing.id) { focus.requestFocus() }
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

private const val BORDER_ALPHA = 0.6f
