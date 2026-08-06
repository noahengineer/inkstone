package dev.inkstone.app

import android.content.Intent
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import dev.inkstone.app.library.LibraryActions
import dev.inkstone.app.library.LibraryScreen
import dev.inkstone.app.library.LibraryViewModel
import dev.inkstone.app.library.TrashScreen
import dev.inkstone.app.note.NoteActivity
import dev.inkstone.app.ui.InkstoneTheme
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import javax.inject.Inject

/**
 * M3's UI: the library. Notes, folders, favourites, search, trash.
 *
 * ## Two activities, no navigation library
 *
 * The editor is [NoteActivity] and gets its note through an intent extra. That is not
 * avoidance of `androidx.navigation` — it is the shape that makes *which note* survive
 * process death for free: a Hilt `ViewModel`'s `SavedStateHandle` is seeded from the
 * hosting activity's intent extras, so a note reopened after Android kills the app is the
 * note the user was in. Holding the id in Compose state would lose it, and holding it in a
 * nav back stack would be a dependency to get the same thing.
 *
 * It also puts the editor on its own lifecycle, which matters more than it sounds: SPEC
 * §5.1 makes materialization an `onStop` job, and with one activity, opening a note and
 * coming back would never stop anything.
 *
 * The trash is a Compose state flip rather than a third activity — nothing about it needs
 * to survive a kill, and coming back to the library is exactly what should happen.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val library: LibraryViewModel by viewModels()

    /** For the trash screen's "N days left", which is display-only time (SPEC §4.1). */
    @Inject
    lateinit var clock: Clock

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { InkstoneTheme { Content() } }
    }

    @Composable
    private fun Content() {
        val state by library.state.collectAsState()
        val trash by library.trash.collectAsState()
        val snackbars = remember { SnackbarHostState() }
        var showingTrash by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(state.message) {
            state.message?.let {
                snackbars.showSnackbar(it)
                library.dismissMessage()
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbars) },
        ) { innerPadding ->
            if (showingTrash) {
                TrashScreen(
                    state = trash,
                    nowEpochMillis = clock.nowEpochMillis(),
                    onRestoreNote = library::restoreNote,
                    onRestoreFolder = library::restoreFolder,
                    onBack = { showingTrash = false },
                    modifier = Modifier.padding(innerPadding),
                )
            } else {
                LibraryScreen(
                    state = state,
                    modifier = Modifier.padding(innerPadding),
                    actions = libraryActions(onOpenTrash = { showingTrash = true }),
                )
            }
        }
    }

    private fun libraryActions(onOpenTrash: () -> Unit) = LibraryActions(
        onOpenNote = ::openNote,
        onOpenFolder = library::openFolder,
        onGoUp = { library.goUp() },
        onNewNote = { library.createNote(::openNote) },
        onNewFolder = library::createFolder,
        onQueryChange = library::setQuery,
        onSort = library::setSort,
        onLayout = library::setLayout,
        onFavouritesOnly = library::setFavouritesOnly,
        onToggleSelect = library::toggleSelection,
        onSelectAll = library::selectAllVisible,
        onClearSelection = library::clearSelection,
        onMoveSelection = library::moveSelection,
        onTrashSelection = library::trashSelection,
        onTrashNote = library::trashNote,
        onTrashFolder = library::trashFolder,
        onRenameNote = library::renameNote,
        onRenameFolder = library::renameFolder,
        onMoveFolder = library::moveFolder,
        onStyleFolder = library::setFolderAppearance,
        onFavourite = library::setFavourite,
        onDuplicate = library::duplicate,
        onOpenTrash = onOpenTrash,
        thumbnail = library::thumbnail,
    )

    private fun openNote(id: DocumentId) {
        startActivity(
            Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_NOTE_ID, id.value),
        )
    }
}
