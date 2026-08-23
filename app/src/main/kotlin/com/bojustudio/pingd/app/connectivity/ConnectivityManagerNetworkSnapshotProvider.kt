package com.bojustudio.pingd.app.connectivity

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.bojustudio.pingd.app.permissions.LocationPermissionStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** Diagnostic only -- see the logging in [ConnectivityManagerNetworkSnapshotProvider] below.
 * Not read by any production logic; exists so `adb logcat -s PingdConnectivity` can show
 * exactly what the platform reports for every network the device holds, since that's the one
 * thing a JVM/Robolectric test cannot observe. */
private const val TAG = "PingdConnectivity"

/**
 * The real, [android.net.ConnectivityManager]-backed [NetworkSnapshotProvider].
 *
 * Watches **every** connected WiFi/cellular network, via
 * [ConnectivityManager.registerNetworkCallback] with an explicit [NetworkRequest], and
 * *separately* tracks which of the device's networks is the default route, via
 * [ConnectivityManager.registerDefaultNetworkCallback]. Both are needed because the scope modes
 * ask two genuinely different questions:
 * - "is transport X connected right now" (WiFi only / Cellular only / SSID whitelist) --
 *   answerable only from the full set. A phone on WiFi with cellular service up holds both
 *   networks at once and the OS names exactly one of them (validated WiFi, nearly always) the
 *   default; reading transports off that one network reports "cellular not connected" on a
 *   device that is plainly connected to cellular.
 * - "is the route general traffic takes actually working" (Any connection) -- answerable only
 *   from the default network.
 *
 * The [NetworkRequest] deliberately keeps [NetworkRequest.Builder]'s implicit
 * `NOT_VPN`/`NOT_RESTRICTED`/`TRUSTED` defaults and adds
 * [NetworkCapabilities.NET_CAPABILITY_INTERNET]: the set is meant to be "networks that carry
 * this device's internet," so it must not be padded out by the special-purpose PDNs a phone
 * quietly holds open (IMS/VoLTE, MMS, SUPL) -- those carry
 * [NetworkCapabilities.TRANSPORT_CELLULAR] with no `INTERNET` capability, and counting them
 * would make `Cellular only` report in scope on a device with mobile data switched off.
 * `INTERNET` is a *declaration* ("this network is for general internet"), not a verdict;
 * [NetworkCapabilities.NET_CAPABILITY_VALIDATED] is the verdict, and it is deliberately not
 * required here so a connected-but-broken network (captive portal, dead WAN) still counts as in
 * scope and surfaces through the probe cycle's freeze-on-failure behavior rather than being
 * hidden as "out of scope."
 *
 * [ConnectivityManager.NetworkCallback.onCapabilitiesChanged] is the source of truth for a
 * network's contents (not `onAvailable`, which doesn't carry capabilities): the platform always
 * follows an `onAvailable` with an `onCapabilitiesChanged` shortly after, and the same callback
 * fires again on a capabilities-only change while the network stays connected -- precisely the
 * "SSID change while still on WiFi" case Stage 4's brief calls out.
 * [ConnectivityManager.NetworkCallback.onLost] removes exactly the one [Network] that went away
 * and leaves every other tracked network untouched, which is the bookkeeping the whole
 * multi-network shape rests on.
 *
 * SSID is read off [NetworkCapabilities.getTransportInfo] (a [WifiInfo] when the transport is
 * WiFi) rather than a separate [WifiManager.getConnectionInfo] call: it comes from the exact
 * capabilities snapshot the callback just delivered, so it can never be stale relative to
 * whatever else this snapshot reports, and it naturally requires no separate wiring for the
 * "SSID changed but capabilities object is otherwise the same shape" case. Without
 * `ACCESS_FINE_LOCATION` granted, the OS reports [WifiManager.UNKNOWN_SSID] here rather than
 * throwing -- this class treats that the same as "no SSID," and the actual
 * permission-gating for [com.bojustudio.pingd.app.prefs.NetworkScope.SSID_WHITELIST] happens one
 * layer up, in [com.bojustudio.pingd.app.state.NetworkScopeMatcher], so that behavior is covered
 * by a plain unit test rather than depending on the OS's ungranted-permission behavior at all.
 *
 * That SSID, though, is *redacted on delivery*. Android decides how much of a location-sensitive
 * field a capabilities object may carry from the requesting app's permission state at the moment
 * that object is dispatched to a registered callback, and never revisits that decision for an
 * object already delivered. Nothing about the network itself changes when the *app* gains
 * `ACCESS_FINE_LOCATION`, so the platform has no reason to dispatch a replacement — a device
 * sitting on one WiFi network can therefore keep reporting an unreadable SSID indefinitely after
 * the user grants precise location, which under
 * [com.bojustudio.pingd.app.prefs.NetworkScope.SSID_WHITELIST] reads as "no whitelisted network is
 * connected" forever. [refreshSignals] closes that hole: every emission triggers a fresh
 * synchronous [readCurrentConnectivity], whose capabilities objects are produced *now* and are
 * therefore redacted against the permission state the app has *now*.
 *
 * @param refreshSignals fires when something the platform will not re-dispatch on its own has
 *   changed — in production, [LocationPermissionStatus.changes]. This is strictly additive to
 *   the callbacks: they remain the live source of truth for every genuine network change, and a
 *   refresh replaces this class's knowledge with the platform's complete current answer rather
 *   than patching part of it.
 */
class ConnectivityManagerNetworkSnapshotProvider(
    private val connectivityManager: ConnectivityManager,
    private val refreshSignals: Flow<Unit> = LocationPermissionStatus.changes,
) : NetworkSnapshotProvider {

    override val snapshotFlow: Flow<ConnectivitySnapshot?> = callbackFlow {
        // Every mutation below happens either on the collecting coroutine's thread (the
        // synchronous seed) or on the platform's internal callback thread, so the accumulated
        // state and the emission derived from it are read and written together under one lock.
        // Without that, a snapshot could be built from a half-applied update.
        val lock = Any()
        val networks = LinkedHashMap<Network, NetworkSnapshot>()
        var defaultNetwork: NetworkSnapshot? = null

        fun emitLocked(reason: String) {
            val snapshot = ConnectivitySnapshot(
                networks = networks.values.toList(),
                defaultNetwork = defaultNetwork,
            )
            Log.d(TAG, "$reason -> ${snapshot.describeForLog()}")
            trySend(snapshot)
        }

        // Seed from the platform's *current* answer before registering, rather than from a
        // placeholder. Registering a callback doesn't synchronously replay anything, and an
        // earlier version of this line sent an empty snapshot unconditionally -- which
        // downstream could not distinguish from a real "no network," so the first visibility
        // decision of every service start was deterministically DISABLED/HIDDEN no matter what
        // the device was actually connected to. On a fresh install that is precisely the
        // "master toggle is on but the tracer stays on the paused frame" report: the wrong
        // answer was not a race, it was computed from a value that meant nothing.
        //
        // getAllNetworks()/getActiveNetwork()/getNetworkCapabilities() answer the same questions
        // the callbacks do, synchronously and without waiting for any dispatch, so the very
        // first emission is already correct -- and stays correct even in the worst case where
        // the callbacks, for whatever reason, never arrive at all. Seeding the *whole set*
        // (not just the default network) matters for the same reason the set exists: a seed
        // that knew only the default network would open every Cellular-only session on a
        // WiFi-preferring phone with a confidently wrong "cellular not connected."
        //
        // Read *before* registering, deliberately: registration always delivers fresh
        // onCapabilitiesChanged callbacks for the current networks moments later, so anything
        // that changes in the gap is corrected by those. Doing it the other way round would
        // risk this synchronous read overwriting a newer callback value with an older one.
        //
        // Note this same read is what refreshSignals re-runs later (see below): "ask the
        // platform, right now, in this process, with this app's current permissions" is the one
        // operation that cannot return anything stale, whether the staleness came from a
        // callback that hasn't arrived yet or from one that arrived while the app was allowed
        // to see less than it is allowed to see now.
        val seed = readCurrentConnectivity()
        if (seed == null) {
            // "We were not allowed to look" is the definition of "not known," and it must not
            // become "confirmed no network."
            Log.d(TAG, "seed (synchronous, pre-registration) -> null (platform did not answer)")
            trySend(null)
        } else {
            synchronized(lock) {
                networks.putAll(seed.networks)
                defaultNetwork = seed.defaultNetwork
                emitLocked("seed (synchronous, pre-registration)")
            }
        }

        val trackedNetworksCallback = TrackedNetworksCallback(
            onNetworkChanged = { network, capabilities ->
                val snapshot = capabilities.toSnapshot()
                synchronized(lock) {
                    networks[network] = snapshot
                    // The raw per-network facts the platform handed back, unfiltered. If a
                    // device is genuinely associated with a network that isn't showing up in
                    // the aggregate, this line is how to prove where it was lost.
                    emitLocked("onCapabilitiesChanged[$network ${snapshot.describeForLog()}]")
                }
            },
            onNetworkGone = { network ->
                synchronized(lock) {
                    // Removing exactly one key. Losing one network must never erase what is
                    // known about the others that are still up.
                    networks.remove(network)
                    emitLocked("onLost[$network]")
                }
            },
        )

        val defaultNetworkCallback = DefaultNetworkCallback(
            onDefaultChanged = { capabilities ->
                val snapshot = capabilities.toSnapshot()
                synchronized(lock) {
                    defaultNetwork = snapshot
                    emitLocked("default onCapabilitiesChanged[${snapshot.describeForLog()}]")
                }
            },
            onDefaultGone = {
                synchronized(lock) {
                    defaultNetwork = null
                    emitLocked("default network gone")
                }
            },
        )

        // No explicit Handler overload on purpose. It looks like these calls need one -- they are
        // made from a Dispatchers.Default pool thread, which has no prepared Looper -- but
        // ConnectivityManager's no-Handler overloads do not use the calling thread's Looper at
        // all: they delegate to getDefaultHandler(), a static CallbackHandler built on
        // ConnectivityThread.getInstanceLooper() (a dedicated self-starting HandlerThread inside
        // the app process), and the platform's own javadoc states "The callback is invoked on the
        // default internal Handler." Verified against the AOSP android-34 sources for
        // ConnectivityManager/ConnectivityThread. Passing a Handler here would only change which
        // thread callbacks land on -- it would fix nothing -- so it is left off rather than added
        // as cargo cult; the correctness of the first emission is guaranteed by the synchronous
        // seed above instead. (It also means both callbacks are dispatched on that one thread and
        // therefore never contend, though the lock above does not depend on that holding.)
        connectivityManager.registerNetworkCallback(internetNetworkRequest(), trackedNetworksCallback)
        connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback)

        // The only thing the callbacks above cannot report: a change in what this app is
        // *allowed to see* about networks that themselves did not change. Re-reading the
        // platform is the entire remedy -- the values a fresh read returns are redacted against
        // the permission state as of that read, so an SSID that was unreadable when its
        // capabilities object was dispatched becomes readable here without the device having to
        // leave and rejoin the network.
        launch {
            refreshSignals.collect {
                // The read happens *under the lock*, unlike the seed's (which runs before any
                // callback can exist). A refresh is a whole-picture replacement, and doing the
                // read outside the lock would let a callback land in between and then be
                // overwritten by an answer from before it -- resurrecting a network that had
                // just been lost, with no second onLost to correct it.
                synchronized(lock) {
                    val fresh = readCurrentConnectivity()
                    if (fresh == null) {
                        // "Not allowed to look" cannot demote what is already known to
                        // "nothing connected"; the callbacks stay in charge.
                        Log.d(TAG, "refresh -> platform did not answer; keeping the known state")
                    } else {
                        networks.clear()
                        networks.putAll(fresh.networks)
                        defaultNetwork = fresh.defaultNetwork
                        emitLocked("refresh (synchronous re-read after a permission change)")
                    }
                }
            }
        }

        awaitClose {
            connectivityManager.unregisterNetworkCallback(trackedNetworksCallback)
            connectivityManager.unregisterNetworkCallback(defaultNetworkCallback)
        }
    }

    /**
     * The platform's current answer for the whole connectivity picture, read synchronously and
     * keyed the same way the callbacks key it, so the two can be merged without translation.
     *
     * Returns an empty result for the real, positive answer "nothing is connected," and `null`
     * only for the genuinely indeterminate case: the platform refused the query outright. `null`
     * leaves the decision to the callbacks rather than fabricating a "not in scope" verdict --
     * the same distinction this whole class's contract rests on.
     *
     * A network that disappears between being listed and having its capabilities read is simply
     * absent from the result: it is gone, which is a real answer about that network, not an
     * unknown about the device.
     */
    private fun readCurrentConnectivity(): SeededConnectivity? =
        try {
            // getAllNetworks() is deprecated (API 31) in favour of NetworkCallback, but a
            // callback is exactly what this read exists to not have to wait for, and there is no
            // non-deprecated synchronous equivalent that reports more than the default network.
            @Suppress("DEPRECATION")
            val allNetworks = connectivityManager.allNetworks
            val tracked = LinkedHashMap<Network, NetworkSnapshot>()
            for (network in allNetworks) {
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (capabilities.isInternetWifiOrCellular()) {
                    tracked[network] = capabilities.toSnapshot()
                }
            }
            val default = connectivityManager.activeNetwork
                ?.let { connectivityManager.getNetworkCapabilities(it) }
                ?.toSnapshot()
            SeededConnectivity(networks = tracked, defaultNetwork = default)
        } catch (_: SecurityException) {
            // ACCESS_NETWORK_STATE is a normal (install-time) permission and is manifest
            // declared, so this is not expected -- but it must not become "confirmed no network."
            null
        }
}

/** The synchronous seed's result, keyed by [Network] so it merges straight into the same
 * bookkeeping the callbacks maintain. */
private class SeededConnectivity(
    val networks: Map<Network, NetworkSnapshot>,
    val defaultNetwork: NetworkSnapshot?,
)

/**
 * Receives every connected network matching [internetNetworkRequest].
 *
 * A named class rather than an anonymous object purely so tests can tell the two registered
 * callbacks apart: Robolectric's `ShadowConnectivityManager` drops every registered callback
 * into one unordered set, and a test that drove the wrong one would prove nothing.
 */
internal class TrackedNetworksCallback(
    private val onNetworkChanged: (Network, NetworkCapabilities) -> Unit,
    private val onNetworkGone: (Network) -> Unit,
) : ConnectivityManager.NetworkCallback() {

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        onNetworkChanged(network, capabilities)
    }

    override fun onLost(network: Network) {
        onNetworkGone(network)
    }
}

/**
 * Receives only the network the OS routes general traffic over. Tracked separately from
 * [TrackedNetworksCallback], never inferred from it: the default network can be a transport the
 * tracked set filters out entirely (ethernet, or a VPN network layered over the real uplink).
 */
internal class DefaultNetworkCallback(
    private val onDefaultChanged: (NetworkCapabilities) -> Unit,
    private val onDefaultGone: () -> Unit,
) : ConnectivityManager.NetworkCallback() {

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        onDefaultChanged(capabilities)
    }

    override fun onLost(network: Network) {
        onDefaultGone()
    }

    override fun onUnavailable() {
        onDefaultGone()
    }
}

/**
 * Matches every connected network that carries this device's general internet traffic over WiFi
 * or cellular. [NetworkRequest.Builder.addTransportType] is OR semantics across transports, and
 * the builder's unstated defaults (`NOT_VPN`, `NOT_RESTRICTED`, `TRUSTED`) are kept on purpose --
 * see the class doc for why the special-purpose cellular PDNs they exclude must stay excluded.
 */
private fun internetNetworkRequest(): NetworkRequest = NetworkRequest.Builder()
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
    .build()

/** The same predicate [internetNetworkRequest] expresses, applied by hand to the synchronous
 * seed's `getAllNetworks()` results, which arrive unfiltered. Kept next to the request itself so
 * the two can't drift into disagreeing about what the tracked set contains. */
private fun NetworkCapabilities.isInternetWifiOrCellular(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        (
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            )

private fun NetworkCapabilities.toSnapshot(): NetworkSnapshot {
    val wifiInfo = transportInfo as? WifiInfo
    val ssid = wifiInfo?.ssid
        ?.takeUnless { it == WifiManager.UNKNOWN_SSID }
        ?.trim('"')
        ?.takeUnless { it.isEmpty() }

    return NetworkSnapshot(
        hasWifiTransport = hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        hasCellularTransport = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        isValidated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        ssid = ssid,
    )
}

/** Diagnostic only. */
private fun NetworkSnapshot.describeForLog(): String =
    "wifi=$hasWifiTransport cellular=$hasCellularTransport validated=$isValidated ssid=${ssid ?: "none"}"

/** Diagnostic only. Prints the whole set rather than a summary of it: the point of the log is to
 * be able to see, from a real device, exactly which networks the platform reported and which one
 * it chose as the default -- the disagreement between those two being the thing that cannot be
 * observed from anywhere but a device. */
private fun ConnectivitySnapshot.describeForLog(): String =
    "networks=" +
        (
            if (networks.isEmpty()) {
                "none"
            } else {
                networks.joinToString(prefix = "[", postfix = "]") { it.describeForLog() }
            }
            ) +
        " default=" + (defaultNetwork?.describeForLog() ?: "none")
