package com.becalm.android.data.remote.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Observes device network reachability.
 *
 * Implementations must distinguish between a network being present and it being
 * genuinely validated (i.e. capable of reaching the internet).
 */
public interface NetworkMonitor {

    /**
     * Synchronous snapshot of current connectivity.
     *
     * Returns `true` when the active network has both [NetworkCapabilities.NET_CAPABILITY_INTERNET]
     * and [NetworkCapabilities.NET_CAPABILITY_VALIDATED]. Returns `false` otherwise, including
     * when there is no active network.
     */
    public fun isOnline(): Boolean

    /**
     * Cold [Flow] that emits the current online state, then emits a new value whenever
     * connectivity changes.
     *
     * Implemented using [callbackFlow] backed by a [ConnectivityManager.NetworkCallback].
     * The callback is unregistered automatically when the collector is cancelled — there
     * are no listener leaks.
     *
     * Consecutive identical values are suppressed via [distinctUntilChanged].
     */
    public val onlineFlow: Flow<Boolean>
}

/**
 * Production [NetworkMonitor] backed by [ConnectivityManager] and [NetworkCapabilities].
 *
 * [isOnline] checks [ConnectivityManager.activeNetwork] and verifies both
 * [NetworkCapabilities.NET_CAPABILITY_INTERNET] and [NetworkCapabilities.NET_CAPABILITY_VALIDATED]
 * so that captive portals (hotel Wi-Fi) are treated as offline.
 *
 * [onlineFlow] is a [callbackFlow] that:
 * 1. Emits the current state immediately on collection via a synthetic [isOnline] check.
 * 2. Updates on [NetworkCallback.onAvailable], [NetworkCallback.onLost], and
 *    [NetworkCallback.onCapabilitiesChanged] to capture captive-portal promotion.
 * 3. Registers a [NetworkRequest] listening for [NetworkCapabilities.NET_CAPABILITY_INTERNET]
 *    so the callback fires on any network that claims internet access.
 * 4. Unregisters the callback in the [awaitClose] block — safe for both normal
 *    cancellation and exceptional teardown.
 *
 * @param context Application context used to obtain the [ConnectivityManager] system service.
 */
public class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override val onlineFlow: Flow<Boolean> = callbackFlow {
        // Emit current state immediately so collectors do not have to wait for a change event
        trySend(isOnline())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isOnline())
            }

            override fun onLost(network: Network) {
                trySend(isOnline())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                // Re-evaluate via isOnline() to respect both capabilities at once
                trySend(isOnline())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // Unregister the callback when the collector is cancelled or the flow completes
        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()
}
