package dev.inkstone.app

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
import dev.inkstone.app.ink.InkScreen
import dev.inkstone.app.note.NoteEditorViewModel
import dev.inkstone.app.ui.InkstoneTheme

/**
 * M2's UI: one notebook, its pages, and the ink surface.
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
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val editor: NoteEditorViewModel by viewModels()

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

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbars) },
                ) { innerPadding ->
                    InkScreen(modifier = Modifier.padding(innerPadding), editor = editor)
                }
            }
        }
    }

    override fun onStop() {
        editor.materialize()
        super.onStop()
    }
}
