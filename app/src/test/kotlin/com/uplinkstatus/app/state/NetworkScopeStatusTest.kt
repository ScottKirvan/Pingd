package com.uplinkstatus.app.state

import com.uplinkstatus.app.connectivity.FakeNetworkSnapshotProvider
import com.uplinkstatus.app.connectivity.NetworkSnapshot
import com.uplinkstatus.app.prefs.FakeUplinkPreferencesRepository
import com.uplinkstatus.app.prefs.NetworkScope
import com.uplinkstatus.app.prefs.UplinkPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ConnectivityNetworkScopeStatus] reactive-wiring tests: confirms [inScopeFlow] reacts to
 * *either* a live-connectivity change or a persisted network-scope-preference change
 * independently -- Stage 4's brief is explicit that neither should require the other to also
 * change for the result to update. Uses [FakeNetworkSnapshotProvider] (a plain
 * `MutableStateFlow` standing in for real `NetworkCallback` events) and
 * [FakeUplinkPreferencesRepository] -- no real `ConnectivityManager`, no real DataStore, no
 * Robolectric needed for this layer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkScopeStatusTest {

    private val wifiHome = NetworkSnapshot(
        hasWifiTransport = true,
        hasCellularTransport = false,
        isValidated = true,
        ssid = "Home",
    )
    private val cellular = NetworkSnapshot(
        hasWifiTransport = false,
        hasCellularTransport = true,
        isValidated = true,
        ssid = null,
    )

    @Test
    fun `a scope-preference change alone flips inScope with no connectivity change`() = runTest {
        val preferences = FakeUplinkPreferencesRepository(
            UplinkPreferences(networkScope = NetworkScope.WIFI_ONLY),
        )
        // Connected to cellular only -- out of scope for WIFI_ONLY, in scope for CELLULAR_ONLY,
        // with the connectivity snapshot never changing across the whole test.
        val snapshotProvider = FakeNetworkSnapshotProvider(initial = cellular)
        val status = ConnectivityNetworkScopeStatus(preferences, snapshotProvider) { true }

        val results = mutableListOf<Boolean>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { status.inScopeFlow.toList(results) }

        assertEquals(listOf(false), results)

        preferences.setNetworkScope(NetworkScope.CELLULAR_ONLY)

        assertEquals(listOf(false, true), results)
    }

    @Test
    fun `a connectivity change alone flips inScope with no preference change`() = runTest {
        val preferences = FakeUplinkPreferencesRepository(
            UplinkPreferences(networkScope = NetworkScope.WIFI_ONLY),
        )
        val snapshotProvider = FakeNetworkSnapshotProvider(initial = NetworkSnapshot.NONE)
        val status = ConnectivityNetworkScopeStatus(preferences, snapshotProvider) { true }

        val results = mutableListOf<Boolean>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { status.inScopeFlow.toList(results) }

        assertEquals(listOf(false), results)

        // Connect to WiFi -- the network-scope preference (WIFI_ONLY) never changes.
        snapshotProvider.snapshot = wifiHome

        assertEquals(listOf(false, true), results)

        // Disconnect entirely -- flips back out of scope, again with no preference change.
        snapshotProvider.snapshot = NetworkSnapshot.NONE

        assertEquals(listOf(false, true, false), results)
    }

    @Test
    fun `SSID whitelist mode without location permission stays out of scope through the full reactive flow`() = runTest {
        val preferences = FakeUplinkPreferencesRepository(
            UplinkPreferences(
                networkScope = NetworkScope.SSID_WHITELIST,
                ssidWhitelist = setOf("Home"),
            ),
        )
        // On WiFi, connected to a whitelisted SSID -- would be in scope if permission were
        // granted, which is exactly why this test is meaningful: only the missing permission
        // keeps it out of scope, and it must do so without throwing.
        val snapshotProvider = FakeNetworkSnapshotProvider(initial = wifiHome)
        val status = ConnectivityNetworkScopeStatus(preferences, snapshotProvider) { false }

        val results = mutableListOf<Boolean>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { status.inScopeFlow.toList(results) }

        assertEquals(listOf(false), results)
    }

    @Test
    fun `SSID whitelist mode reacts to an SSID change while remaining on WiFi`() = runTest {
        val preferences = FakeUplinkPreferencesRepository(
            UplinkPreferences(
                networkScope = NetworkScope.SSID_WHITELIST,
                ssidWhitelist = setOf("Home"),
            ),
        )
        val neighborWifi = wifiHome.copy(ssid = "NeighborsNetwork")
        val snapshotProvider = FakeNetworkSnapshotProvider(initial = neighborWifi)
        val status = ConnectivityNetworkScopeStatus(preferences, snapshotProvider) { true }

        val results = mutableListOf<Boolean>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { status.inScopeFlow.toList(results) }

        assertEquals(listOf(false), results)

        // Still on WiFi transport throughout -- only the SSID capability changed, exactly the
        // "onCapabilitiesChanged while remaining on WiFi" case the spec calls out.
        snapshotProvider.snapshot = wifiHome

        assertEquals(listOf(false, true), results)
    }
}
