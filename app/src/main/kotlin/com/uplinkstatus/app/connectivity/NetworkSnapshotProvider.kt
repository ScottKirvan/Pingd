package com.uplinkstatus.app.connectivity

import kotlinx.coroutines.flow.Flow

/**
 * The seam between "how Android reports live connectivity" and Stage 4's scope-matching
 * logic. An interface (not a concrete class) for exactly the reason
 * [com.uplinkstatus.app.prefs.UplinkPreferencesRepository] and `:core`'s `Prober`/
 * `TracerScheduler` are: it lets tests feed synthetic connectivity events (fake
 * [NetworkSnapshot]s pushed through a plain [kotlinx.coroutines.flow.MutableStateFlow]) into
 * the exact same combining logic production code uses, instead of standing up a real
 * [android.net.ConnectivityManager] and fighting a Robolectric shadow that doesn't replay
 * [android.net.ConnectivityManager.NetworkCallback] events on its own — see
 * [ConnectivityManagerNetworkSnapshotProvider] for the real implementation and
 * `ConnectivityManagerNetworkSnapshotProviderTest` for the one test that *does* exercise it
 * end-to-end against a shadowed `ConnectivityManager`, for confidence in the wiring itself.
 */
interface NetworkSnapshotProvider {
    /**
     * Emits a new [NetworkSnapshot] every time the device's default network changes —
     * connect, disconnect, or a capabilities change while still connected (e.g. an SSID
     * change while remaining on WiFi). [NetworkSnapshot.NONE] represents "no default network
     * right now."
     */
    val snapshotFlow: Flow<NetworkSnapshot>
}
