package com.uplinkstatus.app.state

import com.uplinkstatus.app.connectivity.NetworkSnapshotProvider
import com.uplinkstatus.app.prefs.UplinkPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Stage 4's real replacement for Stage 2/3's manual `NetworkScopeStatus` stand-in object (a
 * `MutableStateFlow<Boolean>` someone set by hand, defaulting to "in scope"). This is now an
 * interface with a real, reactive implementation ([ConnectivityNetworkScopeStatus]) —
 * required, not optional, because [inScopeFlow] has to react independently to *two* different
 * sources changing (live connectivity, and the persisted network-scope/SSID-whitelist
 * preference — see [NetworkScopeMatcher]'s doc and Stage 4's acceptance criteria), and a bare
 * mutable object had no way to derive that from either one on its own.
 *
 * Kept as an interface (rather than [ConnectivityNetworkScopeStatus] being the only shape) for
 * the same reason [UplinkPreferencesRepository] is one: so
 * [com.uplinkstatus.app.service.UplinkStatusService]'s tests can inject a trivial
 * [kotlinx.coroutines.flow.MutableStateFlow]-backed fake instead of standing up a real
 * [android.net.ConnectivityManager] and DataStore file just to flip "in scope" for a test.
 */
interface NetworkScopeStatus {
    /** Emits a new "is the current network in scope" value whenever either live connectivity
     * or the persisted network-scope preference changes — never requiring the other to change
     * in lockstep for the result to update. */
    val inScopeFlow: Flow<Boolean>
}

/**
 * Combines [preferencesRepository]'s persisted [com.uplinkstatus.app.prefs.NetworkScope] /
 * SSID whitelist with [snapshotProvider]'s live connectivity signal, re-running
 * [NetworkScopeMatcher] on every emission from either source. [hasLocationPermission] is a
 * function (not a value snapshotted once at construction) so it reflects the permission's
 * actual state at combine-time — invoked fresh on every connectivity or preference emission,
 * which is enough to satisfy Stage 4's acceptance criteria (permission state isn't itself
 * pushed as a third reactive stream here: neither the spec nor the brief asks the display to
 * react, with no other trigger, to a mid-run permission revocation, and in practice revoking a
 * granted runtime permission from system settings kills the app's process on the overwhelming
 * majority of Android versions this targets, so the next process start re-evaluates it anyway
 * — adding a `BroadcastReceiver`/polling loop just for that no-other-trigger edge case would
 * be exactly the scope creep the brief warns against).
 */
class ConnectivityNetworkScopeStatus(
    private val preferencesRepository: UplinkPreferencesRepository,
    private val snapshotProvider: NetworkSnapshotProvider,
    private val hasLocationPermission: () -> Boolean,
) : NetworkScopeStatus {

    override val inScopeFlow: Flow<Boolean> = combine(
        preferencesRepository.preferencesFlow,
        snapshotProvider.snapshotFlow,
    ) { preferences, snapshot ->
        NetworkScopeMatcher.isInScope(
            scope = preferences.networkScope,
            ssidWhitelist = preferences.ssidWhitelist,
            hasLocationPermission = hasLocationPermission(),
            snapshot = snapshot,
        )
    }.distinctUntilChanged()
}
