package dev.inkstone.core.sync

/**
 * SPEC §7.5 — the response table, as a type.
 *
 * Every remote call in this module returns a [DavOutcome] rather than throwing.
 * CLAUDE.md reserves exceptions for programming errors, and every entry below is
 * a thing a healthy server says on a bad day: a quota is full, someone else has
 * the file open, an admin revoked the app password.
 *
 * The distinction that matters most is between [DavFailure.Unauthorized] and
 * everything else. A 401 is **not** retryable — SPEC §7.2, CLAUDE.md rule 5 — and
 * modelling it as one variant among many is what makes "never auto-retry" a thing
 * the compiler can help with rather than a comment somebody has to remember.
 */
public sealed interface DavOutcome<out T> {

    public data class Ok<out T>(val value: T) : DavOutcome<T>

    public data class Failed(val error: DavFailure) : DavOutcome<Nothing>

    public val valueOrNull: T?
        get() = (this as? Ok)?.value

    public val failureOrNull: DavFailure?
        get() = (this as? Failed)?.error
}

public inline fun <T, R> DavOutcome<T>.map(transform: (T) -> R): DavOutcome<R> = when (this) {
    is DavOutcome.Ok -> DavOutcome.Ok(transform(value))
    is DavOutcome.Failed -> this
}

/** Why a remote call did not do what was asked. */
public sealed interface DavFailure {

    /** A short line for `note.last_error`. Never contains the app password. */
    public val describe: String

    /**
     * **401 — halt.** SPEC §7.2 and CLAUDE.md rule 5.
     *
     * Nextcloud's brute-force protection is on by default: escalating delays to 25 s,
     * then 429 after ~10 failures in 30 minutes, and devices behind one NAT share the
     * budget. Retrying credentials here is the documented way real users get their own
     * IP blocked by their own server.
     *
     * The only permitted follow-up is a **single** confirmation `GET /ocs/v2.php/cloud/user`
     * (see [NextcloudSession.confirmStillAuthorised]); if that 401s too, the account is
     * flagged `NEEDS_REAUTH` and nothing else is sent until the user logs in again.
     */
    public data object Unauthorized : DavFailure {
        override val describe: String = "Not authorised — the app password was rejected. Sign in again."
    }

    /** 403. Not a credentials problem: the user is authenticated and may not do this. */
    public data class Forbidden(val detail: String?) : DavFailure {
        override val describe: String = "Refused by the server${detail?.let { ": $it" }.orEmpty()}"
    }

    public data class NotFound(val path: String) : DavFailure {
        override val describe: String = "Not on the server: $path"
    }

    /**
     * **412 — conflict, never a retry.** SPEC §7.5.
     *
     * `If-Match` failed, which means the remote file changed since the ETag we hold.
     * Blind-retrying would overwrite whatever the other device wrote, which CLAUDE.md
     * rule 1 forbids outright. M6 resolves this per SPEC §7.7; M5 stops and flags.
     */
    public data class PreconditionFailed(val serverEtag: String?) : DavFailure {
        override val describe: String = "The file changed on the server since it was last seen."
    }

    /** 507 — pause this account's uploads rather than hammering a full disk. */
    public data object QuotaExceeded : DavFailure {
        override val describe: String = "The Nextcloud account is out of space."
    }

    /** 423 — someone (or something) holds a lock. `nc:lock-owner-displayname` says who. */
    public data class Locked(val owner: String?) : DavFailure {
        override val describe: String = "Locked on the server${owner?.let { " by $it" }.orEmpty()}."
    }

    /**
     * 429 — already throttled. Honour `Retry-After` when the server sends one.
     *
     * Reaching this without a preceding 401 usually means another device on the same
     * NAT spent the brute-force budget (SPEC §7.2).
     */
    public data class RateLimited(val retryAfterSeconds: Long?) : DavFailure {
        override val describe: String = "The server asked us to slow down."
    }

    /** Any other non-2xx. Kept whole so a bug report has something to go on. */
    public data class Server(val status: Int, val body: String?) : DavFailure {
        override val describe: String = "Server returned $status"
    }

    /** No response at all — offline, DNS, a dropped connection, a refused certificate. */
    public data class Transport(val reason: String) : DavFailure {
        override val describe: String = "Could not reach the server: $reason"
    }

    /** A 2xx whose body was not what the protocol promises. */
    public data class Malformed(val reason: String) : DavFailure {
        override val describe: String = "The server's reply could not be read: $reason"
    }
}

/**
 * Whether waiting and trying again could plausibly work.
 *
 * [DavFailure.Unauthorized] is false and that is the point of the property existing.
 * [DavFailure.PreconditionFailed] is false too: a 412 is a conflict, and the answer is
 * a merge (SPEC §7.7), never the same PUT again.
 */
public val DavFailure.isRetryable: Boolean
    get() = when (this) {
        is DavFailure.Transport, is DavFailure.RateLimited, is DavFailure.Locked -> true

        is DavFailure.Server -> status >= SERVER_ERROR_FLOOR

        DavFailure.Unauthorized,
        DavFailure.QuotaExceeded,
        is DavFailure.Forbidden,
        is DavFailure.NotFound,
        is DavFailure.PreconditionFailed,
        is DavFailure.Malformed,
        -> false
    }

private const val SERVER_ERROR_FLOOR = 500
