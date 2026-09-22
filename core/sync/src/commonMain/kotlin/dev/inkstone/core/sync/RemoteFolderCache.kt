package dev.inkstone.core.sync

/**
 * One remembered remote folder — what the folder browser drew last time it could see the server.
 *
 * @property path the folder's own remote path, no leading or trailing `/`. `""` is the account
 *   root.
 * @property parentPath the listing this folder appeared in. The root's own row has
 *   `parentPath == path == ""`, so every read of children filters the self-entry out; a
 *   `Depth: 1` listing contains its own collection (`RemoteWalk` says the same thing about the
 *   walk).
 * @property listedAtEpochMillis when this folder's **own** children were last listed. `0` means
 *   never, which is not the same as "listed, and empty" — the browser has to be able to say
 *   *"nothing in here"* and *"I have never seen inside here"* differently, and one number that
 *   conflated them would make the offline picker lie in the more alarming direction.
 * @property pending true for a folder the user created with no network. It is in the list because
 *   they asked for it and it is **not on the server yet**; the MKCOL that creates it is the one
 *   [SyncEngine.bind] already enqueues unconditionally. Drawn marked, never silently.
 */
public data class CachedFolder(
    val accountId: String,
    val path: String,
    val parentPath: String,
    val name: String,
    val ocId: String? = null,
    val seenAtEpochMillis: Long = 0L,
    val listedAtEpochMillis: Long = 0L,
    val pending: Boolean = false,
)

/**
 * What one level of the browser knows without asking the server.
 *
 * @property listedAtEpochMillis `0` when this level has never been listed — so an empty
 *   [folders] with a `0` here is *"I have never looked in here"* and an empty one with a
 *   timestamp is *"there are no folders in here"*. The screen says which.
 */
public data class CachedListing(val folders: List<CachedFolder>, val listedAtEpochMillis: Long)

/**
 * ⭐ The folder browser's memory of the server's tree.
 *
 * SPEC §7.6 step 2's browser is one `PROPFIND Depth:1` per level, and it used to keep nothing:
 * offline it showed an empty list, so *"save this note to Physics/Term 2"* could only be
 * **decided** with a network — even though the decision needs none, since binding enqueues
 * MKCOL + UPLOAD either way and nothing in [SyncEngine.bind] touches a socket.
 *
 * ⚠️ **This is not [DirectoryCache], and the two must never be merged.** A `remoteDirectory`
 * row is the reconciler's promise that a directory was *walked to completion* at a given ETag,
 * and a row it did not earn is how a subtree silently stops being walked or a note is read as
 * deleted (SPEC §7.4 rule 1). A row here says only *"a folder by this name was listed here at
 * this time"*. Nothing that decides what syncs may read it.
 *
 * The traffic between them runs one way and read-only: an implementation may *display*
 * directories the reconciler has walked, so that a user who has never opened the browser offline
 * still sees the folders their notes live in.
 */
public interface RemoteFolderCache {

    /** One level, as the browser would draw it. */
    public suspend fun childrenOf(accountId: String, path: String): CachedListing

    /**
     * Record a successful listing of [path].
     *
     * Three things in one transaction, because a half-written listing is a listing that lies:
     * the folders that were there, the fact that [path] itself was listed at [nowEpochMillis],
     * and the removal of children that were in the cache and are not on the server any more.
     *
     * ⚠️ A [CachedFolder.pending] child is never swept, however stale. It is absent from the
     * server precisely because its MKCOL has not run, and deleting it would take away a
     * destination the user has already chosen.
     */
    public suspend fun remember(accountId: String, path: String, folders: List<CachedFolder>, nowEpochMillis: Long)

    /**
     * A folder the user asked for with no network to create it on.
     *
     * Not a queued MKCOL — that is the binding's, and it is already unconditional. This is only
     * the row that lets the picker show a destination the user has just named.
     */
    public suspend fun rememberPending(accountId: String, path: String, nowEpochMillis: Long)

    /** Everything remembered for one account. Called when the account is forgotten. */
    public suspend fun clearFor(accountId: String)
}
