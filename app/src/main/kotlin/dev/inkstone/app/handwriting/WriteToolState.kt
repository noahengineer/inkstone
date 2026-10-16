package dev.inkstone.app.handwriting

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.inkstone.android.ink.SelectedPhrase
import dev.inkstone.app.ink.TextFocus
import dev.inkstone.core.handwriting.PhraseOnPage
import dev.inkstone.core.handwriting.PlaceMode
import dev.inkstone.core.model.PagePoint
import kotlinx.coroutines.channels.Channel

/** What the Write panel is asked to do. Each is executed by `WritePanelLayer`, off the UI thread. */
sealed interface WriteCommand {
    /** Write the panel's text at a page point — a drag let go there, or a tap (§6.2). */
    data class Place(val point: PagePoint, val mode: PlaceMode) : WriteCommand

    /** *Write again*: the selected phrase, new strokes, same words and place. */
    data object WriteAgain : WriteCommand

    /** *Edit text*: open the panel on the selected phrase's words; *Done* replaces it. */
    data object EditText : WriteCommand

    /** *Done* while editing: replace [WriteToolState.editing] with the panel's text. */
    data object Replace : WriteCommand

    /** *Text box → handwriting*: the box's words, in the box's frame width, replacing the box. */
    data class Handwrite(val box: TextFocus) : WriteCommand
}

/**
 * ⭐ The Write tool's state — docs/HANDWRITING-SYNTHESIS.md §6.2.
 *
 * Held by `ObjectToolState`, so the one-line hooks in the files that are not this feature's — the
 * page tap, the selection bar, the selection callback — can reach it without a new parameter
 * through every composable between them. Everything that *does* something is a [WriteCommand] on
 * [commands], executed by the panel's layer, which is the one place holding the writer, the editor
 * and the surface's selection.
 *
 * Not reset with the other object tools on a tool change: a phrase dropped switches to Select, and
 * the panel is still wanted for the next one, exactly as the Elements library stays open.
 */
@Stable
class WriteToolState {
    /** Whether the panel is open. */
    var open by mutableStateOf(false)

    /** What the panel will write. */
    var text by mutableStateOf("")

    /** Lines are broken before this width, in page points, unless the paragraph continued says otherwise. */
    var wrapWidthPt by mutableFloatStateOf(DEFAULT_WRAP_PT)

    /** D15's *Match the note*: on, the page decides pen and size; off, the current pen and the profile's own. */
    var matchNote by mutableStateOf(true)

    /** *Maths*: the text is maths notation, written in two dimensions (docs/HANDWRITING-MATHS.md). */
    var maths by mutableStateOf(false)

    /** The phrase *Edit text* opened the panel on; *Done* replaces it. Null while writing something new. */
    var editing by mutableStateOf<PhraseOnPage?>(null)

    /** The selected generated phrase, from `InkSelection.phrase` on every selection change. */
    var phrase by mutableStateOf<SelectedPhrase?>(null)

    /** A drop or a write is in progress — the panel says so, and does not start a second. */
    var busy by mutableStateOf(false)

    val commands: Channel<WriteCommand> = Channel(Channel.UNLIMITED)

    fun send(command: WriteCommand) {
        commands.trySend(command)
    }

    companion object {
        const val DEFAULT_WRAP_PT: Float = 360f
        const val MIN_WRAP_PT: Float = 80f
        const val MAX_WRAP_PT: Float = 720f
    }
}
