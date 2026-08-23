package com.bojustudio.pingd.app.state

import com.bojustudio.pingd.app.connectivity.ConnectivitySnapshot
import com.bojustudio.pingd.app.prefs.NetworkScope

/**
 * The pure "is the current network in scope" decision — deliberately free of any Android
 * framework type (only [ConnectivitySnapshot], [NetworkScope], and plain [Set]/[Boolean]), so
 * it's unit-testable with plain JUnit, no Robolectric required, the same spirit as `:core`'s
 * `VisibilityDecider` even though this particular logic has to live in `:app` (it depends on
 * [NetworkScope], which Stage 3 placed in `:app.prefs`, and re-homing that enum into `:core`
 * is exactly the kind of upstream re-litigation Stage 4's brief says not to do).
 *
 * The three transport/SSID modes are answered from the *set* of connected networks, never from
 * whichever one the OS currently routes general traffic over. Those are different facts on any
 * ordinary phone: WiFi and cellular are usually both connected at once and Android names
 * validated WiFi the default, so "does the default network have cellular transport" is `false`
 * on a device with perfectly good cellular service. A setting that names a transport is asking
 * whether that transport is connected, and only [ConnectivitySnapshot.networks] can answer it.
 *
 * Per-mode reasoning:
 * - [NetworkScope.WIFI_ONLY] / [NetworkScope.CELLULAR_ONLY] / [NetworkScope.SSID_WHITELIST]
 *   check *transport* only, not [com.bojustudio.pingd.app.connectivity.NetworkSnapshot.isValidated].
 *   Whether the network actually has working internet right now is exactly what the probe
 *   cycle's freeze-on-failure behavior already communicates (a frozen tracer on a
 *   connected-but-broken network); gating scope itself on validation would collapse that into
 *   "out of scope" (DISABLED/HIDDEN) instead, hiding the frozen-tracer signal the spec relies
 *   on to distinguish "no eligible network" from "eligible network, but the probe can't get
 *   through."
 * - [NetworkScope.ANY_CONNECTION] is the one mode that asks about the *default* network, and the
 *   one mode that requires validation. It is not naming a transport to be present, it is asking
 *   whether the device has a working internet connection at all — and "the connection" a device
 *   has, in the sense that matters to this app, is the route its traffic takes: the probe opens
 *   an unbound socket, so it measures the default network and nothing else. Answering this from
 *   "some connected network is validated" would let the mode report in scope on the strength of
 *   a network the probe will never touch. Without the validation requirement, meanwhile, "any
 *   connection" would be satisfied by the mere existence of a network object, which is close to
 *   always true and wouldn't function as a meaningful scope setting at all.
 * - [NetworkScope.SSID_WHITELIST] additionally requires [hasLocationPermission] — checked
 *   explicitly here, not inferred from whether any SSID happens to be readable, so "permission
 *   not granted" is a distinct, directly testable branch rather than something that happens to
 *   fall out of how the OS behaves when ungranted.
 */
object NetworkScopeMatcher {

    fun isInScope(
        scope: NetworkScope,
        ssidWhitelist: Set<String>,
        hasLocationPermission: Boolean,
        snapshot: ConnectivitySnapshot,
    ): Boolean = when (scope) {
        NetworkScope.WIFI_ONLY -> snapshot.hasWifiTransport

        NetworkScope.ANY_CONNECTION -> snapshot.hasValidatedDefaultConnection

        NetworkScope.CELLULAR_ONLY -> snapshot.hasCellularTransport

        NetworkScope.SSID_WHITELIST ->
            hasLocationPermission && snapshot.connectedWifiSsids.any { it in ssidWhitelist }
    }
}
