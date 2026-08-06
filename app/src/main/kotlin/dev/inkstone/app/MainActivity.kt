package dev.inkstone.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.inkstone.app.library.FolderCoverImage
import dev.inkstone.app.library.LibraryActions
import dev.inkstone.app.library.LibraryScreen
import dev.inkstone.app.library.LibraryViewModel
import dev.inkstone.app.library.TrashScreen
import dev.inkstone.app.note.NoteActivity
import dev.inkstone.app.sync.SyncActions
import dev.inkstone.app.sync.SyncScreen
import dev.inkstone.app.sync.SyncViewModel
import dev.inkstone.app.ui.InkstoneTheme
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FolderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val sync: SyncViewModel by viewModels()

    /** For the trash screen's "N days left", which is display-only time (SPEC §4.1). */
    @Inject
    lateinit var clock: Clock

    /**
     * The folder whose cover the photo picker is being opened for.
     *
     * A field rather than Compose state: the picker is an activity, so this process can be
     * killed while it is in front, and the callback has to know what it was for. `Bundle`
     * round-trips it in [onSaveInstanceState].
     */
    private var pickingCoverFor: FolderId? = null

    /**
     * The system photo picker (`PickVisualMedia`).
     *
     * The modern one, not `ACTION_GET_CONTENT`: it needs **no permission at all**, shows only
     * images, and the user chooses exactly what this app can see. Asking for
     * `READ_MEDIA_IMAGES` to set a folder icon would be wildly out of proportion — and this
     * app has no permissions at all today, which is worth keeping.
     */
    private val pickCoverImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val folderId = pickingCoverFor ?: return@registerForActivityResult
        pickingCoverFor = null
        if (uri != null) importCoverImage(folderId, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pickingCoverFor = savedInstanceState?.getString(STATE_PICKING_COVER_FOR)?.let(::FolderId)
        setContent { InkstoneTheme { Content() } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pickingCoverFor?.let { outState.putString(STATE_PICKING_COVER_FOR, it.value) }
    }

    /**
     * Decode a picked image, downscale it, and hand the bytes to the library.
     *
     * Decoding happens here because it needs `BitmapFactory` and a `ContentResolver`, and
     * re-encoding at import means the stored file is a known format at a known size — so
     * nothing downstream has to sniff a file the user picked, and a 12-megapixel photo does
     * not sit in app storage to be decoded on every launch.
     */
    private fun importCoverImage(folderId: FolderId, uri: Uri) {
        lifecycleScope.launch {
            val encoded = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri).use { it?.readBytes() }
                }.getOrNull()?.let { FolderCoverImage.reEncode(it) }
            }
            if (encoded == null) {
                Toast.makeText(this@MainActivity, R.string.cover_image_failed, Toast.LENGTH_SHORT).show()
            } else {
                library.setImageCover(folderId, encoded)
            }
        }
    }

    @Composable
    private fun Content() {
        val state by library.state.collectAsState()
        val trash by library.trash.collectAsState()
        val syncState by sync.state.collectAsState()
        val snackbars = remember { SnackbarHostState() }
        var showingTrash by rememberSaveable { mutableStateOf(false) }
        var showingSync by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(state.message) {
            state.message?.let {
                snackbars.showSnackbar(it)
                library.dismissMessage()
            }
        }

        // SPEC §7.1 — the browser is the Activity's to launch, not the view model's. The
        // login URL is delivered once and consumed, so a recomposition (or a rotation)
        // cannot reopen the browser on a flow the user already finished.
        LaunchedEffect(syncState.openInBrowser) {
            syncState.openInBrowser?.let { pending ->
                sync.browserOpened()
                openLoginPage(pending.browserUrl)
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbars) },
        ) { innerPadding ->
            when {
                showingTrash -> TrashScreen(
                    state = trash,
                    nowEpochMillis = clock.nowEpochMillis(),
                    onRestoreNote = library::restoreNote,
                    onRestoreFolder = library::restoreFolder,
                    onBack = { showingTrash = false },
                    modifier = Modifier.padding(innerPadding),
                )

                showingSync -> SyncScreen(
                    state = syncState,
                    onBack = { showingSync = false },
                    actions = syncActions(),
                    modifier = Modifier.padding(innerPadding),
                )

                else -> LibraryScreen(
                    state = state,
                    modifier = Modifier.padding(innerPadding),
                    actions = libraryActions(
                        onOpenTrash = { showingTrash = true },
                        onOpenSync = { noteId ->
                            sync.open(noteId)
                            showingSync = true
                        },
                    ),
                )
            }
        }
    }

    private fun syncActions() = SyncActions(
        onServerUrlChange = sync::setServerUrl,
        onStartLogin = sync::startLogin,
        onCancelLogin = sync::cancelLogin,
        onSignOut = sync::signOut,
        onChooseAccount = sync::chooseAccount,
        onBrowseTo = sync::browseTo,
        onGoUp = sync::goUp,
        onCreateFolder = sync::createFolder,
        onBindHere = sync::bindHere,
        onCancelBrowse = sync::cancelBrowse,
        onSyncNow = sync::syncNow,
    )

    /**
     * SPEC §7.1 — *"open `login` in a Custom Tab"*.
     *
     * A Custom Tab keeps the user inside the app and, more importantly, shares the
     * browser's cookie jar — so somebody already signed into their Nextcloud in Chrome
     * approves with one tap instead of typing a password on a tablet. If no browser on
     * the device supports one, this falls back to a plain `ACTION_VIEW`, which works and
     * simply looks like leaving the app.
     */
    private fun openLoginPage(url: String) {
        val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
        runCatching { intent.launchUrl(this, Uri.parse(url)) }
            .onFailure { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun libraryActions(onOpenTrash: () -> Unit, onOpenSync: (DocumentId?) -> Unit) = LibraryActions(
        onOpenNote = ::openNote,
        onOpenFolder = library::openFolder,
        onGoUp = { library.goUp() },
        onNewNote = { library.createNote(::openNote) },
        // Opened only on a clean import. A recovery reports what it lost first
        // (SPEC §5.6 point 4), and jumping into the editor is how that gets dismissed
        // unread — `importPdf` decides, not this.
        onImportPdf = { uri -> library.importPdf(uri, ::openNote) },
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
        onSaveToNextcloud = { noteId -> onOpenSync(noteId) },
        onStopSyncing = sync::unbind,
        onOpenNextcloud = { onOpenSync(null) },
        onFillForMeasurement = library::fillForMeasurement,
        thumbnail = library::thumbnail,
        folderCover = library::folderCover,
        // A drawn cover is an ordinary note in the ordinary editor — the same activity a
        // notebook opens in. That is the whole feature.
        onDrawCover = { folderId -> library.drawCover(folderId, ::openNote) },
        onPickCoverImage = { folderId ->
            pickingCoverFor = folderId
            pickCoverImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
    )

    private companion object {
        const val STATE_PICKING_COVER_FOR = "pickingCoverFor"
    }

    private fun openNote(id: DocumentId) {
        startActivity(
            Intent(this, NoteActivity::class.java).putExtra(NoteActivity.EXTRA_NOTE_ID, id.value),
        )
    }
}
