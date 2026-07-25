package com.uplinkstatus.app.connectivity

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    @Test
    fun `reports no network before any callback has fired`() = runTest {
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager())
        val results = mutableListOf<NetworkSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        assertEquals(NetworkSnapshot.NONE, results.last())
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
        val results = mutableListOf<NetworkSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        assertEquals(1, shadowOf(connectivityManager).networkCallbacks.size)
        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        val network = ShadowNetwork.newInstance(100)

        callback.onCapabilitiesChanged(network, capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI))

        val latest = results.last()
        assertTrue(latest.hasWifiTransport)
        assertFalse(latest.hasCellularTransport)
        assertTrue(latest.isValidated)
    }

    @Test
    fun `onCapabilitiesChanged with a cellular network is reflected in the snapshot`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<NetworkSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        val network = ShadowNetwork.newInstance(101)

        callback.onCapabilitiesChanged(network, capabilitiesWith(NetworkCapabilities.TRANSPORT_CELLULAR))

        val latest = results.last()
        assertFalse(latest.hasWifiTransport)
        assertTrue(latest.hasCellularTransport)
    }

    @Test
    fun `onCapabilitiesChanged with an unvalidated network is reflected as not validated`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<NetworkSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        val network = ShadowNetwork.newInstance(103)

        callback.onCapabilitiesChanged(
            network,
            capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI, validated = false),
        )

        val latest = results.last()
        assertTrue(latest.hasWifiTransport)
        assertFalse(latest.isValidated)
    }

    @Test
    fun `onLost resets the snapshot to no active network`() = runTest {
        val connectivityManager = connectivityManager()
        val provider = ConnectivityManagerNetworkSnapshotProvider(connectivityManager)
        val results = mutableListOf<NetworkSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.snapshotFlow.toList(results)
        }

        val callback = shadowOf(connectivityManager).networkCallbacks.single()
        val network = ShadowNetwork.newInstance(102)
        callback.onCapabilitiesChanged(network, capabilitiesWith(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(results.last().hasWifiTransport)

        callback.onLost(network)

        assertEquals(NetworkSnapshot.NONE, results.last())
    }
}
