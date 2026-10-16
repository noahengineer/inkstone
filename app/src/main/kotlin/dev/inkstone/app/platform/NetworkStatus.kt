package dev.inkstone.app.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the device has a network that offers the internet at all — for **what the app says**, never
 * for deciding whether to send anything. Sync's own *"is there a network"* is WorkManager's `CONNECTED`
 * constraint, and a request that fails is how everything else finds out.
 *
 * It exists for the recognition model's panel. ML Kit's download does not fail without a network: it
 * waits, silently, and starts by itself when one appears (measured on an emulator in airplane mode). So
 * *"Downloading…"* with a moving bar was, offline, a promise of progress that was not happening, for as
 * long as the tablet stayed offline.
 *
 * ⚠️ **`INTERNET`, not `VALIDATED`.** Validation is Android probing a Google URL, and a network that
 * blocks or intercepts that probe still downloads models perfectly well — CLAUDE.md records a network
 * that re-signs every TLS connection. Calling such a network *offline* would be a false statement on
 * screen; missing a captive portal costs only the old message.
 */
@Singleton
class NetworkStatus @Inject constructor(@ApplicationContext context: Context) {

    val online: Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun hasInternet(capabilities: NetworkCapabilities?) =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        trySend(hasInternet(manager.getNetworkCapabilities(manager.activeNetwork)))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(hasInternet(capabilities))
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
