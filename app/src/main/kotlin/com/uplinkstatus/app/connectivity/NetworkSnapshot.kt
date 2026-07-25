package com.uplinkstatus.app.connectivity

/**
 * A plain, Android-framework-light snapshot of whatever [android.net.ConnectivityManager]
 * currently considers the *default* network — the fields
 * [com.uplinkstatus.app.state.NetworkScopeMatcher] actually needs, extracted once at the
 * [android.net.NetworkCapabilities] boundary so everything downstream of it (the matching
 * logic, its tests) never has to touch a real [android.net.NetworkCapabilities] or
 * [android.net.wifi.WifiInfo] instance.
 *
 * [ssid] is the *unquoted* SSID string (the raw `WifiInfo.getSSID()` value comes back
 * double-quoted for a normal ASCII SSID) when it was actually readable, or `null` when
 * there's no WiFi transport, or the OS wouldn't report it (e.g. `WifiManager.UNKNOWN_SSID`,
 * which is exactly what happens without location permission — see
 * [ConnectivityManagerNetworkSnapshotProvider]).
 */
data class NetworkSnapshot(
    val hasWifiTransport: Boolean,
    val hasCellularTransport: Boolean,
    val isValidated: Boolean,
    val ssid: String?,
) {
    companion object {
        /** No default network at all — every scope mode is out of scope against this. */
        val NONE = NetworkSnapshot(
            hasWifiTransport = false,
            hasCellularTransport = false,
            isValidated = false,
            ssid = null,
        )
    }
}
