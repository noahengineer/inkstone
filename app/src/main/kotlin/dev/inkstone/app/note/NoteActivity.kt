package dev.inkstone.app.note

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import dev.inkstone.app.R
import dev.inkstone.app.ink.InkScreen
import dev.inkstone.app.sync.PollTicker
import dev.inkstone.app.ui.InkstoneTheme
import javax.inject.Inject

/**
 * The note editor: one notebook, its pages, and the ink surface.
 *
 * Was `MainActivity` until M3, which gave the app a library to open notes *from*. The note
 * to open arrives as [EXTRA_NOTE_ID] and reaches [NoteEditorViewModel] through its
 * `SavedStateHandle`, which Hilt seeds from this activity's intent extras — so the right
 * note comes back after a process kill without anything having to remember it.
 *
 * ## Why `onStop` materializes
 *
 * SPEC §5.1 makes materialization a close-time job — *"runs on: document close, before upload,
 * and on idle-while-charging. Never on the ink path"* — and states the invariant it protects:
 * `document.pdf` is never more than one editing session stale.
 *
 * `onStop` is the last callback Android guarantees before the process can be killed, so it is
 * the only honest place for it. `onDestroy` is not guaranteed, and a view model's `onCleared`
 * is not either. Everything written before this point is already in `journal.ops`, so a kill
 * that beats the materialization costs a fold on the next open and no data.
 *
 * M3 added a second reason it has to be here: materializing is also what redraws the note's
 * library thumbnail (SPEC §5.7). Going back to the library stops this activity, so the
 * picture is up to date by the time the grid is on screen.
 */
@AndroidEntryPoint
class NoteActivity : ComponentActivity() {

    private val editor: NoteEditorViewModel by viewModels()

    /**
     * SPEC §7.6's foreground poll (M6).
     *
     * Retained here as well as in `MainActivity` because the editor is precisely where a peer's
     * edits have to arrive live (SPEC §13.2 row 10) — a note open on two tablets is the case the
     * whole milestone is about. `PollTicker` counts screens rather than tracking one, which is what
     * keeps polling continuous across the handover: Android runs this `onStart` **before** the
     * library's `onStop`.
     */
    @Inject
    lateinit var poll: PollTicker

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            InkstoneTheme {
                val note by editor.state.collectAsState()
                val snackbars = remember { SnackbarHostState() }

                LaunchedEffect(note.recoveredFromCrash) {
                    // The user is entitled to know something was lost, and what. SPEC §5.6 makes
                    // the same argument for Recovered mode: silence is the one option that is
                    // never right.
                    if (note.recoveredFromCrash) {
                        snackbars.showSnackbar(getString(R.string.recovered_from_crash))
                    }
                }
                LaunchedEffect(note.message) {
                    note.message?.let { message ->
                        snackbars.showSnackbar(message)
                        editor.dismissMessage()
                    }
                }

                // ⭐ M12 — the recorder's own channel: "not enough free space", "that was under
                // thirty seconds", "the microphone could not be opened". A **second** effect
                // rather than a field on `NoteEditorState`, because recording state has a
                // different owner and a different lifetime — and because `NoteEditor.publish()`
                // reconstructs that object rather than copying it, so a message put there would be
                // cleared by the next autosave tick before anyone read it.
                val recording by editor.recording.collectAsState()
                LaunchedEffect(recording.message) {
                    recording.message?.let { message ->
                        snackbars.showSnackbar(message)
                        editor.dismissRecordingMessage()
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbars) },
                ) { innerPadding ->
                    InkScreen(modifier = Modifier.padding(innerPadding), editor = editor)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        poll.retain()
    }

    override fun onStop() {
        poll.release()
        editor.materialize()
        super.onStop()
    }

    companion object {
        /** The note to open, as a [dev.inkstone.core.model.DocumentId]'s string value. */
        const val EXTRA_NOTE_ID: String = NoteEditorViewModel.ARG_NOTE_ID

        /** SPEC §8.4 — the page a search hit is on. Absent means "wherever the note was". */
        const val EXTRA_PAGE_ID: String = NoteEditorViewModel.ARG_PAGE_ID

        /**
         * The hit's page-space box as `[left, top, right, bottom]`, to highlight.
         *
         * A float array rather than four extras because it travels as one thing and is
         * meaningless in parts — three of four would place a rectangle somewhere plausible.
         */
        const val EXTRA_HIT_BOX: String = NoteEditorViewModel.ARG_HIT_BOX
    }
}
