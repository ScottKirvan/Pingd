package com.uplinkstatus.app.state

import com.uplinkstatus.app.connectivity.NetworkSnapshot
import com.uplinkstatus.app.prefs.NetworkScope

/**
 * The pure "is the current network in scope" decision — deliberately free of any Android
 * framework type (only [NetworkSnapshot], [NetworkScope], and plain [Set]/[Boolean]), so it's
 * unit-testable with plain JUnit, no Robolectric required, the same spirit as `:core`'s
 * `VisibilityDecider` even though this particular logic has to live in `:app` (it depends on
 * [NetworkScope], which Stage 3 placed in `:app.prefs`, and re-homing that enum into `:core`
 * is exactly the kind of upstream re-litigation Stage 4's brief says not to do).
 *
 * Per-mode reasoning:
 * - [NetworkScope.WIFI_ONLY] / [NetworkScope.CELLULAR_ONLY] / [NetworkScope.SSID_WHITELIST]
 *   check *transport* only, not [NetworkSnapshot.isValidated]. Whether the network actually
 *   has working internet right now is exactly what the probe cycle's freeze-on-failure
 *   behavior already communicates (a frozen tracer on a connected-but-broken network); gating
 *   scope itself on validation would collapse that into "out of scope" (DISABLED/HIDDEN)
 *   instead, hiding the frozen-tracer signal the spec relies on to distinguish "no eligible
 *   network" from "eligible network, but the probe can't get through."
 * - [NetworkScope.ANY_CONNECTION] is the one mode that does require
 *   [NetworkSnapshot.isValidated]: without *some* notion of "is there actually a working
 *   network," "any connection" would be satisfied by the mere existence of a default network
 *   object, which is close to always true and wouldn't function as a meaningful scope setting
 *   at all.
 * - [NetworkScope.SSID_WHITELIST] additionally requires [hasLocationPermission] — checked
 *   explicitly here, not inferred from whether [NetworkSnapshot.ssid] happens to be null, so
 *   "permission not granted" is a distinct, directly testable branch rather than something
 *   that happens to fall out of how the OS behaves when ungranted.
 */
object NetworkScopeMatcher {

    fun isInScope(
        scope: NetworkScope,
        ssidWhitelist: Set<String>,
        hasLocationPermission: Boolean,
        snapshot: NetworkSnapshot,
    ): Boolean = when (scope) {
        NetworkScope.WIFI_ONLY -> snapshot.hasWifiTransport

        NetworkScope.ANY_CONNECTION ->
            snapshot.isValidated && (snapshot.hasWifiTransport || snapshot.hasCellularTransport)

        NetworkScope.CELLULAR_ONLY -> snapshot.hasCellularTransport

        NetworkScope.SSID_WHITELIST ->
            hasLocationPermission &&
                snapshot.hasWifiTransport &&
                snapshot.ssid != null &&
                snapshot.ssid in ssidWhitelist
    }
}
