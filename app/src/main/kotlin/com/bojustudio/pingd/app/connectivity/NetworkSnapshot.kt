package com.bojustudio.pingd.app.connectivity

/**
 * A plain, Android-framework-light snapshot of **one** network the device is currently
 * connected to — the fields [com.bojustudio.pingd.app.state.NetworkScopeMatcher] actually needs,
 * extracted once at the [android.net.NetworkCapabilities] boundary so everything downstream of
 * it (the matching logic, its tests) never has to touch a real
 * [android.net.NetworkCapabilities] or [android.net.wifi.WifiInfo] instance.
 *
 * This describes a single network, *not* the device's overall connectivity: a phone routinely
 * holds several at once (WiFi and cellular simultaneously is the normal state, not an edge
 * case). [ConnectivitySnapshot] is the aggregate the scope logic is evaluated against.
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
)
