package com.uplinkstatus.app.connectivity

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Diagnostic only -- see the logging in [ConnectivityManagerNetworkSnapshotProvider] below.
 * Not read by any production logic; exists so `adb logcat -s UplinkConnectivity` can show
 * exactly what the platform reports for the *default* network on a real device, since that's
 * the one thing a JVM/Robolectric test cannot observe. */
private const val TAG = "UplinkConnectivity"

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

    override val snapshotFlow: Flow<NetworkSnapshot?> = callbackFlow {
        // Seed from the platform's *current* answer before registering, rather than from a
        // placeholder. Registering a callback doesn't synchronously replay anything, and the
        // previous version of this line sent NetworkSnapshot.NONE unconditionally -- which
        // downstream could not distinguish from a real "no network," so the first visibility
        // decision of every service start was deterministically DISABLED/HIDDEN no matter what
        // the device was actually connected to. On a fresh install that is precisely the
        // "master toggle is on but the tracer stays on the paused frame" report: the wrong
        // answer was not a race, it was computed from a value that meant nothing.
        //
        // getActiveNetwork()/getNetworkCapabilities() answer the same question the callback
        // does, synchronously and without waiting for any dispatch, so the very first emission
        // is already correct -- and stays correct even in the worst case where the callback,
        // for whatever reason, never arrives at all.
        //
        // Read *before* registering, deliberately: registration always delivers a fresh
        // onCapabilitiesChanged for the current default network moments later, so anything
        // that changes in the gap is corrected by that callback. Doing it the other way round
        // would risk this synchronous read overwriting a newer callback value with an older one.
        val seed = currentDefaultNetworkSnapshot()
        Log.d(TAG, "seed (synchronous, pre-registration): ${seed.describeForLog()}")
        trySend(seed)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val snapshot = capabilities.toSnapshot()
                // This is the ground truth for what the OS considers the *default* network --
                // i.e. the one this app's WIFI_ONLY/SSID_WHITELIST/CELLULAR_ONLY/ANY_CONNECTION
                // matching is actually evaluated against. If a device is genuinely associated
                // with a WiFi network that isn't showing up here, this line is how to prove it:
                // the raw NetworkCapabilities the platform handed back, unfiltered.
                Log.d(TAG, "onCapabilitiesChanged: ${snapshot.describeForLog()}")
                trySend(snapshot)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "onLost: default network gone")
                trySend(NetworkSnapshot.NONE)
            }

            override fun onUnavailable() {
                Log.d(TAG, "onUnavailable: no default network available")
                trySend(NetworkSnapshot.NONE)
            }
        }

        // No explicit Handler overload on purpose. It looks like this call needs one -- it is
        // made from a Dispatchers.Default pool thread, which has no prepared Looper -- but
        // ConnectivityManager.registerDefaultNetworkCallback(NetworkCallback) does not use the
        // calling thread's Looper at all: it delegates to getDefaultHandler(), a static
        // CallbackHandler built on ConnectivityThread.getInstanceLooper() (a dedicated
        // self-starting HandlerThread inside the app process), and the platform's own javadoc
        // for this overload states "The callback is invoked on the default internal Handler."
        // Verified against the AOSP android-34 sources for ConnectivityManager/ConnectivityThread.
        // Passing a Handler here would only change which thread callbacks land on -- it would
        // fix nothing -- so it is left off rather than added as cargo cult; the correctness of
        // the first emission is guaranteed by the synchronous seed above instead.
        connectivityManager.registerDefaultNetworkCallback(callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    /**
     * The platform's current answer for the default network, read synchronously.
     *
     * Returns [NetworkSnapshot.NONE] for the real, positive answer "there is no default
     * network," and `null` only for the genuinely indeterminate cases: the default network was
     * torn down between the two calls below (so capabilities came back null for a [Network]
     * that existed a moment ago), or the platform refused the query outright. `null` leaves the
     * decision to the callback rather than fabricating a "not in scope" verdict -- the same
     * distinction this whole class's contract rests on.
     */
    private fun currentDefaultNetworkSnapshot(): NetworkSnapshot? =
        try {
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork == null) {
                NetworkSnapshot.NONE
            } else {
                connectivityManager.getNetworkCapabilities(activeNetwork)?.toSnapshot()
            }
        } catch (_: SecurityException) {
            // ACCESS_NETWORK_STATE is a normal (install-time) permission and is manifest
            // declared, so this is not expected -- but "we were not allowed to look" is the
            // definition of "not known," and it must not become "confirmed no network."
            null
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

/** Diagnostic only. `null` here means "the platform genuinely didn't answer" -- see
 * [ConnectivityManagerNetworkSnapshotProvider.currentDefaultNetworkSnapshot]'s doc -- which is
 * itself a fact worth being able to see in logcat, not just infer from its absence. */
private fun NetworkSnapshot?.describeForLog(): String =
    if (this == null) {
        "null (platform did not answer)"
    } else {
        "wifi=$hasWifiTransport cellular=$hasCellularTransport validated=$isValidated ssid=${ssid ?: "none"}"
    }
