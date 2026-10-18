package dev.inkstone.app.sync

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.inkstone.app.BuildConfig
import dev.inkstone.core.sync.MicrosoftOAuthConfig
import dev.inkstone.core.sync.MicrosoftSignInConfig
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The redirect URI the Entra **Android** platform generates (docs/CLOUD-PROVIDERS.md §5.1):
 * `msauth://<package>/<URL-encoded base64 SHA-1 of the signing certificate>`.
 *
 * Computed at run time from the certificate that actually signed the installed APK, so it is right
 * for a debug build, for the debug-key-signed release `tools/send-to-tablet.ps1` sends, and for a
 * future upload key — and so the Sync screen can show the user *the exact string* to register,
 * instead of a recipe for producing it with `keytool`.
 *
 * ⚠️ This is the format Microsoft's own MSAL library uses. Whether the identity platform accepts it
 * for a hand-rolled authorization-code flow (as opposed to MSAL's) is read from documentation and
 * has not been observed; the user's first real sign-in is that observation.
 */
object MicrosoftRedirect {

    fun uriFor(packageName: String, signingCertSha1: ByteArray): String {
        val hash = Base64.getEncoder().encodeToString(signingCertSha1)
        return "msauth://$packageName/${URLEncoder.encode(hash, "UTF-8")}"
    }

    fun sha1(certificate: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(certificate)

    /** The signing certificate's SHA-1, or null when the platform will not say (no signature is not a state to guess at). */
    @Suppress("DEPRECATION", "TooGenericExceptionCaught", "SwallowedException")
    fun signingCertSha1(context: Context): ByteArray? = try {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let(::sha1)
    } catch (unreadable: Exception) {
        null
    }
}

/**
 * What the OneDrive sign-in is configured with: the app registration's client id, the redirect URI
 * this install must be registered under, and — in a debug build only — an address that stands in for
 * Microsoft so the flow can be driven on an emulator.
 *
 * A blank client id is "this build was not given one": [configured] is false, the Sync screen says so,
 * and no sign-in can start. Nothing here is a secret.
 */
@Singleton
class MicrosoftSettings(rawClientId: String, val redirectUri: String, rawStandIn: String) {

    /** What Hilt builds: the build's own values and the certificate this install was signed with. */
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        rawClientId = BuildConfig.MS_CLIENT_ID,
        redirectUri = MicrosoftRedirect.uriFor(
            context.packageName,
            MicrosoftRedirect.signingCertSha1(context) ?: ByteArray(0),
        ),
        // Debug builds only; BuildConfig.MS_STAND_IN is pinned to the empty string in release.
        rawStandIn = BuildConfig.MS_STAND_IN,
    )

    val clientId: String = rawClientId.trim()

    val configured: Boolean get() = clientId.isNotEmpty()

    private val standIn: String = rawStandIn.trim().trimEnd('/')

    val oauth: MicrosoftOAuthConfig = if (standIn.isEmpty()) {
        MicrosoftOAuthConfig(clientId)
    } else {
        MicrosoftOAuthConfig(clientId, tokenEndpoint = "$standIn/oauth/token")
    }

    val signIn: MicrosoftSignInConfig = if (standIn.isEmpty()) {
        MicrosoftSignInConfig(oauth, redirectUri)
    } else {
        MicrosoftSignInConfig(
            oauth = oauth,
            redirectUri = redirectUri,
            authorizeEndpoint = "$standIn/oauth/authorize",
            graphBase = "$standIn/v1.0",
        )
    }
}
