package com.uplinkstatus.app.connectivity

import android.net.ConnectivityManager
import android.net.Network
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
 * [NetworkSnapshotProvider] abstraction [com.uplinkstatus.app.state.NetworkScopeStatusTest]
 * fakes out. Robolectric's `ShadowConnectivityManager` records a registered
 * [ConnectivityManager.NetworkCallback] but doesn't fire it on its own, so this test grabs the
 * callbacks the provider registered and invokes their methods directly -- exactly the
 * "fake/simulated NetworkCallback events" Stage 4's brief describes, just driven through the
 * real callback objects this class hands to [ConnectivityManager] rather than a hand-rolled
 * substitute.
 *
 * The shadow collects both registered callbacks into one *unordered* set, which is why the
 * provider's callbacks are named classes: [trackedNetworksCallback] and [defaultNetworkCallback]
 * below pick out the right one by type rather than by a registration order that isn't real.
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

    /**
     * `NET_CAPABILITY_INTERNET` is on every fixture because the provider's [android.net.NetworkRequest]
     * requires it: the tracked set is meant to be "networks carrying this device's internet,"
     * not every PDN a phone holds open, and a fixture without it would be describing an
     * IMS/MMS-style network the production filter deliberately drops.
     */
    private fun capabilitiesWith(transportType: Int, validated: Boolean = true): NetworkCapabilities {
        val capabilities = NetworkCapabilities()
        val addTransportType = NetworkCapabilities::class.java.getMethod("addTransportType", Int::class.java)
        addTransportType.invoke(capabilities, transportType)
        val addCapability = NetworkCapabilities::class.java.getMethod("addCapability", Int::class.java)
        addCapability.invoke(capabilities, NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) {
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
    ): Network {
        shadowOf(connectivityManager).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, true),
        )
        val activeNetwork = checkNotNull(connectivityManager.activeNetwork)
        shadowOf(connectivityManager).setNetworkCapabilities(activeNetwork, capabilities)
        return activeNetwork
    }

    /** Adds a network the device is connected to but that is *not* the default route -- the
     * cellular half of "on WiFi with mobile data still up," which is what an ordinary phone
     * looks like and what the provider previously could not see at all. */
    private fun addNonDefaultNetwork(
        connectivityManager: ConnectivityManager,
        netId: Int,
        type: Int,
        capabilities: NetworkCapabilities,
    ): Network {
        val network = ShadowNetwork.newInstance(netId)
        shadowOf(connectivityManager).addNetwork(
            network,
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, true),
        )
        shadowOf(connectivityManager).setNetworkCapabilities(network, capabilities)
        return network
    }

    private fun trackedNetworksCallback(connectivityManager: ConnectivityManager): TrackedNetworksCallback =
        shadowOf(connectivityManager).networkCallbacks.filterIsInstance<TrackedNetworksCallback>().single()

    private fun defaultNetworkCallback(connectivityManager: ConnectivityManager): DefaultNetworkCallback =
        shadowOf(connectivityManager).networkCallbacks.filterIsInstance<DefaultNetworkCallback>().single()

    /**
     * Regression test for issue #27 (Cellular-only scope reports out of scope whenever WiFi is
     * also connected; turning WiFi off is what makes it start working).
     *
     * The device here is in the state essentially every smartphone is in: associated with WiFi,
     * which the OS has picked as the default route, *and* holding a cellular data connection.
     * The provider must report both networks. Reading transports off only the default network --
     * what this class used to do -- gives "cellular not connected" on a phone with perfectly good
     * cellular service, because the default network is the WiFi one and its capabilities simply
     * do not mention cellular.
     */
    @Test
    fun `every connected network is reported, not just the one the OS routes traffic over`() = runTest {
        val connectivityManager = connectivityManager()
        setDefaultNetwork(
            connectivityManager,
            ConnectivityManager.TYPE_WIFI,
            capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI),
        )
        addNonDefaultNetwork(
            connectivityManager,
            netId = 200,
            type = ConnectivityManager.TYPE_MOBILE,
            capabilities = capabilitiesWith(NetworkCapabilities.TRANSPORT_CELLULAR),
        )
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<ConnectivitySnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val first = checkNotNull(results.first()) { "the first emission must not be 'unknown'" }
        assertTrue("the connected WiFi network was not reported", first.hasWifiTransport)
        assertTrue("the connected cellular network was not reported", first.hasCellularTransport)
        // ...and the default route is still tracked, separately, as exactly one of them.
        assertTrue(checkNotNull(first.defaultNetwork).hasWifiTransport)
        assertFalse(checkNotNull(first.defaultNetwork).hasCellularTransport)
    }

    /**
     * Regression test for issue #22 (fresh install: master toggle on, tracer stuck on the
     * paused frame until toggled off and back on).
     *
     * The first value a fresh subscription produces must be the platform's real current
     * answer, read synchronously -- not a placeholder. This class used to unconditionally
     * send an empty snapshot here, which meant the first network-scope answer, and
     * therefore the first visibility decision of every single service start, was computed
     * from a value that described nothing. On a WiFi-only fresh install sitting on WiFi, that
     * made "not on WiFi" the app's opening verdict, deterministically and 100% of the time.
     *
     * Nothing is dispatched, posted, or awaited between subscribing and asserting below --
     * that is the point: this holds even if the platform's callbacks are slow, or (worst case)
     * never arrive at all.
     */
    @Test
    fun `the first emission reads the real current networks, before any callback fires`() = runTest {
        val connectivityManager = connectivityManager()
        setDefaultNetwork(
            connectivityManager,
            ConnectivityManager.TYPE_WIFI,
            capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI),
        )
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<ConnectivitySnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val first = checkNotNull(results.first()) { "the first emission must not be 'unknown'" }
        assertTrue("first emission did not see the real WiFi network", first.hasWifiTransport)
        assertFalse(first.hasCellularTransport)
        assertTrue(checkNotNull(first.defaultNetwork).isValidated)
    }

    @Test
    fun `a device with nothing connected at all reports NONE -- a real answer, not 'unknown'`() = runTest {
        val connectivityManager = connectivityManager()
        shadowOf(connectivityManager).setActiveNetworkInfo(null)
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<ConnectivitySnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        // "The platform says nothing is connected" is a positive, actionable fact and must be
        // reported as ConnectivitySnapshot.NONE -- the null/"unknown" channel is reserved for
        // the cases where the platform genuinely didn't answer.
        assertEquals(ConnectivitySnapshot.NONE, results.first())
    }

    @Test
    fun `a later callback still supersedes the synchronously seeded snapshot`() = runTest {
        val connectivityManager = connectivityManager()
        val wifi = setDefaultNetwork(
            connectivityManager,
            ConnectivityManager.TYPE_WIFI,
            capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI),
        )
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<ConnectivitySnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }
        assertTrue(checkNotNull(results.first()).hasWifiTransport)

        // Seeding must not turn this into a one-shot read: the callbacks remain the live
        // source of truth, and a subsequent change still overrides whatever was seeded.
        trackedNetworksCallback(connectivityManager).onLost(wifi)
        trackedNetworksCallback(connectivityManager).onCapabilitiesChanged(
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
        // the other tests below confirm what is registered once that happens.
        assertEquals(0, shadowOf(connectivityManager).networkCallbacks.size)
    }

    @Test
    fun `subscribing registers one all-networks callback and one default-network callback`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(mutableListOf())
        }

        // Two registrations, not one: the set of connected networks and the OS's chosen default
        // route are different facts and neither can be derived from the other.
        assertEquals(2, shadowOf(connectivityManager).networkCallbacks.size)
        trackedNetworksCallback(connectivityManager)
        defaultNetworkCallback(connectivityManager)
    }

    @Test
    fun `onCapabilitiesChanged with a validated WiFi network is reflected in the snapshot`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<ConnectivitySnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val network = ShadowNetwork.newInstance(100)
        trackedNetworksCallback(connectivityManager)
            .onCapabilitiesChanged(network, capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI))

        val latest = checkNotNull(results.last())
        assertTrue(latest.hasWifiTransport)
        assertFalse(latest.hasCellularTransport)
    }

    @Test
    fun `onCapabilitiesChanged with a cellular network is reflected in the snapshot`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<ConnectivitySnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val network = ShadowNetwork.newInstance(101)
        trackedNetworksCallback(connectivityManager)
            .onCapabilitiesChanged(network, capabilitiesWith(NetworkCapabilities.TRANSPORT_CELLULAR))

        val latest = checkNotNull(results.last())
        assertFalse(latest.hasWifiTransport)
        assertTrue(latest.hasCellularTransport)
    }

    @Test
    fun `two networks arriving on separate callbacks are both kept`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<ConnectivitySnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val callback = trackedNetworksCallback(connectivityManager)
        val wifi = ShadowNetwork.newInstance(110)
        val cellular = ShadowNetwork.newInstance(111)
        callback.onCapabilitiesChanged(wifi, capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI))
        callback.onCapabilitiesChanged(cellular, capabilitiesWith(NetworkCapabilities.TRANSPORT_CELLULAR))

        // The second network's arrival must accumulate, not replace: this is the live-callback
        // half of issue #27, the seed being the other half.
        val latest = checkNotNull(results.last())
        assertEquals(2, latest.networks.size)
        assertTrue(latest.hasWifiTransport)
        assertTrue(latest.hasCellularTransport)
    }

    @Test
    fun `onLost removes only the network that went away`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<ConnectivitySnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val callback = trackedNetworksCallback(connectivityManager)
        val wifi = ShadowNetwork.newInstance(120)
        val cellular = ShadowNetwork.newInstance(121)
        callback.onCapabilitiesChanged(wifi, capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI))
        callback.onCapabilitiesChanged(cellular, capabilitiesWith(NetworkCapabilities.TRANSPORT_CELLULAR))

        callback.onLost(cellular)

        // Losing one network must not erase what is known about the others still up -- the
        // bookkeeping the whole multi-network shape depends on.
        val latest = checkNotNull(results.last())
        assertEquals(1, latest.networks.size)
        assertTrue(latest.hasWifiTransport)
        assertFalse(latest.hasCellularTransport)
    }

    @Test
    fun `onCapabilitiesChanged with an unvalidated network is reflected as not validated`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<ConnectivitySnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        defaultNetworkCallback(connectivityManager).onCapabilitiesChanged(
            ShadowNetwork.newInstance(103),
            capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI, validated = false),
        )

        val latest = checkNotNull(results.last())
        assertTrue(checkNotNull(latest.defaultNetwork).hasWifiTransport)
        assertFalse(checkNotNull(latest.defaultNetwork).isValidated)
    }

    @Test
    fun `the default route is tracked independently of the set of connected networks`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<ConnectivitySnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        trackedNetworksCallback(connectivityManager).onCapabilitiesChanged(
            ShadowNetwork.newInstance(130),
            capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI),
        )
        // The OS names cellular the default route while WiFi stays connected -- the two facts
        // disagreeing is the normal case, not an anomaly, and each must survive the other.
        defaultNetworkCallback(connectivityManager).onCapabilitiesChanged(
            ShadowNetwork.newInstance(131),
            capabilitiesWith(NetworkCapabilities.TRANSPORT_CELLULAR),
        )

        val latest = checkNotNull(results.last())
        assertTrue("the connected WiFi network was dropped", latest.hasWifiTransport)
        assertTrue(checkNotNull(latest.defaultNetwork).hasCellularTransport)
    }

    @Test
    fun `losing the default route clears it without clearing the connected networks`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<ConnectivitySnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val wifi = ShadowNetwork.newInstance(140)
        trackedNetworksCallback(connectivityManager)
            .onCapabilitiesChanged(wifi, capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI))
        defaultNetworkCallback(connectivityManager)
            .onCapabilitiesChanged(wifi, capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI))

        defaultNetworkCallback(connectivityManager).onLost(wifi)

        val latest = checkNotNull(results.last())
        assertEquals(null, latest.defaultNetwork)
        assertTrue(latest.hasWifiTransport)
    }

    @Test
    fun `losing every network reports NONE`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<ConnectivitySnapshot?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val network = ShadowNetwork.newInstance(102)
        trackedNetworksCallback(connectivityManager)
            .onCapabilitiesChanged(network, capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI))
        defaultNetworkCallback(connectivityManager)
            .onCapabilitiesChanged(network, capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(checkNotNull(results.last()).hasWifiTransport)

        trackedNetworksCallback(connectivityManager).onLost(network)
        defaultNetworkCallback(connectivityManager).onLost(network)

        assertEquals(ConnectivitySnapshot.NONE, results.last())
    }
}
