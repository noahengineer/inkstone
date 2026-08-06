package dev.inkstone.app.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.inkstone.app.library.LibraryRepository
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.RemoteName
import dev.inkstone.core.model.WorkScheduler
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.DavResource
import dev.inkstone.core.sync.LoginFlowResult
import dev.inkstone.core.sync.LoginFlowStart
import dev.inkstone.core.sync.OutboxStore
import dev.inkstone.core.sync.SyncAccount
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val accounts: NextcloudAccounts,
    private val library: LibraryRepository,
    private val outbox: OutboxStore,
    private val scheduler: WorkScheduler,
) : ViewModel() {

    private val mutable = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = mutable.asStateFlow()

    private var pollingJob: Job? = null

    init {
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

    fun refresh() {
        viewModelScope.launch {
            val known = accounts.all()
            mutable.update {
                it.copy(
                    accounts = known,
                    outboxCount = outbox.count(),
                    // One account and a note to bind: skip straight to the folder browser.
                    // SPEC §7.6 — "a default folder per account makes step 2 one tap".
                    browser = it.browser,
                )
            }
            val single = known.singleOrNull()
            if (mutable.value.bindingNote != null && mutable.value.browser == null && single?.isUsable == true) {
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
            accounts.signOut(accountId)
            mutable.update { it.copy(message = "Signed out. The app password has been revoked.") }
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
                    it.copy(busy = false, browser = RemoteBrowser(accountId, path, result.folders), message = null)
                }

                is NextcloudAccounts.BrowseResult.Failed -> mutable.update {
                    // The folder may simply not exist yet — the account's default is created
                    // by the MKCOL the binding enqueues. Showing it as empty is the truthful
                    // and useful answer; the note still binds there.
                    it.copy(
                        busy = false,
                        browser = RemoteBrowser(accountId, path, emptyList()),
                        message = result.reason,
                    )
                }

                NextcloudAccounts.BrowseResult.Unavailable -> mutable.update {
                    it.copy(busy = false, message = "That account needs signing in again.")
                }
            }
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
            if (accounts.createRemoteFolder(browser.accountId, path)) {
                browseTo(browser.accountId, path)
            } else {
                mutable.update { it.copy(message = "Could not create “$legal” on the server.") }
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
        mutable.update { it.copy(browser = null, bindingNote = null) }
    }

    fun unbind(noteId: DocumentId) {
        viewModelScope.launch {
            library.unbindFromNextcloud(noteId)
            mutable.update { it.copy(message = "Stopped syncing. The copy on the server was left alone.") }
            refresh()
        }
    }

    /** "Sync now". Still goes through the outbox — this only wakes the drainer. */
    fun syncNow() {
        scheduler.enqueueOutboxDrain()
        mutable.update { it.copy(message = "Syncing…") }
    }

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
    val browser: RemoteBrowser? = null,
    val bindingNote: DocumentId? = null,
    val outboxCount: Int = 0,
) {
    val needsReauth: List<SyncAccount> get() = accounts.filter { it.state == AccountState.NEEDS_REAUTH }
}

/** One level of the remote tree (PROPFIND `Depth: 1`). */
data class RemoteBrowser(val accountId: String, val path: String, val folders: List<DavResource>)
