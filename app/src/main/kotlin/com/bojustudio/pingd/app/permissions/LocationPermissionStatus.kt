package com.bojustudio.pingd.app.permissions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The app's own `ACCESS_FINE_LOCATION` state, as an *event stream* rather than a question that
 * can only be asked on demand.
 *
 * Android has no callback for "this app's permissions changed," and
 * [androidx.core.content.ContextCompat.checkSelfPermission] only answers when something thinks
 * to call it. That gap matters because of how the platform hands out location-sensitive network
 * facts: the SSID/BSSID carried by [android.net.NetworkCapabilities.getTransportInfo] is
 * redacted according to the requesting app's permission state *at the moment each capabilities
 * object is delivered to a registered callback*, and is not re-evaluated afterwards. A
 * capabilities object delivered before the grant therefore stays redacted for as long as the app
 * holds it — and the platform has no reason to deliver a fresh one purely because an app's
 * permissions changed, so a device that stays on the same WiFi network can hold an unreadable
 * SSID indefinitely after the user grants precise location. Only a *fresh* read
 * ([android.net.ConnectivityManager.getNetworkCapabilities]) made after the grant reflects the
 * new permission state.
 *
 * So the grant has to be announced by the app itself. This object is where that announcement
 * lands, in the same process-wide-singleton shape
 * [com.bojustudio.pingd.app.state.PingdRuntimeStatus] and
 * [com.bojustudio.pingd.app.state.PingdActivityStatus] already use to carry facts between the
 * activity and the service (they share one process; there is no second process to bridge).
 * [report] is called from wherever the app learns its permission state — the settings screen's
 * permission-request result, and [com.bojustudio.pingd.app.MainActivity]'s `onResume` for a grant
 * made outside the app entirely — and
 * [com.bojustudio.pingd.app.connectivity.ConnectivityManagerNetworkSnapshotProvider] collects
 * [changes] to force exactly one fresh, synchronous re-read of the platform's current networks
 * per genuine change.
 *
 * This deliberately carries no permission *value*. Whether the permission is granted is still
 * read straight from the platform at the point of use
 * ([com.bojustudio.pingd.app.state.NetworkScopeMatcher]'s `hasLocationPermission` argument), which
 * cannot go stale; the only thing missing there was a reason to look again, which is all this
 * provides.
 */
object LocationPermissionStatus {

    /** A revision counter, not a permission value: consumers re-read the real state themselves.
     * Bumping an [Int] is what turns "the same permission was granted again" into a
     * distinguishable emission for [changes] without exposing a value anyone could mistake for
     * an authoritative answer. */
    private val revision = MutableStateFlow(0)

    /** Guarded by `this` (see [report]) — the last state the app actually observed, so a
     * re-report of the same state is not an event. */
    private var lastObserved: Boolean? = null

    /**
     * Emits once per genuine change in the app's location-permission state, *after* collection
     * begins.
     *
     * The current state at subscription time is deliberately dropped: a collector that has just
     * subscribed has, by definition, just read the live permission state for itself (the
     * provider's synchronous seed does exactly that), so replaying it would only cause a
     * duplicate read of something already known.
     */
    val changes: Flow<Unit> = revision.drop(1).map { }

    /**
     * Records the app's current location-permission state, emitting on [changes] only if it
     * differs from the last state recorded.
     *
     * Safe (and expected) to call redundantly — on every resume, on every permission-request
     * result — precisely because an unchanged state is not an event: nothing downstream is asked
     * to do work for a grant that was already known about.
     */
    @Synchronized
    fun report(granted: Boolean) {
        if (lastObserved == granted) return
        lastObserved = granted
        revision.update { it + 1 }
    }

    /** Process-wide singleton; tests must reset it between runs. Production code never calls
     * this. */
    @Synchronized
    internal fun resetForTest() {
        lastObserved = null
        revision.value = 0
    }
}
