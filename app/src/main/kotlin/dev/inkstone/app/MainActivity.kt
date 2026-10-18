package dev.inkstone.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
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
import dev.inkstone.app.audio.RecordingController
import dev.inkstone.app.calculator.CalculatorPreferences
import dev.inkstone.app.handwriting.HandwritingScreen
import dev.inkstone.app.handwriting.HandwritingViewModel
import dev.inkstone.app.handwriting.ModelSpeedSpike
import dev.inkstone.app.ink.EditorPreferences
import dev.inkstone.app.ink.EditorPrefs
import dev.inkstone.app.library.FolderCoverImage
import dev.inkstone.app.library.LibraryActions
import dev.inkstone.app.library.LibraryScreen
import dev.inkstone.app.library.LibraryUiState
import dev.inkstone.app.library.LibraryViewModel
import dev.inkstone.app.library.TrashScreen
import dev.inkstone.app.note.NoteActivity
import dev.inkstone.app.settings.ModelStatus
import dev.inkstone.app.settings.SettingsActions
import dev.inkstone.app.settings.SettingsScreen
import dev.inkstone.app.settings.SettingsUiState
import dev.inkstone.app.sync.AttentionActions
import dev.inkstone.app.sync.AttentionViewModel
import dev.inkstone.app.sync.NeedsAttentionScreen
import dev.inkstone.app.sync.PollTicker
import dev.inkstone.app.sync.SyncActions
import dev.inkstone.app.sync.SyncScreen
import dev.inkstone.app.sync.SyncUiState
import dev.inkstone.app.sync.SyncViewModel
import dev.inkstone.app.ui.DarkBars
import dev.inkstone.app.ui.InkstoneTheme
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.search.SearchHit
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
 * to survive a kill, and coming back to the library is exactly what should happen. Settings is
 * the same, for the same reason.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val library: LibraryViewModel by viewModels()
    private val sync: SyncViewModel by viewModels()

    /** Google's consent screen is an IntentSender Play Services hands us; this is where it returns. */
    private val googleConsent =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            sync.googleConsentResult(if (result.resultCode == RESULT_OK) result.data else null)
        }

    /** SPEC §7.7's list. A third screen, not a dialog — see [NeedsAttentionScreen]. */
    private val attention: AttentionViewModel by viewModels()

    /** Handwriting synthesis's *My handwriting*. Lazy: nothing is read until the screen is opened. */
    private val handwriting: HandwritingViewModel by viewModels()

    /**
     * SPEC §7.6's foreground poll (M6).
     *
     * Retained while this screen is started and released when it stops. WorkManager's periodic floor
     * is fifteen minutes, so the background job cannot be what makes a peer's edit appear while
     * somebody is looking at their library; this is.
     */
    @Inject
    lateinit var poll: PollTicker

    /** For the trash screen's "N days left", which is display-only time (SPEC §4.1). */
    @Inject
    lateinit var clock: Clock

    /**
     * M10 — the editor's remembered toolbar (docs/M10-DESIGN.md §10).
     *
     * WARNING: warmed in `onCreate` **before `setContent`**, so a deep link straight into a note
     * starts the read before the first composition rather than after it, and flushed in `onStop`
     * because that is the last callback Android guarantees before the process can be killed. It is
     * deliberately **not** warmed from `InkstoneApplication.onCreate`, which runs in every process
     * — including `:crashpoint`, which has no business touching settings.
     */
    @Inject
    lateinit var editorPreferences: EditorPreferences

    /** The calculator's remembered state (docs/CALCULATOR.md D4), flushed in `onStop` beside the toolbar's. */
    @Inject
    lateinit var calculatorPreferences: CalculatorPreferences

    /**
     * M12 §9 — *"a process killed mid-lecture leaves an orphaned recording. The next launch
     * finalises it and offers to process it rather than discarding it."*
     *
     * Here rather than in `InkstoneApplication.onCreate` for M6's reason: `onCreate` runs in
     * **every** process, and eager work there is what broke the app's ability to start in a
     * secondary one at all. This is the first screen of the default process, which is exactly
     * where "on next launch" means what it says.
     */
    @Inject
    lateinit var recordings: RecordingController

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
     * `READ_MEDIA_IMAGES` to set a folder icon would be wildly out of proportion.
     *
     * ⚠️ *"This app has no permissions at all today"* was true until M12, which adds
     * `RECORD_AUDIO` and `POST_NOTIFICATIONS` — both asked for at the first press of the record
     * button, never at launch. The argument here is unchanged and is if anything stronger: an app
     * that now asks for a microphone should not also be asking for the photo library.
     */
    private val pickCoverImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val folderId = pickingCoverFor ?: return@registerForActivityResult
        pickingCoverFor = null
        if (uri != null) importCoverImage(folderId, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = DarkBars, navigationBarStyle = DarkBars)
        super.onCreate(savedInstanceState)
        // ⛔ **A launcher tap must bring the app back, not stack a second library on top of it.**
        // The tablet's task on 2026-09-24 was MainActivity → NoteActivity → MainActivity →
        // MainActivity, the top two carrying the launcher's intent. AOSP only brings an existing
        // task forward when its *root* intent matches the launcher's; a task first started any
        // other way — the installer's "Open", `am start`, a notification — gets a fresh instance
        // of this activity on top of whatever is open, every time the icon is pressed. Each one is
        // a whole library (its view models, its hot flows, its thumbnail effects) and, worse, a
        // way to open a note that a live editor underneath already holds: two sessions over one
        // bundle, which is the data loss `NoteSessionHost` exists to prevent. So a launcher start
        // that is not the task root is a request to resume, and resuming is what finishing does.
        if (!isTaskRoot && intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish()
            return
        }
        pickingCoverFor = savedInstanceState?.getString(STATE_PICKING_COVER_FOR)?.let(::FolderId)
        // Only on a genuinely fresh start, not after a rotation: a saved instance state means this
        // process has been alive all along and anything interrupted has already been dealt with.
        if (savedInstanceState == null) recordings.recoverInterrupted()
        // The Settings screen edits the same `EditorPrefs` the editor saves, so the store has to be
        // read here too — the KDoc on [editorPreferences] said so before anything here needed it.
        editorPreferences.warm()
        setContent { InkstoneTheme { Content() } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pickingCoverFor?.let { outState.putString(STATE_PICKING_COVER_FOR, it.value) }
    }

    override fun onStart() {
        super.onStart()
        poll.retain()
    }

    override fun onStop() {
        // Paired with `retain`, and counted rather than a plain stop: opening a note runs
        // `NoteActivity.onStart` *before* this, so a boolean would leave the editor unpolled.
        poll.release()
        // The palette is only editable from the editor today. Flushed here anyway, because a flush
        // that exists on one screen is a flush somebody has to remember to add to the next one.
        editorPreferences.flush()
        calculatorPreferences.flush()
        super.onStop()
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
        val libraryState by library.state.collectAsState()
        val syncState by sync.state.collectAsState()
        val snackbars = remember { SnackbarHostState() }
        var showing by rememberSaveable { mutableStateOf(Showing.LIBRARY) }
        // Where Back from the trash, sync or attention screen goes: the library, or Settings when
        // that is where the user came from. A Back that drops somebody where they had not been is
        // the "random buttons everywhere" feeling the Settings screen exists to remove.
        var backTo by rememberSaveable { mutableStateOf(Showing.LIBRARY) }

        LaunchedEffect(libraryState.message) {
            libraryState.message?.let {
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

        // docs/CLOUD-PROVIDERS.md §5.1 — the OneDrive sign-in page. The result does not come back through
        // here: it arrives at OAuthRedirectActivity, which may be a different process by then.
        LaunchedEffect(syncState.openUrl) {
            syncState.openUrl?.let { url ->
                sync.urlOpened()
                openLoginPage(url)
            }
        }

        // docs/CLOUD-PROVIDERS.md §5.2 - Google's consent screen, launched once and consumed.
        LaunchedEffect(syncState.googleConsent) {
            syncState.googleConsent?.let { pending ->
                sync.googleConsentShown()
                googleConsent.launch(IntentSenderRequest.Builder(pending).build())
            }
        }

        // A PDF adopted off the server opens straight away — and the Sync screen is left standing
        // behind it holding the sentence that says where the ink will go, because the editor
        // arriving over a snackbar is how that sentence gets dismissed unread (SPEC §5.6 point 4,
        // the same reason a *recovery* does not open at all).
        LaunchedEffect(syncState.openNote) {
            syncState.openNote?.let { noteId ->
                sync.noteOpened()
                openNote(noteId)
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbars) },
        ) { innerPadding ->
            Screens(
                padding = Modifier.padding(innerPadding),
                showing = showing,
                backTo = backTo,
                onShow = { next ->
                    // Leaving the library or Settings for a sub-screen records which it was.
                    if (showing == Showing.LIBRARY || showing == Showing.SETTINGS) backTo = showing
                    if (next == Showing.LIBRARY) backTo = Showing.LIBRARY
                    showing = next
                },
                libraryState = libraryState,
                syncState = syncState,
            )
        }
    }

    /**
     * Which of the four screens is in front.
     *
     * ⭐ One value rather than three `Boolean`s (M6). Three independent flags describe eight states
     * for four screens, and the `when` that reads them makes the extra four unreachable by *order* —
     * so "show the trash" while the sync screen is up silently does nothing. It also means adding a
     * fifth screen is one enum entry rather than a fourth flag and a fourth ordering decision.
     *
     * `rememberSaveable` keeps it across a rotation and across process death, which is why it is an
     * enum and not a sealed class: an enum is `Serializable`, which Compose's default saver already
     * accepts, so there is no custom `Saver` to keep in step with the cases.
     */
    private enum class Showing { LIBRARY, TRASH, SYNC, ATTENTION, SETTINGS, HANDWRITING }

    /**
     * The four screens, extracted so [Content] stays about *state* and this stays about *routing*.
     *
     * @param padding the `Scaffold`'s inner padding, already wrapped, because every arm applies it
     *   and passing the raw values would let one of them forget.
     */
    @Composable
    private fun Screens(
        padding: Modifier,
        showing: Showing,
        /** Where the sub-screens' Back goes — see `backTo` in [Content]. */
        backTo: Showing,
        onShow: (Showing) -> Unit,
        libraryState: LibraryUiState,
        syncState: SyncUiState,
    ) {
        // Collected here rather than in [Content] because only one arm of the `when` reads either,
        // so a screen the user is not on costs no subscription.
        val trash by library.trash.collectAsState()
        val attentionState by attention.state.collectAsState()

        when (showing) {
            Showing.TRASH -> TrashScreen(
                state = trash,
                nowEpochMillis = clock.nowEpochMillis(),
                onRestoreNote = library::restoreNote,
                onRestoreFolder = library::restoreFolder,
                onBack = { onShow(backTo) },
                modifier = padding,
            )

            Showing.SYNC -> SyncScreen(
                state = syncState,
                onBack = { onShow(backTo) },
                actions = syncActions(),
                modifier = padding,
            )

            // SPEC §7.7's list, and CLAUDE.md rule 6's whole point: a *screen*, reached
            // deliberately, never a dialog raised over whatever the user was doing.
            Showing.ATTENTION -> NeedsAttentionScreen(
                state = attentionState,
                actions = AttentionActions(
                    onAcknowledge = attention::acknowledge,
                    onOpenNote = ::openNote,
                    onBack = { onShow(backTo) },
                ),
                modifier = padding,
            )

            Showing.HANDWRITING ->
                HandwritingScreen(handwriting, library::downloadRecognitionModel, { onShow(backTo) }, padding)

            Showing.SETTINGS -> SettingsArm(padding, onShow, libraryState, syncState)

            Showing.LIBRARY -> LibraryScreen(
                state = libraryState,
                modifier = padding,
                actions = libraryActions(
                    autoRecordFolders = libraryState.autoRecordFolders,
                    onOpenTrash = { onShow(Showing.TRASH) },
                    onOpenSync = { noteId ->
                        sync.open(noteId)
                        onShow(Showing.SYNC)
                    },
                    onOpenSyncForFolder = { folderId ->
                        sync.openForFolder(folderId)
                        onShow(Showing.SYNC)
                    },
                    onMoveSyncedNote = { noteId ->
                        sync.openForMove(noteId)
                        onShow(Showing.SYNC)
                    },
                    onOpenAttention = { onShow(Showing.ATTENTION) },
                    // A clean sync state, as the old `NEXTCLOUD` chip had: last week's "saved to…" is not news.
                    onOpenSettings = { openSettings(onShow) },
                    onImportFromNextcloud = {
                        // The folder on screen, so an adopted PDF is filed where the user is
                        // standing rather than at the root. It does not follow the file's remote
                        // path — see `LibraryRepository.adoptRemotePdf`.
                        sync.openForPdfImport(libraryState.folderId)
                        onShow(Showing.SYNC)
                    },
                ),
            )
        }
    }

    /**
     * The Settings screen, assembled from the view models this activity already holds.
     *
     * The writing options are local state seeded from [EditorPreferences.current] and re-seeded when
     * the store's first read lands, so a press made before then cannot save stock values over the
     * user's own (`SettingsScreen`).
     */
    @Composable
    private fun SettingsArm(
        padding: Modifier,
        onShow: (Showing) -> Unit,
        libraryState: LibraryUiState,
        syncState: SyncUiState,
    ) {
        val warmed by editorPreferences.snapshot.collectAsState()
        var prefs by remember(warmed) { mutableStateOf<EditorPrefs?>(editorPreferences.current()) }
        val model by library.recognitionModelStatus.collectAsState()
        SettingsScreen(
            state = SettingsUiState(
                accounts = syncState.accounts,
                outboxCount = syncState.outboxCount,
                pendingRecordings = syncState.pendingRecordings,
                attentionCount = libraryState.attentionCount,
                syncMessage = syncState.message,
                syncBusy = syncState.busy,
                prefs = prefs,
                model = ModelStatus(model.downloaded == true, model.downloading, model.error),
                versionName = versionName(),
            ),
            actions = SettingsActions(
                onBack = { onShow(Showing.LIBRARY) },
                onOpenNextcloud = {
                    sync.open(null)
                    onShow(Showing.SYNC)
                },
                onSyncNow = sync::syncNow,
                onOpenAttention = { onShow(Showing.ATTENTION) },
                onPrefsChange = { next ->
                    prefs = next
                    editorPreferences.changed(next)
                },
                onDownloadModel = library::downloadRecognitionModel,
                onOpenTrash = { onShow(Showing.TRASH) },
                onFillForMeasurement = library::fillForMeasurement,
                onOpenHandwriting = { onShow(Showing.HANDWRITING) },
                onMeasureHandwritingModel = { lifecycleScope.launch(Dispatchers.Default) { ModelSpeedSpike.run() } },
            ),
            modifier = padding,
        )
    }

    private fun openSettings(onShow: (Showing) -> Unit) {
        sync.open(null)
        onShow(Showing.SETTINGS)
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    private fun syncActions() = SyncActions(
        onServerUrlChange = sync::setServerUrl,
        onStartLogin = sync::startLogin,
        onCancelLogin = sync::cancelLogin,
        onSignOut = sync::signOut,
        onSignInAgain = sync::signInAgain,
        onForgetAccount = sync::forgetAccount,
        onChooseAccount = sync::chooseAccount,
        onBrowseTo = sync::browseTo,
        onGoUp = sync::goUp,
        onCreateFolder = sync::createFolder,
        onSetDefaultFolderHere = sync::setDefaultFolderHere,
        onBindHere = sync::bindHere,
        onMoveHere = sync::moveHere,
        onPinFolderHere = sync::pinFolderHere,
        onPickPdf = sync::pickPdf,
        onCancelBrowse = sync::cancelBrowse,
        onSyncNow = sync::syncNow,
        onSignInOneDrive = { sync.startOneDriveLogin() },
        onSignInGoogle = { sync.startGoogleLogin() },
        oneDriveConfigured = sync.oneDriveConfigured,
        oneDriveRedirect = sync.oneDriveRedirect,
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

    @Suppress("LongParameterList") // One per thing the library screen routes somewhere else.
    private fun libraryActions(
        /** M12 §10 — read off the state the screen already holds, so a menu costs no query. */
        autoRecordFolders: Map<String, Boolean>,
        onOpenTrash: () -> Unit,
        onOpenSync: (DocumentId?) -> Unit,
        onOpenSyncForFolder: (FolderId) -> Unit,
        onMoveSyncedNote: (DocumentId) -> Unit,
        onOpenAttention: () -> Unit,
        onOpenSettings: () -> Unit,
        onImportFromNextcloud: () -> Unit,
    ) = LibraryActions(
        onOpenAttention = onOpenAttention,
        onOpenNote = ::openNote,
        onOpenHit = ::openHit,
        onDownloadModel = library::downloadRecognitionModel,
        onDismissModelPanel = library::dismissModelPanel,
        onOpenFolder = library::openFolder,
        onGoUp = { library.goUp() },
        onNewNote = { library.createNote(::openNote) },
        // Opened only on a clean import. A recovery reports what it lost first
        // (SPEC §5.6 point 4), and jumping into the editor is how that gets dismissed
        // unread — `importPdf` decides, not this.
        onImportPdf = { uri -> library.importPdf(uri, ::openNote) },
        onImportFromNextcloud = onImportFromNextcloud,
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
        onChangeSyncFolder = { noteId -> onMoveSyncedNote(noteId) },
        onSyncFolderToNextcloud = { folderId -> onOpenSyncForFolder(folderId) },
        // M12 §10 — the per-folder setting, on the folder's own menu.
        autoRecordFolders = autoRecordFolders,
        onSetAutoRecord = library::setAutoRecord,
        onStopSyncing = sync::unbind,
        onOpenSettings = onOpenSettings,
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

    /** SPEC §8.4 — *"a tap scrolls to and highlights the word"*. */
    private fun openHit(hit: SearchHit) {
        val page = hit.pageId ?: return openNote(hit.noteId)
        val box = hit.bounds?.let { floatArrayOf(it.left, it.top, it.right, it.bottom) }
        startActivity(
            Intent(this, NoteActivity::class.java)
                .putExtra(NoteActivity.EXTRA_NOTE_ID, hit.noteId.value)
                .putExtra(NoteActivity.EXTRA_PAGE_ID, page.value)
                .putExtra(NoteActivity.EXTRA_HIT_BOX, box),
        )
    }
}
