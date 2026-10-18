package dev.inkstone.app.sync

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.inkstone.app.MainActivity
import javax.inject.Inject

/**
 * Where the browser lands after Microsoft's sign-in page: `msauth://<package>/…?code=…&state=…`
 * (docs/CLOUD-PROVIDERS.md §5.1).
 *
 * It does two things and shows nothing: hands the URL to [OneDriveAccounts.onRedirect], and brings the
 * app's own screen back in front of the Custom Tab. The completion runs in that singleton's scope, not
 * here and not in a view model, because this Activity is gone a moment later and the process may have
 * been restarted while the user was on Microsoft's page.
 *
 * ⚠️ `exported`, because a browser must be able to start it. Any app can therefore send it an
 * `msauth://` intent. That cannot sign anyone in — the redirect's `state` must match the one in the
 * sealed pending record, and the authorization code is useless without the verifier beside it — but it
 * *can* consume the pending record, so a forged redirect can make the user press the button again.
 * That is a nuisance and not a credential leak, and it is why [MicrosoftSignIn] checks `state` against
 * a record it has already taken rather than leaving the record for the real redirect.
 */
@AndroidEntryPoint
class OAuthRedirectActivity : ComponentActivity() {

    @Inject
    lateinit var oneDrive: OneDriveAccounts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deliver(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deliver(intent)
    }

    private fun deliver(intent: Intent?) {
        intent?.dataString?.takeIf { intent.data?.scheme == SCHEME }?.let(oneDrive::onRedirect)
        // Back to the app: the Custom Tab is above it in the same task, and this clears it away. SINGLE_TOP
        // reuses the running MainActivity (its view model, and so the Sync screen, are untouched).
        val back = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(back)
        finish()
    }

    private companion object {
        const val SCHEME = "msauth"
    }
}
