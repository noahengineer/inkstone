package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.NoteSyncState

/*
 * SPEC §7.6's schema, as the three ports :core:sync needs from persistence.
 *
 *   note(id, title, sync_state, local_rev, last_error, updated_at)
 *   remote_binding(note_id, account_id, remote_path, remote_oc_id,
 *                  last_synced_etag, last_synced_rev)
 *   outbox(note_id, op_type, local_rev_at_enqueue, attempts, next_attempt_at, created_at)
 *
 * They are interfaces here, implemented in :core:database, for two reasons. The protocol
 * and the policy are the part of this milestone that can be got wrong quietly, and they
 * should be testable without a device or a driver. And it fixes the dependency direction
 * as :core:database -> :core:sync, so the module that owns SQLDelight depends on the
 * module that owns the rules rather than the reverse.
 *
 * Block comment, not KDoc — a file-level KDoc attached to no declaration is a "dangling
 * toplevel KDoc" and ktlint cannot auto-correct it (the same note is on Platform.kt).
 */

/**
 * One Nextcloud account.
 *
 * The **app password is not here.** It lives in [dev.inkstone.core.model.CredentialStore],
 * which is Keystore-backed (SPEC §14), keyed by [id]. This row is the part that is safe
 * in a plain database: which server, which user, which folder by default.
 *
 * @property uid ⚠️ SPEC §0.1 #3 — the WebDAV path segment, from `ocs.data.id`. **Not**
 *   [loginName], which may be an email address. Empty means "not resolved yet", which
 *   [AccountState.NEEDS_UID] also says; see [LoginFlowV2] for why that state exists.
 * @property defaultFolder SPEC §7.6 — *"a default folder per account makes step 2 one
 *   tap"*. A remote path, already sanitised.
 */
public data class SyncAccount(
    val id: String,
    val serverUrl: String,
    val loginName: String,
    val uid: String,
    val displayName: String? = null,
    val defaultFolder: String = DEFAULT_FOLDER,
    val state: AccountState = AccountState.ACTIVE,
    val capabilities: NextcloudCapabilities? = null,
    val lastError: String? = null,
) {
    public val isUsable: Boolean get() = state == AccountState.ACTIVE && uid.isNotEmpty()

    public companion object {
        /** Where notes go when the user has not chosen. Created with MKCOL on first use. */
        public const val DEFAULT_FOLDER: String = "Inkstone"
    }
}

public enum class AccountState {
    ACTIVE,

    /**
     * SPEC §7.2 — a 401 was confirmed against `/ocs/v2.php/cloud/user` and the account
     * is halted. **Nothing is sent until the user logs in again** (CLAUDE.md rule 5).
     */
    NEEDS_REAUTH,

    /**
     * The app password is stored but `ocs.data.id` has not been fetched yet. Recoverable
     * on the next launch; unusable for WebDAV until it is (SPEC §0.1 #3).
     */
    NEEDS_UID,
}

/**
 * SPEC §7.6 — `remote_binding`. Where a note lives on the server, and what we last saw.
 *
 * @property remoteOcId ⚠️ `oc:id`, **not** `oc:fileid` (SPEC §7.3). Globally unique
 *   (`<padded-fileid><instanceid>`), which is what keeps this correct if the user ever
 *   adds a second instance.
 * @property lastSyncedEtag the ETag of the bytes we last wrote or read, unquoted. Null
 *   when nothing has been uploaded yet, and null-ish in effect when it was **weak** —
 *   see [ETag] and the `weak` flag, which is why this is an [ETag] and not a `String`.
 * @property lastSyncedRev the `local_rev` those bytes were built from. Together with
 *   `note.local_rev` this answers "is there anything to upload" without opening the note.
 * @property lastUploadAtEpochMillis SPEC §7.6 — the 30-second minimum inter-upload
 *   interval is measured from here.
 * @property lastChecksum MD5 hex of the bytes last uploaded, kept for the weak-ETag
 *   fallback (SPEC §7.3: *"treat the ETag as advisory and compare checksums instead of
 *   using `If-Match`"*).
 * @property movedFromPath where the file still is on the server, when a MOVE is owed.
 *   [remotePath] is always the *intended* path — the one the note is bound to — so that
 *   nothing downstream has to ask "which of these two is current". Null when no move is
 *   pending, which is almost always.
 */
public data class RemoteBinding(
    val noteId: DocumentId,
    val accountId: String,
    val remotePath: String,
    val remoteOcId: String? = null,
    val lastSyncedEtag: ETag? = null,
    val lastSyncedRev: Long = 0L,
    val lastUploadAtEpochMillis: Long = 0L,
    val lastChecksum: String? = null,
    val movedFromPath: String? = null,
) {
    /**
     * Whether anything of this note has ever reached the server.
     *
     * Two things turn on it: SPEC §7.6's *"if the note was ever uploaded, the remote
     * DELETE still runs"*, and whether a PUT carries `If-None-Match: *` (SPEC §7.5's
     * create).
     *
     * [lastUploadAtEpochMillis] is in the test and not only the ETag and the oc:id,
     * because a reverse proxy that strips `OC-ETag` and `OC-FileId` would otherwise leave
     * this false after a *successful* upload — and the next PUT would send
     * `If-None-Match: *` against a file that now exists, which 412s forever. The upload
     * timestamp is ours and nothing on the wire can remove it.
     */
    public val everUploaded: Boolean
        get() = remoteOcId != null || lastSyncedEtag != null || lastUploadAtEpochMillis > 0L
}

/**
 * The three stores, as one parameter.
 *
 * Not a namespace for its own sake: [OutboxDrainer] and [SyncEngine] each need all four,
 * and passing them individually put both constructors over detekt's parameter limit — which
 * is the linter noticing something true. These four always travel together, they always come
 * from the same database, and a caller that had three of them and not the fourth could not do
 * anything correct with them.
 */
public class SyncStores(
    public val accounts: AccountStore,
    public val bindings: BindingStore,
    public val outbox: OutboxStore,
    public val notes: SyncNotes,
)

public interface AccountStore {
    public suspend fun all(): List<SyncAccount>

    public suspend fun find(accountId: String): SyncAccount?

    public suspend fun upsert(account: SyncAccount)

    public suspend fun setState(accountId: String, state: AccountState, lastError: String?)

    public suspend fun remove(accountId: String)
}

public interface BindingStore {
    public suspend fun find(noteId: DocumentId): RemoteBinding?

    public suspend fun forAccount(accountId: String): List<RemoteBinding>

    /** Every path currently claimed on [accountId], so a second note cannot take one. */
    public suspend fun pathsOn(accountId: String): Set<String>

    public suspend fun upsert(binding: RemoteBinding)

    public suspend fun remove(noteId: DocumentId)
}

/**
 * The two things the drainer needs from the note index (SPEC §7.6's `note` table).
 *
 * Deliberately narrow. `:core:sync` has no business reading titles or folder membership,
 * and a port that exposed the whole index would let it start doing so.
 */
public interface SyncNotes {

    /** The note's current `local_rev`, or null if the note is gone. */
    public suspend fun localRev(noteId: DocumentId): Long?

    public suspend fun setSyncState(noteId: DocumentId, state: NoteSyncState, lastError: String?)
}
