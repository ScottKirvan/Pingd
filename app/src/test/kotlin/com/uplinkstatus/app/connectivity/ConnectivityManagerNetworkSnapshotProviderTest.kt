package com.uplinkstatus.app.connectivity

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * Exercises [ConnectivityManagerNetworkSnapshotProvider] against a real (Robolectric-shadowed)
 * [ConnectivityManager], to give confidence in the actual production wiring -- not just the
 * [NetworkSnapshotProvider] abstraction [NetworkScopeStatusTest] fakes out. Robolectric's
 * `ShadowConnectivityManager` records a registered [ConnectivityManager.NetworkCallback] but
 * doesn't fire it on its own, so this test grabs the callback the provider registered and
 * invokes its methods directly -- exactly the "fake/simulated NetworkCallback events" Stage
 * 4's brief describes, just driven through the real callback object this class hands to
 * [ConnectivityManager.registerDefaultNetworkCallback] rather than a hand-rolled substitute.
 *
 * [capabilitiesWith] builds fixture [NetworkCapabilities] instances via reflection rather than
 * `NetworkCapabilities.Builder`/`addTransportType`/`addCapability` directly: this project's
 * compile-time Android SDK stub jar doesn't declare those mutator methods (only getters plus a
 * no-arg constructor), even though Robolectric's real runtime class backing test execution
 * does have them, since they're long-standing public platform API. Reflecting into the loaded
 * runtime class sidesteps that compile-time gap without touching any private/hidden member --
 * every method invoked here is itself public API, just one the local stub jar happens not to
 * expose a compile-time symbol for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectivityManagerNetworkSnapshotProviderTest {

    private fun connectivityManager(): ConnectivityManager =
        checkNotNull(RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java))

    private fun capabilitiesWith(transportType: Int, validated: Boolean = true): NetworkCapabilities {
        val capabilities = NetworkCapabilities()
        val addTransportType = NetworkCapabilities::class.java.getMethod("addTransportType", Int::class.java)
        addTransportType.invoke(capabilities, transportType)
        if (validated) {
            val addCapability = NetworkCapabilities::class.java.getMethod("addCapability", Int::class.java)
            addCapability.invoke(capabilities, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        return capabilities
    }

    /** Puts the shadowed [ConnectivityManager] into "this is the current default network"
     * state -- the situation a real device is already in when this service starts -- so the
     * synchronous seed has something real to read. */
    private fun setDefaultNetwork(
        connectivityManager: ConnectivityManager,
        type: Int,
        capabilities: NetworkCapabilities,
    ) {
        shadowOf(connectivityManager).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, true),
        )
        val activeNetwork = checkNotNull(connectivityManager.activeNetwork)
        shadowOf(connectivityManager).setNetworkCapabilities(activeNetwork, capabilities)
    }

    /**
     * Regression test for issue #22 (fresh install: master toggle on, tracer stuck on the
     * paused frame until toggled off and back on).
     *
     * The first value a fresh subscription produces must be the platform's real current
     * answer, read synchronously -- not a placeholder. This class used to unconditionally
     * `trySend(NetworkSnapshot.NONE)` here, which meant the first network-scope answer, and
     * therefore the first visibility decision of every single service start, was computed
     * from a value that described nothing. On a WIFI_ONLY fresh install sitting on WiFi, that
     * made "not on WiFi" the app's opening verdict, deterministically and 100% of the time.
     *
     * Nothing is dispatched, posted, or awaited between subscribing and asserting below --
     * that is the point: this holds even if the platform's callback is slow, or (worst case)
     * never arrives at all.
     */
    @Test
    fun `the first emission reads the real current default network, before any callback fires`() = runTest {
        val connectivityManager = connectivityManager()
        setDefaultNetwork(
            connectivityManager,
            ConnectivityManager.TYPE_WIFI,
            capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI),
        )
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<NetworkSnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val first = checkNotNull(results.first()) { "the first emission must not be 'unknown'" }
        assertTrue("first emission did not see the real WiFi default network", first.hasWifiTransport)
        assertTrue(first.isValidated)
        assertFalse(first.hasCellularTransport)
    }

    @Test
    fun `a device with no default network at all reports NONE -- a real answer, not 'unknown'`() = runTest {
        val connectivityManager = connectivityManager()
        shadowOf(connectivityManager).setActiveNetworkInfo(null)
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<NetworkSnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        // "The platform says there is no default network" is a positive, actionable fact and
        // must be reported as NetworkSnapshot.NONE -- the null/"unknown" channel is reserved
        // for the cases where the platform genuinely didn't answer.
        assertEquals(NetworkSnapshot.NONE, results.first())
    }

    @Test
    fun `a later callback still supersedes the synchronously seeded snapshot`() = runTest {
        val connectivityManager = connectivityManager()
        setDefaultNetwork(
            connectivityManager,
            ConnectivityManager.TYPE_WIFI,
            capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI),
        )
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<NetworkSnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }
        assertTrue(checkNotNull(results.first()).hasWifiTransport)

        // Seeding must not turn this into a one-shot read: the callback remains the live
        // source of truth, and a subsequent change still overrides whatever was seeded.
        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        callback.onCapabilitiesChanged(
            ShadowNetwork.newInstance(104),
            capabilitiesWith(NetworkCapabilities.TRANSPORT_CELLULAR),
        )

        val latest = checkNotNull(results.last())
        assertTrue(latest.hasCellularTransport)
        assertFalse(latest.hasWifiTransport)
    }

    @Test
    fun `constructing the provider alone does not yet register a callback`() {
        val connectivityManager = connectivityManager()
        ConnectivityManagerNetworkSnapshotProvider(connectivityManager)

        // callbackFlow only registers once a collector actually subscribes to snapshotFlow --
        // the other tests below confirm exactly one callback is registered once that happens.
        assertEquals(0, shadowOf(connectivityManager).networkCallbacks.size)
    }

    @Test
    fun `onCapabilitiesChanged with a validated WiFi network is reflected in the snapshot`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<NetworkSnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        assertEquals(1, shadowOf(connectivityManager).networkCallbacks.size)
        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        val network = ShadowNetwork.newInstance(100)

        callback.onCapabilitiesChanged(network, capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI))

        val latest = checkNotNull(results.last())
        assertTrue(latest.hasWifiTransport)
        assertFalse(latest.hasCellularTransport)
        assertTrue(latest.isValidated)
    }

    @Test
    fun `onCapabilitiesChanged with a cellular network is reflected in the snapshot`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<NetworkSnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        val network = ShadowNetwork.newInstance(101)

        callback.onCapabilitiesChanged(network, capabilitiesWith(NetworkCapabilities.TRANSPORT_CELLULAR))

        val latest = checkNotNull(results.last())
        assertFalse(latest.hasWifiTransport)
        assertTrue(latest.hasCellularTransport)
    }

    @Test
    fun `onCapabilitiesChanged with an unvalidated network is reflected as not validated`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<NetworkSnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        val network = ShadowNetwork.newInstance(103)

        callback.onCapabilitiesChanged(
            network,
            capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI, validated = false),
        )

        val latest = checkNotNull(results.last())
        assertTrue(latest.hasWifiTransport)
        assertFalse(latest.isValidated)
    }

    @Test
    fun `onLost resets the snapshot to no active network`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<NetworkSnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        val network = ShadowNetwork.newInstance(102)
        callback.onCapabilitiesChanged(network, capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(checkNotNull(results.last()).hasWifiTransport)

        callback.onLost(network)

        assertEquals(NetworkSnapshot.NONE, results.last())
    }
}
