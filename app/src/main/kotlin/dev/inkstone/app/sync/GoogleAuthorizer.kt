package dev.inkstone.app.sync

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What Google's `AuthorizationClient` said, without any Play Services type in it — so the logic that
 * uses it ([GoogleAccessTokens], [GoogleDriveAccounts]) can be tested on the JVM with a fake.
 */
sealed interface GoogleAuthorization {
    /** A live access token for the `drive.file` scope. Short-lived; **no refresh token exists on the device**. */
    data class Granted(val accessToken: String) : GoogleAuthorization

    /**
     * The user has not granted (or no longer grants) this app access, and only a screen can fix that.
     * [request] is the `PendingIntent` to launch; typed `Any` here so tests need no Android.
     */
    data class NeedsConsent(val request: Any) : GoogleAuthorization

    /**
     * Anything else. [retryable] is true for "could not reach Google / Play Services is busy", which
     * concludes nothing about the grant; false for a registration Google refused (package or SHA-1 not
     * matched to an OAuth client), which no amount of asking again will fix.
     */
    data class Failed(val reason: String, val retryable: Boolean) : GoogleAuthorization
}

/** The thin seam over Play Services (docs/CLOUD-PROVIDERS.md §5.2). Production is [PlayServicesAuthorizer]. */
interface GoogleAuthorizer {
    /** Ask, silently if the grant stands. [accountEmail] pins a known account; null lets Google offer the picker. */
    suspend fun authorize(accountEmail: String?): GoogleAuthorization

    /** The result of launching a [GoogleAuthorization.NeedsConsent] request; [data] is the returned `Intent`. */
    fun fromConsentResult(data: Any?): GoogleAuthorization

    /** Tell Play Services a token it handed out was refused, so the next [authorize] mints a new one. */
    suspend fun clearToken(token: String)

    /** Withdraw the app's access to this Google account. Best effort: sign-out must not depend on it. */
    suspend fun revoke(accountEmail: String?)
}

/**
 * The real thing: `Identity.getAuthorizationClient` from `play-services-auth` 22.0.0.
 *
 * ⚠️ Checked against the jar with `javap`, **not run against Google**: that a granted request answers
 * `hasResolution() == false` with an access token, that an ungranted one answers a `PendingIntent`, and
 * which status code a mismatched package/SHA-1 produces (`DEVELOPER_ERROR` is assumed).
 */
@Singleton
class PlayServicesAuthorizer @Inject constructor(@ApplicationContext private val context: Context) : GoogleAuthorizer {

    private val client get() = Identity.getAuthorizationClient(context)

    private val scopes = listOf(Scope(DRIVE_FILE_SCOPE))

    @Suppress("TooGenericExceptionCaught")
    override suspend fun authorize(accountEmail: String?): GoogleAuthorization = try {
        val builder = AuthorizationRequest.builder().setRequestedScopes(scopes)
        accountEmail?.let { builder.setAccount(Account(it, GOOGLE_ACCOUNT_TYPE)) }
        outcomeOf(client.authorize(builder.build()).await())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        failed(failure)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun fromConsentResult(data: Any?): GoogleAuthorization = try {
        outcomeOf(client.getAuthorizationResultFromIntent(data as Intent))
    } catch (failure: Throwable) {
        failed(failure)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun clearToken(token: String) {
        try {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Throwable) {
            // Best effort: the next authorize either hands out a good token or says why not.
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun revoke(accountEmail: String?) {
        val email = accountEmail ?: return
        try {
            val request = RevokeAccessRequest.builder()
                .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
                .setScopes(scopes)
                .build()
            client.revokeAccess(request).await()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Throwable) {
            // Sign-out has to work offline; the grant can be removed at myaccount.google.com later.
        }
    }

    private fun outcomeOf(result: AuthorizationResult): GoogleAuthorization {
        if (result.hasResolution()) {
            val pending = result.pendingIntent
                ?: return GoogleAuthorization.Failed("Google asked for consent but gave nothing to show", false)
            return GoogleAuthorization.NeedsConsent(pending)
        }
        val token = result.accessToken
            ?: return GoogleAuthorization.Failed("Google granted access but sent no token", true)
        return GoogleAuthorization.Granted(token)
    }

    private fun failed(failure: Throwable): GoogleAuthorization {
        val code = (failure as? ApiException)?.statusCode
        return when (code) {
            // The app's package and signing certificate do not match an OAuth client in the Cloud project.
            CommonStatusCodes.DEVELOPER_ERROR -> GoogleAuthorization.Failed(
                "Google rejected this app's registration. In the Google Cloud console the Android OAuth client " +
                    "must name package dev.inkstone and this build's SHA-1.",
                retryable = false,
            )

            else -> GoogleAuthorization.Failed(
                "Google sign-in is not available right now (${code ?: failure.message ?: failure}).",
                retryable = true,
            )
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}
