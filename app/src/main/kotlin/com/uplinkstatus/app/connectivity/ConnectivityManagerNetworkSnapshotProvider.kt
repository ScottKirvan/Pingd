package com.uplinkstatus.app.connectivity

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The real, [android.net.ConnectivityManager]-backed [NetworkSnapshotProvider].
 *
 * Uses [ConnectivityManager.registerDefaultNetworkCallback] rather than
 * [ConnectivityManager.registerNetworkCallback] with a broad [android.net.NetworkRequest]:
 * the spec's scope logic only ever cares about "the network in scope" (singular, the one
 * traffic would actually route over right now), which is exactly what "the default network"
 * means — registering for *all* networks would additionally hand back every non-default
 * network the device happens to be holding onto (e.g. a WiFi network still associated but not
 * carrying traffic while on cellular), forcing this class to reimplement "which one of these
 * is actually active" itself instead of letting the OS answer that question directly.
 *
 * [ConnectivityManager.NetworkCallback.onCapabilitiesChanged] is the single source of truth
 * here (not `onAvailable`, which doesn't carry capabilities): the platform always follows an
 * `onAvailable` with an `onCapabilitiesChanged` shortly after, and the same callback fires
 * again on a capabilities-only change while remaining connected — precisely the "SSID change
 * while still on WiFi" case Stage 4's brief calls out — so listening to it alone captures
 * connect, reconfigure, and (via [onLost]) disconnect without missing anything.
 *
 * SSID is read off [NetworkCapabilities.getTransportInfo] (a [WifiInfo] when the transport is
 * WiFi) rather than a separate [WifiManager.getConnectionInfo] call: it comes from the exact
 * capabilities snapshot the callback just delivered, so it can never be stale relative to
 * whatever else this snapshot reports, and it naturally requires no separate wiring for the
 * "SSID changed but capabilities object is otherwise the same shape" case. Without
 * `ACCESS_FINE_LOCATION` granted, the OS reports [WifiManager.UNKNOWN_SSID] here rather than
 * throwing — this class treats that the same as "no SSID," and the actual
 * permission-gating for [com.uplinkstatus.app.prefs.NetworkScope.SSID_WHITELIST] happens one
 * layer up, in [com.uplinkstatus.app.state.NetworkScopeMatcher], so that behavior is covered
 * by a plain unit test rather than depending on the OS's ungranted-permission behavior at all.
 */
class ConnectivityManagerNetworkSnapshotProvider(
    private val connectivityManager: ConnectivityManager,
) : NetworkSnapshotProvider {

    override val snapshotFlow: Flow<NetworkSnapshot> = callbackFlow {
        // Registering the callback doesn't synchronously replay the current state the way a
        // hot LiveData might -- start collectors off at "nothing known yet" so a slow-to-fire
        // first callback doesn't leave a stale/undefined snapshot in the meantime.
        trySend(NetworkSnapshot.NONE)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.toSnapshot())
            }

            override fun onLost(network: Network) {
                trySend(NetworkSnapshot.NONE)
            }

            override fun onUnavailable() {
                trySend(NetworkSnapshot.NONE)
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }
}

private fun NetworkCapabilities.toSnapshot(): NetworkSnapshot {
    val wifiInfo = transportInfo as? WifiInfo
    val ssid = wifiInfo?.ssid
        ?.takeUnless { it == WifiManager.UNKNOWN_SSID }
        ?.trim('"')
        ?.takeUnless { it.isEmpty() }

    return NetworkSnapshot(
        hasWifiTransport = hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        hasCellularTransport = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        isValidated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        ssid = ssid,
    )
}
