package dev.inkstone.core.sync

import dev.inkstone.core.model.NextcloudCredential

/**
 * One account, authenticated, ready to use — and the place the uid rule is enforced
 * rather than documented.
 *
 * Constructing this with a credential whose `uid` is empty throws, because every URL
 * built from it would be `/remote.php/dav/files//…` and the server's answer to that is
 * a 404 that looks like "the file is not there" rather than "you built the wrong URL"
 * (SPEC §0.1 #3). An account in [AccountState.NEEDS_UID] therefore cannot reach the
 * network at all, which is the intent.
 */
public class NextcloudSession(
    public val account: SyncAccount,
    private val credential: NextcloudCredential,
    transport: NextcloudTransport,
) {

    init {
        require(credential.uid.isNotEmpty()) {
            "account ${account.id} has no uid — fetch ocs.data.id before using WebDAV (SPEC §0.1 #3)"
        }
    }

    public val dav: WebDavClient = WebDavClient(transport, credential)

    public val ocs: OcsClient = OcsClient(transport)

    /**
     * SPEC §7.2 — the **one** request permitted after a 401.
     *
     * > A 401 halts the sync worker and flags `NEEDS_REAUTH`. Confirm once with
     * > `GET /ocs/v2.php/cloud/user`; if that also 401s, stop and prompt for re-login.
     *
     * "Confirm once" is the whole contract, and it is one call because a WebDAV path can
     * 401 for reasons that are not about the credential — a share that was withdrawn, a
     * folder an admin locked down. This distinguishes those from a dead app password
     * without ever repeating the credential against the same endpoint.
     *
     * @return true when the credential still works and the 401 was about that path;
     *   false when the credential itself is dead; null when the server could not be
     *   reached, in which case **nothing is concluded** and the account stays as it was.
     *   Treating unreachable as "dead" would sign the user out every time they walk into
     *   a lift.
     */
    public suspend fun confirmStillAuthorised(): Boolean? = when (val outcome = ocs.fetchUid(credential)) {
        is DavOutcome.Ok -> true

        is DavOutcome.Failed -> when (outcome.error) {
            DavFailure.Unauthorized -> false
            is DavFailure.Transport -> null
            else -> null
        }
    }
}
