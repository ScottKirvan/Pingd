package com.uplinkstatus.app.connectivity

/**
 * Everything the scope logic is allowed to know about the device's connectivity at one instant:
 * every internet-providing WiFi/cellular network currently connected ([networks]), plus which
 * network — if any — the OS has picked as the *default* route for general traffic
 * ([defaultNetwork]).
 *
 * The distinction is the whole point of this type. A phone that is on WiFi *and* has cellular
 * service is holding two connected networks, and Android picks exactly one of them as the
 * default route (validated WiFi, overwhelmingly). Asking "does the default network have
 * cellular transport" therefore answers "is cellular the route right now," which is not the
 * question `Cellular only` scope is asking — that setting asks "is cellular connected." Those
 * are different facts and only [networks] can answer the second one.
 *
 * [defaultNetwork] is kept because one mode does legitimately ask the routing question — see
 * [com.uplinkstatus.app.state.NetworkScopeMatcher] — and it is deliberately *not* required to
 * appear in [networks]: the default network can be a transport this app doesn't filter on at
 * all (ethernet, or a VPN network layered over the real uplink).
 *
 * `null` is never a member of this type's vocabulary; "nothing has been reported yet" is
 * expressed by a `null` [ConnectivitySnapshot] reference — see [NetworkSnapshotProvider].
 */
data class ConnectivitySnapshot(
    val networks: List<NetworkSnapshot>,
    val defaultNetwork: NetworkSnapshot?,
) {
    /** True when *any* currently-connected network has WiFi transport, whether or not that
     * network is the OS's chosen default route. */
    val hasWifiTransport: Boolean get() = networks.any { it.hasWifiTransport }

    /** True when *any* currently-connected network has cellular transport, whether or not that
     * network is the OS's chosen default route. */
    val hasCellularTransport: Boolean get() = networks.any { it.hasCellularTransport }

    /** The readable SSIDs of every currently-connected WiFi network. Networks whose SSID the OS
     * wouldn't report (no location permission, `WifiManager.UNKNOWN_SSID`) contribute nothing
     * rather than a placeholder, so an unreadable SSID can never accidentally match a
     * whitelist entry. */
    val connectedWifiSsids: Set<String>
        get() = networks.mapNotNullTo(mutableSetOf()) { if (it.hasWifiTransport) it.ssid else null }

    /** True when the network general traffic actually routes over right now is a WiFi or
     * cellular network the OS has confirmed reaches the internet. */
    val hasValidatedDefaultConnection: Boolean
        get() = defaultNetwork.let {
            it != null && it.isValidated && (it.hasWifiTransport || it.hasCellularTransport)
        }

    companion object {
        /** The platform's real, positive answer "nothing is connected" — as distinct from a
         * `null` [ConnectivitySnapshot], which means nobody has said yet. Every scope mode is
         * legitimately out of scope against this. */
        val NONE = ConnectivitySnapshot(networks = emptyList(), defaultNetwork = null)
    }
}
