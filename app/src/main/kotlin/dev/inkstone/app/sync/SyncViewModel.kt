package dev.inkstone.app.sync

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.inkstone.android.pdf.PdfOpenResult
import dev.inkstone.app.library.LibraryRepository
import dev.inkstone.app.library.RemotePdfAdoption
import dev.inkstone.app.library.RemotePdfPick
import dev.inkstone.core.database.recording.RecordingIndex
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.RemoteName
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.DavResource
import dev.inkstone.core.sync.LoginFlowResult
import dev.inkstone.core.sync.LoginFlowStart
import dev.inkstone.core.sync.OutboxStore
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.RemoteVersion
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.SyncEngine
import dev.inkstone.core.sync.SyncNowReport
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SPEC §7.1 and §7.6 as a screen: sign in, pick a folder, bind a note.
 *
 * ## The login is a long-running job, not a request
 *
 * SPEC §7.1: the poll runs for up to **1200 s** and a 404 is indistinguishable from
 * "expired" — so the client owns the deadline. [pollingJob] is that loop, and cancelling
 * it is the only way out other than approving or timing out. It lives in
 * `viewModelScope`, so backing out of the screen ends it rather than leaving a coroutine
 * polling somebody's server for twenty minutes.
 */
// TooManyFunctions: this screen is the app's whole Nextcloud surface — sign in, sign out, browse,
// bind, pin, move, adopt a file, sync now — and each of those is one press with one method behind
// it. Splitting it by count rather than by meaning would put half a flow in another class.
@Suppress("TooManyFunctions")
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val accounts: NextcloudAccounts,
    /** docs/CLOUD-PROVIDERS.md §5 — the OneDrive sign-in. Everything else reaches it through [accounts]. */
    private val oneDrive: OneDriveAccounts,
    /** docs/CLOUD-PROVIDERS.md §5.2 - the Google Drive sign-in, which is Play Services and has no redirect. */
    private val google: GoogleDriveAccounts,
    private val library: LibraryRepository,
    private val outbox: OutboxStore,
    /** M12 §11 — how many lectures are waiting, and how much of the tablet they hold. */
    private val recordings: RecordingIndex,
    /** ⚠️ For [syncNow] only. "Sync now" has to be able to *repair* the queue, not just poke it. */
    private val sync: SyncEngine,
) : ViewModel() {

    private val mutable = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = mutable.asStateFlow()

    private var pollingJob: Job? = null

    /** Static for the life of the install, so they travel in `SyncActions` rather than in resettable state. */
    val oneDriveConfigured: Boolean get() = oneDrive.configured

    /** The redirect URI this install must be registered under; shown so it can be copied, not derived. */
    val oneDriveRedirect: String get() = oneDrive.redirectUri

    init {
        // The OneDrive sign-in finishes in OneDriveAccounts' own scope (the redirect can arrive in a
        // restarted process), and says so here. Consumed on read so it is shown once.
        viewModelScope.launch {
            oneDrive.notice.collect { notice ->
                if (notice != null) {
                    mutable.update { it.copy(busy = false, message = notice.message) }
                    oneDrive.noticeShown()
                    refresh()
                }
            }
        }
        // SPEC §0.1 #3 and §7.9, once per launch: finish any account whose `ocs.data.id`
        // was never fetched (the recoverable half of §7.1's rule 3 / rule 6 tension), and
        // fill in capabilities for any account that has none. Both are no-ops on the
        // ordinary launch, and neither can start a retry loop — an account already flagged
        // NEEDS_REAUTH is skipped entirely (CLAUDE.md rule 5).
        viewModelScope.launch {
            accounts.repairAndRefresh()
            refresh()
        }
    }

    /**
     * @param noteId non-null when the screen was opened by *"Save to Nextcloud…"* on a
     *   particular note. The difference is what the folder browser's confirm button does:
     *   bind that note, or nothing at all.
     */
    fun open(noteId: DocumentId? = null) {
        mutable.update { SyncUiState(bindingNote = noteId) }
        refresh()
    }

    /**
     * ⭐ Opened by a folder's *"Always sync to…"* (M6).
     *
     * The same folder browser the note flow uses, confirming into a **pin** rather than a
     * binding: everything in this folder and every folder beneath it syncs under the chosen
     * path from then on (SPEC §9's mirror).
     */
    fun openForFolder(folderId: FolderId) {
        mutable.update { SyncUiState(pinningFolder = folderId) }
        refresh()
    }

    /**
     * Pin the folder being configured to the folder being browsed — and sync what is in it.
     *
     * Both halves, in one action. The first version recorded the pin and stopped, which meant
     * the user pinned a folder, looked at their Nextcloud, and found nothing there: a pin is
     * only *consulted* when a note is bound, and nothing bound anything. See
     * [LibraryRepository.syncFolderToRemote].
     */
    fun pinFolderHere() {
        val folderId = mutable.value.pinningFolder ?: return
        val browser = mutable.value.browser ?: return
        viewModelScope.launch {
            val result = library.syncFolderToRemote(folderId, browser.accountId, browser.path)
            val where = browser.path.ifEmpty { "the top level of your Nextcloud" }
            mutable.update {
                it.copy(
                    pinningFolder = null,
                    browser = null,
                    // Says what it did, not just what it will do. A silent success reads exactly
                    // like the bug this replaced.
                    message = buildString {
                        append("Notes in this folder — and any folder inside it — will sync to $where.")
                        if (result.newlyBound > 0) {
                            append(" Uploading ${result.newlyBound} ")
                            append(if (result.newlyBound == 1) "note" else "notes")
                            append(".")
                        }
                        if (result.movedOnServer > 0) {
                            append(" Moving ${result.movedOnServer} already on the server.")
                        }
                        if (result.total == 0) append(" Nothing in it yet — new notes will sync automatically.")
                    },
                )
            }
            refresh()
        }
    }

    /**
     * ⭐ **Opened by *"Import → From Nextcloud"* — pick a PDF that is already on the server and
     * write on it.**
     *
     * The same browser as every other flow, with the files drawn as well as the folders. The
     * listing always carried them (one PROPFIND, SPEC §7.3); [RemoteBrowser.pickingPdf] is what
     * decides whether they are shown, so a user choosing where to save a note is not offered a
     * file to save it into.
     *
     * There is no confirm button. Tapping a folder navigates and tapping a PDF *is* the action —
     * a "choose here" chip would have nothing to mean, since what is being chosen is the file
     * under the finger and not the folder in the breadcrumb.
     */
    fun openForPdfImport(intoFolder: FolderId? = null) {
        mutable.update { SyncUiState(pickingPdf = true, pickingInto = intoFolder) }
        refresh()
    }

    /**
     * Opened by *"Change sync folder…"* on a note that is already synced.
     *
     * The same browser as binding, confirming into a `MOVE` rather than a new binding — which is
     * what stops "relocate a note" meaning "leave a copy behind and make another".
     */
    fun openForMove(noteId: DocumentId) {
        mutable.update { SyncUiState(movingNote = noteId) }
        refresh()
    }

    /** Move the synced copy to the folder being browsed. One MOVE; nothing is duplicated. */
    fun moveHere() {
        val noteId = mutable.value.movingNote ?: return
        val browser = mutable.value.browser ?: return
        viewModelScope.launch {
            val moved = library.moveRemoteTo(noteId, browser.path)
            mutable.update {
                it.copy(
                    movingNote = null,
                    browser = null,
                    message = if (moved == null) {
                        "That note is not synced, so there is nothing to move."
                    } else {
                        "Moving to $moved. The old copy is not left behind."
                    },
                )
            }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val known = accounts.all()
            // M12 §11. A count and a size, not the list: this screen draws two words.
            val pending = recordings.pendingSummary().first()
            mutable.update {
                it.copy(
                    accounts = known,
                    outboxCount = outbox.count(),
                    pendingRecordings = pending.count,
                    pendingRecordingBytes = pending.bytes,
                    // One account and a note to bind: skip straight to the folder browser.
                    // SPEC §7.6 — "a default folder per account makes step 2 one tap".
                    browser = it.browser,
                )
            }
            // "One usable account", not "one account". A remembered signed-out account now sits
            // in the list beside the live one (M6), and `singleOrNull` over the whole list
            // would have quietly stopped skipping to the folder browser the moment a second
            // row existed — losing SPEC §7.6's "a default folder per account makes step 2 one
            // tap" for exactly the users who had signed out and back in.
            val single = mutable.value.usable.singleOrNull()
            val wants = mutable.value.bindingNote != null ||
                mutable.value.pinningFolder != null ||
                mutable.value.movingNote != null ||
                // Picking a PDF to write on wants it most of all: with one account, "From
                // Nextcloud" should land on the user's files. Without this it lands on an
                // account list, which is a settings screen and reads as the wrong button.
                mutable.value.pickingPdf
            if (wants && mutable.value.browser == null && single != null) {
                chooseAccount(single.id)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Sign in — SPEC §7.1
    // -----------------------------------------------------------------------

    fun setServerUrl(value: String) {
        mutable.update { it.copy(serverUrl = value) }
    }

    /**
     * ⭐ Sign back in to an account we already remember — one tap, no typing.
     *
     * The account row survives a sign-out (see [AccountState.SIGNED_OUT]), so the server URL
     * is already known. That is the whole difference between this and [startLogin]: the
     * browser approval still happens, because SPEC §7.1 is the only way to get an app
     * password and there is no shortcut worth having, but the user does not re-enter a URL on
     * a tablet keyboard and does not re-bind a single note afterwards.
     */
    fun signInAgain(accountId: String) {
        val account = mutable.value.accounts.firstOrNull { it.id == accountId } ?: return
        when (account.provider) {
            ProviderKind.ONEDRIVE -> {
                startOneDriveLogin(accountId)
                return
            }

            ProviderKind.GOOGLE_DRIVE -> {
                startGoogleLogin(accountId)
                return
            }

            else -> Unit
        }
        mutable.update { it.copy(serverUrl = account.serverUrl) }
        startLogin()
    }

    /**
     * Step one of the OneDrive sign-in: the URL goes to a Custom Tab, and the answer comes back through
     * `OAuthRedirectActivity`, not through this call. Nothing polls — unlike Nextcloud's Login Flow —
     * so there is no dialog to cancel and nothing left running if the user walks away.
     *
     * @param accountId set to sign in *again* to a known account, which then refuses a different identity.
     */
    fun startOneDriveLogin(accountId: String? = null) {
        viewModelScope.launch {
            val url = oneDrive.begin(accountId)
            mutable.update {
                if (url == null) {
                    it.copy(
                        message = "This build of Inkstone was not given a Microsoft app registration, " +
                            "so it cannot sign in to OneDrive.",
                    )
                } else {
                    it.copy(message = null, openUrl = url)
                }
            }
        }
    }

    /**
     * Step one of the Google Drive sign-in. Play Services either answers at once (the grant stands, so the
     * account appears) or hands back a consent screen for the Activity to show; the result comes back
     * through [googleConsentResult], not through this call.
     */
    fun startGoogleLogin(accountId: String? = null) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null) }
            when (val begun = google.begin(accountId)) {
                is GoogleDriveAccounts.Begin.Done -> {
                    mutable.update { it.copy(busy = false, message = begun.notice.message) }
                    refresh()
                }

                is GoogleDriveAccounts.Begin.Consent ->
                    mutable.update { it.copy(busy = false, googleConsent = begun.request as? PendingIntent) }
            }
        }
    }

    /** The Activity is showing Google's consent screen; do not show it again on the next recomposition. */
    fun googleConsentShown() {
        mutable.update { it.copy(googleConsent = null) }
    }

    /** Google's consent screen closed. [data] is null when the user backed out. */
    fun googleConsentResult(data: Intent?) {
        viewModelScope.launch {
            val notice = google.complete(data)
            mutable.update { it.copy(busy = false, message = notice.message) }
            refresh()
        }
    }

    /** The Activity has opened the sign-in page; do not open it again on the next recomposition. */
    fun urlOpened() {
        mutable.update { it.copy(openUrl = null) }
    }

    /**
     * Step one, then hand the browser URL to the Activity.
     *
     * The polling loop starts immediately and runs in parallel with the user being in the
     * browser. It has to: SPEC §7.1's 200 is delivered **once** and the server deletes its
     * row straight after, so there is no "check when they come back" to fall back on.
     */
    fun startLogin() {
        val server = mutable.value.serverUrl.trim()
        if (server.isEmpty()) return
        pollingJob?.cancel()
        mutable.update { it.copy(busy = true, message = null) }

        pollingJob = viewModelScope.launch {
            when (val begun = accounts.beginLogin(server)) {
                is LoginFlowResult.Success -> {
                    mutable.update { it.copy(busy = false, pendingLogin = begun.value, openInBrowser = begun.value) }
                    awaitApproval(begun.value)
                }

                is LoginFlowResult.Insecure -> fail(
                    "Inkstone only connects over HTTPS. ${begun.serverUrl} is not, and there is " +
                        "deliberately no way to accept it anyway.",
                )

                is LoginFlowResult.Failed -> fail(begun.reason)

                is LoginFlowResult.TimedOut -> fail(begun.advice)

                is LoginFlowResult.NeedsUid -> fail("The server did not identify the account.")
            }
        }
    }

    private suspend fun awaitApproval(start: LoginFlowStart) {
        when (val result = accounts.completeLogin(start)) {
            is NextcloudAccounts.LoginResult.SignedIn -> {
                mutable.update { it.copy(pendingLogin = null, message = "Signed in as ${result.account.loginName}.") }
                refresh()
                if (mutable.value.bindingNote != null) chooseAccount(result.account.id)
            }

            // SPEC §7.1 rule 3 — the app password IS saved. Only the WebDAV path segment
            // is missing, and the next launch resolves it (SPEC §0.1 #3).
            is NextcloudAccounts.LoginResult.NeedsUid -> {
                mutable.update {
                    it.copy(
                        pendingLogin = null,
                        message = "Signed in, but the server did not answer with the account id yet. " +
                            "Inkstone will finish this the next time it can reach the server.",
                    )
                }
                refresh()
            }

            is NextcloudAccounts.LoginResult.Failed -> fail(result.reason)
        }
    }

    fun cancelLogin() {
        pollingJob?.cancel()
        pollingJob = null
        mutable.update { it.copy(pendingLogin = null, busy = false) }
    }

    /** The Activity has launched the browser; do not launch it again on the next recomposition. */
    fun browserOpened() {
        mutable.update { it.copy(openInBrowser = null) }
    }

    fun signOut(accountId: String) {
        viewModelScope.launch {
            val signedOut = mutable.value.accounts.firstOrNull { it.id == accountId }
            val oneDriveAccount = signedOut?.takeIf { it.provider == ProviderKind.ONEDRIVE }
            val googleAccount = signedOut?.takeIf { it.provider == ProviderKind.GOOGLE_DRIVE }
            val result = accounts.signOut(accountId)
            mutable.update {
                it.copy(
                    // Microsoft offers a public client no way to revoke a refresh token, so this must not
                    // claim to have: the grant is forgotten here and can be removed at its source.
                    message = if (googleAccount != null) {
                        "Signed out of Google Drive. Inkstone's permission was withdrawn; if that could not be " +
                            "reached, remove it at myaccount.google.com/permissions."
                    } else if (oneDriveAccount != null) {
                        "Signed out of OneDrive on this device. To remove Inkstone's access entirely, " +
                            "use account.microsoft.com → Privacy → Apps and services. " +
                            if (result.pausedNotes == 0) {
                                ""
                            } else {
                                "${result.pausedNotes} linked notes will resume when you sign in again."
                            }
                    } else if (result.pausedNotes == 0) {
                        // Says what was kept, not just what was revoked. A sign-out that only
                        // mentions the revocation reads as "everything is gone", which is what
                        // this used to actually mean.
                        "Signed out. The app password has been revoked."
                    } else {
                        "Signed out — app password revoked. ${result.pausedNotes} " +
                            "${if (result.pausedNotes == 1) "note stays" else "notes stay"} linked and will " +
                            "resume when you sign in again."
                    },
                )
            }
            refresh()
        }
    }

    /**
     * Forget an account for good, and unbind its notes.
     *
     * Kept distinct from [signOut] in the UI as well as the model, because only one of them is
     * reversible and the difference is invisible once it has happened.
     */
    fun forgetAccount(accountId: String) {
        viewModelScope.launch {
            accounts.forgetAccount(accountId)
            mutable.update {
                it.copy(
                    message = "Account removed. Your notes are still here, and the copies on " +
                        "that server were left alone.",
                )
            }
            refresh()
        }
    }

    // -----------------------------------------------------------------------
    // Folder browser — SPEC §7.6 step 2
    // -----------------------------------------------------------------------

    fun chooseAccount(accountId: String) {
        val account = mutable.value.accounts.firstOrNull { it.id == accountId }
        // SPEC §7.6 — one tap: start in the account's default folder rather than at the root.
        browseTo(accountId, account?.defaultFolder.orEmpty())
    }

    fun browseTo(accountId: String, path: String) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            when (val result = accounts.browse(accountId, path)) {
                is NextcloudAccounts.BrowseResult.Listing -> mutable.update {
                    it.copy(
                        busy = false,
                        browser = RemoteBrowser(
                            accountId = accountId,
                            path = result.path,
                            folders = result.folders,
                            pdfs = result.pdfs,
                            pickingPdf = it.pickingPdf,
                            remembered = result.remembered,
                        ),
                        // ⭐ Not an error line. A remembered listing is a working browser, and
                        // saying so twice — once as chrome and once as a red line — reads as a
                        // failure the user has to do something about. The chrome says it.
                        message = null,
                    )
                }

                is NextcloudAccounts.BrowseResult.Failed -> mutable.update {
                    // The folder may simply not exist yet — the account's default is created
                    // by the MKCOL the binding enqueues. Showing it as empty is the truthful
                    // and useful answer; the note still binds there.
                    it.copy(
                        busy = false,
                        browser = RemoteBrowser(accountId, path, emptyList(), pickingPdf = it.pickingPdf),
                        message = result.reason,
                    )
                }

                NextcloudAccounts.BrowseResult.Unavailable -> mutable.update {
                    it.copy(busy = false, message = "That account needs signing in again.")
                }
            }
        }
    }

    /**
     * "Always save here" — make the folder currently being browsed this account's default.
     *
     * The one thing that turns the folder browser from "navigate out of Inkstone every time"
     * into "my notes go where I keep my documents". Nothing already on the server moves.
     */
    fun setDefaultFolderHere() {
        val browser = mutable.value.browser ?: return
        viewModelScope.launch {
            accounts.setDefaultFolder(browser.accountId, browser.path)
            mutable.update {
                it.copy(
                    message = if (browser.path.isEmpty()) {
                        "New notes will be saved to the top level of your Nextcloud."
                    } else {
                        "New notes will be saved to ${browser.path}."
                    },
                )
            }
            refresh()
        }
    }

    fun goUp() {
        val browser = mutable.value.browser ?: return
        if (browser.path.isEmpty()) return
        browseTo(browser.accountId, browser.path.substringBeforeLast('/', ""))
    }

    fun createFolder(name: String) {
        val browser = mutable.value.browser ?: return
        val legal = RemoteName.sanitise(name)
        val path = if (browser.path.isEmpty()) legal else "${browser.path}/$legal"
        viewModelScope.launch {
            when (val result = accounts.createRemoteFolder(browser.accountId, path)) {
                NextcloudAccounts.FolderCreateResult.Created -> browseTo(browser.accountId, path)

                // ⭐ No network is not a refusal. The folder is remembered, the browser walks
                // into it, and the MKCOL that creates it for real is the one the binding
                // enqueues — so the whole *"new folder, save it there"* flow works offline.
                NextcloudAccounts.FolderCreateResult.Deferred -> {
                    browseTo(browser.accountId, path)
                    mutable.update {
                        it.copy(
                            message = "“$legal” will be created on the server the first time " +
                                "something is saved into it.",
                        )
                    }
                }

                is NextcloudAccounts.FolderCreateResult.Failed -> mutable.update {
                    it.copy(message = "Could not create “$legal” on the server. ${result.reason}")
                }
            }
        }
    }

    /**
     * SPEC §7.6 steps 3–5 — create the binding and enqueue the work.
     *
     * Returns immediately whether or not there is a network. **The note is fully editable
     * throughout**, because this writes three rows and nudges WorkManager; nothing here
     * waits on the server.
     */
    fun bindHere() {
        val noteId = mutable.value.bindingNote ?: return
        val browser = mutable.value.browser ?: return
        viewModelScope.launch {
            val binding = library.bindToNextcloud(noteId, browser.accountId, browser.path)
            mutable.update {
                it.copy(
                    bindingNote = null,
                    browser = null,
                    message = if (binding == null) {
                        "Could not save that note to Nextcloud."
                    } else {
                        "Saving to ${binding.remotePath}. It will upload when there is a connection."
                    },
                )
            }
            refresh()
        }
    }

    /** Leave the folder browser without binding anything. Changes nothing, remotely or locally. */
    fun cancelBrowse() {
        mutable.update {
            it.copy(browser = null, bindingNote = null, movingNote = null, pinningFolder = null, pickingPdf = false)
        }
    }

    /**
     * ⭐ **Download the PDF the user tapped, import it, and bind the note to that same file.**
     *
     * The one place in the app that makes a *foreground* request and asks the user to wait for it.
     * Everything else is the outbox, deliberately (CLAUDE.md rule 4) — but the outbox exists so
     * that **writes** survive a dead network and a dead process, and this is a read whose whole
     * point is that the note opens now. Nothing remote is mutated here; the write that follows it
     * goes through the outbox like every other.
     *
     * ⚠️ **A read-only file is imported, not refused.** [RemotePdfPick] decides that off
     * `oc:permissions` before a byte moves: a shared handout the user may read and not write cannot
     * be annotated *in place*, and binding it anyway would 403 on every PUT, park for a day, and
     * present as a note that quietly never syncs. They get the note, and a sentence saying why it
     * will not write back.
     */
    fun pickPdf(file: DavResource) {
        val browser = mutable.value.browser ?: return
        if (mutable.value.busy) return
        when (val decision = RemotePdfPick.decide(file)) {
            RemotePdfPick.Decision.NotAFile -> browseTo(browser.accountId, file.path)

            RemotePdfPick.Decision.NotAPdf ->
                mutable.update { it.copy(message = "Only PDFs can be opened as notes.") }

            else -> downloadAndImport(
                accountId = browser.accountId,
                file = file,
                inPlace = decision == RemotePdfPick.Decision.AdoptInPlace,
            )
        }
    }

    private fun downloadAndImport(accountId: String, file: DavResource, inPlace: Boolean) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = "Getting ${file.name}…") }
            val fetched = when (val outcome = accounts.fetchPdf(accountId, file.path)) {
                is NextcloudAccounts.FetchResult.Downloaded -> outcome.file

                is NextcloudAccounts.FetchResult.Failed ->
                    return@launch fail("Could not download that file. ${outcome.reason}")

                NextcloudAccounts.FetchResult.Unavailable ->
                    return@launch fail("That account needs signing in again.")
            }
            val title = RemotePdfPick.titleOf(file)
            // ⚠️ The version off the **GET**, not off the listing — see `SyncEngine.bindAdopted`.
            val observed = RemoteVersion(
                etag = fetched.etag,
                ocId = fetched.ocId ?: file.ocId,
                checksum = null,
                syncedRev = null,
            )
            val outcome = if (inPlace) {
                library.adoptRemotePdf(
                    bytes = fetched.bytes,
                    title = title,
                    accountId = accountId,
                    remotePath = file.path,
                    observed = observed,
                    folderId = mutable.value.pickingInto,
                )
            } else {
                // Bound to nothing. The note is the user's; the file on the server is not theirs
                // to write to, and this app does not queue a PUT it knows will 403.
                when (val result = library.importPdf(fetched.bytes, title, mutable.value.pickingInto)) {
                    is PdfOpenResult.Opened -> RemotePdfAdoption.Unbound(result.documentId, result)
                    else -> RemotePdfAdoption.Refused(result)
                }
            }
            report(outcome, file, inPlace)
        }
    }

    /**
     * One sentence per outcome, and **an opened note only when there is nothing to read first**.
     *
     * The same rule `LibraryViewModel.importPdf` follows for a local file: a recovery report that
     * is dismissed by the editor opening over it has not been read (SPEC §5.6 point 4).
     */
    private fun report(outcome: RemotePdfAdoption, file: DavResource, inPlace: Boolean) {
        val message = when (outcome) {
            is RemotePdfAdoption.Adopted ->
                "Writing on ${file.name}. Your ink uploads back to /${outcome.remotePath}."

            is RemotePdfAdoption.AlreadySynced ->
                "That note is already in your library, and already syncing where it was."

            is RemotePdfAdoption.Recovered ->
                "That was an Inkstone note whose data did not survive. It has been recovered as far " +
                    "as possible and is not syncing — check it before you save it back."

            is RemotePdfAdoption.Unbound -> if (inPlace) {
                "Added ${file.name}, but it is not syncing — that account is unavailable."
            } else {
                "Added ${file.name} as a note. You may not write to that file on the server, so your " +
                    "ink stays here until you save the note to Nextcloud yourself."
            }

            is RemotePdfAdoption.Refused -> "That PDF could not be opened as a note."
        }
        val opens = outcome !is RemotePdfAdoption.Recovered
        mutable.update {
            it.copy(
                busy = false,
                browser = null,
                pickingPdf = false,
                message = message,
                openNote = outcome.noteId.takeIf { _ -> opens },
            )
        }
        refresh()
    }

    /** Consumed by the Activity, which owns navigation — the same handshake as [openInBrowser]. */
    fun noteOpened() {
        mutable.update { it.copy(openNote = null) }
    }

    fun unbind(noteId: DocumentId) {
        viewModelScope.launch {
            library.unbindFromNextcloud(noteId)
            mutable.update { it.copy(message = "Stopped syncing. The copy on the server was left alone.") }
            refresh()
        }
    }

    /**
     * "Sync now". Still goes through the outbox (CLAUDE.md rule 4) — it never writes directly.
     *
     * ⚠️ **Reconcile, then drain, then re-read.** All three, because the button was all talk.
     *
     * It used to be `enqueueOutboxDrain()` plus a hard-coded *"Syncing…"* — which is a no-op
     * against both ways a recording gets stuck (a park nothing could lift, and a row that was
     * never written; see [SyncEngine.syncNowRequested]) *and* invisible when it works, because
     * nothing re-read the counts afterwards. A press produced the same screen whether it had
     * uploaded a lecture or done absolutely nothing, which is how M12's first real upload
     * attempt looked identical to a healthy queue.
     *
     * [refresh] afterwards is what makes the header honest: if the recording count drops, it
     * worked; if it does not move, it genuinely did not, and that is now visible rather than
     * hidden behind a present participle that never cleared.
     */
    fun syncNow() {
        viewModelScope.launch {
            mutable.update { it.copy(message = "Syncing…") }
            val report = sync.syncNowRequested()
            refresh()
            mutable.update { it.copy(message = describe(report)) }
        }
    }

    /**
     * ⚠️ **Say which of the reasons applies, and what to do about it.**
     *
     * Every branch here is a distinct action for the user, and the two that matter are the two
     * that used to be silent: a recording in an unbound note can *never* upload, and one whose
     * account cannot send is waiting on a sign-in. Both looked exactly like a healthy queue.
     */
    private fun describe(report: SyncNowReport): String = when {
        report.recordingsUnbound > 0 -> {
            val n = report.recordingsUnbound
            "$n recording${plural(n)} ${isAre(n)} in a note that is not synced. Open the note in " +
                "your library and choose \"Save to Nextcloud…\" — nothing can upload until then."
        }

        report.recordingsNotSignedIn > 0 -> {
            val n = report.recordingsNotSignedIn
            "$n recording${plural(n)} cannot upload because this account is signed out or needs " +
                "signing in again."
        }

        report.recordingsQueued > 0 -> {
            val n = report.recordingsQueued
            "Uploading $n recording${plural(n)}." +
                if (report.parksLifted > 0) " Retrying ${report.parksLifted} that had been put off." else ""
        }

        report.outboxRows > 0 -> "Syncing ${report.outboxRows} pending change${plural(report.outboxRows)}."

        // ⚠️ Never "Syncing…" left on screen forever. "Nothing waiting" is a real answer and the
        // user is entitled to it; it is also the answer that tells them the problem is elsewhere.
        else -> "Everything is up to date."
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private fun isAre(n: Int) = if (n == 1) "is" else "are"

    fun dismissMessage() {
        mutable.update { it.copy(message = null) }
    }

    private fun fail(reason: String) {
        mutable.update { it.copy(busy = false, pendingLogin = null, message = reason) }
    }
}

data class SyncUiState(
    val accounts: List<SyncAccount> = emptyList(),
    val serverUrl: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    /** Non-null while SPEC §7.1's poll loop is running. */
    val pendingLogin: LoginFlowStart? = null,
    /** Set once, consumed by the Activity, which owns launching a browser. */
    val openInBrowser: LoginFlowStart? = null,
    /** The OneDrive sign-in URL, set once and consumed the same way. */
    val openUrl: String? = null,
    /** Google's consent screen, set once for the Activity to launch and consumed the same way. */
    val googleConsent: PendingIntent? = null,
    val browser: RemoteBrowser? = null,
    val bindingNote: DocumentId? = null,
    /** Non-null while a synced note's remote copy is being relocated (a MOVE, not a re-bind). */
    val movingNote: DocumentId? = null,
    /** Non-null while a *folder* is being pinned to a remote path (M6). */
    val pinningFolder: FolderId? = null,
    /** True while the browser is being used to pick a PDF to write on, which is what shows files. */
    val pickingPdf: Boolean = false,
    /**
     * Which library folder an adopted PDF is filed into — the one the user was looking at.
     *
     * Null is the root, and null is also what every other flow leaves it as. It says nothing about
     * where the file lives on the server: an adopted note is bound to a path the user chose long
     * before, and SPEC §9's mirror does not reach backwards into it.
     */
    val pickingInto: FolderId? = null,
    /** Set once by an import, consumed by the Activity, which owns opening a note. */
    val openNote: DocumentId? = null,
    val outboxCount: Int = 0,
    /**
     * ⭐ M12 §11 — *"Five lectures a day and a week offline is ~275 MB of audio waiting on the
     * tablet. That must be **visible** — pending recordings and their sizes, somewhere reachable —
     * rather than discovered when the device fills up."*
     *
     * Here rather than on the library screen because this is the screen that answers *"what does
     * this device still owe the server"*, and a lecture is the largest thing on that list by three
     * orders of magnitude.
     */
    val pendingRecordings: Int = 0,
    val pendingRecordingBytes: Long = 0L,
) {
    val needsReauth: List<SyncAccount> get() = accounts.filter { it.state == AccountState.NEEDS_REAUTH }

    /**
     * Accounts the user signed out of, which are remembered and one tap from working again
     * (M6 — see [AccountState.SIGNED_OUT]).
     */
    val signedOut: List<SyncAccount> get() = accounts.filter { it.state == AccountState.SIGNED_OUT }

    /** Accounts currently able to sync. What the folder browser and note binding can use. */
    val usable: List<SyncAccount> get() = accounts.filter { it.isUsable }
}

/**
 * One level of the remote tree (PROPFIND `Depth: 1`).
 *
 * [pdfs] is always populated — the listing carried it either way — and [pickingPdf] is what says
 * whether this browser is *for* them.
 */
data class RemoteBrowser(
    val accountId: String,
    val path: String,
    val folders: List<NextcloudAccounts.RemoteFolderEntry>,
    val pdfs: List<DavResource> = emptyList(),
    val pickingPdf: Boolean = false,
    /**
     * ⭐ Non-null when the server could not be reached and these folder names came out of the
     * cache instead.
     *
     * Everything the browser is *for* still works in this state — binding a note, pinning a
     * folder, setting the default — because none of those touch the network: they write rows and
     * enqueue MKCOL + UPLOAD. The one thing that does not is adopting a PDF, which is a GET, and
     * [pdfs] is empty here for exactly that reason.
     */
    val remembered: NextcloudAccounts.Remembered? = null,
)
