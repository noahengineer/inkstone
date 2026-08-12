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

    /**
     * ⭐ **The user signed out. The credential is gone; everything else is remembered.**
     *
     * *Added in M6, because signing out was effectively irreversible.* Sign-out has to do
     * two things — revoke the app password server-side (SPEC §7.1) and delete the local
     * credential (SPEC §14) — and it used to do two more that nothing asked for: delete the
     * account row, and unbind every note on it.
     *
     * The cost of those two landed entirely on the user. Signing back in meant re-typing a
     * server URL on a tablet, and then re-binding **every note, one at a time, choosing its
     * folder again** — because `remote_binding` is the only record of which note lives where,
     * and it had been thrown away. For a library of any size that is not a sign-in, it is a
     * migration.
     *
     * None of it was needed. A row holding a server URL, a login name, a uid, a default
     * folder and a cached capabilities document contains nothing secret — SPEC §14 is
     * specific that the *app password* is the thing that must be protected, and it is the one
     * thing this state does not keep. And the bindings are pure local metadata about files
     * that are still sitting on the server exactly where they were left.
     *
     * So the row stays, the bindings stay, and signing in again is one tap that resumes
     * every note where it left off.
     *
     * **Unusable, like every other non-ACTIVE state** — [SyncAccount.isUsable] is false, so
     * no request can be made with it and `NextcloudSession` cannot be built from it. What
     * makes it different from [NEEDS_REAUTH] is whose decision it was: a reauth is the
     * server refusing us and needs a warning, whereas this is the user doing what they asked
     * for and needs no alarm at all.
     */
    SIGNED_OUT,
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

    /**
     * ⭐ The numeric `oc:fileid` inside [remoteOcId] — what a `SEARCH` matches on (SPEC §7.4 rule 2).
     *
     * SPEC §7.3's `oc:id` is `<zero-padded fileid><instanceid>`, and an instanceid is `oc` followed
     * by ten random characters, so the fileid is the **leading run of digits**. Not the first eight
     * characters: a fileid above 99 999 999 is simply longer than its padding, and truncating one
     * there would silently address a different file.
     *
     * *Derived rather than stored,* and the argument is what happens when the derivation is wrong
     * rather than how likely that is. A value that resolves to nothing yields *"genuinely gone"*,
     * which is exactly the conclusion the pass had already reached without asking — so a bad
     * derivation can only fail to *rescue* a moved note, never destroy one. That, plus
     * [dev.inkstone.core.sync.WebDavXml.searchByFileIdBody] verifying `oc:id` on the way back, is
     * why this does not need a column and a migration.
     *
     * Null when there is no `oc:id`, when it starts with no digits, or when those digits do not fit
     * a `Long`. Every one of those means *"cannot tell"* to the caller, never *"deleted"*.
     */
    public val remoteFileId: Long?
        get() = remoteOcId?.takeWhile(Char::isDigit)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
}

/**
 * The three stores, as one parameter.
 *
 * Not a namespace for its own sake: [OutboxDrainer] and [SyncEngine] each need all four,
 * and passing them individually put both constructors over detekt's parameter limit — which
 * is the linter noticing something true. These four always travel together, they always come
 * from the same database, and a caller that had three of them and not the fourth could not do
 * anything correct with them.
 *
 * [recordings] is M12's, and is **not defaulted**. A default would be an empty store, which
 * makes "this build cannot upload a lecture" the silent behaviour of every construction site
 * somebody forgot — the exact shape of the six inert-but-green features CLAUDE.md lists.
 */
public class SyncStores(
    public val accounts: AccountStore,
    public val bindings: BindingStore,
    public val outbox: OutboxStore,
    public val notes: SyncNotes,
    public val recordings: RecordingUploads,
)

/**
 * ⭐ M12 — one recording that has been captured and may still owe the server a copy.
 *
 * **Paths, not bytes** (the same rule [OutboxEntry] states for the outbox row itself, and the
 * same one [dev.inkstone.core.model.RemoteBundleExtractor] states for a downloaded PDF). An
 * hour of voice-grade Opus is ~11 MB and a lecture day is three of them; carrying that through
 * a value class would put the largest object in the app on the heap for as long as a query
 * result lives.
 *
 * @property audioPath `recordings/<id>.opus` — Ogg, so a process killed at minute 50 leaves
 *   fifty playable minutes rather than an unreadable MP4 (doc §9).
 * @property timelinePath `recordings/<id>.timeline` — the append-only stroke↔audio log.
 *   ⚠️ It travels in the **same** outbox op as the audio: two ops means one can land without
 *   the other, and a transcript with no timeline is just a transcript (doc §5).
 * @property durationMillis elapsed-realtime, from the recording's start. Never wall clock —
 *   though the reason here is not CLAUDE.md's (no two devices are ever compared) but that a
 *   clock correction mid-lecture would otherwise change how long the lecture was.
 */
public data class PendingRecording(
    val recordingId: String,
    val noteId: DocumentId,
    val audioPath: String,
    val timelinePath: String,
    val audioBytes: Long,
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
)

/**
 * What `:core:sync` needs to know about recordings — deliberately four questions and no more.
 *
 * Narrow for the reason [SyncNotes] is narrow: the drainer's business is *"what does this note
 * owe the server, and where are the bytes"*. Starting, stopping and discarding a recording are
 * `:app`'s, and a port that exposed them would let the sync layer open a microphone.
 */
public interface RecordingUploads {

    /**
     * The recording an outbox row names — **and only while it still owes the server a copy.**
     *
     * ⚠️ Null once it has been uploaded, and that is the contract rather than an accident.
     * [markUploaded] and removing the outbox row are two stores and therefore not one
     * transaction; a process killed between them leaves a row pointing at files the server
     * already has. Answering null there costs one wasted drain and nothing else. Answering
     * *"here it is"* costs an 11 MB re-upload and, once doc §8's server half has trashed the
     * audio it transcribed, **a second transcription of the same lecture**.
     *
     * Also null for a recording discarded under the thirty-second floor after its row was
     * enqueued, which is the same question arriving a different way.
     */
    public suspend fun find(recordingId: String): PendingRecording?

    /**
     * Finished recordings on [noteId] that the server does not have.
     *
     * ⚠️ Read by [SyncEngine.resumeAccount]. Signing out drops every outbox row (see
     * [SyncEngine.pauseAccount], and the M5 stall it exists to prevent), so without this a
     * lecture captured before a sign-out would never upload — and nothing would report it.
     * The rows are the queue; **this table is the record of what is owed**, which is what
     * makes the queue rebuildable.
     */
    public suspend fun pendingFor(noteId: DocumentId): List<PendingRecording>

    /**
     * ⭐ Every finished recording the server does not have, across all notes.
     *
     * ⚠️ Deliberately **not** filtered by binding, because the recordings that are stuck are
     * exactly the ones a binding-shaped query cannot see. This is what lets `syncNowRequested`
     * say *"3 recordings are waiting, and here is what is stopping them"* rather than doing
     * nothing indistinguishable from doing nothing successfully — which is how M12's first real
     * upload attempt spent an evening.
     */
    public suspend fun allPending(): List<PendingRecording>

    /** The server now has both files. Does not change the recording's state; see `Recording.sq`. */
    public suspend fun markUploaded(recordingId: String, atEpochMillis: Long)
}

public interface AccountStore {
    public suspend fun all(): List<SyncAccount>

    public suspend fun find(accountId: String): SyncAccount?

    public suspend fun upsert(account: SyncAccount)

    public suspend fun setState(accountId: String, state: AccountState, lastError: String?)

    public suspend fun remove(accountId: String)
}

/**
 * A binding this reconcile pass did not stamp — a **candidate** for SPEC §7.6 step 3's fourth
 * branch, and never a conclusion.
 *
 * ⚠️ A pruned subtree leaves its bindings unstamped too, and so does one whose PROPFIND failed,
 * and so does one the pass limit stopped short of. The caller must intersect these with the
 * directories it actually *walked* before trashing anything. Trashing the list as it stands is
 * the hundred-notes-in-the-trash bug `remoteBinding.lastSeenRemoteAt` exists to prevent.
 *
 * Two columns rather than the whole binding, so that reading one new field does not require a
 * second representation of a row that already has one.
 */
public data class StaleBinding(val noteId: DocumentId, val remotePath: String)

public interface BindingStore {
    public suspend fun find(noteId: DocumentId): RemoteBinding?

    public suspend fun forAccount(accountId: String): List<RemoteBinding>

    /** Every path currently claimed on [accountId], so a second note cannot take one. */
    public suspend fun pathsOn(accountId: String): Set<String>

    public suspend fun upsert(binding: RemoteBinding)

    public suspend fun remove(noteId: DocumentId)

    // The four below are M6's — SPEC §7.6 step 3 needs to look a binding up by things other than
    // its note, and needs one field of it that [RemoteBinding] deliberately does not carry.

    /**
     * The note bound to a remote file, by `oc:id` — SPEC §7.6 step 3's match.
     *
     * No account argument: `oc:id` is `<padded-fileid><instanceid>` and therefore globally unique
     * (SPEC §7.3), which is the whole reason this project stores it rather than `oc:fileid`.
     *
     * A list rather than one row, because SPEC §7.4's caveats include one `oc:id` presented at two
     * paths — a file shared in twice — and throwing in the middle of a pass is worse than
     * answering with both.
     */
    public suspend fun findByOcId(remoteOcId: String): List<RemoteBinding>

    /**
     * The secondary key SPEC §7.4 requires: a Nextcloud trash-restore **changes the fileid**, so a
     * restored note cannot be found by `oc:id` at all and is re-bound by path (SPEC §7.6).
     *
     * Also what repairs a binding whose `remoteOcId` a reverse proxy stripped from the PUT
     * response — otherwise the reconciler would meet its own note as a stranger's and adopt a
     * second copy of it.
     */
    public suspend fun findByPath(accountId: String, remotePath: String): List<RemoteBinding>

    /**
     * Mark these notes as seen by the pass that started at [seenAtEpochMillis].
     *
     * One statement for the whole pass rather than one per note: which notes were found is only
     * known at the end of the walk, and a 500-note library would otherwise be 500 round trips.
     * Empty must be a no-op — `IN ()` is a SQL syntax error.
     *
     * ⚠️ Stamp with the value the pass **started** at, and read it back with the same value, so
     * that [notSeenSince] is its exact complement. A second clock reading here puts a note stamped
     * mid-pass on the wrong side of that comparison, and the wrong side is the trash.
     */
    public suspend fun stampSeen(noteIds: Collection<DocumentId>, seenAtEpochMillis: Long)

    /** Candidates for the deletion branch — read [StaleBinding] before acting on one. */
    public suspend fun notSeenSince(accountId: String, sinceEpochMillis: Long): List<StaleBinding>
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
