package com.bojustudio.pingd.app.connectivity

import kotlinx.coroutines.flow.Flow

/**
 * The seam between "how Android reports live connectivity" and Stage 4's scope-matching
 * logic. An interface (not a concrete class) for exactly the reason
 * [com.bojustudio.pingd.app.prefs.PingdPreferencesRepository] and `:core`'s `Prober`/
 * `TracerScheduler` are: it lets tests feed synthetic connectivity events (fake
 * [ConnectivitySnapshot]s pushed through a plain [kotlinx.coroutines.flow.MutableStateFlow])
 * into the exact same combining logic production code uses, instead of standing up a real
 * [android.net.ConnectivityManager] and fighting a Robolectric shadow that doesn't replay
 * [android.net.ConnectivityManager.NetworkCallback] events on its own — see
 * [ConnectivityManagerNetworkSnapshotProvider] for the real implementation and
 * `ConnectivityManagerNetworkSnapshotProviderTest` for the one test that *does* exercise it
 * end-to-end against a shadowed `ConnectivityManager`, for confidence in the wiring itself.
 */
interface NetworkSnapshotProvider {
    /**
     * Emits a new [ConnectivitySnapshot] every time the device's connectivity changes — any
     * network connecting or disconnecting, a capabilities change on a network that stays
     * connected (e.g. an SSID change while remaining on WiFi), or the OS switching which
     * network is the default route.
     *
     * The emitted value describes *every* connected WiFi/cellular network, not just the default
     * one. A phone with WiFi and cellular both up is the ordinary case, and a scope setting
     * naming a transport is asking whether that transport is connected — not whether the OS
     * happens to be routing general traffic over it. Implementations that can only see the
     * default network cannot answer that question correctly.
     *
     * Two *different* facts are representable here, and conflating them is what caused the
     * fresh-install "tracer never starts" bug:
     * - [ConnectivitySnapshot.NONE] — "the platform says nothing is connected right now."
     *   A real, positive answer; every scope mode is legitimately out of scope against it.
     * - `null` — **"nothing has been reported yet."** The absence of an answer, not an
     *   answer. Downstream must not turn this into a user-visible "not in scope" verdict;
     *   see [com.bojustudio.pingd.core.visibility.VisibilityDecider.decideOrNull].
     *
     * Implementations should keep the `null` window as short as physically possible (the real
     * one queries the platform's current networks synchronously at subscription time, so in
     * practice it never emits `null` at all), but consumers must still handle it: an
     * implementation that genuinely cannot determine the current state must be able to say so
     * rather than guess.
     */
    val snapshotFlow: Flow<ConnectivitySnapshot?>
}
