package com.uplinkstatus.app.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [NetworkSnapshotProvider] test double -- the "simulated `NetworkCallback` events"
 * Stage 4's brief asks for, expressed as pushes onto a [MutableStateFlow] rather than actually
 * driving a real [android.net.ConnectivityManager.NetworkCallback] object. Tests set [snapshot]
 * to simulate a connect, disconnect, or capabilities-only change (e.g. an SSID change while
 * remaining on WiFi) and assert on what a collector further downstream (
 * [com.uplinkstatus.app.state.ConnectivityNetworkScopeStatus], or
 * [com.uplinkstatus.app.service.UplinkStatusService] end-to-end) derives from it.
 */
internal class FakeNetworkSnapshotProvider(
    initial: ConnectivitySnapshot? = null,
) : NetworkSnapshotProvider {

    private val state = MutableStateFlow(initial)

    override val snapshotFlow: Flow<ConnectivitySnapshot?> = state.asStateFlow()

    /** `null` reproduces the pre-report window of a real subscription: connectivity has said
     * nothing yet. It is the default precisely so a test that means "no network" has to say
     * [ConnectivitySnapshot.NONE] out loud rather than getting it by accident -- the two being
     * interchangeable is the bug this seam exists to keep from coming back. */
    var snapshot: ConnectivitySnapshot?
        get() = state.value
        set(value) {
            state.value = value
        }
}
